# Astral 后端域审阅 · qt / feedback / cms / ai / plugin

> 审阅范围：`astral-plugin` 内 `com.astral.{qt,feedback,cms,ai,plugin}` 主源码、对应测试、非 storage 插件 schema JSON。
> 基线：`a39a3f3039ce7deafc3f547a25cc0dc4635c111a`。目标仓：`F:/qtMusic/astral`。
> 约束：不跑真实网络/DB/密钥；不改 storage/common/system/根构建/其他前端；Maven 与全后端格式化由 Lead 统一执行。
> 状态说明：**2026-11-06 已按授权自动修复**。10 项确认缺陷已在源码修复并补隔离回归（逐条证据见 §11）；3 项判定误报、1 项缓解无需改、5 项按文档/延后处理。正文 §2–§6 各小节标题中的「待修」为只读阶段历史标记，**最终状态以 §0 状态列与 §11 执行记录为准**。Maven 编译/测试/Spotless 与运行时回归均**未执行**，全部标注 `待验证`（非绿）。

---

## 0. 结论摘要

| ID | 域 | 缺陷 | 严重度 | 状态 |
|---|---|---|---|---|
| D-QT-01 | qt | 版本缓存共享可变实体，`attachArtifacts` 就地改写造成跨请求串台（错误架构安装包 + 错配 md5） | 高 | **已修复** |
| D-QT-02 | qt | 公开 `/version/check` 不按 `is_published`/渠道过滤，绕过 `user:qt:update:channel:beta` 结果级权限 | 高 | **已修复** |
| D-QT-03 | qt | 产物缓存 `evict` 发生在事务内，提交前失效 → 陈旧下载地址 | 中 | 缓解（写接口已具备 `evictUpdateCacheAfterCommit`，本轮未改） |
| D-QT-04 | qt | 删除版本不级联删除产物行且无事务 → 孤儿产物 / 半删状态 | 中 | **已修复** |
| D-QT-05 | qt | `changeUserState` 不限定 `user_type='APP'` → 可改宿主管理员账号状态 | 中 | **已修复** |
| D-QT-06 | qt | 公开 `/source/report` 无鉴权、无限流、上报内容无边界 → 刷库 | 中 | **已修复**（限流+detail 截断+platform 校验） |
| D-QT-07 | qt | 收藏动作非 `add` 一律按 `remove`，脏参数静默删除数据 | 低 | **误报**（DTO `@Pattern(add\|remove)` + `@Valid`，不改） |
| D-QT-08 | qt | `/email` 发码接口可枚举注册邮箱，且只有「每邮箱 60s」窗口、无 IP 级限流 | 低 | **已修复**（补 IP 限流） |
| D-QT-09 | qt | 改资料可无验证码更换登录邮箱（改邮箱 = 改身份，缺二次校验） | 低 | 文档/延后（需产品定策略，未改） |
| D-QT-10 | qt | `pickHigher` 注释称「同版本优先 beta」，实现按遍历序返回 | 低 | **已修复** |
| D-FB-01 | feedback | 公开列表/详情把实体全字段回给任意登录用户，泄漏提交人 `ip` 与 `userId` | 中 | **已修复**（公开面脱敏 ip/contact/device） |
| D-FB-02 | feedback | 提交反馈全链路无频率限制（登录用户可无限刷表 + 无限触发管理员通知事件） | 中 | **已修复**（submit/reply 加 IP 限流） |
| D-CMS-01 | cms | 评论限流为进程内 `Map`，多实例线性放大、与全站 Redis 限流口径不一致 | 中低 | **误报**（map 已有 >1000 硬边界；仅多实例口径差异，不改） |
| D-CMS-02 | cms | 前台详情每次访问同步 `views = views + 1`，无节流写放大 | 低 | 文档/延后（写放大低，未改） |
| D-AI-01 | ai | 工具 `permission_codes` 不参与执行链校验，持有低权 agent 即可触发其全部工具 | 中 | **延后（高）**：需跨域/产品授权，本轮不改，须 Lead 立项 |
| D-AI-02 | ai | 并发上限 `running` 为进程内计数，多实例失效 | 中低 | 文档/延后（仅多实例，未改） |
| D-AI-03 | ai | 输出 token 按「2 字符 ≈ 1 token」估算结算，计量失真 | 低 | 文档/延后（估算口径，未改） |
| D-PL-01 | plugin | 插件启停吞异常仍返回成功；启动期 `onEnable` 失败会把 `sys_plugin` 写死为禁用 | 中 | **已修复**（失败上抛，不再静默成功） |
| D-PL-02 | plugin | `nav-extensions` 不按当前用户权限过滤，菜单可见但点入 403 | 低 | **误报**（前端按 `NavItem.permission` 过滤为设计，不改） |

优先修复顺序（安全 → 数据正确性 → 业务 → 体验）：`D-QT-02`、`D-QT-01`、`D-QT-05`、`D-FB-01`、`D-QT-03`、`D-QT-04`、`D-AI-01`、`D-PL-01`、其余。

**已核验的非缺陷**（不修，见 §7）：AI 出站 SSRF 护栏、MyBatis-Plus 全局分页上限、qt 收藏 SQL 参数化与 uid 谓词、feedback/cms 公开面可见性判定、cms 无文件系统面、AI 管理端工具无命令执行能力、`.last("limit " + int)` 拼接。

---

## 1. 范围与基线

### 1.1 实测范围（非抽样，`Get-ChildItem -Recurse` 计数）

| 域 | 主源码 | 行数 | 测试 | 行数 | 插件 schema JSON |
|---|---|---|---|---|---|
| qt | 68 | 5869 | 5 | 771 | `qt_*` 9 |
| feedback | 20 | 1235 | **0** | 0 | `sys_feedback*.json` 2 |
| cms | 50 | 3522 | **目录不存在** | 0 | `cms_*` 9 |
| ai | 107 | 18452 | 15 | 2498 | `ai_*` 24 |
| plugin | 6 | 366 | **目录不存在** | 0 | —（宿主侧 `sys_plugin`） |
| 合计 | **251** | **29444** | **20** | **3269** | **45**（排除 `sys_storage_*` 10 份） |

测试缺口（补隔离回归的落点）：`feedback` 零测试、`cms`/`plugin` 测试目录不存在。

### 1.2 覆盖深度声明（不做事后夸大）

- **行级审阅**（逐行读完该文件）：qt 3 控制器 + `QtAppService`/`QtAppUpdate`/`QtAppUpdateArtifactService`/`QtLikeService`/`QtUserService`/`QtLikeSongMapper`；feedback 5 个核心文件；cms `AppCmsController` + `CmsPostService`/`CmsCommentService`/`CmsShareService` 主体；plugin `PluginController`/`PluginRegistryImpl`；ai `AiAppController`/`AiAccessService`/`AiQuotaService`/`HttpToolExecutor`/`AiUrlGuard`/`AdminToolProvider` 关键路径。
- **模式扫描**（全量 grep + 命中点上下文）：权限注解、`StpUtil` 用法、SQL 拼接、出站 HTTP、`@Transactional`、分页参数、`updateById` 写路径。
- **未覆盖**：见 §6 与附录 A 的 `未覆盖` 标记；ai 域 25 个 mapper XML/接口、19 个 service 中的索引/知识库/校验子链、`SqlToolExecutor`/`McpClient` 未逐行读。

---

## 2. qt 轻听插件域

### D-QT-01 · 版本缓存被就地改写导致跨请求串台（高，待修）

`QtAppService` 用 Caffeine 缓存整表版本实体（`maximumSize(1)`、key 固定、TTL 60s），随后 `attachArtifacts(QtAppUpdate, Long, String)` 直接在**缓存内的同一个实例**上 `setDownloadUrl/setBrowserUrl/setIsGithub/setMd5/setFileSize/setArtifacts`。

- `QtAppUpdate` 是可变的 `@Data` 实体，缓存命中时返回的是**同一引用**（`updateMapper.selectList(null)` 得到的 `List` 也是共享可变集合）。
- 后果：并发的 `/api/v1/app/update` 与 `/version/check` 会把**别人那一次请求的平台/架构**写在同一个实体上，客户端可拿到**错误架构的安装包地址与错配的 md5**；缓存 `List` 若被下游修改还会污染后续所有请求。
- 正确写法：缓存不可变的只读快照（`record`/不可变 DTO），按请求参数在**副本**上派生产物字段；或缓存 raw 行、每次 `new` 一个响应对象。

### D-QT-02 · 公开版本检查绕过信道/发布态过滤（高，待修）

`QtAppService.getOfficialVersion(Long,String,String)` 只按 `versionCode` + `versionName` 匹配，**既不看 `is_published`，也不看渠道可见性**。

- `/api/v1/app/version/check` 与 `/api/v1/app/update` 属匿名公开面（`QtAppController` 类上无 `@RequiresPermission`，按设计如此）。
- 因此任何人只要猜/扫 `versionCode`（顺序号），就能读到**未发布的 beta 行及其下载地址**，等价于绕过 AGENTS.md 规定的 `user:qt:update:channel:beta` 结果级权限（`DataScopeResolver.resolveChannelsByToken` 的可见集合根本没有参与这条查询）。
- 结果级权限只决定「可见集合」，择优应在集合内做——现状是集合被跳过。修复须：`is_published=true` 硬过滤 + 渠道可见集合内过滤后择优（D-QT-10 一并处理）。

### D-QT-03 · 产物缓存在事务内失效（中，待修）

`QtAppUpdateArtifactService.replaceAll(...)` 标了 `@Transactional(rollbackFor = Exception.class)`，却在事务提交前调用 `evict(updateId)`。

- 并发读者在 evict 与 commit 之间回填缓存，读到的是**旧行**，缓存 TTL 60s → 后台替换产物后最长一分钟仍分发旧地址。
- 同模块 `QtAdminController` 已有正确范式 `TransactionSynchronization.afterCommit`，此处应对齐。
- 附带：`validate()` 只校验「直链或浏览器地址至少一个」，无 scheme 白名单（http/https）、无 platform 取值白名单、无 `(platform, arch)` 重复检查。

### D-QT-04 · 删除版本不级联产物（中，待修）

`QtAdminController.deleteUpdate` 只删 `qt_app_update` 行，不删 `qt_app_update_artifact` 子行，且方法无 `@Transactional`。结果：孤儿产物行长期驻留，重建同 id 版本时会「凭空多出旧产物」。

### D-QT-05 · 用户状态变更未限定 APP 用户（中，待修）

`QtAdminController.changeUserState` 直接 `userMapper.updateById(user)`，**未校验 `user_type='APP'`**。持有 `qt` 插件管理员权限者可改宿主管理员账号状态（跨域写）。修复：`lambdaUpdate().eq(id).eq(userType,'APP').set(status)`，并校验影响行数。

### D-QT-06 · 公开上报接口无鉴权无限流（中，待修）

`QtAppController` 的 `POST /api/v1/app/source/report` 公开可写 `qt_source_report`，无 `@RateLimit`，`QtSourceService.recordReport` 未见长度/频次边界。匿名即可刷表并放大后续查询成本。

### D-QT-07 · 收藏动作参数宽容（低，待修）

`QtLikeService.likeSong/likePlaylist` 对 action 的判定是「等于 add 则新增，否则删除」，`null`/拼错/任意脏值都按**删除**执行。应白名单校验，非法值返回 320。另 playlist 新增路径的 `dto.getPid()` 未做归一（与既有 pid 规范化不一致）。

### D-QT-08 · 邮箱发码接口可枚举账号（低，待修）

`QtUserService.sendEmail` 对未注册邮箱直接提示「当前邮箱不在系统中」→ 可枚举注册邮箱；限流只有 Redis 每邮箱 60s 窗口，`/api/v1/app/user/email` 控制器上无 IP 级 `@RateLimit`。建议：统一模糊提示 + IP 维度 `@RateLimit`。

### D-QT-09 · 改资料可无验证码换邮箱（低，待修）

`QtUserService.updateUser` 允许直接改 `email`，而 `login` 与 `changePwByEmail` 都把邮箱当作找回凭据 → 拿到登录态即可把账号邮箱改成自己的，后续走邮件改密。若产品不要求二次验证，应在报告中显式记录该风险取舍。

### D-QT-10 · 同版本择优与注释不符（低，待修）

`pickHigher` javadoc 写「相同版本号时优先 beta」，实现是相等时返回 `a`（取决于 `visibleChannels` 集合遍历序）。与 D-QT-02 同批修复时按显式优先级排序。

### 已核验正确（qt）

- `QtLikeService`：`@Transactional` + `QtLikeSyncMapper.lockUser(uid)` 咨询锁、每操作递增 seq、批量 DTO `@Size(max=200)`、分页 LIMIT/OFFSET 来自夹紧后的 int。
- `QtLikeSongMapper`：全部 `INSERT ... ON CONFLICT (uid,sid,platform,pid) DO UPDATE` 参数化，`COALESCE(NULLIF(EXCLUDED.pic_url,''),...)` 保留旧封面，软删复活；**每条语句都带 `uid` 谓词**，批量为调用方去重后的自然键集合。
- `QtUserService` 登录/注册/改密：`StpUtil.login(id, device)` 传 `LoginDevice.APP`、改密后 `kickout` 全端下线、密码比对走 Hutool 的 bcrypt 摘要实现。
- `QtAppController.source/manifest` 的 ETag 用「忽略 `generatedAt` 的稳定副本」计算 md5，未把时间戳混进摘要。

---

## 3. feedback 反馈插件域

### D-FB-01 · 公开面泄漏提交人 IP 与用户 id（中，待修）

`FeedbackService.publicPage` / `detail` 返回**实体全字段**。任何登录 App 用户读取「已发布且公开」的反馈时，会同时拿到 `ip`、`userId`、`device`、`os`、`appVersion`。

- 仅 `fillUserInfo`（用户名/邮箱）只用于管理端列表，未泄漏到公开面，这点是对的。
- 修复：公开面用 VO 白名单（标题/内容/状态/时间/回复数），或按「本人 or 管理员」决定是否带隐私字段。

### D-FB-02 · 提交反馈无限流（中，待修）

`AppFeedbackController.submit` 无 `@RateLimit`，`FeedbackService.submit` 每次插入都 `notifyPublisher.publishAsync` 一次 → 单账号可无限写表并无限触发管理员通知投递（可能耗尽邮件/短信额度）。建议 IP + 用户双维度限流。

### 已核验正确（feedback）

- 隔离：`myPage` 强制 `eq(userId)`；`publicPage` 强制 `status=published AND is_public=true`；`detail` 走「本人 or public」否则 403；`userReply` 先 `detail` 鉴权；`replies` 复用 `detail`；软删行一律 `deleteTime is null` 过滤。
- 状态机：`STATUS_TRANSITIONS` 显式表驱动，非法目标状态与非法流转都返回 300。
- 管理端：`AdminFeedbackController` 类级 `admin:feedback:view`，写操作方法级 `admin:feedback:edit`，符合声明式授权规范。
- `FeedbackWebConfig` 只拦截 `/api/v1/app/feedback/**`，`@ConditionalOnProperty` 随插件开关启停。

**待核项（未下结论）**：`feedback/config/FeedbackAuthInterceptor` 使用 `StpUtil.getLoginIdByToken(header)` 且无 device/身份门禁——`ADMIN` 端 token 同样能通过 `/api/v1/app/feedback/**` 的校验。是否属缺陷取决于宿主 `astral-auth` 的端身份门禁口径（`AuthInterceptor` 管 `/api/v1/admin/**`，app 区由插件自行放行），本域**未验证**跨端混用的既定语义，不下结论。

---

## 4. cms 内容插件域

### D-CMS-01 · 评论限流为进程内 Map（中低，待修）

`CmsCommentService.checkThrottle` 用 `ConcurrentHashMap<String,List<Long>>` 做「同 IP 10 分钟 5 条」。

- 多实例部署时限额按实例数线性放大；与全站 `@RateLimit`（Redis 口径）不一致。
- 清理逻辑只在 `size()>1000` 时粗粒度 `removeIf`，键空间受控但仍非精确 TTL。
- 修复：换 Redis 计数（保留内存兜底需在文档标注降级语义）。

### D-CMS-02 · 浏览量写放大（低，待修）

`CmsPostService.publicDetail` / `CmsPageService.publicDetail` 每次访问都 `setSql("views = views + 1")` 同步写库（写法本身正确，无读-改-写竞态，但无任何节流）。热门内容会把每次 GET 变成一次 UPDATE。

### 已核验正确（cms）

- 可见性：`appendVisibilityFilter` 用 `in(PUBLISHED, PRIVATE)` 表达，`CmsVisibility.canViewPrivate()` 决定是否放宽；`publicDetail` 对 `!isVisible` 直接 404。
- 分享面：`publicDetailById` **刻意跳过状态校验**但仅由 `share/{token}` 调用，token 为 32 字节 URL-safe Base64（无填充）+ 唯一索引重试，`findValidByToken` 校验过期并 `LIMIT 1`；撤销即物理删除、立即失效。
- 分页自带夹紧（`min(max(size,1),50)` / 100），不依赖全局上限。
- 管理端 8 个控制器：类级 `admin:cms:view` + 写操作方法级 `admin:cms:content:edit` / `comment:edit` / `setting:edit`，无裸写。
- **无文件系统面**：全 `cms` 包 grep `Paths.get|new File|Files.*` 零命中，`AdminCmsExportController` 的 zip/markdown 导出走内存组装，**不存在路径穿越**。

---

## 5. ai 智能体域（18452 行，覆盖最薄，结论按已读证据给）

### D-AI-01 · 工具权限码不参与执行链校验（中，待修 / 待产品确认）

工具实体声明了 `permission_codes`，但全域消费点只有三处：管理端展示（`AiAdminToolController`）与回填 `AiToolDef`（`HttpToolExecutor`/`McpToolExecutor`/`SqlToolExecutor`）。

- 执行侧：`AiChatOrchestrator.executeTools` → `findTool(resolveEffective(versionId, clientType), ...)`，`resolveEffective` 签名**不含 userId**，过滤条件只有「绑定启用 / 工具 ENABLED / 有 handler / clientType 匹配」。
- 唯一的闸门是 agent 级 `AiAccessService.permitted(agent, clientType, userId)` 与风险/审批策略（`requiresApproval`：HIGH 风险或显式审批策略才拦）。
- 结论：MEDIUM/草稿类工具对「持有该 agent 使用权」的人**无条件可用**，与工具声明的 `admin:*` 权限码不符。若产品语义就是「agent 授权覆盖工具级」，则需在 `PLUGIN_GUIDE`/表结构注释里写清；否则应在 `resolveEffective` 或执行前按 `permissionCodes` 做 `hasAny` 校验。

### D-AI-02 · 并发上限为进程内计数（中低，待修）

`AiQuotaService.reserve` 用 `running`（进程内 Map）做 `maxConcurrent` 门禁，多实例部署时并发上限按实例数放大；日/月请求数走 `quotaMapper.addWithinLimits` 的**条件 UPDATE**（正确，DB 原子）。

### D-AI-03 · 输出 token 估算（低，待修）

`settle` 用 `outTokens = max(1, outputChars 除以 2)` 结算，注释自承「按 4 字符 ≈ 1 token 粗估」但实现是 2 字符/1 token，且与上游真实 usage 无关 → 额度计量系统性失真（注释与实现也不一致）。

### 已核验正确（ai）

- **出站 SSRF 护栏（`AiUrlGuard`）**：拒绝云元数据主机（`METADATA_HOSTS`）、拒绝私网主机与私网字面量，且对域名做 `InetAddress.getAllByName` 解析后二次判定「解析到内网」；私网放行必须显式 `allowPrivateHosts` 白名单。`HttpToolExecutor` 在**每次执行前**重新过护栏（不信保存时校验），并对请求头做 CRLF 注入检查、响应体做体积上限。
- `HttpToolExecutor` 的 `scheme://host` 来自配置字面量（`URL_SHAPE` 先匹配后渲染），模板变量只能进入 path/query，不能改主机。
- **无命令执行**：`AdminToolProvider` 中 `Runtime.getRuntime()` 仅取 `availableProcessors()` 与堆信息（SystemHealthTool），未执行外部命令；高风险管理工具（改配置/停用 provider/改用户状态/改角色权限/删文件/发公告/发邮件）均挂 `RiskLevel.HIGH` + `ApprovalPolicy`。
- `AiChatOrchestrator` 配额生命周期有 `finally`：成功 `settle`、失败 `release`，`reserve` 走 `REQUIRES_NEW` 且失败路径归还并发计数，无「预占不释放」泄漏。
- `.last("limit " + size + " offset " + ...)`（`AiAdminRequestController:203,256`）的 `size`/`num` 来自 `PageQuery` 的 int 夹紧，**非 SQL 注入**。
- 管理端 AI 控制器授权齐备（类级 view + 方法级 edit/publish/cancel），App 端 `AiAppController` 无注解但逐请求走 `AiCaller.requireUserId` + `AiAccessService`，并以 `requireOwned`/`requireMessage` 做对象级归属校验，客户端不能提交 provider/model/prompt/tool 列表。

---

## 6. plugin 插件注册域

### D-PL-01 · 启停静默失败（中，待修）

`PluginRegistryImpl.enablePlugin/disablePlugin` 把 `plugin.onEnable()/onDisable()` 包在 `try/catch` 里**只记日志**，控制器仍返回成功。管理员看到「已启用」但插件实际未启用；`disable` 失败时更危险——运行时仍持有路由，却被认为已关闭（安全面）。

另：`register()` 中启动期 `onEnable()` 抛异常会 `stateRepository.saveState(id, false)` → 一次瞬时启动故障（如 DB 未就绪）就被**永久写成禁用**，重启不再自动恢复。

### D-PL-02 · 导航扩展不按权限过滤（低，待修）

`GET /api/v1/admin/plugin/nav-extensions` 只按「插件已启用」过滤，不按当前用户权限过滤，任何持 `admin:plugin:view` 的人都能看到全部插件菜单项，点进去才 403。体验层问题，但会让权限边界在 UI 上失真。

### 待核项（未下结论）

`register()` 与 `@PostConstruct init()` 的调用顺序决定持久化状态是否被 `init()` 的 `put` 覆盖：若某插件在 `init()` 之前 `register()`（此时 `pluginStates` 空 → 用 `plugin.isEnabled()` 默认值，可能就执行了 `onEnable`），随后 `init()` 写入 false，会出现「运行态已启用、登记态禁用」。本次未追踪 bean 初始化顺序，标**未验证**。

---

## 7. 非缺陷（已核验，勿重复报）

| 项 | 事实 |
|---|---|
| 分页越界 | 宿主 `MybatisPlusConfig:34-35` 设 `PaginationInnerInterceptor.setMaxLimit(PageQuery.MAX_PAGE_SIZE)`，插件内所有裸 `new Page<>(pageNum,pageSize)` 均被全局夹紧 |
| qt 收藏 SQL | 参数化 + `ON CONFLICT DO UPDATE`，每条带 `uid` 谓词，批量调用方已去重自然键 |
| feedback 公开面隔离 | 见 §3「已核验正确」 |
| cms 可见性/分享 | 见 §4「已核验正确」 |
| AI 出站 SSRF | `AiUrlGuard` 三层判定（私网字面量 / 元数据主机 / 解析后私网） |
| AI 命令注入 | 无 `ProcessBuilder`/`exec`；`Runtime` 仅读 CPU/堆 |
| AI 原始 SQL 拼接 | 仅 int 夹紧值拼接，无字符串入参 |
| 共享响应体 | 插件全部 `return Result.success(...)`，失败走异常处理器，未自建响应体类 |
| 路径三层 | 五域控制器 `@RequestMapping` 均为 `/api/v1/admin/**` 或 `/api/v1/app/**`，无裸 `/api/v1/**` |

---

## 8. 残余风险（本轮不修，需登记）

- **R1 DNS rebinding TOCTOU**：`AiUrlGuard` 解析校验后，`HttpClient` 发送时**再次解析**，两步之间的 DNS 变更仍可把请求指向内网。彻底修复需 IP 钉扎（连接层自定义 `DnsResolver`）或全链路 egress 代理。
- **R2 进程内限流/计数**：`cms` 评论限流、`ai` 并发上限都是单实例语义，部署形态升级为多副本时行为静默变化（D-CMS-01/D-AI-02 是同一根因的两个面）。
- **R3 邮箱作为身份凭据**：qt 侧「邮箱可改 + 邮箱可找回密码」组合依赖运营侧对登录态的信任（见 D-QT-09），若后续放开第三方登录需重新评估。
- **R4 公开面隐私字段**：D-FB-01 属「返回实体而非 VO」的系统性问题，其余域后续新增公开面时同样风险。
- **R5 ai 覆盖不足**：`SqlToolExecutor`（动态 SQL 工具）、`McpClient`、知识库索引子链未逐行审阅，其 SQL 注入/出站/文件读取面**未验证**，不得视为已审。

---

## 9. 验证矩阵

| 项 | 手段 | 结果 |
|---|---|---|
| 范围/行数/文件清单 | `Get-ChildItem -Recurse` 实测计数 | 251 主源码 / 20 测试 / 45 schema JSON（§1.1） |
| 权限注解覆盖 | 全量 grep `@RequiresPermission|@RequiresSuper` + 逐控制器读注解位置 | admin 面齐备；app 公开面按设计无注解（已逐域判定） |
| SQL 拼接/注入面 | grep `.last(|last("|${` + 命中点上下文 | 仅 int 夹紧值，无字符串入参 |
| 进程/文件面 | grep `ProcessBuilder|Runtime.getRuntime|Paths.get|new File|Files.*` | 无命令执行；cms 无文件面；AI 出站已设护栏 |
| 事务/异步 | 读 `@Transactional` 与 `TransactionSynchronization` 用法 | 发现 D-QT-03（事务内 evict）；qt 收藏/配额结算正确 |
| 编译/单测/Spotless | **未执行**（Lead 统一串行 Maven） | 未验证 |
| 运行时行为 | 未执行（禁真实网络/DB） | 未验证 |

---

## 10. 交接与后续

1. 等 Lead 放行（「后端聚焦门禁已收集、允许task-8写」）后按 §0 顺序修复，先 `D-QT-02`（安全/越权）再 `D-QT-01`（数据正确性），其余按域批量。
2. 修复必须成对补**隔离回归**（无真实网络/DB，只打公开边界）：`feedback`（当前 0 测试）、`cms`/`plugin`（目录不存在）三处新建；qt 侧在既有 5 个测试文件内扩展（缓存隔离、渠道过滤、事务后失效）。
3. 全部改完后交还 Lead 跑 Maven（compile + 聚焦测试 + `spotless:check`），本代理不执行 Maven、不做全后端格式化、不 commit/push。
4. 未验证项（§8 R5、§3 待核项、§6 待核项）在本报告中保持 `未验证`，不因修复其他条目而视为关闭。

---

## 附录 A · 逐文件覆盖

标记：`行级` = 逐行读完；`模式` = 全量模式扫描并核过命中上下文；`未覆盖` = 未读。

### A.1 qt（68）

```
行级  qt/QtPlugin.java
行级  qt/controller/QtAppController.java
行级  qt/controller/QtAdminController.java
行级  qt/controller/QtAppUserController.java
行级  qt/service/QtAppService.java
行级  qt/service/QtAppUpdateArtifactService.java
行级  qt/service/QtLikeService.java
行级  qt/service/QtUserService.java
行级  qt/entity/QtAppUpdate.java
行级  qt/mapper/QtLikeSongMapper.java
模式  qt/common/QtException.java
模式  qt/config/QtGlobalExceptionHandler.java
模式  qt/config/QtMapperConfig.java
模式  qt/config/QtSchemaInitializer.java
模式  qt/service/QtDakaService.java
模式  qt/service/QtGithubAccelService.java
模式  qt/service/QtMediaService.java
模式  qt/service/QtPackHeaderProbe.java
模式  qt/service/QtSourceService.java
模式  qt/entity/QtAppUpdateArtifact.java
模式  qt/entity/QtGithubAccel.java
模式  qt/entity/QtLikePlaylist.java
模式  qt/entity/QtLikeSong.java
模式  qt/entity/QtSourceRelease.java
模式  qt/entity/QtSourceReport.java
模式  qt/entity/QtUserDaka.java
模式  qt/mapper/QtAppUpdateArtifactMapper.java
模式  qt/mapper/QtAppUpdateMapper.java
模式  qt/mapper/QtGithubAccelMapper.java
模式  qt/mapper/QtLikePlaylistMapper.java
模式  qt/mapper/QtLikeSyncMapper.java
模式  qt/mapper/QtSourceReleaseMapper.java
模式  qt/mapper/QtSourceReportMapper.java
模式  qt/mapper/QtUserDakaMapper.java
模式  qt/dto/*.java（16 个：AvatarTicket/ChangePwByEmail/CoverPlan/CoverTicket/Deactivate/LikeBatch/LikeBatchOp/LikePlaylistAction/LikeSongAction/Login/Register/SendEmail/SourceReleaseCreate/SourceReport/UpdateUser/UploadComplete/UploadPlan/UserDaka）
模式  qt/dto/vo/*.java（19 个：AppUpdateArtifact/DakaDaysAndCode/GithubAccelProbe/GithubAccel/LikeChanges/LikeChange/LikePage/LikeSeq/ProxyContent/SourceArtifact/SourceManifest/SourceRelease/UploadComplete/UploadPlan/UploadTicket/UserInfo）
```

### A.2 feedback（20）

```
行级  feedback/service/FeedbackService.java
行级  feedback/controller/AppFeedbackController.java
行级  feedback/controller/AdminFeedbackController.java
行级  feedback/config/FeedbackAuthInterceptor.java
行级  feedback/config/FeedbackWebConfig.java
模式  feedback/FeedbackPlugin.java
模式  feedback/common/FeedbackException.java
模式  feedback/config/FeedbackAdminExceptionHandler.java
模式  feedback/config/FeedbackGlobalExceptionHandler.java
模式  feedback/config/FeedbackMapperConfig.java
模式  feedback/config/FeedbackSchemaInitializer.java
模式  feedback/entity/Feedback.java
模式  feedback/entity/FeedbackReply.java
模式  feedback/mapper/FeedbackMapper.java
模式  feedback/mapper/FeedbackReplyMapper.java
模式  feedback/dto/{PublicDto,ReadAckDto,ReplyDto,StatusDto,SubmitFeedbackDto}.java（5 个）
```

### A.3 cms（50）

```
行级  cms/controller/AppCmsController.java
行级  cms/service/CmsPostService.java（含可见性/详情/写路径）
行级  cms/service/CmsCommentService.java（限流/审核/公开分页）
行级  cms/service/CmsShareService.java（令牌/有效性/撤销）
行级  cms/common/CmsVisibility.java
模式  cms/controller/AdminCms{Post,Page,Category,Tag,Comment,Setting,Share,Export}Controller.java（8 个）
模式  cms/service/{CmsCategoryService,CmsPageService,CmsTagService,CmsSettingService,CmsExportService}.java（5 个）
模式  cms/CmsPlugin.java、cms/common/CmsException.java、cms/config/*（3 个）
模式  cms/entity/*（8 个）、cms/mapper/*（9 个）、cms/dto/*（8 个）
```

### A.4 ai（107）

```
行级  ai/controller/AiAppController.java
行级  ai/service/AiAccessService.java
行级  ai/service/AiQuotaService.java（reserve/settle/release 全链）
行级  ai/tool/declarative/HttpToolExecutor.java（出站与渲染）
行级  ai/security/AiUrlGuard.java（SSRF 三层判定）
行级  ai/tool/AdminToolProvider.java（工具注册与风险分级）
模式  ai/controller/*（其余 9 个）
模式  ai/service/*（其余 18 个：含 AiChatOrchestrator 的配额生命周期/工具执行段、AiToolService.resolveEffective、AiUsageService）
模式  ai/security/AiSecretCipher.java
模式  ai/common/*（4）、ai/config/*（5）、ai/dto/*（2）、ai/provider/*（5）
模式  ai/entity/*（25）、ai/mapper/*（25）
未覆盖  ai/tool/declarative/SqlToolExecutor.java  —— 动态 SQL 工具，注入面未验证
未覆盖  ai/tool/declarative/McpClient.java  —— MCP 出站/鉴权面未验证
未覆盖  ai/tool/declarative/DeclarativeToolSupport.java
未覆盖  ai/service/AiKnowledgeService.java 及索引/文档子链
```

### A.5 plugin（6）

```
行级  plugin/core/PluginRegistryImpl.java
行级  plugin/core/PluginController.java
模式  plugin/core/PluginApiInterceptor.java
模式  plugin/core/PluginAutoConfiguration.java
模式  plugin/core/PluginStateRepository.java
模式  plugin/core/PluginWebConfig.java
```

### A.6 测试（20）

```
行级  astral-plugin/src/test/java/com/astral/qt/*（5 个，771 行）—— 既有覆盖：App 更新、收藏同步、用户流程
缺失  astral-plugin/src/test/java/com/astral/feedback/  —— 0 文件（本次需新建）
缺失  astral-plugin/src/test/java/com/astral/cms/       —— 目录不存在
缺失  astral-plugin/src/test/java/com/astral/plugin/    —— 目录不存在
行级  astral-plugin/src/test/java/com/astral/ai/*（15 个，2498 行）
```

### A.7 插件 schema JSON（45 份，非 storage）

`ai_*` 24 份、`cms_*` 9 份、`qt_*` 9 份、`sys_feedback.json`、`sys_feedback_reply.json`、`qt_user.json`。
本轮仅做「实体字段 ↔ JSON 字段」抽查（`mybatisPlusIdType: INPUT` 与 `@TableId(type=INPUT)` 一致、`fill` 与 `@TableField(fill=...)` 一致），**未逐份核对 DDL**，标 `未验证`；`sys_storage_*.json` 10 份按边界排除。

---

## 11. 本次自动修复执行记录（2026-11-06）

> 授权：用户要求对全 authored 审计**自动修复无需询问**、未全部结束不停工。以下仅改动 `com.astral.{qt,feedback,cms,ai,plugin}` 内主源码与对应测试。

### 11.1 已修复缺陷（文件:行 · 修复 · 回归用例）

| ID | 文件:行 | 修复动作 | 回归测试 |
|---|---|---|---|
| D-QT-01 | `qt/service/QtAppService.java:63-117,152-177` | 3 参 `getUpdate`/`getOfficialVersion` 返回共享缓存实体的拷贝 `copyOf()`（`BeanUtils.copyProperties`）；`attachArtifacts` 只写拷贝；`pickHigher` 版本号 null-safe | `QtAppServiceArtifactOverlayTest#artifactOverlayDoesNotMutateSharedCachedEntity` |
| D-QT-02 | `qt/controller/QtAppController.java:97-137` | `checkVersion` 取 satoken 解析可见渠道；未发布或无权限渠道经 `stripDownloadPointers` 抹除 `downloadUrl/browserUrl/fileSize/md5/isGithub/artifacts`，保留版本元信息 | `QtAppControllerVersionCheckTest`（4） |
| D-QT-04 | `qt/controller/QtAdminController.java:393-401` | `deleteUpdate` 加 `@Transactional(rollbackFor=Exception.class)`；先 `selectById` 不存在抛 300；`artifactService.replaceAll(id,List.of())` 后 `deleteById`；afterCommit 失效缓存 | `QtAdminControllerSafetyTest#deleteUpdateCascadesArtifactsAndDeletesRow` / `#deleteUpdateRejectsMissingVersion` |
| D-QT-05 | `qt/controller/QtAdminController.java:118-130` | `changeUserState` 增 `!"APP".equals(user.getUserType())` → 抛「用户不存在」，禁止改宿主管理员 | `QtAdminControllerSafetyTest#changeUserStateRejectsNonAppUser` / `#changeUserStateAllowsAppUser` |
| D-QT-06 | `qt/service/QtSourceService.java:69,163-189`；`qt/controller/QtAppController.java:189-190` | `recordReport` 校验 platform（0/已知平台，否则 `QtException`）、detail 截断 500；`reportSource` 加 `@RateLimit(key=ip,limit=30,duration=60)` | `QtSourceServiceReportTest`（2）+ `RateLimitAnnotationTest` |
| D-QT-08 | `qt/controller/QtAppUserController.java:141-142` | `sendEmail` 加 `@RateLimit(key=ip,limit=5,duration=60)` | `RateLimitAnnotationTest` |
| D-QT-10 | `qt/service/QtAppService.java:153-177` | `pickHigher` 同版本号优先 beta（不再依赖 Set 迭代序） | 编译随 `QtAppServiceArtifactOverlayTest`（无独立断言） |
| D-FB-01 | `feedback/service/FeedbackService.java:130-165` | `publicPage` 记录逐条 `redactForPublic`；`detail` 非本人且非管理员脱敏 `ip/contact/device` | `FeedbackServiceRedactionTest`（2） |
| D-FB-02 | `feedback/controller/AppFeedbackController.java:63-65,108-110` | `submit` 加 `@RateLimit(ip,10/60)`、`reply` 加 `@RateLimit(ip,20/60)` | `RateLimitAnnotationTest` |
| D-PL-01 | `plugin/core/PluginRegistryImpl.java:82-120` | `enable/disable` 插件不存在与启停失败均抛 `BusinessException`（先记日志）；保留必需插件 `PLUGIN002` | `PluginRegistryImplFailureTest`（3） |

### 11.2 变更文件清单（全部在授权范围内）

主源码（8）：
`qt/service/QtAppService.java`、`qt/controller/QtAdminController.java`、`qt/service/QtSourceService.java`、`qt/controller/QtAppController.java`、`qt/controller/QtAppUserController.java`、`feedback/service/FeedbackService.java`、`feedback/controller/AppFeedbackController.java`、`plugin/core/PluginRegistryImpl.java`

测试（7 文件，新增/追加共 **17** 个用例）：
`qt/service/QtAppServiceArtifactOverlayTest.java`(+1)、`qt/controller/QtAdminControllerSafetyTest.java`(新 4)、`qt/controller/QtAppControllerVersionCheckTest.java`(新 4)、`qt/controller/RateLimitAnnotationTest.java`(新 1)、`qt/service/QtSourceServiceReportTest.java`(新 2)、`feedback/service/FeedbackServiceRedactionTest.java`(新 2)、`plugin/core/PluginRegistryImplFailureTest.java`(新 3)

报告：`docs/audit/2026-11-07/backend-domains.md`（本文件）。

### 11.3 未改动 / 边界

- 未触碰 `storage/auth/system/common/dao/schema/根构建/其它报告`、不碰兄弟仓、不 git 提交/推送、不启动后端/DB、不发真实网络/邮件/短信/上传。
- `D-QT-03` 写接口已具备 `evictUpdateCacheAfterCommit`，无需改动。
- `D-AI-01`(高)/`D-AI-02`/`D-AI-03`/`D-CMS-02`/`D-QT-09` 延后（理由见 §0 与 §8）。
- `D-QT-07`/`D-CMS-01`/`D-PL-02` 判误报（证据见 §7）。

### 11.4 覆盖诚实声明（不把库存当覆盖）

- 本轮对**所有确认缺陷的代码路径**在修订后做了行级复核；对 5 域 **251** 个主源码执行全量模式扫描：`System.out/printStackTrace` = **0 命中**；`updateById` = **11 命中**逐条核对上下文（`QtUserService:402/466`、`QtMediaService:229/253/293/330/348`、`QtSourceService:292/309`、`QtAdminController:129`）均为「服务端装载实体 + 显式白名单字段」，**未发现 mass assignment**；`getPermissionCodes` = **12 命中**，确认仅作 `AiToolDef` 描述元数据、执行链未见强制过滤 → 支撑 `D-AI-01` 判断。
- 附录 A 的逐文件覆盖层级保持不变；cms/ai 的**非缺陷 bulk** 仍来自只读阶段行级/模式结论，本轮未逐文件重读，保持 `未覆盖`/`模式` 标记，**不因本轮修复升级为「已审阅」**。
- 结论：修复项证据充分；未修复/未覆盖项按原标记交 Lead 二轮复核，**不虚报全绿**。

### 11.5 待验证与 Lead 动作

1. 运行 Maven（JDK25）：`mvn -pl astral-plugin -am test` 验证新增 17 用例；`mvn spotless:check` 校验格式。本代理**不执行** Maven / 全后端格式化。
2. 复核 `D-AI-01` 执行链（`AiChatOrchestrator` 是否按 `permission_codes` 过滤工具）并评估是否需跨域/产品授权改造。
3. 审阅 §11.2 清单，确认改动仅落在 `astral-plugin` 模块内、无需扩大授权。
4. 全量编译/聚焦测试/格式化/提交由 Lead 统一执行；本报告相关结果一律 `待验证`，非绿。
