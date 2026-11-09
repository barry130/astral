# Astral 第三轮续审与修复（2026-11-09）

用户在上一轮交付后要求“还有没有其他问题，有的话继续”。本轮延续 [原授权与收口判据](../2026-11-07/SPEC.md)：直接修复确认问题、完成验证与独立复审、仅做本地提交。增量固定起点为 `587b4ae215375dd745dde0630ddbd2de5c94efbf`，原始全项目审计基线为 `a39a3f3039ce7deafc3f547a25cc0dc4635c111a`。

产品修复提交为 `e0d9236df7fc641081707a70416270ffb35de170`，共 23 个产品、测试及 POM 文件。本轮深入检查权限关联完整性、通知规则更新/删除交错和短信模板局部更新；全项目其他领域的覆盖延续 [首轮审计](../2026-11-08/verification.json) 与 [第二轮续审](../2026-11-09/CONTINUATION.md)，不把本轮定向深入检查描述为重新逐行通读全仓。

最终 `mvn install -Daudit.postgres.port=9684 -Daudit.redis.port=8549` 于 **2026-11-09 19:13:37 +08:00** 通过：**139 套、881 项测试，0 失败、错误或跳过**。当前 schema generator 实际安装并在 `astral-dao` 生成 34 个实体。构建后已按逐套日志、模块总数和 139 份 Surefire XML 交叉核对；23 个改动文件的 SHA256 与构建前快照相同，受版本控制的内容与产品提交一致。

## 已修复行为与证据

| 编号 | 原行为与影响 | 修复后的行为 | 行为证据 |
| --- | --- | --- | --- |
| R3-PERM / P2 | 直接新增/局部修改角色权限可保留或写入不存在的权限定义；权限删除与批量分配、App 默认权限同步、直接关联写入没有共同锁，可能在引用检查与写入间留下悬空关联。 | 公开关联写入在事务中校验有效权限；角色锁之后按 ID 顺序锁权限。权限删除先锁定权限，再检查角色引用和删除。删除先提交时写入拒绝；分配先提交时删除拒绝。 | `round3-permission-link-red.log`、`round3-permission-race-red.log`；`RoleAuthorizationPersistenceTest` 的缺失权限用例，以及 `AdminToolRoleConcurrencyTest` 的 PostgreSQL 分配/同步/直接新增/直接修改对删除交错。 |
| R3-S1 / P2 | 通知规则 DELETE 先读取旧渠道快照；并发 PUT 新增渠道及收件人后，DELETE 会删全部渠道却漏删新增渠道的收件人。 | 三表写操作下沉至公开 Service 事务。更新、删除先锁规则父行，再查询或修改子表；已删除规则的更新返回 `NOTIFY002`，重复删除保持幂等。全量保存和显式空值语义保留。 | `round3-notify-race-red.log`；新增 `NotifyRuleConcurrencyTest` 使用真实 PostgreSQL、MyBatis、Spring 事务与 JDBC 时序屏障，覆盖删除先行和更新先行两种时序。 |
| R3-S2 / P2 | 短信模板 PUT 只校验提交字段，允许将事件、编码、名称或供应商模板编码改为空白，破坏发送映射。 | 公开 Service 锁定存量行、合并有效状态并统一校验必需字段、事件/SMS 渠道、唯一性及审核状态；仅写提交的可编辑字段。省略字段保留，合法可空字段可写空串；忽略更新请求的 ID 与时间字段，空补丁不写库。缺失目标返回 `SMS002`。缓存在事务提交后失效。 | `round3-sms-patch-red.log`；新增 `SmsTemplatePatchPersistenceTest` 的 21 项真实持久化回归，内存 SMS SPI 验证实际读取的供应商编码、回滚及未提交更新/删除期间的缓存读取。 |

R3-PERM 是主代理确认的权限完整性问题组；R3-S1、R3-S2 沿用独立 Standards 初审编号，不合并或重新排序审阅轴。两个独立审阅轴的最终判断分别记录在本目录的 [Standards 最终复审](standards-final-review.md) 与 [Spec 复审](spec-review.md)。

[Standards 初审](standards-review.md) 固定于 `587b4ae`，其中“两项尚未关闭”是该时点的真实结论，保持原文。新的固定产品提交、回归日志和最终复审提供关闭证据。

## 两路独立复审

| 审阅轴 | 固定产品结论 | 范围与关闭情况 |
| --- | --- | --- |
| Standards | PASS；0 项未关闭确认缺陷，0 项 Fowler 建议 | 全部 23 个增量文件及必要规范/调用合同已核对；R3-S1、R3-S2 关闭。见 [独立 Standards 报告](standards-final-review.md)。 |
| Spec | PASS；0 项剩余确认问题 | 全部增量改动及公开行为合同已核对，权限、通知规则和短信模板要求闭合。见 [独立 Spec 报告](spec-review.md)。 |

两个 reviewer 均独立核对了 23/23 源码哈希和父代理保存的测试日志，只写指定报告，没有重跑构建、操作服务或改动产品。两个轴分别保留判断；上述 PASS 的范围是本轮增量及继承覆盖，不扩称全仓不存在其他缺陷。

## 验证与失败分类

最终聚焦运行 `target/round3-focused-green2.log` 于 18:59:39 通过，11 套 / 102 项测试全部执行；其中角色/权限组有 9 项真实 PostgreSQL 测试，通知规则并发组有 2 项。上述聚焦测试全部包含在最终 881 项 reactor 中，不重复累加成总测试数。

短信缓存迁移过程曾由 `round3-sms-cache-red.log` 复现提交前失效导致旧值重新缓存的问题，现已通过提交后失效关闭。这是实施过程中的回归保护，未另算作原始基线缺陷。

以下失败不计产品行为 RED：

- `round3-notify-race-green.log`：Mockito Mapper 夹具缺少 MyBatis-Plus Mapper 类型元数据，修正夹具后重跑，产品逻辑不因此改变。
- `round3-focused-green.log`：新增负例误以为 `changePasswordByEmail` 只支持 EMAIL；注册表实际支持 EMAIL/SMS/INAPP，并要求 EMAIL 必达。负例改用越界审核状态，保留应拒绝的断言，随后完整聚焦运行通过。
- Redis 的首次启动配置因日志路径访问失败而退出，修正为无持久化、控制台日志的隔离实例；启动失败不是产品测试失败。
- 一次独立审阅的任务传递失败产生了其他项目的结果，该结果已排除。最终复审以磁盘上的明确任务、Astral 固定提交和两份独立报告为准。

19:03:42 完成 JDK 25 静态门禁：Spotless 通过，SpotBugs **248 Medium / 0 High / 0 Low**，Checkstyle **379 warning / 0 error**。没有修改规则、排除文件、门槛或 Java 目标。新增/迁移的六条提示及继承条目均有逐项结论，详见 [质量评估](quality-review.md) 与两份 assessment JSON；通过构建不意味着没有维护性提示。

## 取舍与覆盖边界

- 管理写入口使用数据库行锁及有序锁集合实现跨实例一致性。角色→权限的顺序保持，批量替换清理不重新获取扩大后的角色集合；正常授权读取不因本次修复加锁。没有执行高并发性能压测。
- 一致性证据覆盖本轮公开 Controller/Service 业务写入口；没有新增数据库外键或宣称任意手工 SQL、所有继承 CRUD 都具备同一不变量。
- 通知规则并发与角色/权限并发使用独占 PostgreSQL schema 和真实事务。短信局部更新、其他持久化语义及审批生命周期采用 H2/MyBatis/Spring；它们不替代 PostgreSQL 的锁证据，也不构成 HTTP、浏览器或生产验收。
- 短信投递观察使用内存 SPI；模型、邮件和外部渠道均使用隔离替身，没有真实 SMS、SMTP、模型或生产库调用。
- 本轮未改前端、博客、Cloudflare、Telegram、部署和 CI 源文件。前端 Node 24 ESLint/Prettier 重新通过；类型、回归及生产构建证据沿用未变源码对应的历史记录，没有声称本轮重跑。
- 浏览器扩展仍未就绪，原授权已禁止替代 CDP/Playwright/bootstrap；本轮未执行浏览器自动化或将历史未接受诊断算作验收。

本次所有的 PostgreSQL `127.0.0.1:9684`、Redis `127.0.0.1:8549` 已在核实 PID、可执行文件、数据目录/配置和监听归属后停止。再次检查两个监听和两个进程均为空；Redis 的短暂退出延迟已保留在清理收据。既有 PostgreSQL 夹具目录依照此前删除拒绝保留，没有重试递归删除。

完整测试明细、原始报告/日志指纹、源码快照、独立结论和清理收据见 [verification.json](verification.json)。最终审计记录以单独的本地文档提交交付；产品提交和审计文档均未推送远程。
