# 2026-11-09 继续审阅：Spec 轴（21a24750 固定提交终审）

**最新判定：限定范围 Spec PASS，固定产品提交为 `21a24750ea35c276293370bd2b1d7fa510957c06`。** 已在前次 `71ad84e...80601e13` 复核基础上，仅核对 `80601e13...21a24750` 的 S2-02 锁后存在性窄修补。默认 App 角色在初次查询后被并发删除时，两个写入口均在写关联前明确拒绝；未破坏已关闭的 J-N1 公告投放、J-N2 最后可用超管保护、J-N3 审批授权指纹及 prepare/link 结论。本限定范围没有确认的残余需求缺失、错误实现或范围外功能；不宣称本轮再次逐文件独立审阅全仓。

已只读汇总最新固定提交日志：`continue-final2-reactor.log` 于 **2026-11-09 15:20:52** 成功，**137套/852项，0失败、0错误、0跳过**，实际生成34个实体并完成 install；`continue-final2-quality.log` 于 **15:24:20** 成功。真实 PostgreSQL 的 `AdminToolRoleConcurrencyTest` 现为5项，含默认角色删除交错的2参数及此前3项；`AiApprovalLifecycleTest` 的36项仍是实际 MyBatis/Spring 事务与 **H2 PostgreSQL mode**，不可称为 PostgreSQL 运行证据。质量命令成功不等于零静态告警，其评估归 Standards 轴。下列初始快照、未提交复核与80601e13结论保留历史，当前补充依据见末节；2026-11-08 交付结论不回写。

- 授权：用户在上一轮交付后明确要求“继续还可以继续审阅的”；继续深查、修复并只本地提交。
- 固定基线：`a39a3f3039ce7deafc3f547a25cc0dc4635c111a`。
- 本轮最终比较：`git diff 71ad84e416cdaab58cac18c81bfb690174ae0b72...21a24750ea35c276293370bd2b1d7fa510957c06`。两端可解析、差量非空；提交列表为 `80601e1 fix: preserve authorization across role approval and notification updates`、`21a2475 fix: reject default role writes after concurrent deletion`。本次追加复核只读后一个提交的2文件窄差量。
- 本轮初始只读快照：`71ad84e416cdaab58cac18c81bfb690174ae0b72`（产品提交 `a9f044067fcf05c5cd914b9202c784a7eb67d587`）。开始时工作树干净；Lead 随后在独立写入范围实施，以下源码行号按初始快照归因。
- 差量入口：`git diff a39a3f3039ce7deafc3f547a25cc0dc4635c111a...71ad84e416cdaab58cac18c81bfb690174ae0b72`，已确认相关路径非空。提交链：`e57bb02 → 07a48e8 → ccae720 → 07c3e40 → 7be9a82 → a9f0440 → 71ad84e`。
- 需求来源：`docs/audit/2026-11-07/SPEC.md:5,20–22,27–28,35,38`；行为契约来自 `AI_MODULE_DESIGN.md`、通知宿主约定及公开工具定义。

## 初始发现（历史归因，当前状态见顶部及末尾）

| 编号 / 优先级 | 分类与确认的行为错误 | 发现时状态 |
| --- | --- | --- |
| J-N1 / P1 | **错误实现：发布结果与实际受众不一致。** `publish_notice` 承诺发布后立即可见，却把 `INAPP` 当平台写入，桌面/Web 等端读不到；绕过宿主写路径又留下 60 秒旧缓存。未知受众如 `ADMIN` 被原样保存，读取端最终按所有人可见，可能把管理员认为受限的正文下发给游客。默认 `SYSTEM` 也使公告误入通知保留期。 | 已报告 Lead，待公开读写链路回归。 |
| J-N2 / P1 | **错误实现：最后超管保护承诺没有执行。** 工具声明“不允许移除最后一个超级管理员的角色”，实际只查用户/角色存在后整体替换。唯一超管把自身角色改成普通角色会成功，授权缓存刷新后无人能再通过超管入口。 | 已报告 Lead，与服务事务/授权完整性修复共用边界。 |
| J-N3 / P2 | **需求部分实现：审批未校验目标授权版本。** 计划摘要覆盖工具配置，不包含目标用户角色集合/所选角色有效状态。待批期间其他管理员更新角色后，旧计划仍整体覆盖新集合；相同角色 ID 的权限含义变化也不会使计划过期。违背“重新校验权限和对象版本”。 | 已报告 Lead；计划新增服务端准备钩子及服务锁内指纹比对，待真实审批回归。 |

没有确认新的范围外功能。上述问题均来自当前已承诺的行为；不要求新增全局角色删除、用户类型变更安全政策，也不扩展通知工具为任意角色受众投放系统。

## 初始证据与最小关闭条件（保留历史）

### J-N1：通知发布范围、即时性与类型口径

**合同。** `AdminToolProvider.java:1039–1053` 将动作定义为“发布站内公告”“发布后立即对目标受众可见”；`AI_MODULE_DESIGN.md:838–850` 要求展示目标、字段、影响范围，确认后返回真实结果。`AGENTS.md:177–181` 指定公共通知常量及宿主查询/发布所有权；`NoticeConstants.java:14–19,24,51` 明确平台维度、`all` 与广播 `announce`。`SPEC.md:20,27–28,35` 要求维持字典契约并修复已确认错误。

**因果链。**

1. `astral-plugin/src/main/java/com/astral/ai/tool/AdminToolProvider.java:1063–1077` 直接 `noticeMapper.insert`，固定 `channel=INAPP`、默认 `noticeType=SYSTEM`，不调用 `NoticeService.create`。
2. `astral-system/src/main/java/com/astral/system/notice/NoticeTargets.java:163–182` 只识别实际平台、`all` 和历史 app/pc；`INAPP` 作为全脏值回落 Android+iOS。`NoticeServiceImpl.java:83–89` 进行该平台过滤，Windows/Linux/macOS/Harmony/Web 请求均无法取得这条广播。管理端 inbox 不按平台过滤，故“管理端能看到”不能证明客户端投放正确。
3. 同服务 `:93–110` 缓存原始行 60 秒；正常 `create:211–223` 会失效缓存，工具直接插表不会。预热 active 后发布，即使手机端也不能立即取得新记录。`:97–101` 只豁免 `announce` 的保留期，`SYSTEM` 在非零保留期配置下会随时间被排除。
4. 工具 schema `:1051–1052` 只声明自由字符串，`:1071` 原样保存 `audience`。`NoticeServiceImpl.java:412–422` 对不认识的 `ADMIN` / `ROLE:ops` 最终返回 true；`AppNoticeController.java:51–61` 的 active 是公开读取入口。审批卡 `ChatPanel.tsx:623–625` 仍展示原字符串，实际范围却包含游客。

**最小关闭。** 复用宿主通知发布能力，明确全平台 `CHANNEL_ALL` 与广播 `TYPE_ANNOUNCE`，保留 sender=ai；只接受现有 `ALL/LOGGED_IN/NOT_LOGGED_IN` 受众，未知值拒绝，不静默放宽。工具参数说明与实际值域一致；仅调用 `create` 而仍传 `INAPP/SYSTEM` 不能关闭本项。无需为了此工具改变历史脏数据的读取兼容策略。

**公开回归接缝。** 真实公开 handler 批准执行 → 真实 `NoticeServiceImpl` → App active/center；先预热空通知缓存，发布后立即核 Android、Windows、Web 等平台可读。未知受众须 REJECTED 且零写入，LOGGED_IN 对匿名不可见。用非零保留期及较早 create_time 验证真正公告不被通知窗口排除。Mapper 可用隔离真实持久层；不把仅验证 insert 参数称为公开读写闭环。已有 `NoticePlatformTest` 只证明反馈回复场景的渠道归一，未覆盖这个工具路径。

### J-N2：唯一超管可由角色工具自降权

**合同。** `AdminToolProvider.java:967` 明文承诺“不允许移除最后一个超级管理员的角色”。`RequiresSuper.java:16–17` 与 `StpInterfaceImpl.java:74–79,102–114` 明确超管身份来自启用的 `is_super=1` 角色，不能用 `user_type=ADMIN` 代替判断。

**因果链。** `ChangeRolePermissionTool.apply:978–1004` 要求 roleIds 非空并检查用户存在，随后直接 `assignRoles`。初始 `UserRoleServiceImpl.java:38–71` 只验证角色 ID 存在，然后事务内删除原关联、批量插入新关联并通知。唯一可用超管 A 当前持 S（启用且 is_super=1），批准把 A 的角色整体替换为普通角色 R，两个存在性检查均通过，S 关联被删除。缓存仍保留旧权限只会延迟故障；失效或过期后无法再使用 `@RequiresSuper` 入口恢复。

**最小关闭。** 在实际写事务及共同锁下实施这个已承诺的保护，按当前启用超管角色/可用账号重新计算；不能在工具中先 count、无锁再写。只检查 `roleIds` 非空也不等价于保留超管。若所有公开赋角色入口复用该安全边界，可避免双超管并发各自降权；不由此推导本轮必须增设所有删除/禁用/类型入口的全局新政策。

**公开回归接缝。** 公开工具或真实审批 → Spring 代理的真实 `UserRoleService` / Mapper：唯一可用超管换普通角色应拒绝并保留关联；存在另一可用超管时正常替换；两个独立超管同时自降权，最终至少保留一个可用超管。普通 ADMIN 角色不能误算作超级管理员。已有权限码测试只证明调用者需要 `*:*:*`，没有证明目标最后超管保护。

### J-N3：等待审批期间的授权目标变化未冻结

**合同。** `AI_MODULE_DESIGN.md:840,844,1808` 要求审批展示影响范围，并在执行前重新校验对象状态/版本。该要求不等于“执行前用户仍存在”。

**因果链。** `AiChatOrchestrator.java:950–970` 持久化模型参数及工具计划摘要；`AiToolService.java:747–768` 的摘要包含 executor_config、risk、approval，MCP 另含服务器指纹，均没有这次角色操作的目标授权状态。`approve:367–373` 重新计算上述工具摘要；`ChangeRolePermissionTool:997–1001` 仅重读用户存在后整体替换。因此初始 U=[R1] 的旧待批计划要改为 [R3]，在等待中 U 被合法改为 [R1,R2] 后，批准旧计划仍会移除新加 R2。同一 roleId 在等待中改变有效权限/超管属性也不会使该旧计划失效。

**最小关闭及准备钩子边界。**

1. 仅为需要目标校验的代码工具提供受控服务端 prepare 阶段，在第一次持久化计划前采集预期指纹；模型传入同名 expected 字段必须被覆盖/拒绝。审批不得重新 prepare 并覆盖旧指纹，历史缺指纹计划应要求重新生成。
2. 指纹覆盖目标用户当前有序角色集合，以及拟授予角色的有效权限身份（status、is_super、权限关联的稳定版本或内容）。只散列 roleIds 不能识别同 ID 的权限含义变化；不必纳入无关用户/全库内容。
3. 执行时在领域服务相同事务/共同锁内比对预期与当前，再替换。仅在编排器事先查询比对仍留有检查后变更窗口。相关角色/权限写路径须遵守共同锁，复用 Lead 的领域并发方案。
4. test-run 是明确人工即时执行入口，可当场服务端 prepare 后进入相同校验/写入边界；仍必须执行 J-N2 最后超管保护。
5. 已确认零写入的最后超管/指纹冲突应返回明确 REJECTED。当前 `AiChatOrchestrator.invoke:1097–1114` 将非 READ 工具抛异常统一保守映射 UNKNOWN；修复可让 handler 识别专门领域拒绝并返回拒绝结果，不能把该安全拒绝显示成“可能已执行”。也不能因此把所有业务异常泛化为可安全重试的 REJECTED。

**公开回归接缝。** 复用 `AiApprovalLifecycleTest` 的实际 `start → WAITING_APPROVAL → approve → resume` 夹具，在等待期间通过真实领域服务改变 U 的角色集合，再批准旧计划：应明确拒绝、保留新集合、无变更通知。再覆盖选中角色权限含义变化、伪造 expected 字段、目标未变时正常成功。用 latch 安排“比对后写前”的竞争，确认校验与替换确实共享锁；只给 hash helper 写单测不能证明闭环。

## 已核对的反证、协作与证据边界

- 审阅范围限于管理工具定义/执行、宿主通知写读契约、角色替换调用、审批与取消/结果持久化相关边界。读取了 `AdminToolProvider`、`NoticeServiceImpl`、`NoticeTargets`、`NoticeDisplay`、两端 NoticeController、schema/常量、`UserRoleServiceImpl`、`StpInterfaceImpl`、`AiChatOrchestrator`、`AiToolService`、`AiToolPermissionGate`、`AiAdminToolController`、`AiRequestService`、`AiToolTypes`，并核对相关公开夹具及审批卡参数展示。没有声称本轮再次逐文件通读全仓。
- `approve` 已独立提交 RUNNING/executionToken，再执行、保存结果；同一 call 的重复审批不能重新执行。`AiToolContext.executionKey` 已有稳定 callId。取消先于认领、认领后的在途副作用分别遵守上一轮已说明边界，不因存在发送前最后一次状态读取窗口重新报告旧生命周期缺陷。
- 邮件专用路径当前先过滤 EMAIL 再解析收件人/投递，真实结果含已确认数量，失败用 UNKNOWN；旧 I-N2 跨渠道问题不再重报。本轮未把“订阅规则允许多个 EMAIL 投递”另行判为缺陷，也未虚构审批者必须不同于请求者的政策。
- 用户/角色/权限写完整性、缓存版本失效由 Lead 独占实施；初始 `assignRoles` 缺少缓存失效已由 Lead 独立确认，本文不重复计数。J-N2/J-N3 的保护/目标指纹与其服务锁方案协同。
- 3 项均已即时消息交回 Lead。收到停止扩域指令后仅完成报告。未运行构建、测试、服务、数据库、Redis、浏览器或网络；未读取真实凭据；仅新建本文，无产品编辑或提交。上一轮 828/133 等门禁不作为本轮新修复通过证据。
- 初始收口要求：修复后的固定 SHA、公开 RED→GREEN 日志与实际实现的限定复核；发现时状态为“确认缺陷，待闭环”，后续进度以本报告最新核验为准。

## 未提交修复窄复核（历史）

本节按 Lead 指定的 `git diff --ignore-space-at-eol 71ad84e -- …` 读取共享工作树及新增测试；仅限 J-N1～J-N3 关闭条件。后续 Lead 仍在修改直接关联写入口，因此本文不是整个未提交 diff 的最终审阅，也不把已读取的 focused 日志归为最后提交门禁。复审者未执行测试或服务。

### 原问题修复与已读取证据

| 问题 | 当前实现核验 | focused 证据及边界 |
| --- | --- | --- |
| J-N1 | `AdminToolProvider.PublishNoticeTool` 使用 `NoticeService.create`，固定公共 `CHANNEL_ALL/TYPE_ANNOUNCE`，保留 sender=ai；schema 与执行期均限制现有受众/公告类型，未知值先拒绝。宿主服务补 display 等默认值并立即失效原始通知缓存。原平台漏投、热缓存滞后、未知受众放宽、公告过期路径已消除。 | `continue-notice-red.log:44` 真实公开 handler→NoticeService/H2 预热后 Android 读取期望1实际0；`continue-notice-green.log:47,55,59` 为通知2项+既有权限4项，共6通过。两项通知用例实际验证7平台、sender/type、未知受众/类型零写入、登录受众过滤，以及30天前公告仍可见。是公开服务+真实MyBatis/H2，不是 HTTP/浏览器或生产库验收。 |
| J-N2 | `UserRoleServiceImpl` 的事务替换按有序未删 ADMIN 集合及目标用户加锁，再锁角色定义；当前启用且 is_super=1 的角色判断最后可用超管，另一账号必须 active ADMIN。拒绝发生在清空关联之前。缓存失效仅注册 afterCommit；工具将 SYS001/012/016/017 这些明确域拒绝返回 REJECTED，其他异常保留不确定语义。 | `continue-last-super-red.log:52` 原码没有抛预期拒绝；green `:52,156,160` 为领域3项+通知10项=13通过。缓存独立RED `continue-role-cache-red.log:51` 为仍读旧权限；回归还覆盖事务未提交/回滚不改变版本。`continue-role-concurrency.log:257,261` 为真实PG2项：两个已批准角色工具竞争，第二个拒绝且保留一个超管；同用户两个整体替换最终不混成并集。前者通过真实 JDBC 返回后 latch 及 `pg_stat_activity` 等待锁安排并发，未接真实用户会话。 |
| J-N3 原覆盖路径 | `ChangeRolePermissionTool.prepareArguments` 深拷贝参数并删除模型自带 `_authorizationSnapshot`，调用服务生成真实指纹。`planTools` 持久化准备后参数；approve 不重建预期值。服务 `assignRolesIfUnchanged` 同事务按 users→all roles→relevant permissions 加锁、重算后才写；指纹包含目标状态、当前集合、相关 role status/is_super/code、权限关联及 permission code/status。缺指纹旧计划拒绝；test-run 也先服务端准备。 | `continue-role-plan-red.log:258` 原审批把等待中已改成 `[1,2]` 的集合覆盖成 `[3]`；green `:263,267` 为真实服务/持久层的 **H2 PostgreSQL mode 生命周期4项**：等待期改集合、模型伪造指纹但目标未变、同roleId变is_super及缺指纹、test-run最后超管拒绝与有另一超管时成功。此前称“真实PG”是报告错误，已更正。角色意义改变由合成 JDBC 安排，不能写成 PostgreSQL 锁行为或所有角色权限公开入口竞争实测。 |

锁协议还核对了 `RolePermissionServiceImpl` 的公开整体替换/新增/更新/删除与新 `updateAssignment`：均在角色父行锁内写关联，Controller 更新已转用该领域方法；`AppUserRoleService.syncPermissions` 事务内锁 APP_USER 父行，`assignToUser` 锁用户再锁角色；角色/权限自身 SQL 更新与相应 FOR UPDATE 行锁互斥。当前已读实现没有发现 J-N3 的共同锁缺口。新 `RoleAuthorizationPersistenceTest.approvalSnapshotDetectsPermissionAndDirectAssignmentChanges` 在源码中存在，但上述13项日志早于该新增测试，不能把它算入已核验13项证据。

**证据深度限制，不计作新产品缺陷：** 当前4项生命周期与2项PG并发没有实际安排“审批已经取得权限快照锁，同时另一个公开 RolePermission 写入口尝试变更”的交错。源码共同锁足以支撑当前因果判断；若最终报告声称该竞争已实测，需要对应公开入口测试日志，不能借用最后超管并发用例。

### J-N3 准备钩子回退缺口（历史发现，当前已覆盖）

**确认链。** 新 `AdminToolProvider.java:979–988` 对非空 userId 调用 `assignmentSnapshot`。不存在/软删除的用户在服务锁后抛 `SYS001`；`AiChatOrchestrator.planTools:950` 在统一 invoke 的域拒绝转换之外调用该钩子，异常使整轮计划事务回滚，`runSafely:575–579` 把它变为整个请求的泛化 AI006 失败。旧路径原可由 `ChangeRolePermissionTool.apply` 明确返回“用户不存在”REJECTED。同 batch 的其他工具计划也随该事务丢失。`AiToolService.testRun:406` 同样在创建 `ai_tool_call` 审计行之前准备，因此这个原可审计的确定域拒绝变为异常且没有工具审计。

**合同与最小关闭。** `AI_MODULE_DESIGN.md:844–850` 要求校验对象状态、审计并返回执行结果；`SPEC.md:13,35` 要求错误恢复与真实反馈。只处理准备阶段已确认的域拒绝，不伪造指纹、不吞数据库故障：例如捕获 SYS001 并保留缺指纹参数，随后由现有 apply 明确拒绝；或在 planTools 持久化 REJECTED 工具结果并继续其他计划。test-run 同样保留明确拒绝审计。公开 start→计划/确认→收束与 test-run 增加不存在/删除目标案例，断言零角色写入、可见域拒绝、请求能正常收束及审计存在。此项是 J-N3 新钩子的回归边界，不另增第四领域。

上段记录发现时的确认链。以下为12:42时的修复与证据；当时仍待最终固定 SHA，工作树“原路径覆盖”不等于整轮提交已验收。

### 12:42 定向门禁后的最新核验与事实纠正

1. **准备期拒绝已覆盖。** 当前 `UserRoleServiceImpl.assignmentSnapshot:59–62` 在锁定的未删用户集合中找不到目标时返回 null，避免参与中的事务因确定域拒绝被标记 rollback-only。`AdminToolProvider.prepareArguments:979–988` 仍先删除模型预期值，仅在实际生成非空指纹时加入；`apply:1011–1013` 对不存在或 deleted=1 明确 REJECTED。真实数据库故障没有被该 null 分支吞掉。`AiToolService.testRun` 先创建工具审计再调用 apply，因此拒绝被持久为 `source=test-run/status=REJECTED`。
2. **新增公开用例实际验证。** `AiApprovalLifecycleTest.missingOrDeletedRoleTargetIsAnAuditedToolRejection:1012–1027` 两个参数分别安排不存在用户与软删除用户，调用公开 start，断言进入 WAITING_APPROVAL；批准后正常收束 SUCCEEDED、原角色集合不变、TOOL 消息明确“不存在”；随后公开 testRun 返回 REJECTED 并查得一条拒绝审计。该夹具是实际编排/Controller方法/服务、MyBatis及Spring代理事务，提供方、身份、外部副作用为隔离替身；不是 HTTP/浏览器验收。
3. **数据库类型纠正。** 本日报告此前将四项 `AiApprovalLifecycleTest` 称为真实PG，属于事实错误，已经更正。该类 `:77,120–121` 使用 `org.h2.jdbcx.JdbcDataSource`、`jdbc:h2:mem:lifecycle_…;MODE=PostgreSQL`。PostgreSQL mode 不提供真实 PostgreSQL advisory-lock 语义，不能用此夹具证明它。`AdminToolRoleConcurrencyTest:84–89` 才使用 `jdbc:postgresql://127.0.0.1:{audit.postgres.port}` 和独立随机 schema，并观察 `pg_stat_activity` 等待锁。前一轮冻结报告不由本成员修改；已将这项技术纠正明确交回 Lead。
4. **失败证据口径。** `continue-focused-green.log:498–516` 的两个失败发生在新增审计计数断言，原断言误查 `source='TEST_RUN'`。产品常量 `AiConstants.ToolCallSource.TEST_RUN:219` 一直为 `test-run`，当前断言已按实际契约更正。此失败不计为产品 RED，也不据此声称曾漏写审计。原 J-N3 角色覆盖 RED（`[1,2]` 被改为 `[3]`）仍是有效行为反例；准备钩子回退的先前判断来自源码因果链，未夸作该错误断言已复现它。
5. **70项日志核实。** `continue-focused-final.log:221,229,234,338` 为 system 四套3+6+7+10=26；`:484,486,488,494` 为 plugin 四套36+2+4+2=44；每套均0失败/错误/跳过。`:513,516` 为 BUILD SUCCESS、2026-11-09 12:42:00。生命周期36项包含上述两例；领域7项包含新增权限/直接关联指纹与缓存检查。该计数含其他已授权领域的回归，本轴仅核验与三项问题相关的实现，不宣称独立深读全部70项。
6. **待补的独立并发证据。** 已看到新增 `AdminToolRoleConcurrencyTest.approvedReplacementHoldsAuthorizationStableUntilItsTransactionCommits:235–254`：批准角色替换已在事务中删除旧关联、仍持锁时，另一线程调用公开 `RolePermissionService.assignPermissions`，要求观察真实锁等待，再释放前者并验证两次事务结果。12:42日志的该类仍只有原2项；尚未将这个新增单项记作 GREEN。它用于补强原 J-N3 同锁证据，不扩展新产品领域。

截至上述12:42历史核验，HEAD 尚为 `71ad84e416cdaab58cac18c81bfb690174ae0b72`，产品修复未提交；当时保留新增并发日志及最终提交/门禁待办。该状态已由下节固定提交复核取代。

## 前次固定提交复核（历史：2026-11-09，80601e13）

**当时结论：J-N1～J-N3 及其准备期回退、关联共同锁关闭，限定 Spec PASS。** 当时独立核实 HEAD 为 `80601e13d87f5bbfeee35ef2040cf4ad22589925`，增量基线为 `71ad84e416cdaab58cac18c81bfb690174ae0b72`；原总基线 `a39a3f3039ce7deafc3f547a25cc0dc4635c111a` 保留。固定增量共26文件，产品与回归共1提交。读取时受版本控制的工作树无差异，未跟踪内容为本日协作文档。本轴只编辑本文，不把其他成员的领域检查归为自身覆盖。S2-02后续补强见下一节，不将本节历史表述用于否定该后续确认问题。

### 已知问题关闭依据

| 项目与合同 | 固定提交实现 | 最小证据与结论 |
| --- | --- | --- |
| **J-N1 错误实现，关闭**。`SPEC.md:20,27–28,35`；工具“立即对受众可见”及 `AGENTS.md:179–187` 的宿主发布合同。 | `AdminToolProvider.java:1063–1109` 由 `NoticeService.create` 发布，固定 `CHANNEL_ALL/TYPE_ANNOUNCE`、sender=ai；未知受众/类型在写入前明确拒绝。`NoticeServiceImpl.java:211–223` 补默认字段并失效缓存。 | `AdminToolNoticePersistenceTest` 真实 handler→宿主服务→MyBatis/H2：缓存预热后7平台立即读取、限定登录受众、未知值零写入、旧公告不受通知保留窗口误删；最终日志 `:1341` 2项通过。 |
| **J-N2 错误实现，关闭**。工具最后可用超管保护承诺及 `AGENTS.md:383–386` 授权变更即时失效合同。 | `UserRoleServiceImpl.java:73–180,236–319` 在有序用户→角色锁内保护整体替换及直接关联更新/删除；明确域拒绝在写入前发生。缓存版本由 `PermissionCache.bumpAfterCommit` 在成功提交后更新，事务回滚保留旧版本。 | `RoleAuthorizationPersistenceTest` 8项（最终日志 `:639`）覆盖最后超管、提交/回滚缓存、默认App角色、缺失角色/用户与软删除用户；`AdminToolRoleConcurrencyTest` 真实PG3项（`:1350`）含两个批准工具竞争及同用户整体替换不混成并集。未把用户类型/角色定义所有变更路径扩成新的全局最后超管政策。 |
| **J-N3 需求部分实现，关闭**。`AI_MODULE_DESIGN.md:840,844,848–850,1808` 的影响范围、重新校验对象状态/版本、审计及真实结果合同。 | `AdminToolProvider.java:979–1027` 删除模型伪造指纹，服务端准备后持久化；`AiChatOrchestrator.java:950–958` 仅首次规划调用 prepare，审批沿用旧指纹。`UserRoleServiceImpl.java:61–83,184–231` 对目标、当前/请求角色及相关权限身份在共同锁内重算再写，缺指纹或变化明确拒绝。 | H2生命周期原角色覆盖RED有效；最终 `AiApprovalLifecycleTest` 36项通过（`:1120`），新增角色场景检查等待期变化、同ID角色含义变化、模型伪造/历史缺失指纹、即时试跑。真实PG新增 `approvedReplacementHoldsAuthorizationStableUntilItsTransactionCommits:235–259` 使公开 `RolePermissionService.assignPermissions` 等待审批事务锁，实际观察 `pg_stat_activity.wait_event_type='Lock'` 后释放；已计入上述3项GREEN。 |
| **准备钩子回退，关闭**。同上审计/真实结果合同，及 `SPEC.md:13,35` 的错误恢复要求。 | `assignmentSnapshot` 对不存在/软删除目标返回null；prepare不伪造预期值，apply给出明确REJECTED。数据库异常仍传播；`testRun` 创建审计后执行apply，不以吞异常伪造成功。 | `AiApprovalLifecycleTest.missingOrDeletedRoleTargetIsAnAuditedToolRejection:1012–1037` 两参数经公开start→等待审批→批准→收束及testRun，验证零角色写入、可见拒绝和 `source='test-run'` 审计；包含在36项最终GREEN。先前误查 `TEST_RUN` 的断言失败仍不作为产品RED。 |

关联边界已只读复核：`RolePermissionServiceImpl.java:38–116` 的整体替换及公开save/update/remove共用角色父行锁；`RolePermissionController.update` 委托 `updateAssignment`；`AppUserRoleService.syncPermissions/assignToUser` 在角色或用户→角色锁内写入；`RoleServiceImpl.deleteRoleWithRelations` 先锁角色再检查引用。角色、权限自身SQL更新与快照取得的对应行锁互斥。新增真实PG公开写入口竞争补齐了12:42历史段的证据待办；该用例不等于HTTP/浏览器或任意隔离级别并发验收。

### 最终门禁与证据边界

- 只读解析 `target/continue-final-reactor.log` 所有含 `-- in` 的逐suite结果，合计 **137 suites / 850 tests / 0 failures / 0 errors / 0 skipped**，避免再累计模块汇总。`:143,178` 记录34个schema的实际实体生成；各模块均有install记录；`:6377,6380` 为 BUILD SUCCESS 与 `2026-11-09T13:12:21+08:00`。
- `target/continue-final-quality.log:32–119` 记录全模块Spotless check，`:127` 为Checkstyle aggregate，`:519–631` 为SpotBugs执行；`:648,651` 为 BUILD SUCCESS 与 `2026-11-09T13:17:14+08:00`。这里确认执行成功，不将报告目标成功表述为零告警，不代替独立Standards判断。
- 本次只核验已知三项、准备钩子与相关关联合同。没有发现仍需修改产品的确证缺陷，也没有确认范围外新增功能。新增锁和缓存提交回调服务于已有授权合同；未把性能偏好或未承诺安全策略当作遗漏要求。
- 保留此前逐域审阅归因，不宣称此次独立通读整个原始基线差量；邮件模板等同提交内容由Lead及相应轴负责。审阅者没有运行构建、测试、服务、数据库、Redis、网络或浏览器，没有触碰凭据，也没有修改产品或提交。所有运行结论来自实际夹具源码与Lead生成的固定提交日志，H2、隔离真实PG、HTTP和生产验证严格区分。

## 最新固定提交补充复核（2026-11-09，21a24750）

**结论：S2-02 同组窄修补符合现有关联写入合同，J-N1～J-N3 和 prepare/link 的限定 Spec PASS 保持。** 当前产品HEAD已核实为 `21a24750ea35c276293370bd2b1d7fa510957c06`；`80601e13...21a24750` 仅修改 `AppUserRoleService` 和 `AdminToolRoleConcurrencyTest`。本次没有扩大其他领域或重新声称全量差量独立深读。S2-02沿用原发现归因，不另计为新的J项。

- **合同及实现。** `SPEC.md:20,27–28,35` 要求维持安全/数据契约、修复已确认业务错误；`AppUserRoleService` 的公开方法合同是向实际存在的默认角色同步权限或为用户建立该角色关联。`AppUserRoleService.java:103–105,160–161,182–189` 用 `requireLockedRole` 检查 `SELECT ... FOR UPDATE` 返回值：初次 `ensureRole` 读到的角色已被并发删除时，空结果立即抛 `SYS003`。该分支位于两个入口的关联写入之前，仍在原 `rollbackFor=Exception` 事务内，正常角色的既有幂等路径保持；没有把旧角色ID写成孤儿关联，也没有放宽角色存在性。
- **与已闭环问题的一致性。** 窄差量不涉及公告、审批参数准备、指纹内容、执行比对、最后超管或缓存回调实现。App赋角色仍为用户→角色锁顺序，权限同步仍持角色父行锁，校验通过后才可写；新增的空集拒绝补全了原共同锁在角色被删除时的边界，未形成逆向锁或重建旧审批指纹。prepare不存在/软删目标回退和直接关联拒绝结论保持。
- **有效RED→GREEN。** 新 `AdminToolRoleConcurrencyTest.appRoleWritersRejectRoleDeletedAfterLookup:297–340` 使用隔离真实PostgreSQL，对 `assign`、`sync` 两参数分别从公开服务进入；JDBC只安排默认角色初次查询完成后暂停，另一线程经公开 `RoleService.deleteRoleWithRelations` 删除并提交，再恢复写线程。断言 `SYS003`、角色仍不存在、用户角色及角色权限均无该ID关联。`continue-app-role-red.log:266–290` 两项均因旧码“应抛拒绝却没有抛出”失败，属于有效行为RED；历史命名的 `continue-app-role-red2.log:265,284,287` 实为2项GREEN，14:47:34成功，不能因文件名含red将其误记为失败。
- **固定SHA最终门禁。** 逐suite汇总 `continue-final2-reactor.log` 得 **137套/852项，0失败/错误/跳过**；`:142,177` 确认34实体实际生成，所有模块有install记录，`:6366,6369` 为 BUILD SUCCESS / `2026-11-09T15:20:52+08:00`。相关回归为角色领域8项（`:633`）、H2生命周期36项（`:1109`）、通知2项（`:1330`）、真实PG角色竞争5项（`:1339`）。`continue-final2-quality.log:32–127,519–651` 确认Spotless、Checkstyle、SpotBugs执行并于15:24:20成功；静态告警评估仍归Standards，本轴不把质量任务成功等同于零告警。

本次只编辑本文，保留历史证据；没有产品修改、构建/测试执行、服务/数据库/网络访问或Git提交。运行结论来自只读源码和既有日志，未扩展为HTTP、浏览器或生产验收。
