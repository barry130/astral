# 第三轮续审：静态分析与质量评估

固定产品提交：`e0d9236df7fc641081707a70416270ffb35de170`。增量起点：`587b4ae215375dd745dde0630ddbd2de5c94efbf`。评估对比 [第二轮逐项账本](../2026-11-09/quality-review.md)，继承条目保留原处理依据。

JDK 25 下运行 `mvn spotless:check checkstyle:checkstyle-aggregate spotbugs:spotbugs`，于 **2026-11-09 19:03:42 +08:00** 完成，`target/round3-final-quality.log` 记录 `BUILD SUCCESS`。Spotless 通过；Checkstyle **379 warning / 0 error**；SpotBugs **248 Medium / 0 High / 0 Low**。规则、排除文件、告警门槛和 Java 目标均未更改。

## Checkstyle 差量

376 条按路径、规则和消息匹配上一轮评估；3 条新增或迁移提示对应本轮明确校验/局部更新分支，3 条旧 Controller 提示消失，净数保持 379。原始 XML SHA256、每项位置和决定见 [checkstyle-assessment.json](checkstyle-assessment.json)。

| 固定提交位置 | 提示 | 判断与验证 |
| --- | --- | --- |
| `SysNotifyRuleServiceImpl.validate:107` | NPath 17,388 | 规则名称/事件、渠道去重、支持与必达渠道、模板归属、收件人及平台校验从 Controller 下沉。父行锁与公开 Service 事务关闭真实竞态；复杂度保留为维护提示，既有独立校验条件仍需存在。 |
| `SysSmsTemplateServiceImpl.updateTemplate:29` | NPath 131,072 | 锁行后合并五个有效状态字段，再对九个可编辑列显式 SET。指标将独立可选字段组合相乘；白名单便于核对字段边界，省略值不会回放陈旧存量。21 项公开持久化/SPI 回归覆盖空白拒绝、合法补丁、冲突、空补丁、回滚及缓存时序。 |
| `SysSmsTemplateServiceImpl.validateTemplate:97` | NPath 3,240 | 四个非空白必需字段、审核上下界、编码/事件唯一性与注册事件/SMS 渠道校验对应明确业务错误。有效状态的各拒绝分支经过测试，没有发现可保存非法状态的路径。保留为维护提示。 |

其余继承条目的原始位置与处理理由保留在账本中，复杂度、声明顺序和风格提示不能统一算作误报，也不因本轮未重写来源而消失。

## SpotBugs 差量

245 条按类型、类、字段和消息匹配上一轮评估；3 条从 `NotifyRuleController` 移至 `SysNotifyRuleServiceImpl`，对应旧 Controller 的 3 条消失，净数保持 248。见 [spotbugs-assessment.json](spotbugs-assessment.json)。

迁移提示均为 `BX_UNBOXING_IMMEDIATELY_REBOXED`，位于 `createRule:60`、`updateRule:76` 和 `persistChannels:227` 的开关/限额默认值三元表达式。null 分支先给 1/0，非 null 分支才拆箱；没有空值拆箱或状态含义变化。微小装箱成本作为维护提示保留，公开保存及 PostgreSQL 并发回归通过；没有为改变指标改动缺省值或全量保存契约。

所有 248 条 Medium 均有明确 disposition/reason，待评估项为 0；11 份当前 SpotBugs XML 与聚合 Checkstyle XML 的哈希已重新核对。继承的 Spring 注入/DTO 可变引用提示、已有框架反证及其他提示继续使用各自依据，不一概称为误报。

## 实际质量判断与验证限度

本轮发现并修复的主要缺陷属于语义层：权限写入与删除未共享锁、通知规则删除使用陈旧子表快照、短信 partial 请求绕过有效状态校验。静态扫描没有替代公开写入口、真实持久层和确定并发时序的验证。

多表通知写入统一到公开 Service 事务，短信更新显式区分有效状态验证与提交字段持久化，权限写入/删除共享有序数据库锁。这些改动提高了事务和字段边界的可核验性；通知校验及补丁白名单仍有较高分支复杂度，作为维护成本保留。没有为降低告警数引入反射复制、弱化验证或空转发拆分。

最终 reactor 于 **19:13:37** 通过：139 套 / 881 项测试，0 失败、错误或跳过，实际运行当前 generator 生成 34 个实体。完整日志、模块总数与 139 份 Surefire XML 相互一致；构建前后 23 个改动文件 SHA256 一致，整个受版本控制的工作树内容与产品提交相同。

聚焦 102 项含 9 项 PostgreSQL 角色/权限回归和 2 项通知规则并发回归；短信 21 项为 H2/MyBatis/Spring 与内存 SMS SPI。锁协议覆盖本轮公开写入口，没有做高并发性能评估。前端无源码变更，ESLint/Prettier 本轮通过，其余前端验证沿用历史；浏览器限制及外部渠道隔离保持。完整证据见 [verification.json](verification.json)。
