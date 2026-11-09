# Astral 继续审阅与修复（2026-11-09）

用户在上一轮交付后要求“继续还可以继续审阅的”。本轮沿用原会话的直接修复授权和仅本地提交约束，增量起点为 `71ad84e416cdaab58cac18c81bfb690174ae0b72`，历史总体基线为 `a39a3f3039ce7deafc3f547a25cc0dc4635c111a`。原始授权见 [SPEC](../2026-11-07/SPEC.md)。

本轮深入检查角色分配、权限缓存与审批等待窗口、助手公告发布、单行邮件模板局部更新。保留上一轮全项目覆盖记录，没有把本轮的定向深入检查描述为重新逐行通读全仓。实现和最终验证由主代理完成；Standards 与 Spec 两路只读审阅分别记在本目录报告中。

最终产品提交为 `21a24750ea35c276293370bd2b1d7fa510957c06`，包含本轮首个产品提交 `80601e13d87f5bbfeee35ef2040cf4ad22589925`。最终完整构建于 2026-11-09 15:20:52 通过：137 套 / 852 项测试，0 失败、错误或跳过，实际代码生成 34 个实体。15:24:20 静态门禁通过；379 条 Checkstyle warning 和 248 条 SpotBugs Medium 均保留逐条评估，没有把构建成功描述为零告警。

## 已修复行为与证据

| 编号 | 原行为与影响 | 当前行为 | 证据 |
| --- | --- | --- | --- |
| L-N1 / P1 | 助手直接调用角色服务后不失效授权缓存；旧权限最长保留 5 分钟。新增事务若在提交前 bump，其他连接仍可能把旧值缓存到新版本。 | 用户角色写入口在提交后统一失效，回滚不改变版本；App 默认角色写入复用相同 helper。Controller 不再重复提前失效。 | `continue-role-cache-red.log` 复现旧权限；`RoleAuthorizationPersistenceTest` 验证实际授权读取、提交和回滚；`continue-app-cache-red.log` 复现提交前 bump。 |
| L-N2 / P2 | 整体角色替换缺少共同父行锁；并发写可能把两个目标集合合并。direct 关联入口可写不存在的角色或用户。 | 整体替换、直接增改删共用用户→角色的事务锁；关联目标在锁内校验。角色删除及权限关系写入锁定同一角色父行集合。 | `continue-role-link-red.log` 与 `continue-user-link-red.log` 为存在性行为 RED；真实 PostgreSQL 两请求证明整体替换保持一个完整集合。 |
| J-N1 / P1 | 公告工具把 `INAPP` 写进平台字段，桌面/Web 漏投；绕过宿主缓存；未知 audience 被读为所有人；默认类型错误地受通知保留期限制。 | 通过宿主 `NoticeService.create` 发布全平台 `announce` 公告，只接受 `ALL/LOGGED_IN/NOT_LOGGED_IN`；未知受众或类型明确拒绝，保留 sender=ai。 | `continue-notice-red.log` 热缓存读取失败；`AdminToolNoticePersistenceTest` 覆盖 7 平台、匿名/登录隔离、未知输入及保留期。 |
| J-N2 / P1 | 工具承诺保护最后超管，实际可将唯一可用超管改成普通角色。 | 事务锁内计算可用超管，角色替换及直接关联移除均保护最后一人；确定的拒绝返回 `REJECTED`。 | `continue-last-super-red.log`、`continue-direct-role-red.log`；领域回归及真实 PostgreSQL 双超管同时降权，最终保留一人。 |
| J-N3 / P2 | 旧审批计划不包含目标授权状态，等待期间的新分配会被覆盖；同 ID 角色的权限含义变化不使计划过期。 | 服务端首次准备并持久化授权指纹，覆盖模型伪造值；审批不重建，在相同事务锁内重算并拒绝过期计划。test-run 同样保护。 | `continue-role-plan-red.log` 复现新集合被覆盖；实际会话生命周期覆盖过期/未变/伪造/历史缺值/角色含义变化；PG 用例证明公开权限修改等待审批事务提交。 |
| S2-01 / P2 | 单行邮件模板只校验提交字段：省略 scene 可保存未知变量，只换事件漏查旧内容；省略 channel 的判重误伤 EMAIL/INAPP 兄弟行。 | 公开 Service 锁定行、合并有效状态并验证，再按白名单局部写入；变量归一化与有效状态一致，提交后失效邮件缓存。允许尚未启用渠道的预配置。 | `continue-mail-patch-red.log`；`MailTemplatePatchPersistenceTest` 3 项覆盖正文/主题/声明/只换事件、渠道内唯一性和保留字段。 |
| S2-02 / P2 | App 默认角色首次读取后可被并发删除；后续角色锁返回空集被忽略，分配用户或同步权限仍可写入悬空关联。 | 两条公开入口共用锁后存在性校验；角色已删除时抛出 `SYS003`，原事务回滚，不留下用户角色或角色权限关联。 | `continue-app-role-red.log` 的真实 PostgreSQL 两项参数化用例均复现旧行为；`continue-app-role-red2.log` 为修复后的 GREEN（文件名沿用），两项均通过。 |

L-N1/L-N2 为主代理确认项；J-N1～J-N3 与 S2-01/S2-02 分别保留两个独立审阅轴的编号，不合并重排。S2-02 是固定提交复核发现的同组锁协议残余，补修未扩展到其他领域。

准备钩子新增的一处回归也已关闭：不存在或已删除目标不抛出使计划事务回滚的异常，而是没有快照，执行阶段明确拒绝并留下工具审计；聊天可正常收束。测试初版错误查询 `source='TEST_RUN'`，实际契约为 `test-run`，因此该次失败不作为产品缺陷 RED。`continue-role-boundaries-red.log` 为构造参数迁移引起的测试编译失败，同样不计行为 RED。

S2-02 首次夹具运行 `continue-app-role-redgreen.log` 因 `RoleServiceImpl` 构造参数使用了错误 Mapper 而编译失败；修正夹具后的 `continue-app-role-red.log` 才是有效行为 RED。最终 reactor 的第一次启动因未加引号的 PowerShell `-D` 参数解析立即失败，未进入测试；修正调用后重跑，最终结论仅引用成功完成的运行。

## 实现取舍与验证范围

- 管理员集合及角色集合按 ID 顺序加锁，角色审批涉及的权限定义随后加锁。它提高了管理写操作的串行化范围，以换取跨实例一致性；没有对正常授权读请求加锁。此轮没有进行高并发性能压测。
- 最后超管保护限于本次角色分配/关联写路径，不宣称新增了覆盖所有用户类型变更、用户删除及角色启停的全局不变量。原工具的明确承诺及并发角色降权触发链已处理。
- `AiApprovalLifecycleTest` 使用真实 Spring/MyBatis **H2 PostgreSQL mode**，不是 PostgreSQL。`AdminToolRoleConcurrencyTest` 使用独占 PostgreSQL schema、独立事务和 `pg_stat_activity` 锁等待观察。公告及模板回归为真实服务/持久层调用，没有浏览器或 HTTP 端到端声明。
- 通知故障、外部模型和发送端使用隔离边界替身；日志里的投递成功/故障场景来自夹具，不代表实际 SMTP、短信或模型请求。
- 没有修改前端、博客、worker、Telegram、部署和打包文件。它们的既有完整测试/构建结果沿用上一轮记录；本次提交要求的前端 ESLint/Prettier 已重新执行。
- 浏览器扩展仍未就绪，原授权禁止替代浏览器自动化；本轮没有浏览器验收。PostgreSQL 与 Redis 只使用本机隔离端口 9684/8549，无生产库操作。

最终全量门禁、静态分析逐项评估、日志指纹、提交与服务清理记录见本目录 `verification.json` 和 `quality-review.md`。两个独立审阅轴的固定提交结论见 [Spec](spec-review.md) 与 [Standards](standards-review.md)。
