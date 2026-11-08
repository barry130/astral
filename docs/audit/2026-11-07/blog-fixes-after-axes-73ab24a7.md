# Blog task-23：四项已接受发现的修复闭环

日期：2026-11-07（Asia/Shanghai）。职责：唯一 Blog source/tests writer；Java、最终独立审阅与本地提交由 Lead 负责。

**分片状态：sourceFrozen=true；六串行门禁全部 exit 0；server 48/48、React 36/36，fail/skip/cancel/todo 均 0；所有相关 jobs 已收齐，无 task-23 残留 Node 进程。未 stage、commit 或 push。**

这只关闭 task-23 的四项已接受发现及其限定验证，不宣称完成 Astral 总目标，也不宣称完成 118 文件的完整语义阅读或 fresh independent 双轴验收。真实浏览器验收仍 UNKNOWN。

## 1. 固定对象与边界

- 入口 HEAD 与收尾实查 HEAD 均为 `a39a3f3039ce7deafc3f547a25cc0dc4635c111a`。原候选为非空 WIP diff + untracked，不把空 HEAD 三点差异当作审查对象。
- 原 [Spec 报告](<F:/qtMusic/astral/docs/audit/2026-11-07/blog-spec-review-73ab24a7.md>) SHA256：`9a1be33ecdec604365e8027e41629a3d2208c58b5115311228935b0654d0a5cb`。
- 原 [Standards 报告](<F:/qtMusic/astral/docs/audit/2026-11-07/blog-standards-review-73ab24a7.md>) SHA256：`fca9fb107a211877499edac8c37c1c0dd46526dcb8ae6e96eadd7a9a788ae899`。两份原报告保持未改。
- 原 [118 项 verification](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/verification.json>) 的 Blog 清单逐项核对：本次恰好 6 项改变、112 项保持原 SHA。清单哈希覆盖不是逐项语义阅读证明。
- 只改下面列出的 4 个授权生产文件及 2 个必要测试文件。未改 manifests/lock、旧 reports/receipts、编译器、9 个驱动的集合、ignores、共享 PLAN、Java、astral-front、其他项目或 DSH。
- 遵守 [SPEC](<F:/qtMusic/astral/docs/audit/2026-11-07/SPEC.md>)、[TEST-SEAMS](<F:/qtMusic/astral/docs/audit/2026-11-07/TEST-SEAMS.md>)、[UI_GUIDE](<F:/qtMusic/astral/docs/UI_GUIDE.md>) 与 Integration 同源 Strict 契约。不再把专用数学功能当作基线要求。

## 2. 生产修复与公共回归

### P2：Tab parity

[MarkdownEditor:245–311](<F:/qtMusic/astral/astral-blog/src/components/markdown-editor.tsx#L245-L311>)：折叠光标 plain Tab 在准确光标处插入两空格；选中块 Tab 缩进完整行；Shift+Tab 反缩进完整行。已有 Ctrl/Cmd `]` / `[` 路径、历史与列表继续行为保留。disabled、compositionStart 生命周期与 native isComposing 不拦截。

为防 keyboard trap，Esc 明确释放下一次 Tab / Shift+Tab 供原生焦点导航；blur/其他按键清除该一次性释放状态，帮助文本说明此路径。原 32 项及其原断言全部保留；原“native Tab”步骤增加 Esc，适配明确的新导航契约，未删除/弱化断言。新增真实 React 回归覆盖 caret、selection、focus、undo/redo、block/outdent、bracket shortcuts 和 composing。

### P2：body-only fullscreen parity

[MarkdownEditor](<F:/qtMusic/astral/astral-blog/src/components/markdown-editor.tsx>) 新增“全屏编辑 / 退出全屏”、aria-pressed 和可访问 region 名称；使用同一根节点与同一 textarea 的 CSS fixed viewport 模式，不使用浏览器 Fullscreen API，也不重新挂载/reparent textarea。切换保留内容、selection、focus 与 undo history；composition 期间不切换。

[EditableArticle:330–423](<F:/qtMusic/astral/astral-blog/src/components/editable-article.tsx#L330-L423>) 同步 body 模式，将标题、工作台头部（含保存/取消）与属性栏 hidden + inert，保留它们的 DOM/草稿并在退出恢复；头部 fullscreen 时明确使用 hidden class，避免 unconditional flex author rule 覆盖原生 hidden。保存/关闭原有规则保留，成功保存与关闭重置 parent 模式。

集成公开回归真实暴露 Escape 所有权问题：Radix 的 document-level Escape 先于 textarea bubbling，会先打开丢弃确认。文章层在 body 模式保护自己的关闭路径，textarea Escape 先退出 body 模式；嵌套实际链接 Dialog 的 Escape 只关内层，不退出正文或打开丢弃确认。root 以实际 DOM containment 排除 portal 冒泡。原 Emoji/DropdownMenu 的 nearest actual Dialog portal 与 pointer-events-auto 均保持。

新增两项真实 React 回归分别覆盖独立 widget 与完整文章工作台；DOM fixture 的 hidden/inert/portal/class 断言不是 CSS geometry 或 hit-testing 验收。

### P2：BFF Strict authentication cookies

[BFF:133–154](<F:/qtMusic/astral/astral-blog/src/lib/bff.ts#L133-L154>) 将 forward 与 clear 的 sameSite 都改成 strict。保留 satoken / astral_csrf allowlist、host-only（不复制 Domain）、Path=/、token HttpOnly、CSRF 可读、HTTPS/canonical Secure、Max-Age/Expires 检查、clear maxAge=0，以及安全 identity projection。不引入客户端 token 存储。

[实际登录回归:160–195](<F:/qtMusic/astral/astral-blog/tests/security.test.cjs#L160-L195>) 将原允许 Lax/Strict 的断言收紧为 Strict，并加 Max-Age=60；执行的是真实 login POST、Request/Response/NextResponse 和外部 fetch fixtures。真实 Set-Cookie 在浏览器中的处理顺序不由该测试证明。

### P3：共享 upstream failure normalization

[BFF:158–174](<F:/qtMusic/astral/astral-blog/src/lib/bff.ts#L158-L174>) 的 upstreamFailure 增加默认等于 fallback 的可选 businessFallback；[export route](<F:/qtMusic/astral/astral-blog/src/app/api/admin/export/[...path]/route.ts>) 调用该现有 helper，删除重复包络。

行为保持：business error 的 code/errorCode/msg、data=null、HTTP transport status 与 business status 分层不变；无业务消息时仍为“导出失败。”，JSON 成功冒充或 malformed JSON 仍为“后端导出响应异常。”。非 JSON 的 MIME allowlist、原字节流、attachment、private no-store、nosniff 保持。未改原 export 保护测试；server 同一套件在重构前后均 48/48。不为纯重构伪造 RED。

## 3. 真正执行的 RED → 最小修复 → 同 suite GREEN

每行都为完整既有 suite（不以内部 parser/source string/复刻算法制造测试）。所有 RED 是目标行为失败，不是环境/安装失败。每次独立日志、UUID receipt 与前后输入 hash 均保留。

| 纵切 | RED receipt / 实际失败 | GREEN receipt / 同 suite |
| --- | --- | --- |
| Tab caret | [RED](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-tab-caret-red-3f82ec33-77b7-4504-9aa6-d1afe344d985/receipt.json>)：33 tests / 32 pass / 1 fail，`ab !== a  b`，exit 1 | [GREEN](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-tab-caret-green-dac4e05f-c09c-4b48-8dfe-79042352555c/receipt.json>)：33/33，exit 0 |
| Tab block | [RED](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-tab-block-red-da73737c-ce52-429a-bbca-ff26bc9b9daf/receipt.json>)：34/33/1，Tab defaultPrevented 为 false，exit 1 | [GREEN](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-tab-block-green-b895e65e-08af-4b71-8fc3-671bb58872cc/receipt.json>)：34/34，exit 0 |
| Fullscreen widget | [RED](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-fullscreen-widget-red-a17949a7-cbb0-43c3-901e-6bbd25eb8335/receipt.json>)：35/34/1，“body fullscreen control exists”失败，exit 1 | [GREEN](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-fullscreen-widget-green-26450ef4-ccf3-4880-b758-ba3622255d8d/receipt.json>)：35/35，exit 0 |
| Fullscreen article | [RED](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-fullscreen-article-red-0fda047e-a4c2-4637-8e7b-4ed4d7aff260/receipt.json>)：36/35/1，covered title 仍可 keyboard navigation，exit 1 | [GREEN](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-fullscreen-article-green2-71dff0bb-7c8c-44a9-b9da-11fc72c1e050/receipt.json>)：36/36，exit 0 |
| Strict actual login | [RED](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-strict-login-red-37881d41-cab3-4639-938f-95d7b4279d24/receipt.json>)：48/47/1，真实 satoken SameSite=lax 不匹配 Strict，exit 1 | [GREEN](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-strict-login-green-d3221f98-cf59-4aaa-9f0d-26c922b42355/receipt.json>)：48/48，exit 0 |

- [首次 article “green”尝试](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-fullscreen-article-green-03c87b45-0af2-4319-a8fe-03138b488683/receipt.json>) 仍是 RED：36/35/1，主 textarea Escape 后 dialog count `2 !== 1`。文件名不是判据；此失败被保留，未伪称通过、未放宽 count 断言。
- [P3 post-refactor](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-export-refactor-green-c3933710-92df-4cf8-880b-9158ed9347c6/receipt.json>)：原 public export protection + 全 server 48/48，exit 0。
- [窄格式化 receipt](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-narrow-format-e209aed9-f4e4-4631-979b-a53caf62ae9c/receipt.json>)：只写六项授权文件，exit 0；此步骤 sourceStable=false 是预期格式化变化，不能作冻结 gate。
- 下述 closure JSON 的 allAttempts 包含每个上述 receipt 和 output 的完整 SHA256、时间、退出码与计数，不仅是结果摘要。

## 4. 冻结后的六串行门禁

窄格式化后 source/tests 冻结，不再改作者源或测试。次序严格为 format → type → lint → server → React → isolated production build，前一项 finished 不晚于下一项 started；六项 before/after 与第一个 gate 的 frozen 输入 byte/hash 全等。

| Gate / receipt | Exit / 结果 | Receipt SHA256 | Output SHA256 |
| --- | --- | --- | --- |
| [format](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-final-format-8079555b-ce2b-455a-a530-a01f1f840c3a/receipt.json>) | 0，全 matched files 格式通过 | `e5586d7d03663f6fb72f5180c8d34a96c48fa5efd34938a90432995899b021c7` | `17aa973d3f004560237d9a95171210b0671deff23d61628eecf7322ff5938f20` |
| [type](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-final-type-3d8d143d-6e70-4039-92ee-6f161d0cda36/receipt.json>) | 0，原 typecheck | `d71750cdd536e2292c79eb6c352026b62c3f9aaa2cb82dbff3bf0ad997e49c48` | `8366207267355d3e3d5bf3bf6e8c94c5f93f6078c34f08973fa2b38cdda6cc92` |
| [lint](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-final-lint-baee6e39-4600-4358-a4ad-581b11b4835d/receipt.json>) | 0，max-warnings=0；无输出 | `ae46b08608fcb9d1b9ae538f3e87fd58411b87005a20815e79ddb87af8269298` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| [server](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-final-server-1c965210-fbe6-4f62-95d7-d5db93bd593c/receipt.json>) | 0，48/48；fail/skip/cancel/todo 0 | `ccf4ecaebb23fa31cecd965a31c9aefe45b0c0e881b9823cc879af7e9272d9eb` | `fd9f703edc3901edcceb1435d762ae18be5a2fdbba964306f088f8a970df38cd` |
| [React](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-final-react-1054148e-0d91-40f9-91f6-57774d9dfa67/receipt.json>) | 0，36/36；fail/skip/cancel/todo 0 | `9c048de39df0ed6d2ae19cb93582bb2e2852fcbda71b0c96ea58d991f476725d` | `afbcdaa7f5a63458c83bebde3a67e4421b0635b5fc3461e0185f01961e3c69d3` |
| [build](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-final-build-b32d4552-1a34-4f8e-ab6d-a31f74088a8e/receipt.json>) | 0，原 build:isolated / Next production build | `76a84bc0e9830e6a6bd8f50b66ff48e230841a72cc26407d3635e8c6cffc19bc` | `0bcd6d2efdf3bb098930ae51bea682bfc1d1a42f0e5a34b4dad02c1ac4aefe72` |

门禁保留原脚本与原恢复配置，format 覆盖全 src、原 tests drivers、README 与配置；lint 使用原 Astral ESLint + no-explicit-any:error，不弱化规则。server 与 React 均原 test-concurrency=1 / test-timeout=10000；未增加 ignore/skip、删驱动或全局格式化。

精确运行时（同一路径及 args 亦写进各 receipt）：

```powershell
# cwd: F:/qtMusic/astral/astral-blog
& 'C:/Users/canace/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/node/bin/node.exe' tests/.artifacts/task23-gate-73ab24a7.cjs <new-unique-label> <format|type|lint|server|react|build>
```

实际 Node `v24.21.0`、Next `16.4.3`；[package manifest](<F:/qtMusic/astral/astral-blog/package.json#L35-L37>) 为 React/ReactDOM `^19.2.0`，实际解析并参与构建的 React 为 **19.3.1**，没有改依赖。[pnpm lock](<F:/qtMusic/astral/astral-blog/pnpm-lock.yaml>) 与 package SHA 均保持原值。精确 pnpm 入口为 `C:/Users/canace/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/pnpm/bin/pnpm.mjs`。

## 5. 全清单冻结与文件 SHA

[Closure JSON](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-close-5b9670a4-9099-4c67-9da1-6b7a04c690fa.json>) SHA256：`13ddfd90651eecbbfa1bae8fa30bcbf03c66e7709bb9b7833bc233417865c3cd`。

其中 frozen.sources 包含全部 104 authored build inputs，frozen.tests 包含原全部 9 驱动，frozen.scope 包含 118 个 Blog 清单项；finalGates 和 allAttempts 记录完整时间/结果/输出/收据 SHA。校验程序已逐项比较六 gates 的 before/after、当前磁盘和孤立 build manifest。

聚合定义为按英文 locale 排序后的 `{path: SHA256}` map 的 `SHA256(JSON.stringify(map))`（无结尾换行），具体算法亦在 closure 中；不是文件串接 hash。

- 104 source inputs aggregate：`9ea631be6b49bc60bb4b35b0f42eae4bfd1bc62ea8e8f341255ef21da8d1eab5`
- 9 original drivers aggregate：`46f86f597ba2189f4b10c37f10ccd104792f993eb98df908ff5380e582688cdb`
- 118 Blog scope aggregate：`19007a5cddc36bcfcebcbe86631b39c596ad8bba46b5bb212d60c05996590996`

| 本次变化项 | 冻结后 SHA256 |
| --- | --- |
| [export route](<F:/qtMusic/astral/astral-blog/src/app/api/admin/export/[...path]/route.ts>) | `4883c71a7e77ef4c4e930ca1a572c32457ae9bdba1c3175e9ece436d14f755c3` |
| [EditableArticle](<F:/qtMusic/astral/astral-blog/src/components/editable-article.tsx>) | `50e33e469c592de47f85e0bfbcde130ed5d668a1cce589f3b563694dbfef938e` |
| [MarkdownEditor](<F:/qtMusic/astral/astral-blog/src/components/markdown-editor.tsx>) | `07c30d9d3ece8a2b6a377ba6cea7c11468c46c58fd6a92974da9a15d553c09d0` |
| [BFF](<F:/qtMusic/astral/astral-blog/src/lib/bff.ts>) | `7b3f937638ba431006245c3022a26a1a8123a5999357e891dfdb1cc7b6b783e7` |
| [React tests](<F:/qtMusic/astral/astral-blog/tests/react.test.cjs>) | `0869f4421b7132b5b73d7493d9162f5e428475c3e201ff4e390568ff253769bd` |
| [Security tests](<F:/qtMusic/astral/astral-blog/tests/security.test.cjs>) | `abcfd7c0bc413b5741334ca47c83cd4ca978b1dacbf86ad1e8ddbb1e05eb6bbf` |

其余 112 项保持原 verification SHA；9 驱动全部保留，其中只有上述 2 项测试有变化，另 7 项 byte/hash 不变。额外 runner/closure/receipt 位于原已忽略 artifacts，仅作本分片证据，不替换作者 drivers。

## 6. 孤立构建、清理和限制

[生产 build receipt](<F:/qtMusic/astral/astral-blog/tests/.artifacts/production-fa7e43c8-45b7-4e56-a013-ca88bcce6b32/build-receipt.json>) SHA256：`7a1a534d18a0f378af987be553697f5ef4968d94351cfb788914aa8057d9a194`。

- 原 build:isolated 复制 authored 源，生成 synthetic loopback config；realEnvCopied=false，没有复制真实 env，没有连接真实 backend/DB/data。其 104 项 authoredManifest 与冻结输入完全一致。
- Next 提示多 lockfiles / inferred workspace root warning；日志有此 warning，未为静音改 root/lock/config。编译、TypeScript 和路由产物完成，exit 0；这不是运行页面验收。
- [Cleanup receipt](<F:/qtMusic/astral/astral-blog/tests/.artifacts/task23-cleanup-0374f521-fe34-4871-9b88-e8c64fc3be38.json>) SHA256：`193ce6079e6c72f767c7248944b79c0e63ff2ffedbb6fd120a703feef21576dc`。先独立核对绝对节点、Junction/1040 与目标，再仅对专属 production fixture 的 node_modules 执行 `cmd rmdir`（无 /s、无递归）。junction 已移除，共享 node_modules 保留。首次 receipt 写入因实际 PowerShell 不支持 utf8NoBOM 失败，随后用 UTF-8 write 工具补写；没有重试删除。该记录纠正已明确披露。
- 最终报告验证首次使用 Node -e 遭实际 Windows PowerShell 原生参数引号剥离而 SyntaxError；改为专属 .cjs 文件执行同一校验，未改作者源/测试、未把该失败算成门禁通过。
- 专属 Node process command line 筛查结果为 0；无进程被停止，无应用 server 被启动。构建 background job 与只读 listing job 均 collected，当前无相关 jobs。
- 没有删除 pnpm store、外部 DOM fixture、旧候选/receipt 或共享依赖。失败日志和生产 fixture 证据保留。
- 真实 React/createRoot 与真实公共组件，只有授权的外部 jsdom 26.1.0 DOM fixture；真实 BFF 公共 route 只替换外部 fetch/request-cookie。无内部 AST/parser/source-string 替代测试或生产算法副本。
- jsdom 仅证明 DOM/events/state。真实 CSS viewport/小屏几何、trusted IME、真实 focus navigation、滚轮/点击 hit-testing、浏览器处理 Set-Cookie 顺序、真实路由导航和性能仍 UNKNOWN；未启动 Chrome/Edge/CDP/Playwright/AutoGLM，也未再次请求浏览器授权。

## 7. 交 Lead 的确定下一步

作者源与测试保持冻结。Lead 先独立复核本报告/closure/六 receipts 的 SHA 与当前 118 map，按权限完成 fresh full independent Spec/Standards coverage，再统一处理本地提交。共享 PLAN 与 Astral 总目标由 Lead 更新；task-23 不进行 staging、commit、push 或目标状态变更。
