# astral-blog

Astral 站点的公开博客 + Wiki 前台。类 Typecho/WordPress 风格的轻量阅读站点，作为**独立 Next.js 应用**部署（与管理端 `astral-front` 分离），数据来自 astral 后端 CMS 插件的公开 API（`/api/v1/app/cms/*`，匿名，契约见仓库 `docs/CMS_PLUGIN.md`）。

## 技术栈

- Next.js **16.4.3**（App Router + Turbopack）
- React 声明 **^19.2.0**（当前 pnpm 锁解析为 **19.3.1**）+ TypeScript **5.6.3**（strict）
- Tailwind CSS v4（`@tailwindcss/postcss`）+ 自有设计令牌（`--color-canvas` / `--color-ink` / `--color-brand` 等，见 `src/app/globals.css`）
- Markdown 渲染：**marked** + **highlight.js**（服务端渲染，文章/Wiki 内容均由后台管理员撰写）
- UI 基元：Radix UI + shadcn/ui 风格组件（`src/components/ui/`）
- 包管理：**pnpm**；本应用只维护 [pnpm-lock.yaml](pnpm-lock.yaml)，生产使用 `pnpm install --frozen-lockfile`。管理端的 npm 锁不属于本应用，禁止在博客生成第二套锁。

## 架构要点

- **浏览器不直连后端**：所有对 astral 后端的请求都在服务端完成 —— 列表/详情页在 RSC 中 `fetch`，评论提交走本应用自己的 Route Handler（`src/app/api/comment/route.ts`）转发。天然无 CORS 问题。
- **数据页动态渲染**（`export const dynamic = 'force-dynamic'`）：构建不依赖真实 CMS 必需数据；正式隔离验收使用 `pnpm build:isolated`，不复制真实环境文件并约束应用 fetch。运行时明确区分「服务不可用，可重试」与合法空结果，不把读失败伪装成无内容。
- 后端地址由环境变量 **`CMS_API_BASE`** 控制（默认 `http://localhost:27000`），见 `src/lib/cms.ts`。
- 评论的业务失败（站点关闭评论、触发限流等）会把后端 `Result.msg` 原样透传给用户展示。

## 页面路由

| 路由               | 说明                                                                                   |
| ------------------ | -------------------------------------------------------------------------------------- |
| `/`                | 首页：文章列表（置顶标识/分类/标签/日期/阅读量）+ 分页；侧栏：站点信息、分类树、标签云 |
| `/post/[slug]`     | 文章详情：标题/日期/分类/标签/浏览量 + Markdown 正文 + 评论区（列表 + 匿名提交表单）   |
| `/wiki`            | Wiki 目录：`page/tree` 渲染为树状列表                                                  |
| `/wiki/[slug]`     | Wiki 页面：面包屑（breadcrumb 父链）+ Markdown 正文 + 子页面列表 + 评论                |
| `/categories`      | 导航「分类」落地页：分类树 + 标签列表                                                  |
| `/category/[slug]` | 按分类过滤的文章列表 + 分页                                                            |
| `/tag/[slug]`      | 按标签过滤的文章列表 + 分页                                                            |
| `/api/comment`     | POST：评论提交的 Route Handler（服务端转发到后端 `POST /api/v1/app/cms/comment`）      |

slug 解析遵循后端公开侧规则：**纯数字按 ID，否则按 slug**；无 slug 的分类/标签/页面自动回退用数字 ID 拼链接。

## 本地运行

```bash
cd astral-blog
pnpm install

# 可选：配置后端地址（默认 http://localhost:27000，即本地 astral 后端）
cp .env.example .env.local   # Windows PowerShell: Copy-Item .env.example .env.local
# 按需修改 .env.local 中的 CMS_API_BASE

pnpm dev        # 开发，http://localhost:3001（端口已固定在 package.json，避开管理端 3000）
```

同时启动后端（仓库根目录）即可联调：

```bash
mvn -pl astral-server spring-boot:run -Dspring-boot.run.arguments=--server.port=27000
```

后端未启动时前台仍可展示可恢复的「暂时无法加载」失败状态；详情仅在后端明确返回资源不存在时显示 404。合法空数据才显示「暂无内容」，读失败不冒充空结果。

## 构建

```bash
pnpm build      # 普通生产构建，含类型检查；不等同于隔离/零网络证明
pnpm start      # 以生产模式启动（需先 build）
```

## 环境变量

| 变量                    | 必填                   | 默认                     | 说明                                                                                     |
| ----------------------- | ---------------------- | ------------------------ | ---------------------------------------------------------------------------------------- |
| `CMS_API_BASE`          | 否（生产建议显式配置） | `http://localhost:27000` | astral 后端基地址，末尾不要带 `/`；公开 CMS API 路径为 `{CMS_API_BASE}/api/v1/app/cms/*` |
| `NEXT_PUBLIC_SITE_URL`  | 否（生产建议显式配置） | `http://localhost:3001`  | 站点公开访问地址，用于 canonical、OpenGraph 与 `sitemap.xml` 的绝对 URL，末尾不要带 `/`  |
| `NEXT_PUBLIC_ADMIN_URL` | 否（生产建议显式配置） | `http://localhost:3000`  | 管理后台地址，用于登录后的“内容管理后台”链接，末尾不要带 `/`                             |

> 后端侧无需为博客做任何额外配置：`/api/v1/app/` 匿名放行（见 `docs/CMS_PLUGIN.md`）。
> 若后端启用了评论功能之外的 IP 限制或防火墙，请放行托管平台的服务端出口 IP。

## 部署

### 部署前：确认后端地址

- astral 后端（Spring Boot，端口 27000）需要部署在一个**博客平台的服务端可公网访问**的地址，例如 `https://astral-api.example.com`（自建服务器反代 / 内网穿透均可，博客侧不关心实现）。
- 在托管平台的环境变量里配置 `CMS_API_BASE=https://astral-api.example.com`（无尾斜杠）。
- 不需要给博客配置后端 CORS：所有请求由平台函数（服务端）发出。

### 方式一：EdgeOne Pages（腾讯云）

1. 将 `astral-blog` 推送到 Git 仓库（GitHub / GitLab / Gitee 均可）。
2. 登录 [EdgeOne Pages 控制台](https://console.cloud.tencent.com/edgeone/pages) → **创建项目** → 选择 **导入 Git 仓库** 并授权选中该仓库。
3. 框架预设选择 **Next.js**；构建命令保持 `pnpm install --frozen-lockfile && pnpm build`；在「根目录」填 `astral-blog` 指向子目录。仅支持 npm 的平台应先配置 pnpm，不混用或重建博客锁。
4. **环境变量**中添加：`CMS_API_BASE = https://astral-api.example.com`（你的后端公网地址）、`NEXT_PUBLIC_SITE_URL = https://blog.example.com`、`NEXT_PUBLIC_ADMIN_URL = https://admin.example.com`。构建期 Node 版本要求 **24 LTS**，如平台默认版本过低可添加 `NODE_VERSION = 24`。
5. 触发部署，等待构建完成获得 `*.edgeone.app` 预览域名，先验证页面与评论功能。
6. 绑定自定义域名：项目 → **域名管理** → **添加自定义域名**（如 `blog.example.com`），按提示在你的 DNS 处添加 CNAME 记录指向平台分配的接入域名，等待证书签发生效。
   - 使用中国大陆接入节点需域名已完成 ICP 备案；未备案可选择仅境外节点。
7. 注意：控制台的预设参数以平台当前 UI 为准（EdgeOne Pages 迭代较快）；关键是「Next.js 预设 + CMS_API_BASE 环境变量 + 自定义域名 CNAME」三件事。

### 方式二：Cloudflare Workers（OpenNext，需验证）

博客登录转发使用 `node:crypto`（RSA 加密登录密码，见 `src/lib/crypto.ts`），因此**必须运行在 Node.js 运行时**，不能直接部署到纯 Edge Runtime。Cloudflare 需使用官方 OpenNext 适配器并开启 `nodejs_compat`：

1. 代码推送到 GitHub / GitLab。
2. `pnpm add -D @opennextjs/cloudflare wrangler`，按官方文档创建最小 `wrangler.jsonc`（`compatibility_flags: ["nodejs_compat"]`）。
3. 构建：`npx opennextjs-cloudflare build`；预览：`npx opennextjs-cloudflare preview`；发布：`npx opennextjs-cloudflare deploy`。
4. 在 Worker 环境变量中配置 `CMS_API_BASE`、`NEXT_PUBLIC_SITE_URL`、`NEXT_PUBLIC_ADMIN_URL`（Production 与 Preview 都加）。

> 版本兼容提示：OpenNext 对 Next 最新大版本的支持可能滞后；若构建日志提示不支持的 Next 版本，优先改用 Node 平台（EdgeOne Pages Node 函数 / Docker / Vercel），不要为适配器降级 Next 主版本。`@cloudflare/next-on-pages` 不适用于本项目（其只支持 Edge Runtime，且对 Next 16 支持滞后）。

### 方式三：Docker standalone（镜像构建仍需目标环境验证）

`NEXT_PUBLIC_*` 会在构建时进入浏览器 bundle；仅在 `docker run` 传同名环境变量不能更改已构建的管理端链接。Dockerfile 提供两个构建参数，生产必须使用真实公开域名并重建镜像：

```bash
docker build --build-arg NEXT_PUBLIC_SITE_URL=https://blog.example.com \
  --build-arg NEXT_PUBLIC_ADMIN_URL=https://admin.example.com -t astral-blog .
docker run --rm -p 3001:3000 -e CMS_API_BASE=http://host.docker.internal:27000 astral-blog
```

这里的域名是说明性占位，不是验收期间访问的生产地址。容器运行入口为 standalone，绑定 `0.0.0.0:3000`，后端地址仍可在运行时覆盖；Docker CLI/镜像、Linux 原生依赖和真实部署不由 Windows 离线 Next 构建替代。

### 部署后自检清单

- [ ] 首页能列出后台已发布文章（置顶在前，按发布时间倒序）
- [ ] `/wiki` 目录树、面包屑、子页面跳转正常
- [ ] 分类 / 标签点击过滤正常（无 slug 的用数字 ID）
- [ ] 登录后账号菜单的「内容管理后台」跳转到真实管理端域名（由 `NEXT_PUBLIC_ADMIN_URL` 控制）
- [ ] 文章详情提交评论：成功提示 → 后台审核通过后前台可见；关闭评论/触发限流时能看到后端返回的 `msg`
- [ ] 后端不可达：显示明确的失败与重试提示，不伪装成合法空结果或资源 404

## 目录结构

```
astral-blog/
├── src/
│   ├── app/
│   │   ├── layout.tsx              # 站点布局：顶栏（站名+导航）+ 页脚
│   │   ├── page.tsx                # 首页（文章列表 + 侧栏）
│   │   ├── post/[slug]/page.tsx    # 文章详情 + 评论
│   │   ├── wiki/page.tsx           # Wiki 目录树
│   │   ├── wiki/[slug]/page.tsx    # Wiki 页面（面包屑 + 正文 + 子页面）
│   │   ├── categories/page.tsx     # 分类 + 标签目录
│   │   ├── category/[slug]/page.tsx
│   │   ├── tag/[slug]/page.tsx
│   │   ├── api/comment/route.ts    # 评论提交转发（浏览器 → 本路由 → 后端）
│   │   └── globals.css             # Tailwind v4 入口 + Markdown 正文样式
│   ├── components/                 # header/footer/sidebar/列表/分页/评论/空态/树
│   └── lib/
│       ├── cms.ts                  # 后端公开 API 客户端 + 类型 + 统一容错
│       ├── markdown.ts             # marked 服务端渲染
│       ├── format.ts               # 日期/计数格式化
│       ├── links.ts                # slug/id 链接生成
│       └── site.ts                 # 站点展示信息（读取后端公开设置，失败回退默认值）
├── package.json / tsconfig.json / next.config.ts / postcss.config.mjs
└── README.md
```

## 可复现的隔离验收

- `pnpm test:server`：真实 TS 模块、BFF Route Handler、DTO/URL/Markdown/业务与传输包络；仅替换后端外部边界。
- `pnpm test:react`：实际 React/ReactDOM 组件、请求代际/锁/登录/草稿/剪贴板生命周期；需要 jsdom（可用 `BLOG_TEST_JSDOM_PATH` 指向已安装模块，不改变本应用依赖或全局包管理设置）。
- `pnpm build:isolated`：构建原始 Next 应用的独立快照，产物放在 [测试产物目录](tests/.artifacts/)，回执包含源码/public/配置/package/锁 SHA-256；真实 `.env*` 不复制。
- 启动 [合成后端](tests/fixture-api.cjs)；在快照的 `.next/standalone` 下定位该项目实际服务入口（多锁工作区可能嵌套），将该快照的 `.next/static` 和 `public` 复制到入口旁的对应目录，再用 Node 执行该入口。设置 `HOSTNAME=127.0.0.1`、`PORT=41890`、`CMS_API_BASE=http://127.0.0.1:41891`、两项公开 URL 均为 `http://127.0.0.1:41890`，并通过 `NODE_OPTIONS=--require=<绝对路径>` 预加载 [应用 fetch 限制器](tests/loopback-fetch.cjs)；不以 `next start` 冒充 standalone 验收。
- `pnpm test:browser <快照绝对路径>`：Chrome 默认；可用 `BLOG_TEST_BROWSER_CHANNEL=msedge` 选择 Edge，`BLOG_TEST_PLAYWRIGHT_PATH` 指向已安装的 playwright-core。独立临时配置，不使用真实账号/浏览器配置或主机剪贴板。`--diagnostic` 与源码哈希差异永不接受。
- 最终浏览器门强制运行父项排除、Dialog 内可信 CDP 滚轮/焦点、SSR/Flight/并发匿名隔离、IME、保存锁/失败草稿、安全插入、TOC、实测对比度、可信触摸热区与本地退出。任何 pageerror、非许可外部请求、缺少案例或系统头均拒绝回执。
- 合成后端不能证明 Java RSA 实际兼容、生产配额、后端撤销、真实权限缓存或 Docker/托管平台部署；应用 fetch 限制也不是操作系统级零出网证明。

## 编辑器恢复与边界

恢复版保留标题/slug/分类/标签/状态/父项/排序/发布字段、原生 Markdown 输入、安全链接/图片插入、格式工具、列表续写、50 个/4M 字符历史预算、普通 Tab、分栏/延后预览、未保存提示及锁定提交。固定基线包含 120 个表情、17 项 Markdown 语法速查、H1–H5 标题与双向比例预览滚动同步；当前实现保留这些入口，并另提供 H6。Node/React 夹具只验证状态和事件逻辑，不能证明真实滚轮、布局或弹层命中；本轮没有接受的浏览器验收。固定提交的原编辑器没有专用公式插入项；另行整读该提交的 Markdown renderer 确认仅使用 Marked/GFM 和代码、图片、表格、标题 hooks，没有数学扩展。数学字符串持久化不等于数学排版能力。

## 已知占位与后续接入点

- **站点信息**：站名/描述/页脚来自后端公开接口 `GET /api/v1/app/cms/setting`（`src/lib/site.ts` 的 `loadSiteSettings()`），后端不可达或字段为空时回退本地默认值。
- **评论真实 IP**：评论限流按后端视角的请求 IP 计。平台函数出口 IP 是共享的，若需按访客真实 IP 限流，需在 `src/app/api/comment/route.ts` 转发时透传 `X-Forwarded-For`，并把托管平台出口网段加入后端 `astral.plugins.cms.trusted-proxies` 白名单（默认白名单仅含内网网段，不受信的 XFF 会被后端忽略，不存在伪造风险）。
- **缓存策略**：认证读与 BFF 使用 private/no-store；React `cache` 仅在当前服务端渲染去重，不能跨访客缓存。不要直接移除 `force-dynamic` 或启用共享 ISR：登录用户可读取非公开内容。只有独立、强制匿名的数据通道经过隔离验证后才可讨论公共缓存。
- **图片**：封面直接用 `<img>` 渲染后台填写的任意外链，未接入 `next/image` 域名白名单，便于后台自由使用图床。
- **SEO**：`src/app/sitemap.ts` / `src/app/robots.ts` 在运行时生成站点地图与爬虫规则；生产部署请设置 `NEXT_PUBLIC_SITE_URL`（如 `https://blog.example.com`），否则 canonical/OG 会回退到 `http://localhost:3001`。
