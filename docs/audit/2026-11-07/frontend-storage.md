# 前端审计报告 · 存储管理 / 文件管理 / HTTP 渠道 / 上传链路

- 基线：`a39a3f3039ce7deafc3f547a25cc0dc4635c111a`
- 审计域：`astral-front` 的存储管理页、文件管理、CUSTOM_HTTP 渠道编辑器、上传 API/客户端、存储定向回归
- 日期：2026-11-07
- 作者：storage-front（Team task-10）
- 证据等级：**源码逐文件静态审阅 + 定向 Node 处理函数回归**。未执行浏览器交互、真实后端、真实上游上传、生产构建；`npm run build`、全量 ESLint/Prettier、浏览器核验由 Lead 统一执行。

---

## 0. 结论摘要

| 编号 | 问题 | 严重度 | 状态 |
|---|---|---|---|
| FE-01 | `submitConfig` 用可选链取渠道编辑器，ref 为空时把「未保存」误报为「已保存」并关闭弹窗 | 高 | 已修 |
| FE-02 | 配置保存无同步锁，`validateFields`/渠道详情等待期间重复点击会重复提交 | 中 | 已修 |
| FE-03 | `testConfig` 在 `body.code !== 200` 时无分支，测试失败无任何提示 | 中 | 已修 |
| FE-04 | 存储概览加载失败被空 `catch` 吞掉，页面空白且无反馈 | 中 | 已修 |
| FE-05 | `setDefault` 未捕获 Promise 拒绝，设为默认失败时无提示 | 中 | 已修 |
| FE-06 | `openPermissions` 对非 200 响应无 else 分支，授权弹窗打不开且无提示 | 中 | 已修 |
| FE-07 | 授权行主体/权限为空时后端静默跳过，前端仍提示「授权已保存」（假成功） | 高 | 已修 |
| FE-08 | 授权绑定把所有配置压成首个 priority，第二个及以后配置的优先级丢失 | 中 | 已修 |
| FE-09 | 授权主体类型暴露字典中的 `PLUGIN`，后端只接受 `USER` | 中 | 已修 |
| FE-10 | Provider 选项与「新建配置」入口未按 `admin:storage:edit` / `admin:storage:channel:edit` 分开裁剪 | 高 | 已修 |
| FE-11 | CUSTOM_HTTP 配置列表的「目标」「Worker/公开域名」列空白，误以为未配置 | 低 | 已修 |
| FE-12 | Provider 配色表缺 `CUSTOM_HTTP`，全部落成默认橙色 | 低 | 已修 |
| FE-13 | 上传测试允许选中 CUSTOM_HTTP 文件夹并尝试直传端到端链路，必然失败且文案误导 | 中 | 已修 |
| FE-14 | 上传测试只查前 50 个文件，刚登记的文件可能不在结果中；查询失败文案 undefined | 中 | 已修 |
| FE-15 | 任务/审计页签首次进入不加载、分页未接后端 current/size，切页无效 | 中 | 已修 |
| FE-16 | 数据字典加载失败被空 `catch` 吞掉，下拉静默显示原始值 | 低 | 已修 |
| FE-17 | 文件夹绑定下拉不保留已禁用但已绑定的配置，编辑时绑定关系不可见 | 中 | 已修 |
| FE-18 | `StorageConfig` 未声明显式 `configVersion`，CUSTOM_HTTP 更新不传 `expectedVersion`，并发修改可被静默覆盖 | 高 | 已修 |
| FE-19 | `hasFolderPermission` 对 `myPermissions` 为空/缺失调用 `.split`，目录加载即可崩溃 | 高 | 已修 |
| FE-20 | 上传进行中再次拖入/选择文件被直接 `return`，用户文件静默丢弃 | 中 | 已修 |
| FE-21 | 目录列表请求无版本号/卸载保护，快速切换后台视图或管理员/用户身份时旧响应覆盖新状态 | 中 | 已修 |
| FE-22 | `addUploads` 在无上传权限或未选目录时静默 return，拖拽上传无任何反馈 | 中 | 已修 |
| FE-23 | 渠道编辑器限额文案仍写「单图 1 MiB」，后端实际上限为 5 MiB，用户被错误限制 | 中 | 已修 |
| FE-24 | 默认允许 MIME 含 webp/gif/avif，后端仅接受 PNG/JPEG 内容，默认配置保存/上传必失败 | 高 | 已修 |
| FE-25 | 认证方式提示漏掉 `BASIC`，用户不知道后端支持 Basic Auth | 低 | 已修 |
| FE-26 | 上传地址输入只校验非空，不校验 `https://`，http 地址保存时才被后端拒绝 | 高 | 已修 |
| FE-27 | 凭据引用集合由会抛错的 `buildProfile` 计算，表单不完整时返回空集，已配置凭据被误标「自动清除」 | 中 | 已修 |
| FE-28 | `planOrigins` 使用全部批准目标的并集且未匹配时回退全部来源，可能提交错误 `allowedUrlOrigins` | 高 | 已修 |
| FE-29 | 凭据 CLEAR/KEEP 三态处理不完整：未配置也发 CLEAR、地址变化仍 KEEP、未引用仍 SET，均触发 STORAGE037 | 高 | 已修 |
| FE-30 | 渠道编辑器提交/离线校验收无同步锁，双击会重复创建渠道或重复校验 | 中 | 已修 |
| FE-31 | STORAGE035/037 错误提示文案与后端实际语义不符，用户按错误方向排查 | 低 | 已修 |
| FE-32 | 两步上传组件的目录/字典加载失败被吞，`pollOperation`/`openFile` 非 200 不抛错，查询失败静默 | 中 | 已修 |
| FE-33 | 创建/发送请求断网或超时时被当普通失败并重置幂等键，可能重复创建上传操作 | 高 | 已修 |
| FE-34 | `contentAccepted` 被直接当成功返回，非终态（CREATED/REMOTE_SUCCEEDED）文件未落库 | 高 | 已修 |
| FE-35 | 直传 ticket 的裸 `fetch` 网络错误文案不可操作；登记无 `publicId` 仍返回空标识 | 中 | 已修 |
| FE-36 | 后端 `SUPPORTED_MIMES` 仅 PNG/JPEG 但错误文案列出 GIF/WebP；`UploadImageValidator` 对全部 image/* 执行 JPEG/PNG 内容检查 | 中 | 已记录（后端契约偏差，前端已按真实契约收紧） |

共 36 项：35 项前端代码缺陷已修复，1 项后端契约偏差已记录并上报。**未发现需要放宽安全检查换取功能正常的情况**；未重新引入旧 CONFIG/GROUP/INHERIT 三态设计。

---

## 1. 覆盖清单（真实清点）

### 1.1 主代码逐文件覆盖（8 个文件，6696 行）

| 文件 | 行数 | 实际审阅范围 | 覆盖内容 | 深度 |
|---|---:|---|---|---|
| `astral-front/src/app/dashboard/plugin/storage/page.tsx` | 2053 | 1–2053 | 概览/配置/文件夹/权限/上传测试/任务/审计全部页签与弹窗、权限门禁、字典与批准目标加载、CUSTOM_HTTP 分支 | 全文件逐行 |
| `astral-front/src/components/storage/FileManager.tsx` | 1520 | 1–1520 | 目录树、文件列表、上传队列（3 worker）、预览、下载/永久链接、批量删除、拖拽上传、空态与错误态 | 全文件逐行 |
| `astral-front/src/components/storage/HttpChannelEditorFields.tsx` | 1202 | 1–1202 | 渠道表单、画像构建、MIME/限额/超时/条件校验、批准目标与来源计划、凭据三态、提交/离线校验锁 | 全文件逐行 |
| `astral-front/src/components/storage/ServerSideUpload.tsx` | 547 | 1–547 | 两步上传创建、发送/轮询、终态处理、结果未知恢复、目录与字典加载、文件打开 | 全文件逐行 |
| `astral-front/src/api/storage.ts` | 637 | 1–637 | 管理端/用户端全部存储 API 方法、DTO 类型、分页/票据/链接响应类型 | 全文件逐行 |
| `astral-front/src/lib/storage-upload.ts` | 190 | 1–190 | 两步上传与 ticket 直传、轮询、终态判定、幂等键、网络错误归一 | 全文件逐行 |
| `astral-front/src/app/files/page.tsx` | 52 | 1–52 | App 文件页入口，鉴权/布局包装 | 全文件逐行 |
| `astral-front/src/app/imgbed/page.tsx` | 6 | 1–6 | 旧图床入口重定向到 `/files` | 全文件逐行 |

### 1.2 测试覆盖

| 文件 | 行数 | 实际审阅范围 | 覆盖内容 | 说明 |
|---|---:|---|---|---|
| `astral-front/tests/storage-upload-feedback.test.cjs` | 257 | 1–257 | 原上传反馈 6 项契约 + 上传队列不丢批次 2 项 | 既有回归保留并扩充 |
| `astral-front/tests/storage-channel-editor.test.cjs` | 245 | 1–245 | 渠道编辑器 `buildProfile`/`referencedSecretsFromForm`/三态凭据 8 项 | 新增，纯函数契约 |
| `astral-front/tests/storage-upload-operation.test.cjs` | 183 | 1–183 | 两步上传终态、非终态轮询、UNKNOWN_OUTCOME 不重发、PUT 超时未知、创建业务失败 6 项 | 新增，处理函数契约 |

共 21 项 storage 定向回归，全部通过（见 §9）。测试为处理函数契约级，不等同浏览器渲染/真实上传验证。

### 1.3 只读依赖与后端契约核对范围

只读、未修改（共享文件属 Lead 范围）：

- `astral-front/src/api/client.ts`：`request.get/post/put/delete` 默认 `unknown`；`body.code !== 200` 抛 `Error(errorCode)`；网络错误统一中文文案。
- `astral-front/src/components/antd-compat`、`components/ui`、`hooks/use-toast`、`lib/utils`：页面沿用既有兼容层与主题令牌，未新增 antd。
- 后端只读核对：`AdminStorageController`、`AdminStorageHttpConfigController`、`StorageUserController`、`UploadOperationController`、`HttpProfileValidator`、`ApprovedTargetPolicy`、`HttpUploadProfile`、`HttpSecretEnvelope`、`UploadImageValidator`、`UploadOperationService`、`StorageDtos`。

### 1.4 成熟页面参考（同仓库，可迁移做法）

| 来源 | 可迁移做法 | 落点 |
|---|---|---|
| `astral-front/src/components/admin/NoticeManagement.tsx` | 加载失败必须可见、表单提交统一 `message.error`、弹窗内错误不吞 | `page.tsx` 的 `errText`/`loadOverview`/权限弹窗 |
| `astral-front/src/app/dashboard/qt/SourceReleasesTab.tsx` | 表格分页回调驱动 current/size、首次挂载主动加载、上传失败逐条保留原因 | `loadTasks`/`loadAudits`、`FileManager` 失败聚合 |
| `astral-front/src/app/dashboard/qt/page.tsx` | 页签懒加载与显式刷新、提交期间按钮锁 | 任务/审计页签、配置/文件夹提交锁 |

未照搬其业务组件或依赖；只复用交互契约。

---

## 2. 管理页 `page.tsx`（FE-01～FE-18）

| 编号 | 位置 | 问题与复现 | 修复 | 验证 |
|---|---|---|---|---|
| FE-01 | `page.tsx:186-234` `submitConfig` | `httpEditorRef.current?.submit(...)` 返回 `undefined` 时 `if (error) return` 不触发，随后 `message.success('已保存')` 并关闭弹窗，实际未调用保存 | 显式取 `editor`，为空时 `message.error('渠道编辑器尚未加载，请稍后重试')` 并 return；成功仅在 `editor.submit` 返回空错误后提示 | 源码核对；`tsc` storage 范围 0 错 |
| FE-02 | `page.tsx:173-232` | 双击「确定」可在 `validateFields` 与渠道详情 await 期间再次进入 `submitConfig`，创建两条配置 | 新增 `configSubmitLockRef`（L173），入口即锁、`finally` 释放；渠道编辑器内部另有提交锁 | 源码核对 |
| FE-03 | `page.tsx:282-295` `testConfig` | `res.code !== 200` 无 else，测试失败无任何 UI 反馈；`healthStatus` 非 UP 时也只显示后端的 message | 增加 `message.error(res.msg \|\| '测试失败')` 分支；异常统一走 `errText` | 源码核对 |
| FE-04 | `page.tsx:156-170` `loadOverview` | `.catch(() => {})` 吞掉概览失败，页面统计全空且无提示 | `.catch((e) => message.error(errText(e)))` | 源码核对 |
| FE-05 | `page.tsx:890-898` `setDefault` | `storageApi.setDefaultConfig` 无 try/catch，Promise 拒绝成为未处理 rejection，按钮无反馈 | 包 try/catch，失败 `message.error(errText(e, '设为默认失败'))` | 源码核对 |
| FE-06 | `page.tsx:421-443` `openPermissions` | `res.code !== 200` 时既不开弹窗也不提示，用户以为按钮失效 | 增加 else `message.error(res.msg \|\| '授权加载失败')`；catch 也提示 | 源码核对 |
| FE-07 | `page.tsx:445-486` `savePermissions` | 后端对 `subjectId`/`permissions` 为空的行静默跳过；前端仍提示「授权已保存」。重复主体也会被后端覆盖 | 保存前逐行校验主体与权限，重复 `subjectType:subjectId` 直接拒绝并提示合并 | 源码核对 |
| FE-08 | `page.tsx:96-99,431-435,1860+` 权限弹窗 | `permConfigs?: number[]` + 单一 `permPriority` 来自 `configs[0]`，多个配置绑定只保留第一个优先级 | 改为 `{storageConfigId, priority}[]`，每行每个绑定独立 InputNumber；提交按原对象映射 | 源码核对 |
| FE-09 | `page.tsx:134-141` `subjectTypeOptions` | 直接使用字典全部值，暴露 PLUGIN；后端 `savePermissions` 仅接受 SUBJECT_USER | 过滤为 `USER`，字典缺失时回退 `[{value:'USER'}]` | 源码核对 |
| FE-10 | `page.tsx:143-153` `providerOptions`；新建按钮与 `initialValue` | `CUSTOM_HTTP` 需要 `admin:storage:channel:edit`，其它 Provider 需要 `admin:storage:edit`；原逻辑把两类权限混用，可能给只有一类权限的角色显示不可创建项 | Provider 选项按 `CUSTOM_HTTP ? canEditChannel : canEdit` 过滤；新建入口 `canEdit \|\| canEditChannel`；默认 Provider `canEdit ? 'TELEGRAM' : 'CUSTOM_HTTP'` | 源码核对；权限矩阵见 §7 |
| FE-11 | `page.tsx:763-776` 配置列 | CUSTOM_HTTP 的 `providerOptions` 不含 bucket/chatId，目标列与 Worker/公开域名列显示 `-`，像配置缺失 | 目标列渲染「见渠道详情」；公开域名列对 HTTP 显示 `见渠道详情` | 源码核对 |
| FE-12 | `page.tsx:737-746` `PROVIDER_COLOR` | 缺 `CUSTOM_HTTP` 键，Tag 全部落默认橙色，与 TELEGRAM 混淆 | 增加 `CUSTOM_HTTP: 'magenta'` | 源码核对 |
| FE-13 | `page.tsx:595-606,1150-1165` | 上传测试对 CUSTOM_HTTP 仍走 ticket 直传四步，必然在凭证阶段失败；文件夹下拉仍可选 CUSTOM_HTTP 目录 | `runUploadTest` 首行判断 CUSTOM_HTTP 并给出「走服务端两步上传/渠道测试」提示；上传测试文件夹选项对 CUSTOM_HTTP `disabled` | 源码核对 |
| FE-14 | `page.tsx:646-652,664-675` | 上传测试查询 `pageFiles({size:50})`，新增文件排到 50 名以后即报「未登记」；`pageRes` 非 200 未检查；catch 中 `(e as Error)?.message` 可能 undefined | size 改 100；非 200 `throw new Error(pageRes.msg \|\| '文件列表查询失败')`；统一 `errText(e,'上传测试失败')` 并显示 `测试失败：...` | 源码核对 |
| FE-15 | `page.tsx:694-709,711-734` 任务/审计页签 | 初始 `useEffect` 只加载配置/目录/字典，任务和审计在点页签前永远空；分页组件未接 `loadTasks(current,size)`/`loadAudits(current,size)` | 挂载即 `loadTasks()`/`loadAudits()`；分页 `onChange` 传 current/size；请求失败 `message.error` | 源码核对 |
| FE-16 | `page.tsx:711-731` 字典加载 | `fetchDictOptions(...).catch(() => {})` 吞错，下拉和标签静默显示原始值 | `message.warning('枚举字典加载失败，下拉与标签将显示原始值：' + errText(error))` | 源码核对 |
| FE-17 | `page.tsx:1683-1718` 文件夹绑定 Select | 只把启用配置作为选项，已绑定但已禁用的配置不渲染，编辑时看不到原绑定；直接保存可能丢绑定 | 保留当前绑定配置为 disabled 选项并标注「（已禁用）」，其余启用配置可选；删除仍可移除 | 源码核对 |
| FE-18 | `api/storage.ts:22-33,364-382`；`page.tsx:206-211` | `StorageConfig` 未声明 `configVersion`，`getHttpChannel` 返回的 `configVersion` 在类型上不可见；更新时 `expectedVersion` 恒 undefined，后端乐观锁失效 | DTO 增加 `configVersion?: number`；`HttpChannelSummary.configVersion: number`；`submitConfig` 传 `expectedVersion: httpChannelDetail?.configVersion` | `tsc` storage 范围 0 错 |

---

## 3. 文件管理 `FileManager.tsx`（FE-19～FE-22）

| 编号 | 位置 | 问题与复现 | 修复 | 验证 |
|---|---|---|---|---|
| FE-19 | `FileManager.tsx:60-64` `hasFolderPermission` | `folder?.myPermissions === 'ALL'` 为 false 时继续 `folder?.myPermissions.split(...)`；`myPermissions` 缺失即 TypeError，目录加载/权限按钮崩溃 | 先取 `const permissions = folder?.myPermissions`，再 `permissions === 'ALL' \|\| !!permissions?.split(...)` | 源码核对 |
| FE-20 | `FileManager.tsx:485-535` `uploadEntries` | 上传中再次拖入/选择文件命中 `if (uploadLock.current) return`，批次静默丢弃，用户以为已排队 | 新增 `pendingBatchesRef` 队列：上传中 `push` 并 `toast.info('正在上传，已加入等待队列（N 个文件）')`；当前批次 `finally` 取出下一批，连续批次保持 `uploading=true` 防止按钮闪回可点 | `storage-upload-feedback` 2 项队列回归通过 |
| FE-21 | `FileManager.tsx:163,203-247` `loadFolders` | 无请求版本号；快速切换管理员/用户视图或组件卸载后，旧响应仍 `setFolders`，覆盖新目录状态 | `foldersVersion` ref：请求开始 `++`，响应/错误/finally 仅在版本一致时写状态；卸载 effect `++` 使旧响应失效 | 源码核对；`tsc` 0 错 |
| FE-22 | `FileManager.tsx:537-544` `addUploads` | `!canUpload \|\| folderId == null` 直接 return，拖拽/选择文件无任何提示 | 先判断 `!incoming.length`；无权限/无目录时 `toast.error('当前目录不支持上传，请先进入已启用且有上传权限的文件夹')` | 源码核对；原有上传反馈 6 项通过 |

---

## 4. HTTP 渠道编辑器 `HttpChannelEditorFields.tsx`（FE-23～FE-31）

| 编号 | 位置 | 问题与复现 | 修复 | 验证 |
|---|---|---|---|---|
| FE-23 | `HttpChannelEditorFields.tsx:710`；常量 `HARD_MAX_BYTES` | 说明文案仍写「图片硬上限 1 MiB」，与后端 `HttpUploadProfile.HARD_MAX_BYTES=5242880` 不符，用户上传 2–5 MiB 被自身文案劝退 | `HARD_MAX_BYTES=5242880`；文案改「单文件硬上限 5 MiB（仅 JPEG/PNG）」；`api/storage.ts` 注释同步 | 源码核对 |
| FE-24 | `HttpChannelEditorFields.tsx:31-32,44,163-178` `HARD_MAX_BYTES`/`SUPPORTED_MIMES`、`parseMimes`、`buildProfile`；`ServerSideUpload.tsx:29,403` | 默认 `allowedMimes` 含 `image/webp,image/gif,image/avif`；后端 `SUPPORTED_MIMES` 仅 PNG/JPEG，保存返回 STORAGE 校验失败；渠道表单会把这些值当合法项保存，实际无法上传 | 常量收紧为 `['image/png','image/jpeg']`；`parseMimes` 对不支持的 MIME 显式报错「当前后端仅支持…GIF/WebP/AVIF 会被服务端内容校验拒绝」，不静默删除用户输入；`ACCEPTED_MIME` 仅作文件选择器提示。**上传入口不做客户端 MIME 过滤**：FileManager 的 `input` 无 `accept`，ServerSideUpload 的 `startUpload` 只拒绝 `!file.type`，用户选到 GIF/WebP 仍会进入上传并以服务端 STORAGE045 原因显式失败，不会被静默丢弃 | `storage-channel-editor` `parseMimes/buildProfile` 测试通过；源码核对 |
| FE-25 | `HttpChannelEditorFields.tsx:183-235` `buildAuth` | NONE 分支提示只列 HEADER/QUERY/FORM，漏掉后端支持的 BASIC，用户误以为 BASIC 不可用 | 提示补 `BASIC` | `storage-channel-editor` 8 项通过覆盖 buildProfile 相关断言 |
| FE-26 | `HttpChannelEditorFields.tsx:237-300` `buildProfile` | endpoint 只校验非空，`http://` 地址可进入提交；后端 `ApprovedTargetPolicy.require` 仅接受 https，保存必失败 | 新增 `^https:\/\//i` 校验，错误文案「上传地址需以 https:// 开头（后端只批准 HTTPS 目标，http 会被拒绝）」 | `storage-channel-editor` `buildProfile rejects http endpoints` 通过 |
| FE-27 | `HttpChannelEditorFields.tsx:547-557` `referencedNames` | 原逻辑 `buildProfile(...)` 在表单不完整时抛错、catch 返回空数组；所有已配置凭据被判「未引用」并自动 CLEAR，保存后凭据丢失 | 新增 `referencedSecretsFromForm(form)`，只从当前表单文本/认证字段扫描 `${secret:...}` 与 BASIC 用户名，不构建完整画像、不抛错；`buildSecretChanges` 使用该集合 | `storage-channel-editor` `referencedSecretsFromForm` 通过 |
| FE-28 | `HttpChannelEditorFields.tsx:504-527` `matchedApprovedTarget`/`planOrigins` | 原 `approvedOrigins` 是全部批准目标 `urlOrigins` 的并集；endpoint 未匹配时仍返回全部来源，编辑态还优先用可能过期的已保存 origins。后端要求 `allowedUrlOrigins` 必须是当前 endpoint 对应目标的子集，错误来源会导致 STORAGE035/校验失败 | 按 `${origin}${path}` 精确匹配当前 endpoint；匹配到就返回该目标 `urlOrigins ?? []`；未匹配且批准清单为空（功能未启用）才回退编辑画像 origins；否则返回 `[]`。UI 在未匹配时内联展示 STORAGE039/035 预警 | 源码核对；`storage-channel-editor` origins 测试通过 |
| FE-29 | `HttpChannelEditorFields.tsx:569-617` `buildSecretChanges` | 三态处理错误：未配置也发 CLEAR（无意义）、endpoint origin/path 变化仍 KEEP（后端 STORAGE037）、画像不再引用仍 KEEP（STORAGE037）、未引用仍 SET（STORAGE037）、空值 SET 无提示 | `secretBindingChanged` 检测端点 origin/path；未配置 CLEAR 跳过；未引用自动 CLEAR；绑定变化 KEEP 抛出可操作错误；SET 要求被引用且非空；保留 KEEP/SET/CLEAR 精确 payload | `storage-channel-editor` 4 项凭据测试通过（configured-unreferenced、SET、binding change、三态 payload） |
| FE-30 | `HttpChannelEditorFields.tsx:468-469,624-700` | `submit`/`runValidate` 只用 state `saving/validating` 防重，React 状态更新前的同一 tick 双击仍可进入两次 | 增加 `submitLockRef`/`validateLockRef`，入口同步置 true，`finally` 释放；`runValidate` 同时拒绝 `saving` 状态 | 源码核对 |
| FE-31 | `HttpChannelEditorFields.tsx:97-130` `channelErrorHint` | STORAGE035 原文案暗示仅「批准列表为空」，实际包括 endpoint/路径不完全匹配；STORAGE037 原文案未说明「未引用/地址绑定变化」 | 035 改为「上传地址未精确匹配部署批准目标（路径也要一致；批准列表为空时同样返回该码），不会发出任何出站请求」；037 改为「凭据与当前画像不匹配（多余、未引用或上传地址绑定已变化）」 | 源码核对 |

---

## 5. 两步上传组件与上传库（FE-32～FE-35）

### 5.1 `ServerSideUpload.tsx`

| 编号 | 位置 | 问题与复现 | 修复 | 验证 |
|---|---|---|---|---|
| FE-32 | `ServerSideUpload.tsx:102-118,154-165,320-335` | 目录/字典加载 `.catch(() => {})` 吞错；`pollOperation` 对非 200 不抛错直接返回；`openFile` 对非 200 不抛错。轮询失败时页面停在查询中，用户不知道发生了什么 | 目录失败 `toast.error`、字典失败 `toast.warning`；`pollOperation` 非 200 `throw new Error(result.msg)`；`openFile` 非 200 throw；错误统一 `errorText` | `storage-upload-operation` 6 项通过；源码核对 |
| FE-33 | `ServerSideUpload.tsx:167-219,221-310,337-360` | 创建/发送阶段网络超时或断网被当成普通失败，UI 回到可选择新文件状态，用户换新文件会生成新幂等键，服务端可能已有原操作 → 重复上传/配额占用；`<input>` 未清空时同一文件无法再次选择 | `handleFailure(caught, knownUploadId, runId, phase)` 区分 `create/send/query`；网络未知在 `create/send` 阶段也保留旧 uploadId 并提示「结果未知，服务端可能已受理，请重新选择同一文件（系统复用幂等键）」；`onPick` 清空 `event.target.value`；`stage==='unknown'` 仅允许查询原操作，不创建新上传 | `storage-upload-operation` UNKNOWN_OUTCOME/PUT 超时/创建失败 3 项通过 |

### 5.2 `lib/storage-upload.ts`

| 编号 | 位置 | 问题与复现 | 修复 | 验证 |
|---|---|---|---|---|
| FE-34 | `storage-upload.ts:60-122,124-131` | `sent.data.contentAccepted` 只代表正文已收到，不代表已落库；原代码 `if (contentAccepted) return settle(...)`，此时 operation 可能仍是 CREATED/REMOTE_SUCCEEDED，返回了未 COMMITTED 的文件 | 仅 `contentAccepted && isTerminal(operation)` 才 `settle`；否则进入 GET 轮询（40 次×500ms）；`settle` 只在 `COMMITTED` 返回 publicId，`UNKNOWN_OUTCOME`/`FAILED`/`EXPIRED` 抛出明确错误，不重发 | `storage-upload-operation` `only COMMITTED`、`contentAccepted non-terminal polls`、`UNKNOWN_OUTCOME no resend` 通过 |
| FE-35 | `storage-upload.ts:80-120,133-190` | `putUploadContent` 断网/超时只抛浏览器原文 `Network Error/timeout`，用户以为可立即重试；`uploadWithStorageTicket` 两处裸 `fetch` 的 `Failed to fetch` 无操作含义；`registerUpload` 无 `publicId` 仍返回空串 | PUT 异常按网络关键词映射为「上传结果未知：…请稍后在文件列表确认，不要重复上传同一份文件」；新增 `fetchUpload` 包装两处 ticket fetch，网络错误映射为「上传连接中断，无法确认服务端是否收到文件…」；登记后 `if (!publicId) throw` 明确提示查文件列表 | `storage-upload-operation` PUT timeout 通过；`storage-upload-feedback` HTTP 200/code 300 STORAGE045 通过 |

---

## 6. 后端契约偏差（FE-36，已记录，不由前端修复）

| 编号 | 后端位置 | 事实 | 前端处理 | 处置 |
|---|---|---|---|---|
| FE-36 | `HttpProfileValidator.java:72,607`；`UploadImageValidator.java`；`UploadOperationService.java:413` | `SUPPORTED_MIMES = Set.of("image/png","image/jpeg")` 只允许 PNG/JPEG，但 L607 的错误文案列出 gif/webp；`UploadImageValidator` 对所有声明 `image/*` 的上传执行 JPEG/PNG 内容检查，GIF/WebP/AVIF 必失败，验证级别无法绕过 | 前端 `SUPPORTED_MIMES`、默认 MIME、上传 `accept` 全部收紧为 `image/png,image/jpeg`，并在渠道编辑器提示「GIF/WebP/AVIF 会被服务端内容校验拒绝」 | 已记入本报告并同步 Lead；后端错误文案/能力应另行修正，前端不再展示不支持的选项 |

其他后端契约核对结论（无前端缺陷）：

- `ApprovedTargetPolicy.require`：绝对 https、无 query/fragment/userinfo、精确 `origin+path` 匹配；前端提示与校验已对齐。
- `HttpSecretEnvelope.applyChanges`：绑定 = `channelId + endpoint origin + path`；KEEP 在绑定变化时拒绝（STORAGE037）、未配置 KEEP（STORAGE036）、未引用（STORAGE037）；前端三态已对齐。
- `AdminStorageController`：类级 `admin:storage:view`，所有变更 `admin:storage:edit`；`AdminStorageHttpConfigController`：类级 `admin:storage:channel:view`，create/update/delete `admin:storage:channel:edit`，`/{id}/test` `admin:storage:channel:test`，`/validate` 仅 `channel:view`。页面权限门禁按此拆分。
- `HttpStorageConfigService.testHttpChannel`：需要凭据、`policy.require(endpoint)`、transport probe；返回 `success/safeCode`，前端只在 `success && safeCode==='OK'` 报成功。
- 部署默认 `custom-http.enabled=false`、`approved-targets=[]`：真实渠道测试仅会返回 STORAGE039/STORAGE035；前端已提示，不伪造成功。

---

## 7. 权限与版本约束核对

### 7.1 管理页权限矩阵

| 操作 | 所需权限 | 前端门禁 |
|---|---|---|
| 查看存储页/概览/目录/文件/任务/审计 | `admin:storage:view` | 页面整体由路由权限控制；未知权限后端拒绝 |
| 新建/编辑/删除/设默认普通配置 | `admin:storage:edit` | `canEdit` 控制 Provider、按钮、弹窗 |
| CUSTOM_HTTP 渠道新建/编辑/删除 | `admin:storage:channel:edit` | `canEditChannel` 控制 HTTP 选项、编辑/删除/新建入口 |
| 渠道测试 `/configs/{id}/test` | `admin:storage:channel:test` | 行内「测试」按钮由 `canTestChannel` 控制 |
| 文件上传/预览/删除 | 目录权限 `UPLOAD/READ/UPDATE/DELETE` | `hasFolderPermission` + `canUpload`/`canRead`/`selectable` |

**注意**：只有 `admin:storage:channel:edit` 没有 `admin:storage:view` 的角色无法加载页面；这是后端类级权限的既有约束，前端不能也不应绕过。

### 7.2 版本/字典/事务

- `StorageConfig.configVersion` 已进入 DTO；`getHttpChannel` 返回 `HttpChannelView extends HttpChannelSummary`，`configVersion` 必填；更新请求带 `expectedVersion`。
- 所有下拉枚举走 `fetchDictOptions`，失败时警告降级原始值；无硬编码业务枚举新增。
- 所有请求经 `api/client.ts` axios；仅预签名直传/下载使用裸 fetch，且不添加自定义头。
- 旧 `CONFIG/GROUP/INHERIT` 三态 UI 已完全不存在；目录绑定为 `{configId, priority}[]` + `storageStrategy: FAILOVER/ROUND_ROBIN`。

---

## 8. 残余限制与未完成验证

### 8.1 未执行的 Browser 证据（明确清单）

本报告**没有任何浏览器证据**；以下均未执行，不能把 DTO 类型检查、SSR 渲染、`tsc` 或 Node handler 测试当作 browser 证明：

- 未启动 Chrome/Playwright/CDP，未加载 `http://localhost:3000` 或任意页面 URL；
- 未做截图、DOM 快照、可访问性树、控制台 error/warning 采集；
- 未做真实鼠标点击、键盘 Tab/Enter/Escape、移动端触摸、软键盘、滚动与 Popover/Dialog 滚动穿透测试；
- 未触发原生文件选择器、拖拽上传、下载链接点击或剪贴板；
- 未观察浏览器 Network 面板中的请求/响应、CSRF 头、上传进度或轮询时序；
- 未验证 Radix Popover 在 Dialog 内的 portal/`pointer-events` 实际行为（共享组件属 Lead 范围）。

因此本报告只能证明「源码契约与 handler 级回归」，不能证明页面在浏览器中可操作、布局正确或真实上传成功。浏览器核验必须在 Lead 的隔离浏览器步骤完成后再更新门禁结论。

### 8.2 环境限制

- **未启动真实后端**（`custom-http.enabled=false`、`approved-targets=[]`），未做真实渠道测试、真实对象存储上传或 Telegram/Worker 回调。`AdminStorageHttpConfigController` 的真实测试在部署默认配置下只能返回 STORAGE039/STORAGE035。
- **未执行浏览器/E2E**：未验证 Radix Popover 滚动、移动端触摸/软键盘、拖拽文件、下载链接实际签名可达性；这些留给 Lead 的隔离浏览器核验。
- **未执行 `npm run build`**：按任务边界由 Lead 统一执行；本报告不把 `tsc`/ESLint 静态通过当作生产构建证明。
- **未提交 Git**：所有文件为工作区改动，未 commit/push。

### 8.3 代码级残余（不作为本轮修复项）

| 项 | 位置 | 说明 |
|---|---|---|
| `parseOptions` 容错 | `page.tsx` 文件夹弹窗 | `uploadPolicy` JSON 解析失败时静默使用空对象回退，保证编辑弹窗可打开；后端仍会在保存时校验，不伪造有效策略 |
| `submitFolder` 优先级范围 | `page.tsx:342` | 前端不单独限制 priority 数值范围，由后端 `StorageFolderService` 校验；当前表单仅提示「数字越小优先级越高」 |
| `ServerSideUpload` unknown 无 uploadId | `ServerSideUpload.tsx:337` | 若创建请求断网且服务端未返回 uploadId，不显示「重新开始」按钮，只保留顶部「选择图片」以复用幂等键；这是为避免重复创建的有意取舍 |
| CUSTOM_HTTP 渠道测试失败分支 | `page.tsx` 行内测试 | `testHttpChannel` 仅在 `res.code === 200` 分支处理，非 200 由 `client.ts` 抛错进 catch；UI 无额外 else 分支，错误由 axios 统一提示 |
| `uploadWithStorageTicket` 登记失败 | `lib/storage-upload.ts:157-190` | PUT 已成功但 `registerUpload` 失败时提示「上传登记失败」，未标注「结果未知」；后端可通过 uploadId 对账，前端无法确认对象是否已落库，属残余数据一致性风险 |
| `storage-upload` 创建成功后大小超限 | `lib/storage-upload.ts:60-122` | 服务端返回 `maxBytes` 小于文件大小时抛错，但已创建 CREATED 操作、预占配额；后端没有取消操作 API，客户端无法清理，属后端设计限制 |
| `FileManager` eslint 警告 | `FileManager.tsx:315` | `react-hooks/exhaustive-deps` 提示 cleanup 读取 `requestVersion.current` 可能变化；现有 effect 是空依赖卸载清理，功能正确，保留为基线风格，不为此扩大改动 |
| 存储新增测试 seam | `tests/storage-*.test.cjs` | 测试以 TypeScript AST 抽取生产纯函数/处理函数 + 隔离适配器执行，属 handler-contract 回归；不是同 realm 公开模块/真实组件渲染，不能替代 Lead 的浏览器验收 |

---

## 9. 验证记录（实际执行）

### 9.1 命令与结果

| 命令 | 工作目录 | 结果 |
|---|---|---|
| `npx eslint src/app/dashboard/plugin/storage/page.tsx src/components/storage/FileManager.tsx src/components/storage/HttpChannelEditorFields.tsx src/components/storage/ServerSideUpload.tsx src/api/storage.ts src/lib/storage-upload.ts` | `astral-front` | **exit 0**；0 errors，1 warning：`FileManager.tsx:315 react-hooks/exhaustive-deps`（既有 ref cleanup 警告） |
| `node --test tests/storage-upload-feedback.test.cjs tests/storage-channel-editor.test.cjs tests/storage-upload-operation.test.cjs`（Node 24） | `astral-front` | **21/21 pass，0 fail**，668ms（格式化测试文件后复跑） |
| `npx tsc --noEmit --pretty false` | `astral-front` | **exit 1**：仓库其它域 42 个错误（`SourceReleasesTab.tsx`、system/通知/权限/表结构/用户等，Lead 范围）；过滤 `storage/page.tsx\|components/storage/\|api/storage.ts\|lib/storage-upload.ts` 结果 **0 个存储域错误** |
| `npx prettier --check`（6 个改动主文件 + 3 个测试） | `astral-front` | **exit 1**：`page.tsx`、`FileManager.tsx`、`HttpChannelEditorFields.tsx`、`api/storage.ts` 及新 `storage-channel-editor.test.cjs` 有格式差异；用 `git show HEAD:<file> \| prettier --check --stdin-filepath <file>` 复核，前 4 个文件在基线即失败（既有格式噪声），本次未全量格式化；新测试已 `--write` 格式化 |
| `git diff --check`（存储改动文件） | `astral` | **exit 0**，无尾随空白/冲突标记 |
| `git diff --stat` | `astral` | 6 个主文件 + 1 个既有测试：553 insertions / 191 deletions；另新增 2 个未跟踪测试文件 |

### 9.2 测试覆盖明细

`storage-upload-feedback.test.cjs`（8 项，全部通过）：

- HTTP 200/code 300 STORAGE045 立即显示并保留队列原因
- 并发批次失败更新同一个 popup，逐文件原因不丢
- 成功批次不显示失败 popup
- 显式重试重新开 popup，不复用旧 id
- 上传中新增批次进入等待队列、不启动第二并发批次
- 当前批次结束自动排空队列
- 失败 popup 先于另一个并发上传完成出现
- 原 6 项上传反馈契约保留（基线上 6 项均通过）

`storage-channel-editor.test.cjs`（8 项，全部通过）：`buildProfile` 拒绝 http、仅 PNG/JPEG MIME、保留批准来源与未管理 response pointer、`referencedSecretsFromForm` 扫描 BASIC 与 `${secret:...}`、configured-unreferenced 自动 CLEAR、SET 要求引用与非空、endpoint binding change 拒绝 KEEP、三态 payload 精确。

`storage-upload-operation.test.cjs`（6 项，全部通过）：只 COMMITTED 算成功、contentAccepted 非终态轮询到 COMMITTED、UNKNOWN_OUTCOME 不重发/不重建、PUT 超时未知、创建业务失败先抛、重复创建不能发送时轮询既有操作。

### 9.3 未执行/不可声称

- 未执行 `npm run build`、全量 `npx eslint src`、全量 `npx prettier --check src`——Lead 收口统一执行。
- 未执行浏览器、移动端、真实上传、真实后端集成测试。
- 报告中的“全文件”是**源码逐文件静态阅读**，不是行覆盖工具数据；未用覆盖率百分比冒充。

---

## 10. 交付结论

1. **覆盖率**：存储前端 8 个主文件、3 个测试文件全部纳入；主代码 6696 行逐文件阅读，`page.tsx`、`FileManager.tsx`、`HttpChannelEditorFields.tsx`、`ServerSideUpload.tsx`、`api/storage.ts`、`lib/storage-upload.ts` 均全文件覆盖。
2. **修复**：36 项账本中 35 项前端代码缺陷已直接修复；FE-36 为后端 MIME 契约偏差，前端已按真实契约收紧并记录给 Lead。
3. **保持不变**：基线聚合失败 toast（`FileManager` 首次失败立即提示、同批更新同 id、逐文件原因保留）、3-worker 并发、原有 6 项上传反馈回归、`output: 'standalone'` 配置、旧三态 UI 已删除的事实；未新增客户端文件大小预检查（后端 `maxBytes/HARD_MAX_BYTES` 为权威）。
4. **前端门禁边界**：storage 域 `tsc` 0 错、ESLint 0 error、21/21 定向回归通过、`git diff --check` 通过；全量 prettier 中 4 个文件存在基线既有格式失败，新测试已格式化；全量 `tsc` 仍有仓库其它域 42 个错误，Lead 需统一处理。
5. **状态**：源码阶段完成，可冻结给 Lead 做全量 `tsc`/ESLint/Prettier/`npm run build`、浏览器核验与最终提交；本报告不声称生产/浏览器验证通过。
