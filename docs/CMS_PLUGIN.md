# CMS 内容管理插件（cms）

类 Typecho/WordPress 的内容管理插件：文章（多分类/标签）+ Wiki 层级页面 + 匿名评论（审核制）+ 站点设置
+ 私密内容与分享链接 + Markdown/ZIP 导出，可作为个人内容管理平台（日记、备忘录、笔记）。pluginId=`cms`，包名 `com.astral.cms`，全部代码位于 `astral-plugin`。

## 启用

- 配置：`astral.plugins.cms.enabled`（默认 true，环境变量 `CMS_PLUGIN_ENABLED`）。
- 建表：`CmsSchemaInitializer` 启动时幂等执行 `astral-plugin/src/main/resources/sql/cms-schema.sql`（PostgreSQL）。
- 字典：`V20261022001__cms_dict.sql` 提供 `cms_content_status` / `cms_comment_status` / `cms_comment_target_type`，
  `V20261024001__cms_private_share_dict.sql` 补 `private` 状态值与 `cms_share_target_type`。前端下拉一律走数据字典，不得硬编码。
- 管理端菜单：`CmsPlugin` 通过 NavItem 下发「内容管理 → /dashboard/cms」（sort 220，权限 `admin:cms:view`）。

## 数据表（9 张，主键均为全局序列 `表名_id`）

| 表 | 说明 |
| --- | --- |
| cms_post | 文章：title/slug/summary/content(MD)/cover/category_id(主分类冗余列)/status(draft,published,private,hidden)/is_top/views/author_id/published_at，软删 |
| cms_category | 分类（树）：name/slug/parent_id/sort/description |
| cms_tag | 标签：name 唯一/slug |
| cms_post_tag | 文章×标签关联（post_id,tag_id 唯一） |
| cms_post_category | 文章×分类关联（post_id,category_id 唯一），支持一篇文章多个分类 |
| cms_page | Wiki 页面（树）：title/slug/content/parent_id/sort/status(draft,published,private,hidden)/views，软删 |
| cms_comment | 评论：target_type(post,page)/target_id/nickname/email/content/status(pending,approved,rejected)/ip/user_agent |
| cms_setting | 站点设置 KV：site_title/site_description/footer_text/blog_url(博客前台地址，用于「查看博客」跳转与分享链接拼装)/comment_enabled(默认true)/comment_need_review(默认true) |
| cms_share | 分享链接：target_type(post,page)/target_id/token(唯一)/expire_at(NULL=永久)/remark/create_by，硬删即撤销 |

- post/page 的 slug 唯一约束为部分索引（`WHERE slug IS NOT NULL AND delete_time IS NULL`），软删后别名可复用。
- 公开侧 slug 解析规则：纯数字按 ID，否则按 slug。
- **多分类**：权威数据在 `cms_post_category`；`cms_post.category_id` 保留为「主分类」冗余列（取提交列表第一项）。
  读取时文章对象返回 `categories: [{id,name,slug}...]`（主分类在最前，同时保留 `categoryId`/`categoryName` 向后兼容）；
  按分类筛选（管理端 `categoryId`、公开端 `categorySlug`）为「包含该分类」语义，关联表为空时回退冗余列匹配。
- **私密可见性**：`status=private` 的内容只对「已登录且拥有 `admin:cms:view` 权限」的用户可见（超管 `*:*:*` 自动满足）。
  判定集中在 `com/astral/cms/common/CmsVisibility.java`（`isVisible(status)` / `canViewPrivate()`），
  公开端全部读接口（文章列表/详情、页面树/详情、分类与标签的 postCount、评论目标校验）都经它过滤，
  匿名访客看不到也查不到（按 ID 直接访问返回 404）。注意 `/api/v1/app/**` 虽是匿名放行，但宿主
  `AuthInterceptor` 仍会解析 Cookie 中的 `satoken`，所以前台只要在服务端请求时转发 Cookie，登录态就生效。
- **分享即临时授权**：有效分享 token 可让未登录访客只读访问**包括私密**的目标内容，因此
  `publicDetailById()` 有意跳过状态校验（只校验未删除），调用方必须先校验 token 有效期。

## 权限码

| 码 | 用途 |
| --- | --- |
| admin:cms:view | 查看（文章/分类/标签/页面/评论/设置） |
| admin:cms:content:edit | 内容编辑（文章/分类/标签/页面增删改） |
| admin:cms:comment:edit | 评论审核与删除 |
| admin:cms:setting:edit | 站点设置修改 |

## 配置项（astral.plugins.cms.*）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| enabled | true | 插件开关 |
| rate-limit-count | 5 | 同 IP 评论限流条数 |
| rate-limit-window-seconds | 600 | 评论限流窗口（秒） |
| trusted-proxies | 内网网段 | X-Forwarded-For 可信代理 CIDR |

## 管理 API（/api/v1/admin/cms，宿主 Sa-Token 鉴权）

通用：列表/详情类接口需 `admin:cms:view`；写接口需对应 edit 权限。响应统一 `Result{code,errorCode,message,data,timestamp}`，分页为 MyBatis-Plus `Page{records,total,size,current,pages}`。

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| GET /api/v1/admin/cms/post/page?pageNum&pageSize&keyword&status&categoryId | view | 文章分页（附 categories[]、categoryName、tags）；categoryId 为「包含该分类」筛选 |
| GET /api/v1/admin/cms/post/{id} | view | 文章详情（含 categories[]、tagId 列表） |
| POST /api/v1/admin/cms/post | content:edit | 新建。body：PostSaveDto{title,slug,summary,content,cover,categoryIds[].categoryId,status,isTop,tagIds[]} |
| PUT /api/v1/admin/cms/post/{id} | content:edit | 更新 |
| PUT /api/v1/admin/cms/post/{id}/status | content:edit | body：ContentStatusDto{status}。首次发布自动写 published_at |
| DELETE /api/v1/admin/cms/post/{id} | content:edit | 软删并清标签/分类关联 |
| GET /api/v1/admin/cms/category/tree | view | 分类树（附 postCount：仅已发布计数） |
| POST /api/v1/admin/cms/category · PUT /{id} · DELETE /{id} | content:edit | body：CategorySaveDto{name,slug,parentId,sort,description}。删除被拦截：存在子分类或已发布文章 |
| GET /api/v1/admin/cms/tag/list | view | 标签列表（附 postCount） |
| POST /api/v1/admin/cms/tag · PUT /{id} · DELETE /{id} | content:edit | body：TagSaveDto{name,slug}。name 唯一；删除级联清关联 |
| GET /api/v1/admin/cms/page/tree | view | Wiki 页面树（管理端全量） |
| GET /api/v1/admin/cms/page/{id} | view | 页面详情 |
| POST /api/v1/admin/cms/page · PUT /{id} · PUT /{id}/status · DELETE /{id} | content:edit | body：PageSaveDto{title,slug,content,parentId,sort,status}。删除被拦截：存在子页面 |
| GET /api/v1/admin/cms/comment/page?pageNum&pageSize&status&targetType | view | 评论分页（附 targetTitle，含 email/ip/userAgent） |
| PUT /api/v1/admin/cms/comment/{id}/audit | comment:edit | body：CommentAuditDto{status}，仅 approved/rejected |
| DELETE /api/v1/admin/cms/comment/{id} | comment:edit | 硬删 |
| GET /api/v1/admin/cms/setting/list | view | 设置全量（含默认值兜底） |
| PUT /api/v1/admin/cms/setting | setting:edit | body：SettingSaveDto{settings:Map}，未知键报错 |
| GET /api/v1/admin/cms/share/list | view | 全部分享链接（附 targetTitle、url、expired） |
| GET /api/v1/admin/cms/share/list-by-target?targetType&targetId | view | 某个内容的分享链接 |
| POST /api/v1/admin/cms/share | content:edit | 创建分享。body：ShareCreateDto{targetType,targetId,expireAt,remark}；expireAt 早于当前时间报错 |
| DELETE /api/v1/admin/cms/share/{id} · DELETE /by-target?targetType&targetId | content:edit | 撤销（硬删，立即失效） |
| GET /api/v1/admin/cms/export/post/{id} · /page/{id} | view | 导出单个内容为 Markdown（带 YAML 头信息，`Content-Disposition` 附件下载） |
| GET /api/v1/admin/cms/export/zip · /markdown | view | 全站 ZIP（`posts/` + `pages/` 文件树 + `manifest.md`）/ 全部文章合集 Markdown |

## 公开 API（/api/v1/app/cms，匿名，供 astral-blog 消费）

宿主 AuthInterceptor 对 `/api/v1/app/` 非 user 区匿名放行，无需额外配置。评论公开返回**不含** email/ip/userAgent。

读接口按请求登录态自动决定可见范围：匿名只看 `published`；带有效 `satoken` Cookie 且具备 `admin:cms:view` 的用户额外能看到 `private`。

| 方法与路径 | 说明 |
| --- | --- |
| GET /post/page?pageNum&pageSize&categorySlug&tagSlug&keyword | 已发布文章分页：置顶→published_at→id 降序；categorySlug/tagSlug 支持别名或数字 ID；列表不返回 content，返回 categories[] |
| GET /post/{idOrSlug} | 文章详情（须 published，浏览量 +1），含 categories[]（主分类在最前） |
| GET /category/tree | 分类树（含已发布文章数） |
| GET /tag/list | 标签列表（含已发布文章数） |
| GET /page/tree | Wiki 目录（仅 published，含子页面树） |
| GET /page/{idOrSlug} | 页面详情：含 breadcrumb[{id,title,slug}]（父链）与子页面 children，浏览量 +1 |
| GET /comment/page?targetType&targetId&pageNum&pageSize | 已通过评论分页（targetType=post/page） |
| GET /setting | 站点公开设置：site_title / site_description / footer_text / comment_enabled / comment_need_review（**不含** blog_url，那是管理端内部外链地址） |
| POST /comment | 匿名评论。body：{targetType,targetId,nickname,email?,content}；校验 comment_enabled、目标已发布；限流同 IP 窗口 5 条；comment_need_review=true 时入 pending，否则直接 approved |
| GET /share/{token} | 按分享令牌读取内容，返回 {targetType,targetId,expireAt,post\|page}。token 无效或过期返回 code=404。**可直接读取私密内容**（分享即临时授权），未登录也可访问 |

## 前端

- **astral-front 管理端**：`/dashboard/cms`（菜单由插件 NavItem 下发），API 封装 `astral-front/src/api/cms.ts`；二级导航右侧「查看博客」入口跳转博客前台，地址取站点设置 `blog_url`，未配置时回退 `NEXT_PUBLIC_BLOG_URL` 或 `http://localhost:3001`。
- **astral-blog 独立前台**：`astral-blog/`（独立 Next.js 应用，本地跑在 **3001**（`package.json` 已固定 `next dev -p 3001`，避开管理端 3000），绑定单独域名部署 EdgeOne Pages / Cloudflare Pages，见其 README）。所有对后端请求在服务端完成（RSC/Route Handler 转发），浏览器不直连 astral 后端，天然无 CORS。

## 页面宽度与布局

博客前台的横向容器统一用 `globals.css` 里的 `.page-shell` 类（`@layer components`），不再逐页写 `mx-auto max-w-*`：

- 宽屏（≥ 1024px）固定占**视口宽度的 80%**。早期用 `max-w-6xl`（1152px），在 2560 屏上只占 45%，两侧大片空白。
- 窄屏（< 1024px）宽度本来就紧，回退为满宽 + `padding-inline: 1.25rem`。
- `max-width: 2200px` 只给超宽屏兜底（> 2750px 才生效），2560 屏上仍是 2048px，不会被提前夹窄。

接入位置：`app/{page,categories,wiki,not-found}.tsx`、`app/{post,wiki,share}/[...]/page.tsx`、`components/site-{header,footer}.tsx`。

配套的**内容密度调整**（否则 80% 宽度下内容会显得稀疏）：

- 没有 TOC 的正文兜底列由 `max-w-[760px]` 放宽到 `max-w-[1100px]`（TOC 存在时仍走 `lg:grid-cols-[220px_minmax(0,1fr)]`）。
- `/wiki` 改成「每个根页面一张卡片 + `sm:grid-cols-2 2xl:grid-cols-3`」，卡片标题即根页面链接，子页面在下方缩进。
- `/categories` 改成 `lg:grid-cols-[minmax(0,1fr)_320px]`，分类树在左、标签在右。
- 首页保持 `264px` 侧栏 + 主列的 Joe 式双栏。

字号也随视口一起放大（容器铺开后固定字号在大屏上会显得又稀又小）：

- `.markdown` 的 `font-size: clamp(1.0625rem, 0.35vw + 0.75rem, 1.3125rem)` —— 1440 屏约 17px，2560 屏约 21px。
- 标题层用 `em`（h1 1.45em / h2 1.18em / h3 1.08em / h4 1em），随正文字号等比缩放。
- 目录项、文章标题、列表卡片标题与摘要、内页标题同步用 `clamp()` 随视口微调。
- `.markdown` 不再设 `max-width`（曾是 `72ch` ≈ 981px）。容器已经按 80% 收过一道，正文再叠一层字符宽上限就会又变回一根窄柱 —— 这是「范围变大了但文字还是一小条」的根因。

### 标题层级去重

一屏里出现多遍「标题」是最容易被一眼看出的排版问题，已按下面几条收口：

- **正文 h1 压到 1.45em 并加细下划线**。原先是 1.95em，比页面标题（26~30px）还大，读者会以为正文里那个才是标题。现在层级读作「页面标题 → 章（h1，带下划线）→ 节（h2，带左侧竖线）→ 小节（h3）」。
- **正文首个 h1 与文章标题相同时会被跳过**（`markdown.ts` 的 `articleTitle` 选项），避免「页面标题 + 正文标题」重复。
- **站点名只出现在顶栏**：`blog-sidebar.tsx` 的简介卡去掉了重复的站点名与「站长」，只留头像、简介、文章/分类计数。
- **页脚收成单行**：`site-footer.tsx` 去掉了左侧品牌块，只保留 `© {year} {站点名}` + 导航 + 回到顶部。
- **装饰性英文小标（`eyebrow`）从页面标题上撤掉**：首页的 `JOURNAL`、分类页的 `Library`、Wiki 页的 `Knowledge base`，以及各内页的「分类 / 标签 / Wiki」都删了；`/categories` 的标题合并成「分类与标签」。
- **Wiki 详情页**：面包屑只列祖先层级，不作末项重复当前标题（当前标题由下方 h1 承担）；顶级页面没有祖先时，面包屑整体换成「← Wiki 目录」返回链接，不再同时出现两处「Wiki」。
- **Wiki 索引页**：没有子页面的根页面直接渲染成整张可点卡片（右侧箭头），不再留一行「暂无子页面」的空档。

### Web 界面规范（Vercel Web Interface Guidelines）

两个前端都按这份规范过了一遍，可复用的约定如下（改动分散在 `globals.css` 与各组件，此处只记「为什么」）：

- **视口**：`viewport` 带 `viewport-fit=cover`，但**不禁用缩放**（`maximumScale` 已移除——它会让低视力用户无法放大页面）。`themeColor` 按 `prefers-color-scheme` 给明暗两组。
- **安全区**：`viewport-fit=cover` 后页面会铺到刘海/圆角下方，因此新增 `.safe-top` / `.safe-bottom` 两个工具类（`env(safe-area-inset-*)`），套在粘顶的 `site-header`、管理端 `dashboard-header`、编辑工作台顶栏与主体上。
- **视口高度**：`min-h-screen` 换成 `min-h-dvh`（移动端地址栏收放时 `100vh` 会溢出）。管理端除了 Tailwind 类，`globals.css` 里 `.login-container`/`.dashboard-layout`/`.dashboard-content`/`.ql-page`/`.imgbed-container` 的 `100vh` 也补了一行 `100dvh`（**保留原 `100vh` 在前作为老浏览器回退**，后写的覆盖前者）。注意 TOC 的 `max-h-[calc(100vh-8rem)]` 是滚动区上限、不是页面高度，保留 `vh`。
- **焦点可见**：两个 app 都有全局 `:focus-visible` 规则；`textarea` 类控件自身 `outline-none` 的（Markdown 编辑器），焦点反馈改由**外层容器**用 `focus-within:border-* + focus-within:ring-*` 承担，避免「Tab 进去看不出焦点在哪」。
- **折行**：`.markdown` 的标题块加 `text-wrap: balance`（多行标题两行长度接近，不留孤字），正文段落用 `text-wrap: pretty`。
- **截断**：所有 `truncate` 的文本（分类名、Wiki 标题、子页面标题）都补 `title`，悬停可看全。
- **触控**：`@media (pointer: coarse)` 下按钮最小 44px 高；`min-h-11` 用在窄屏列表行与页脚链接上，`sm:min-h-0` 在宽屏还原。
- **已有且无需再加的**：`<html lang="zh-CN">`、`prefers-reduced-motion` 块、`aria-current`（导航/分页/面包屑）、`scroll-margin-top`（锚点跳转不被粘顶栏盖住）、`autoComplete` + `inputMode`（登录/改密/评论）、`aria-live`（加载与上传队列）、危险操作 `confirm` 带对象名。


## Markdown 编辑器

正文编辑端（管理端 `PostForm`/`WikiForm` 与博客前台页内编辑）共用同一款编辑器组件，两个应用各有一份源码，样式类名按各自主题令牌对齐：

| 位置 | 文件 |
| --- | --- |
| 管理端 | `astral-front/src/components/markdown-editor.tsx` |
| 博客前台 | `astral-blog/src/components/markdown-editor.tsx` |

- **工具栏**：撤销/重做、加粗/斜体/删除线/行内代码、引用/分割线、无序/有序/任务列表、链接/图片/表格/代码块、标题级别下拉（H1~H5）、emoji 弹层、语法速查下拉。
- **三种视图**：仅编辑 / 分栏预览 / 仅预览，另有全屏编辑。分栏预览下两侧**滚动联动**——textarea 与渲染后的 HTML 行高不同，故按「可滚动高度」比例对齐（任一内容不足一屏时不同步），并用 `syncLock` 避免两侧 scroll 事件互相触发抖动。
- **默认选项**：不引入 CodeMirror/Monaco，底层仍是受控 `<textarea>`，编辑器只做语法插入与预览，因此不增加体积与依赖风险；预览与前台详情页走同一个 `renderMarkdown` 渲染管线，所见即所得。
- **键盘**：Ctrl/Cmd+B/I/K、Ctrl+Z / Ctrl+Shift+Z、Tab/Shift+Tab 整块缩进、回车自动延续列表（有序递增、任务项复制未勾选态、空项退出）。
- **只读态**：`readOnly` 时只渲染预览，不显示工具栏（用于无编辑权限的场景，如只读 Wiki 页面）。
- 渲染依赖 `marked`（gfm，不消毒——正文来自后台，视为受信）；预览排版样式为 `globals.css` 里的 `.markdown` 段：管理端走 shadcn 语义令牌（随 `data-theme` 深色模式切换），博客前台走暖纸色主题令牌。
- **语法高亮**：`astral-blog/src/lib/highlight.ts` 与 `astral-front/src/lib/highlight.ts` 用 `highlight.js/lib/core` 按需注册 25 种语言（java/js/ts/python/go/sql/bash/xml/yaml/json…，另有一张别名表 `js→javascript`、`sh→bash`、`html→xml` 等），未注册的语言退回纯文本。代码块渲染成 mac 风格标题条（三个圆点 + 语言名 + 复制按钮），复制按钮由事件委托处理——博客前台走 `<MarkdownEnhance />`（挂载在 `astral-blog/src/app/layout.tsx`），管理端走 `markdown-editor.tsx` 预览容器的 `onClick`。
- **表格**：渲染器额外包一层 `.md-table` 容器负责横向滚动，表格自身保持 `display: table` 撑满整行并带整块圆角描边 + 表头浅底 + 单元格竖分隔线（若直接给 `<table>` 加 `overflow-x` 必须改为 `display:block`，会导致表头按内容宽度收缩、右侧留白且列错位）。
- **图片**：渲染器给正文里的 `<img>` 统一补 `referrerpolicy="no-referrer"`（图床如 `cdn.canace.cn` 开了防盗链，带 Referer 会被 403）+ `loading="lazy"`。

## 私密内容与个人内容平台

除博客文章外，cms 也可当作**个人内容管理平台**（日记、备忘录、随手笔记）使用，靠两件事实现：

- **`private` 状态**：只在管理端 + 博客前台**登录态**下可见，匿名访客在列表/详情/搜索里都看不到。
  用法：文章或页面把状态设为「私密」即可（无需新增内容类型字段）。
  由于分类树的 `postCount` 也走同一套可见性判定，私密文章不会在匿名访客的分类计数里露出痕迹。
- **分享链接**：给某篇私密内容生成带随机 token 的只读链接，可**随时撤销**、可设**过期时间**；
  拿到链接的人无需登录即可阅读（分享即临时授权），撤销后立即 404。
  链接地址取站点设置 `blog_url` 拼装（未配置时管理端只显示 `/share/{token}` 相对路径）。

### 分享页版式

`app/share/[token]/page.tsx` 与详情页同口径：宽屏下左侧「目录」列常驻（`lg:grid-cols-[220px_minmax(0,1fr)]`，TOC 由 `renderMarkdownWithToc` 产出），正文在右；没有任何 h1~h3 时退回居中单栏 `max-w-[1100px]`。左栏底部额外标注「通过分享链接访问，仅可阅读。」。

分享页仍不显示评论与编辑入口 —— 访客即便有登录态，编辑也应在后台进行。


## 导出

三个入口都在管理端（`/api/v1/admin/cms/export/**`，需 `admin:cms:view`），返回的是**文件流**而非 `Result` 包装：

- **单篇 Markdown**：`GET /post/{id}`、`GET /page/{id}` —— 带 YAML 头信息（title/slug/date/status/categories/tags/cover/summary）。
- **全站 ZIP**：`GET /zip` —— `posts/*.md` + `pages/*.md` + 一份 `manifest.md` 目录索引；文件名优先 slug，其次标题，
  剔除 Windows 非法字符并截断，同名自动追加 `-2`、`-3`。
- **全部文章合集**：`GET /markdown` —— 所有文章拼成一个 `.md`，用 `---` 分隔。

PDF/打印走浏览器自身能力（前台详情页直接「打印 → 另存为 PDF」），不额外引入服务端渲染 PDF 的依赖。

博客前台也有两处入口：分享页 `/share/[token]`（`astral-blog/src/app/share/[token]/page.tsx`，`robots: noindex`，只读、不显示评论与编辑 UI），以及导出转发 `astral-blog/src/app/api/admin/export/[...path]/route.ts`（把文件流原样透传给浏览器，配合页内编辑工作台顶栏的「导出」按钮）。

## 博客前台页内编辑（编辑工作台）

登录且具备 `admin:cms:content:edit` 的访客，在文章/页面详情页右上角会看到「编辑」按钮；点击后**整页切换成覆盖视口的工作台**（`astral-blog/src/components/editable-article.tsx`）：

- **顶栏**：编辑对象类型、标题、未保存脏标记、`Ctrl/Cmd+S` 保存 / `Esc` 关闭提示、取消与保存。
- **左侧主区**：无框大标题输入 + `MarkdownEditor fill`（吃满剩余高度，工具栏/状态条固定）。
- **右侧字段面板**：由 `PanelCard` 组成——「发布」（状态、置顶或父页面+排序、URL 别名）、「摘要」、「封面图」（含预览）、「分类」（多选，第一项标「主」，树形缩进）、「标签」。
- 编辑态盖住阅读态，因此**左侧标题目录（TOC）在编辑时自然不显示**，正文宽度从 760px 阅读栏扩到整屏。
- 分类/标签/页面树一次性来自本站 Route Handler `GET /api/admin/meta`（服务端并发取三个 Admin 接口，任一失败给空数组，不阻塞编辑）。
- 保存走 `PUT /api/admin/post/{id}` 或 `/api/admin/page/{id}`，仍由服务端转发到后端 Admin 接口并二次校验权限；**未在表单里的字段（`publishedAt`、`slug`、`cover` 等）从打开时的快照回填**，避免保存把字段清空。`categoryIds` 是唯一写入源，`categoryId` 冗余为 `categoryIds[0]` 兼容旧读取方。
- 父页面下拉会排除自身及全部子孙（前端先排除，后端 `parentId` 校验兜底），避免成环。

## 本地启动

`run-frontend.bat` 会先后拉起两个前端：astral-blog（3001，独立窗口）与 astral-front 管理端（3000，日志写 `logs/frontend.log`）。端口已被占用或 `astral-blog/node_modules` 缺失时会跳过博客并在新窗口执行安装。`run-all.bat` 亦会一并启动后端 27000。

## 从 Typecho 迁移内容

`data-migration/` 提供一套可重跑的迁移脚本（`dump_typecho.py` 导出 → `etl_to_cms.py` 清洗入库，另有 `backfill_post_category.py` 回补多分类关联），已用于把 `www.canace.cn` 的 24 篇文章 + 3 个页面 + 10 个分类 + 站点设置迁入 cms 表。迁移口径（主键保留源 cid、多分类全部保留且第一个为主分类、正文 `<!--markdown-->` 与 Joe 主题标签清洗、摘要与封面自动生成等）见 `data-migration/README.md`。

## 实现索引

- 插件入口：`astral-plugin/src/main/java/com/astral/cms/CmsPlugin.java`
- 控制器：`controller/AppCmsController.java` + `controller/AdminCms{Post,Category,Tag,Page,Comment,Setting,Share,Export}Controller.java`（8 个）
- 服务：`service/Cms{Post,Category,Tag,Page,Comment,Setting,Share,Export}Service.java`
- 可见性判定：`common/CmsVisibility.java`（`isVisible(status)` / `canViewPrivate()`）
- 实体：`entity/Cms*.java`（9 个）；Mapper：`mapper/Cms*Mapper.java`（9 个）
- DTO：`dto/{PostSaveDto,ContentStatusDto,CategorySaveDto,TagSaveDto,PageSaveDto,CommentSubmitDto,CommentAuditDto,SettingSaveDto,ShareCreateDto}.java`
- 建表 SQL：`astral-plugin/src/main/resources/sql/cms-schema.sql`；schema JSON：`resources/schema/cms_*.json`（9 份）
- 字典迁移：`astral-server/src/main/resources/db/migration/V20261022001__cms_dict.sql`（枚举初版）、`V20261024001__cms_private_share_dict.sql`（private 状态 + 分享目标类型）
- 博客前台登录/编辑：`astral-blog/src/lib/auth-server.ts`（Cookie 转发）、`src/app/api/auth/*`、`src/app/api/admin/*`、`src/components/{session-provider,login-dialog,editable-article,markdown-editor}.tsx`
