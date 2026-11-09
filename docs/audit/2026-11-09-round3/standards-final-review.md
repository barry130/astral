# Standards 最终复核（2026-11-09）

**Standards：PASS（限下述已审范围）。未关闭的确认缺陷 0 项；初审 R3-S1、R3-S2 两项 P2 已关闭。**

## 固定提交与实际范围

- 原始审计基线：`a39a3f3039ce7deafc3f547a25cc0dc4635c111a`。
- 本次增量基线：`587b4ae215375dd745dde0630ddbd2de5c94efbf`。
- 固定产品提交：`e0d9236df7fc641081707a70416270ffb35de170`。
- 三个引用均已解析为提交，两组 diff 均非空；增量包含 23 个产品、测试和 POM 文件，1322 行新增、385 行删除。

```powershell
git -C F:/qtMusic/astral diff 587b4ae215375dd745dde0630ddbd2de5c94efbf...e0d9236df7fc641081707a70416270ffb35de170
git -C F:/qtMusic/astral diff a39a3f3039ce7deafc3f547a25cc0dc4635c111a...e0d9236df7fc641081707a70416270ffb35de170
git -C F:/qtMusic/astral log a39a3f3039ce7deafc3f547a25cc0dc4635c111a..e0d9236df7fc641081707a70416270ffb35de170 --oneline
```

已深读全部 23 个增量文件及必要的调用者、生成实体、schema、迁移和测试基础设施。具体覆盖权限关联存在性、分配与删除的锁序，默认 App 权限同步，通知规则三表更新与删除，短信模板局部更新、校验和提交后缓存行为，以及相关持久化/并发测试与夹具调整。文件清单与固定源码哈希见 [源码快照](F:/qtMusic/astral/target/round3-tested-source-snapshot.json)。

原始基线以来的总体 diff 涉及 1179 个文件；此前更广的覆盖继承自 2026-11-07、2026-11-08、2026-11-09 的审计，本次不是重新逐行审完整仓库。兄弟项目不在范围内。

## 规范与审查依据

- [AGENTS.md](F:/qtMusic/astral/AGENTS.md)，包括模块职责、通知配置合同、更新字段白名单（第 365 行）与权限缓存版本要求（第 384 行）。
- [CODING_GUIDE.md](F:/qtMusic/astral/docs/CODING_GUIDE.md:58)，尤其第 2、3 节；第 60、62、63 行要求受检异常回滚、外部调用的 public Service 事务与多步关联写入原子性。
- [checkstyle.xml](F:/qtMusic/astral/checkstyle.xml)、[根 POM](F:/qtMusic/astral/pom.xml)；相关模块没有发现更具体的 AGENTS、CONTRIBUTING 或 CODING_STANDARDS。UI 未变，UI_GUIDE 仅为继承的标准。
- [TEST-SEAMS.md](F:/qtMusic/astral/docs/audit/2026-11-09-round3/TEST-SEAMS.md) 与 [初始 Standards 报告](F:/qtMusic/astral/docs/audit/2026-11-09-round3/standards-review.md)。初始报告来自工作区，记录的是产品起点 `587b4ae`，并非该提交树中的文档；原文保持不变。
- 沿用 code-review 的完整 Fowler 判断基线：Mysterious Name、Duplicated Code、Feature Envy、Data Clumps、Primitive Obsession、Repeated Switches、Shotgun Surgery、Divergent Change、Speculative Generality、Message Chains、Middle Man、Refused Bequest。仓库规则优先；启发式建议与硬性违例分开，工具已覆盖的问题不重复计数。未读取 Spec 最终报告。

## Standards findings / verdict

**R3-S1：关闭。** [SysNotifyRuleServiceImpl](F:/qtMusic/astral/astral-system/src/main/java/com/astral/system/notify/SysNotifyRuleServiceImpl.java:68) 的公开更新和删除方法使用 `rollbackFor = Exception.class`，均先通过第 95 行的 `lockRule` 锁定父规则，再读取和清理子表。删除先完成时更新报 `NOTIFY002`；更新先完成时删除读取完整渠道集合。三表操作位于 Service 事务内，符合 CODING_GUIDE 第 60、62、63 行，并保留全量保存/显式 NULL 合同。[NotifyRuleConcurrencyTest](F:/qtMusic/astral/astral-system/src/test/java/com/astral/system/notify/NotifyRuleConcurrencyTest.java:165) 第 165、208 行的两个真实 PostgreSQL 时序均检查最终无残留渠道或收件人。

**R3-S2：关闭。** [SysSmsTemplateServiceImpl](F:/qtMusic/astral/astral-system/src/main/java/com/astral/system/notify/SysSmsTemplateServiceImpl.java:31) 先锁定存量行、合并提交的必需映射字段，再在第 97 行校验有效状态；必需字段的空串/空白被拒绝，省略字段保留存量。写入仅限已提交的可编辑字段，客户端 ID、createTime、updateTime 不进入白名单；updateTime 由服务端设置。第 128 行的 `afterCommit` 保留回滚时缓存不失效的语义。[SmsTemplateController](F:/qtMusic/astral/astral-system/src/main/java/com/astral/system/controller/SmsTemplateController.java:70) 的 POST/PUT 使用 `@Valid`；生成实体的必需字符串仅有 `@Size`，省略字段仍兼容 partial 请求。[SmsTemplatePatchPersistenceTest](F:/qtMusic/astral/astral-system/src/test/java/com/astral/system/controller/SmsTemplatePatchPersistenceTest.java) 提供真实持久化和内存短信 SPI 的回归证据。

权限路径亦核对了角色先于权限、批量权限按 ID 顺序加锁，保留字段的权限存在性校验，以及生产调用者的缓存版本更新。[RolePermissionServiceImpl](F:/qtMusic/astral/astral-system/src/main/java/com/astral/system/service/impl/RolePermissionServiceImpl.java:39) 第 63 行使用 `super.remove`，避免在取得权限锁后再次扩展角色锁集合；[PermissionServiceImpl](F:/qtMusic/astral/astral-system/src/main/java/com/astral/system/service/impl/PermissionServiceImpl.java:26) 删除路径只锁权限后检查关联。

**独立 Standards 结论：0 项未关闭确认缺陷，0 项需要提出的 Fowler 启发式建议；已审增量范围 PASS。**

## 验证证据与边界

- 本 reviewer 独立核对源码快照的全部 23 个文件：SHA-256 不匹配 0 个；固定产品提交到工作区的 `astral-auth`、`astral-system`、`astral-plugin` diff 为空。
- 父代理运行的 [最终 reactor 日志](F:/qtMusic/astral/target/round3-final-reactor.log)：139 个套件、881 项测试，失败/错误/跳过均为 0；实际生成 34 个实体，结束于 `2026-11-09T19:13:37+08:00`。
- 父代理运行的 [聚焦 GREEN 日志](F:/qtMusic/astral/target/round3-focused-green2.log)：11 个套件、102 项测试，包含 9 项真实 PostgreSQL 角色/权限测试和 2 项通知规则并发测试。
- 父代理的 [质量门禁日志](F:/qtMusic/astral/target/round3-final-quality.log)：SpotBugs Medium 248、High 0；Checkstyle warning 379、error 0。既有记录逐项评估后门禁通过，不把这些计为新的手工 Standards 缺陷。
- 本 reviewer 仅作源码、规范和日志复核，没有运行构建、测试、服务、网络/浏览器操作或提交，仅写本报告。公开 Controller/Service 的直接调用及数据库测试不称为 HTTP、浏览器或生产验收。浏览器扩展不可用，未进行浏览器验证，也未使用替代自动化。
- 短信回归使用内存 SPI；没有真实外部 SMS、SMTP 或模型投递。测试服务清理由父代理负责，本 reviewer 不操作服务。
