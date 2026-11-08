# Blog 编辑器固定基线功能清单（task-18 · revision 2）

Lead 的实际 [task-18 get 收据](<task-recovery-records.json#L37-L69>)已读（收据全文 1–71，单次 read 71 行）：revision 2、owner lead、in_progress、无 blocker。精确 scope 为两测试重读、原 runtime 报告纠正、固定提交提取物完整阅读及四报告交付；子成员不修改任务或 PLAN。

## 1. 证据身份、计数与 fullRead

唯一源码证据为 Lead 保存的不可变 UTF-8 提取物：[固定基线提取物](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log>)。其声明来源为提交 `a39a3f3039ce7deafc3f547a25cc0dc4635c111a` 下的 [markdown-editor.tsx（仅声明提交来源，未读取 live 文件）](<../../../astral-blog/src/components/markdown-editor.tsx>)；本成员不运行 Git，不重新提取，也不把工作树同名源码当证据。提交与提取的对应身份由 Lead 提供，本成员独立确认提取物内容、计数及端点 hash。

| 项目 | 当前实读收据 |
| --- | --- |
| raw 行 / 非空行 / 字节 | 1028 / 962 / 38503 |
| 读前 SHA256（12:48:53.8231559 +08:00） | `4874c086710fb495f34097ea93133962e0d09555d1a6145f30caf7c5ca786a41` |
| 读后 SHA256（12:51:02.6229265 +08:00） | `4874c086710fb495f34097ea93133962e0d09555d1a6145f30caf7c5ca786a41` |
| 实际 read 区间 | 1–80, 81–160, 161–240, 241–320, 321–400, 401–480, 481–560, 561–640, 641–720, 721–800, 801–880, 881–960, 961–1028 |
| read 调用 / 最大段长 | 13 / 80 |
| continuous 1–EOF / fullRead | true / true，仅此提取物 |
| 统计口径 | UTF-8 ReadAllLines 计 raw；逐行 Trim 非空计数；Get-Item 字节；Get-FileHash SHA256 |

端点 hash 相等不证明采样间绝无短暂修改。fullRead 依据 13 次实际 read 输出及最后 EOF，不依据 shell 计数、grep 命中或旧报告推断。

## 2. 原有 Emoji（确有插入，不是只有图标）

[505–533 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L505-L533>)有「插入表情」Popover，10 列网格、184px 最大高度、可垂直滚动；每个 Emoji 按钮执行 `insertAtCursor(emoji)`。[204–215 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L204-L215>)用 textarea 当前 selectionStart/End 替换选区并通过 apply 恢复光标。这是静态实现，不是浏览器点击验收。

[984–997 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L984-L997>)固定数组有 **120 项**，以下按源码原顺序列出（含变体选择符的原字符，不是外部 Emoji 库推测）：

```text
😀 😁 😂 🤣 😃 😄 😅 😆 😉 😊
😋 😎 😍 😘 🙂 😐 😑 😶 🙁 😣
😥 😮 🤐 😯 😪 😫 😴 😌 😛 😜
😝 🤤 😒 😓 😔 😕 🙃 🤑 😲 🙄
😤 😭 😨 😩 🤯 😬 😰 😱 🥵 🥶
😳 🤪 😵 😡 😠 🤬 😷 🤒 🤕 🤢
🤮 🤧 😇 🤠 🤡 🤥 🤫 🤭 🧐 🤓
😈 👻 💀 ☠️ 👽 🤖 💩 😺 😸 😹
👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉
👆 👇 ☝️ ✋ 🤚 👋 🤝 🙏 💪 🎉
🎊 🔥 ✨ 💯 ⭐ 🌟 ❤️ 💔 💖 💙
💚 💛 🧡 💜 🖤 🤍 ✅ ❌ ⚠️ ❓
```

## 3. 数学：插入与渲染必须分开

**组件内没有专用数学公式插入按钮/操作/快捷键/速查 snippet。** 此负向结论基于完整 1–1028 行，而非关键词搜索：

- 全部 [3–51 行 imports](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L3-L51>)包含 UI、图标及 `renderMarkdown`，无数学编辑控件/本地数学插件导入。
- 全部插入原语、键盘处理及工具 actions 位于 [115–377 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L115-L377>)，只提供通用包裹/行前缀/块/光标、链接与图片、Tab/列表延续等；没有 `$...$`/`$$...$$` 专用操作。
- 完整 [455–609 行工具栏/菜单](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L455-L609>)及 [976–1028 行常量、速查、快捷键](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L976-L1028>)没有数学项。

**数学渲染能力未知，不能结论为“不支持”。** [350 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L350>)调用导入的 `renderMarkdown(value)`；[646–650 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L646-L650>)将结果作为 HTML 预览。导入目标实现不在授权提取物内，未读取，因此无法判断 KaTeX/MathJax/其他数学处理。用户仍可手输任意 Markdown/数学字符串；这不等于存在专用插入 UI，也不等于渲染经过验收。

## 4. 语法帮助、工具栏与基础编辑

- **15 个直接工具 actions**：[352–377 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L352-L377>)为撤销、重做、加粗、斜体、删除线、行内代码、引用、分割线、无序列表、有序列表、任务列表、链接、图片、表格、代码块。工具按钮有 aria-label/Tooltip；标题不是直排六按钮，而是 [490–501 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L490-L501>) **H1–H5** 下拉（没有 H6 项）。
- 工具栏另有 Emoji、语法速查、编辑/分栏/预览三视图与全屏。分栏按钮 `hidden sm:inline-flex`；布局 `flex-wrap` 自动换行，见 [455–609 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L455-L609>)。只确认静态布局/事件定义，不确认任何视口实际布局。
- **17 项点击即插入的速查**：[999–1017 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L999-L1017>)为一级标题、二级标题、加粗、斜体、删除线、行内代码、代码块、引用、无序列表、有序列表、任务列表、链接、图片、表格、分割线、折叠块、脚注。[551–555 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L551-L555>)执行 insertAtCursor；不因此证明折叠块/脚注已被外部 renderer 正确渲染。
- 快捷键帮助有 **8 项**（B/I/K/Z/Shift+Z、Tab/Shift+Tab、列表 Enter、空项 Enter），见 [1019–1028 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L1019-L1028>)；实际键盘处理还支持 Ctrl/Cmd+Y 重做和 Ctrl/Cmd+S 阻止浏览器默认保存，见 [258–348 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L258-L348>)。语法菜单和底部 Markdown 按钮都可打开快捷键 Dialog，见 [561–568 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L561-L568>)、[655–686 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L655-L686>)。
- 包裹选区/占位后选中、行前缀整行开关、块前后空行、两格 Tab/Shift+Tab、多行缩进、有序列表递增/任务项重置未勾选/空项退出在 [115–348 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L115-L348>)实现。行前缀动作末尾总是折叠选区；自管 undo/redo 只存正文（200 past 上限），不保存选择方向/光标，见 [101–113 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L101-L113>)、[217–229 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L217-L229>)。

## 5. 双向滚动（确有两向接线，不是只从编辑侧同步）

[387–424 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L387-L424>)的 syncScroll(source) 仅在 split 工作：按两侧 `scrollHeight-clientHeight` 可滚动高度比例计算目标；任一高度 ≤0 则退出；差值 <1px 则退出；syncLock 标记被程序滚动侧，并在下一帧清锁；切入 split 时先从 editor 对齐一次。

两向事件接线分别在 [623 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L623>) `syncScroll('editor')` 与 [641 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L641>) `syncScroll('preview')`。确认的是比例同步算法和双向静态接线；不是逐段文档语义锚点、实际防抖/滚轮效果/字体尺寸改变后的正确性验收。

## 6. IME、selection、focus 与嵌套快捷键边界

| 轴 | 完整基线中的实际实现 | 不得越过的结论边界 |
| --- | --- | --- |
| IME | 完整 [258–348 行键盘处理](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L258-L348>)无 `isComposing`/keyCode229 检查；[618–635 行 textarea](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L618-L635>)无 compositionStart/End 接线 | 未声明显式 IME 防护；不能由此直接称具体输入法已复现错误 |
| 普通工具 selection/focus | [115–127 行 apply](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L115-L127>)在 rAF 中 focus + setSelectionRange；[705–710 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L705-L710>)普通 ToolButton mouseDown preventDefault 保留 textarea 焦点 | 不等于菜单/Popover/Dialog/键盘操作全路径保护；也未保存 selectionDirection、关闭浮层后的 focus 契约 |
| 弹层插入 | Emoji/速查点击及 link/image 提交读取当时 textarea selection；LinkDialog 的预填字符串来自 selectionOf，超过60字符不预填，见 [669–684 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L669-L684>)、[954–959 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L954-L959>) | 组件没有打开浮层时持久化 selectionStart/End 的票据；实际 portal、焦点恢复、是否改错选区需另行受控验收，不能读 UI 库/浏览器补证 |
| 嵌套快捷键 | 本组件快捷键绑定仅在 textarea；handler 内 preventDefault 后 return，不调用 stopPropagation；Ctrl/Cmd+S 明确留给外层表单，见 [258–298 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L258-L298>)。各子 Dialog 为输入/按钮，没有组件级快捷键监听 | 外层表单/祖先事件处理不在证据范围；不能断言双执行或嵌套隔离已正确。Dialog 内快捷键不会由此文件的 textarea handler直接处理，不代表浏览器/祖先不处理 |
| 延迟选区恢复 | apply 的 rAF 未在此组件附加取消/版本校验；undo/redo 不恢复选区，见 [115–127 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L115-L127>)、[217–229 行](<../../../target/audit-blog-editor-baseline-20261107-1205.txt.utf8.log#L217-L229>) | 静态潜在交互风险，不是已复现 stale-focus/错误选区事件 |

## 7. 明确关闭边界

- fullRead=true 只属于这份固定提取物，不属于 live Blog writer、导入 renderer/UI 组件、工作树编辑器、整个 Blog 或整 AI。
- 文件名中的 parity 表示供 Lead 使用的**基线功能对照基准**；未读取 live writer，不建立“当前实现已保留/已丢失原功能”的比较结论。
- 浏览器偏好 Chrome、extension_confirmed=false、auto_approve=false；本成员未进行 CDP、Playwright、manual Chrome、浏览器 diagnostic，也未以“未接受”标签绕过限制。
- 未改源码/测试/POM/schema，未运行 Git/Maven/服务/DB/网络，未触及 siblings；共享 task/PLAN 仍由 Lead 维护。
- 两测试重读与旧 runtime 报告分类/超限 provenance 纠正见 [runtime-03-review](<backend-ai-shards/runtime-03-review.md>)；只交付 task-18 报告范围，不宣称 task-12 或整 AI 完成。
