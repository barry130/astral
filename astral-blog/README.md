# astral-blog

Astral 站点的公开博客 + Wiki 前台。类 Typecho/WordPress 风格的轻量阅读站点，作为**独立 Next.js 应用**部署（与管理端 `astral-front` 分离），数据来自 astral 后端 CMS 插件的公开 API（`/api/v1/app/cms/*`，匿名，契约见仓库 `docs/CMS_PLUGIN.md`）。

## 技术栈

- Next.js **16.3.8**（App Router + Turbopack，与 astral-front 同版本）
- React 19 + TypeScript 5.6（strict）
- Tailwind CSS v4（`@tailwindcss/postcss`，默认调色板，中性色 + 唯一强调色 sky-700）
- Markdown 渲染：**marked**（服务端渲染，文章/Wiki 内容均由后台管理员撰写）
- 包管理：**pnpm**

## 架构要点

- **浏览器不直连后端**：所有对 astral 后端的请求都在服务端完成 —— 列表/详情页在 RSC 中 `fetch`，评论提交走本应用自己的 Route Handler（`src/app/api/comment/route.ts`）转发。天然无 CORS 问题。
- **数据页全部动态渲染**（`export const dynamic = 'force-dynamic'`）：构建期不发任何必需数据请求，**后端不可达也能正常 `pnpm build`**；运行时后端不可达时页面展示友好空态而非 500。
- 后端地址由环境变量 **`CMS_API_BASE`** 控制（默认 `http://localhost:27000`），见 `src/lib/cms.ts`。
- 评论的业务失败（站点关闭评论、触发限流等）会把后端 `Result.msg` 原样透传给用户展示。

## 页面路由

| 路由 | 说明 |
| --- | --- |
| `/` | 首页：文章列表（置顶标识/分类/标签/日期/阅读量）+ 分页；侧栏：站点信息、分类树、标签云 |
| `/post/[slug]` | 文章详情：标题/日期/分类/标签/浏览量 + Markdown 正文 + 评论区（列表 + 匿名提交表单） |
| `/wiki` | Wiki 目录：`page/tree` 渲染为树状列表 |
| `/wiki/[slug]` | Wiki 页面：面包屑（breadcrumb 父链）+ Markdown 正文 + 子页面列表 + 评论 |
| `/categories` | 导航「分类」落地页：分类树 + 标签列表 |
| `/category/[slug]` | 按分类过滤的文章列表 + 分页 |
| `/tag/[slug]` | 按标签过滤的文章列表 + 分页 |
| `/api/comment` | POST：评论提交的 Route Handler（服务端转发到后端 `POST /api/v1/app/cms/comment`） |

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
mvn -pl astral-server spring-boot:run -Dspring-boot:run.arguments=--server.port=27000
```

后端未启动时前台也能正常打开：列表/详情等各区块显示「暂时无法加载/暂无内容」的空态。

## 构建

```bash
pnpm build      # 生产构建（含类型检查；不发真实数据请求）
pnpm start      # 以生产模式启动（需先 build）
```

## 环境变量

| 变量 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `CMS_API_BASE` | 否（生产建议显式配置） | `http://localhost:27000` | astral 后端基地址，末尾不要带 `/`；公开 CMS API 路径为 `{CMS_API_BASE}/api/v1/app/cms/*` |

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
3. 框架预设选择 **Next.js**；构建命令保持 `pnpm install && pnpm build`（若平台仅支持 npm，则 `npm install && npm run build`；亦可在「根目录」填 `astral-blog` 让平台指向子目录）。
4. **环境变量**中添加：`CMS_API_BASE = https://astral-api.example.com`（你的后端公网地址）。如构建期 Node 版本报错，可再添加 `NODE_VERSION = 22`。
5. 触发部署，等待构建完成获得 `*.edgeone.app` 预览域名，先验证页面与评论功能。
6. 绑定自定义域名：项目 → **域名管理** → **添加自定义域名**（如 `blog.example.com`），按提示在你的 DNS 处添加 CNAME 记录指向平台分配的接入域名，等待证书签发生效。
   - 使用中国大陆接入节点需域名已完成 ICP 备案；未备案可选择仅境外节点。
7. 注意：控制台的预设参数以平台当前 UI 为准（EdgeOne Pages 迭代较快）；关键是「Next.js 预设 + CMS_API_BASE 环境变量 + 自定义域名 CNAME」三件事。

### 方式二：Cloudflare Pages（@cloudflare/next-on-pages）

本项目所有服务端代码只用标准 `fetch` + `marked`（均为 edge 兼容实现，无 Node 专属 API），可直接走 `@cloudflare/next-on-pages` 适配器把服务端跑在 Cloudflare Edge 上：

1. 代码推送到 GitHub / GitLab。
2. Cloudflare Dashboard → **Workers & Pages** → **Create application** → **Pages** → **Connect to Git**，选择仓库。
3. 构建配置：
   - Framework preset：`Next.js`（或 None，手动填下面两项）
   - **Build command**：`npx @cloudflare/next-on-pages@1`
   - **Build output directory**：`.vercel/output/static`
   - 仓库子目录：`astral-blog`（monorepo 场景）
4. **Settings → Environment variables** 添加（Production 与 Preview 都加）：
   - `CMS_API_BASE = https://astral-api.example.com`
   - `NODE_VERSION = 22`
5. **Settings → Functions → Compatibility flags**：为 Production 和 Preview 都添加 `nodejs_compat`（next-on-pages 必需）。
6. 首次部署完成后获得 `*.pages.dev` 域名；绑定自定义域名：项目 → **Custom domains** → Set up a custom domain（域名需托管在该 Cloudflare 账号或可添加 CNAME）。
7. 本地验证（可选）：

   ```bash
   npx @cloudflare/next-on-pages@1
   npx wrangler pages dev .vercel/output/static
   ```

> 版本兼容提示：`@cloudflare/next-on-pages` 对 Next 最新大版本的支持可能滞后。若构建日志提示不支持的 Next 版本，可临时把 `package.json` 中 `next` 锁到适配器公告支持的最新版本；或改用官方新一代适配器 **OpenNext（opennextjs-cloudflare）**，它将站点部署为 Cloudflare Workers（而非 Pages）：`pnpm add -D @opennextjs/cloudflare wrangler`，构建 `npx opennextjs-cloudflare build`，本地预览 `npx opennextjs-cloudflare preview`，发布 `npx opennextjs-cloudflare deploy`（需最小化 `wrangler.jsonc` 配置，详见其官方文档）。

### 部署后自检清单

- [ ] 首页能列出后台已发布文章（置顶在前，按发布时间倒序）
- [ ] `/wiki` 目录树、面包屑、子页面跳转正常
- [ ] 分类 / 标签点击过滤正常（无 slug 的用数字 ID）
- [ ] 文章详情提交评论：成功提示 → 后台审核通过后前台可见；关闭评论/触发限流时能看到后端返回的 message
- [ ] 停掉后端再刷新：页面为友好空态而非 5xx

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
│       └── site.ts                 # 站点展示信息（占位，见 TODO）
├── package.json / tsconfig.json / next.config.ts / postcss.config.mjs
└── README.md
```

## 已知占位与后续接入点

- **站点信息**：后端暂无公开站点设置接口（`cms_setting` 的 `site_title`/`site_description`/`footer_text` 未开放公开 API），站名/描述/页脚先在 `src/lib/site.ts` 与 `src/app/layout.tsx` 用默认值占位（代码内有 TODO 标记），接口就绪后改为 RSC 拉取。
- **评论真实 IP**：评论限流按后端视角的请求 IP 计。平台函数出口 IP 是共享的，若需按访客真实 IP 限流，需在 `src/app/api/comment/route.ts` 转发时透传 `X-Forwarded-For`，并把托管平台出口网段加入后端 `astral.plugins.cms.trusted-proxies` 白名单（默认白名单仅含内网网段，不受信的 XFF 会被后端忽略，不存在伪造风险）。
- **缓存策略**：当前全部动态渲染（每请求回源）。若后端内容更新频率低，可把数据页改为 ISR（`export const revalidate = 60` + 移除 `force-dynamic`）以降低后端压力。
- **图片**：封面直接用 `<img>` 渲染后台填写的任意外链，未接入 `next/image` 域名白名单，便于后台自由使用图床。
