# Blog 独立 Spec 复审 — FAIL

冻结基线/HEAD：`a39a3f3039ce7deafc3f547a25cc0dc4635c111a`。提交范围及三点 diff 均为空；评审对象是 dirty＋untracked WIP，而非 HEAD。未读取 Standards 报告；本报告覆盖有限，不宣称完整结案。

- **(a) P2：Tab 功能未保留。** [SPEC:45](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/snapshot/docs/audit/2026-11-07/SPEC.md#L45>)要求保留列表/Tab。[基线:300–320](<F:/qtMusic/astral/target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L300-L320>)支持光标处插入两格及块缩进；[当前:255–276](<F:/qtMusic/astral/astral-blog/src/components/markdown-editor.tsx#L255-L276>)只提供 Ctrl/Cmd+[ / ] 整行缩进，Tab 直接返回。例如 `a|b` 无法得到 `a  |b`；整行缩进并不等价。恢复该语义并保留可退出的键盘导航，或提供经 Spec 核验的替代。
- **(c) P2：整屏工作台不是正文全屏的等价实现。** [SPEC:43](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/snapshot/docs/audit/2026-11-07/SPEC.md#L43>)不授权默认删功能。[基线:427–451、595–607](<F:/qtMusic/astral/target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L427-L451>)可使正文独占视口并退出；[补丁:5592–5604](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/tracked-working-tree.patch#L5592-L5604>)删除控制。[当前工作台:331、377–405](<F:/qtMusic/astral/astral-blog/src/components/editable-article.tsx#L331-L405>)虽铺满视口，仍常驻标题/320px 属性栏；[当前视图:409–425](<F:/qtMusic/astral/astral-blog/src/components/markdown-editor.tsx#L409-L425>)无正文专注模式。应恢复或实现可往返的等价模式。
- **(b) 未确认越界扩张。** 已读新增安全边界、错误反馈和 sitemap 属于 [SPEC:5、13](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/snapshot/docs/audit/2026-11-07/SPEC.md#L5-L13>)质量/体验范围；不据此给未读部分通过。

Emoji、速查、双向同步接线存在。父页算法保留祖先1/2与无关9，排除编辑7/后代8；弹层 context 接最近 Dialog 实际节点。真实滚轮/双向同步几何、IME、可信输入、Cookie/后端运行仍 UNKNOWN。两份真实基线未建立专用数学能力，依 [SPEC:44](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/snapshot/docs/audit/2026-11-07/SPEC.md#L44>)不虚构数学恢复要求。门禁元数据不替代语义或浏览器验收。

---

## 阅读覆盖清单（正文长度限制外）

FULL = 本成员实际 `read` 区间并集覆盖全文件；每次最多80行。PARTIAL 只表示列出的区间。搜索/哈希/父会话阅读均不算 FULL。候选118文件中：39 FULL（含1个测试工具）、1 PARTIAL、其余78 NOTREAD。生产/配置 authoredManifest 的104文件中：38 FULL、1 PARTIAL、65 NOTREAD。未整读完整候选或9003行补丁；故这是有确证缺陷的有限覆盖 FAIL，不是全范围复审完成。

### 实际当前文件

| 文件 | 实际 read 区间 | 状态 |
|---|---:|---|
| [markdown-editor.tsx](<F:/qtMusic/astral/astral-blog/src/components/markdown-editor.tsx#L1-L535>) | 1–535 | FULL |
| [editable-article.tsx](<F:/qtMusic/astral/astral-blog/src/components/editable-article.tsx#L1-L658>) | 1–658 | FULL |
| [dialog-portal.tsx](<F:/qtMusic/astral/astral-blog/src/components/ui/dialog-portal.tsx#L1-L9>) | 1–9 | FULL |
| [popover.tsx](<F:/qtMusic/astral/astral-blog/src/components/ui/popover.tsx#L1-L42>) | 1–42 | FULL |
| [dialog.tsx](<F:/qtMusic/astral/astral-blog/src/components/ui/dialog.tsx#L1-L142>) | 1–142 | FULL |
| [select.tsx](<F:/qtMusic/astral/astral-blog/src/components/ui/select.tsx#L1-L177>) | 1–177 | FULL |
| [dropdown-menu.tsx](<F:/qtMusic/astral/astral-blog/src/components/ui/dropdown-menu.tsx#L1-L80>) | 1–80 / 212 | PARTIAL |
| [comments-section.tsx](<F:/qtMusic/astral/astral-blog/src/components/comments-section.tsx#L1-L66>) | 1–66 | FULL |
| [retry-button.tsx](<F:/qtMusic/astral/astral-blog/src/components/retry-button.tsx#L1-L16>) | 1–16 | FULL |
| [editor.ts](<F:/qtMusic/astral/astral-blog/src/lib/editor.ts#L1-L232>) | 1–232 | FULL |
| [markdown-insert.ts](<F:/qtMusic/astral/astral-blog/src/lib/markdown-insert.ts#L1-L15>) | 1–15 | FULL |
| [markdown-tools.ts](<F:/qtMusic/astral/astral-blog/src/lib/markdown-tools.ts#L1-L143>) | 1–143 | FULL |
| [backend.ts](<F:/qtMusic/astral/astral-blog/src/lib/backend.ts#L1-L46>) | 1–46 | FULL |
| [bff.ts](<F:/qtMusic/astral/astral-blog/src/lib/bff.ts#L1-L174>) | 1–174 | FULL |
| [session.ts](<F:/qtMusic/astral/astral-blog/src/lib/session.ts#L1-L86>) | 1–86 | FULL |
| [auth-server.ts](<F:/qtMusic/astral/astral-blog/src/lib/auth-server.ts#L1-L77>) | 1–77 | FULL |
| [client-request.ts](<F:/qtMusic/astral/astral-blog/src/lib/client-request.ts#L1-L29>) | 1–29 | FULL |
| [url.ts](<F:/qtMusic/astral/astral-blog/src/lib/url.ts#L1-L48>) | 1–48 | FULL |
| [admin-route.ts](<F:/qtMusic/astral/astral-blog/src/lib/admin-route.ts#L1-L85>) | 1–85 | FULL |
| [comment.ts](<F:/qtMusic/astral/astral-blog/src/lib/comment.ts#L1-L38>) | 1–38 | FULL |
| [rate-limit.ts](<F:/qtMusic/astral/astral-blog/src/lib/rate-limit.ts#L1-L94>) | 1–94 | FULL |
| [result.ts](<F:/qtMusic/astral/astral-blog/src/lib/result.ts#L1-L53>) | 1–53 | FULL |
| [markdown.ts](<F:/qtMusic/astral/astral-blog/src/lib/markdown.ts#L1-L361>) | 1–361 | FULL |
| [cms-validation.ts](<F:/qtMusic/astral/astral-blog/src/lib/cms-validation.ts#L1-L219>) | 1–219 | FULL |
| [cms-status.ts](<F:/qtMusic/astral/astral-blog/src/lib/cms-status.ts#L1-L21>) | 1–21 | FULL |
| [site-url.ts](<F:/qtMusic/astral/astral-blog/src/lib/site-url.ts#L1-L39>) | 1–39 | FULL |
| [page-input.ts](<F:/qtMusic/astral/astral-blog/src/lib/page-input.ts#L1-L11>) | 1–11 | FULL |
| [cms.ts](<F:/qtMusic/astral/astral-blog/src/lib/cms.ts#L1-L399>) | 1–399 | FULL |
| [crypto.ts](<F:/qtMusic/astral/astral-blog/src/lib/crypto.ts#L1-L24>) | 1–24 | FULL |
| [login route](<F:/qtMusic/astral/astral-blog/src/app/api/auth/login/route.ts#L1-L83>) | 1–83 | FULL |
| [logout route](<F:/qtMusic/astral/astral-blog/src/app/api/auth/logout/route.ts#L1-L37>) | 1–37 | FULL |
| [session route](<F:/qtMusic/astral/astral-blog/src/app/api/auth/session/route.ts#L1-L35>) | 1–35 | FULL |
| [admin meta route](<F:/qtMusic/astral/astral-blog/src/app/api/admin/meta/route.ts#L1-L48>) | 1–48 | FULL |
| [admin export route](<F:/qtMusic/astral/astral-blog/src/app/api/admin/export/[...path]/route.ts#L1-L68>) | 1–68 | FULL |
| [comment route](<F:/qtMusic/astral/astral-blog/src/app/api/comment/route.ts#L1-L61>) | 1–61 | FULL |
| [error.tsx](<F:/qtMusic/astral/astral-blog/src/app/error.tsx#L1-L51>) | 1–51 | FULL |
| [loading.tsx](<F:/qtMusic/astral/astral-blog/src/app/loading.tsx#L1-L23>) | 1–23 | FULL |
| [robots.ts](<F:/qtMusic/astral/astral-blog/src/app/robots.ts#L1-L22>) | 1–22 | FULL |
| [sitemap.ts](<F:/qtMusic/astral/astral-blog/src/app/sitemap.ts#L1-L68>) | 1–68 | FULL |
| [source-manifest.cjs](<F:/qtMusic/astral/astral-blog/tests/source-manifest.cjs#L1-L35>) | 1–35 | FULL；仅哈希工具 |

以上完整文件共4370行，加 PARTIAL 80行，实际当前候选正文阅读4450行。不把固定基线/Spec/补丁重复内容计入该数。

### 固定基线、规范与候选材料

| 文件 | 实际 read 区间 | 状态/用途 |
|---|---:|---|
| [真实固定 Editor 摘录](<F:/qtMusic/astral/target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L1-L1028>) | 1–1028 | FULL；独立语义比对 |
| [真实固定 renderer 摘录](<F:/qtMusic/astral/target/audit-blog-renderer-baseline-20261107.txt.utf8.log#L1-L247>) | 1–247 | FULL；数学/渲染边界 |
| [冻结 SPEC](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/snapshot/docs/audit/2026-11-07/SPEC.md#L1-L58>) | 1–58 | FULL |
| [冻结 TEST-SEAMS](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/snapshot/docs/audit/2026-11-07/TEST-SEAMS.md#L1-L32>) | 1–32 | FULL |
| [冻结 UI_GUIDE](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/snapshot/docs/UI_GUIDE.md#L1-L249>) | 1–249 | FULL；范围为 astral-front，不机械套用 Blog |
| [冻结 patch](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/tracked-working-tree.patch>) | 1–80；3770–3839；4664–4743；5298–5377；5568–5647 / 9003 | PARTIAL，390行；其余未语义整读 |
| [冻结 untracked](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/untracked.txt#L1-L33>) | 1–33 | FULL；清单不等于文件正文 |
| [冻结 status](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/status.txt#L1-L103>) | 1–103 | FULL；Git 元数据 |
| [冻结 commits](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/commits.txt>) | 空文件 | FULL；Git 元数据 |
| [冻结 fixed-ref](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/fixed-ref.txt#L1>) | 1 | FULL；Git 元数据 |
| [冻结 head](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/head.txt#L1>) | 1 | FULL；Git 元数据 |
| [冻结 verification](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/verification.json#L1-L120>) | 1–120 / 725 | PARTIAL；gate/read claims 只计元数据 |
| [安装 Next 的 cookies 指南](<F:/qtMusic/astral/astral-blog/node_modules/next/dist/docs/01-app/03-api-reference/04-functions/cookies.md#L1-L80>) | 1–80 / 302 | PARTIAL；当前 async/request-time API边界 |
| [根 AGENTS](<F:/qtMusic/astral/AGENTS.md>)、[冻结 AGENTS](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/snapshot/AGENTS.md>) | 无 tool-read | 仅宿主指令注入；不称 FULL |

其余候选路径默认 NOTREAD，包含其余8个测试 driver、入口/详情/分享页、登录/评论/会话组件、增强器、其余 UI 基元、CSS/manifest/锁文件/部署说明。路径盘点以 [冻结候选 checkedFiles](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/verification.json#L78>)为准；机器逐文件哈希盘点不算语义阅读。`glob` 的129项/100项截断以及 patch 252个 hunk grep 也不算 FULL。未读取历史 parity/recovery 报告来代替真实基线，未读取另一轴报告。

### 判断边界与独立证据

- 父页：当前 [editor:139–164](<F:/qtMusic/astral/astral-blog/src/lib/editor.ts#L139-L164>) collect 仅沿编辑节点子树收集排除集合，flatten 其余节点。树 `1→2→7→8` 加独立 `9` 时保留1/2/9、排除7/8；[实际 combobox:442–467](<F:/qtMusic/astral/astral-blog/src/components/editable-article.tsx#L442-L467>)消费该列表，含根选项与不可用当前值。这里只是算法/组件接线的独立阅读，不声称真实点击或浏览器 PASS。
- 最近 Dialog：实际 [dialog:39–71](<F:/qtMusic/astral/astral-blog/src/components/ui/dialog.tsx#L39-L71>) ref 更新真实 Content 节点并向子树提供 context；[popover:23–41](<F:/qtMusic/astral/astral-blog/src/components/ui/popover.tsx#L23-L41>)、[select:57–63](<F:/qtMusic/astral/astral-blog/src/components/ui/select.tsx#L57-L63>)、[dropdown:39–46](<F:/qtMusic/astral/astral-blog/src/components/ui/dropdown-menu.tsx#L39-L46>)用该节点 portal 且 pointer-events-auto。无源码证据支持“仍无条件 body portal”的缺陷，但真实 wheel/defaultPrevented/scrollTop/overflow 几何 UNKNOWN。
- 当前 [Emoji:346–380、速查:381–406](<F:/qtMusic/astral/astral-blog/src/components/markdown-editor.tsx#L346-L406>)和 [同步:72–89](<F:/qtMusic/astral/astral-blog/src/components/markdown-editor.tsx#L72-L89>)均实际存在；[onScroll:445、458](<F:/qtMusic/astral/astral-blog/src/components/markdown-editor.tsx#L435-L461>)已接线。浏览器同步效果 UNKNOWN，不虚构无实现或效果已通过。
- 未做浏览器、CDP、bootstrap、诊断/重试、网络、真实后端/DB/环境读取；未跑回归/构建门禁。已加载本地 code-review、React/Vercel、Web guidelines skill；新鲜网络 guidelines 未获取（明确 no-network 边界）。没有 browser GREEN。

### 冻结核对与关闭状态

- 独立运行固定命令：`git diff --no-ext-diff --binary a39a3f3039ce7deafc3f547a25cc0dc4635c111a -- astral-blog`；与冻结 patch 的逐行内容一致（这里只做机器比对，不称补丁完整语义阅读）。固定 ref/HEAD一致、`fixed..HEAD` 0提交、三点 diff 0行。
- Git tracked＋untracked 排除ignored共118文件，与snapshot逐文件SHA256匹配，0缺失/0差异；另用实际哈希工具核对104 authored源码/配置同样0差异。candidate原 aggregate标识 `d0f3718da427be53de894365a600ae0a35ba9c08112acba6cd30fa21aa15b8c3` 来自冻结元数据；本成员的冻结结论依据逐文件哈希，不冒充重建了该aggregate算法。
- patch：403998 bytes，SHA256 `01fdf3bdfaf8a89a6d8b1c84f8ac12ba24d9b6c7f055963462e3024d94d47fb7`。
- Editor基线：38503 bytes，SHA256 `4874c086710fb495f34097ea93133962e0d09555d1a6145f30caf7c5ca786a41`；renderer基线：8630 bytes，SHA256 `504aed9905e17b823f4d01bc4bd83bb52d0cfd254fedd3d1c197c6ec61489642`。
- 初次 Node `-e` 哈希命令因PowerShell引号丢失出现 SyntaxError/exit1；改stdin保留脚本后exit0。三次超出已知EOF的read不计覆盖（219/399/35行已完整阅读）。本成员未删除、忽略产品测试或伪称通过。
- 产品/审计文件只写本报告，未改源码/测试/锁/POM/共享PLAN或另一报告；另按宿主纪律追加工作日志。未stage/commit/push，未启后台job或子代理。收尾状态是“报告交付、两项Spec失败待Lead处理、全候选语义覆盖仍未完成”，不是Astral完成或最终验收。
