# 第四轮续审：静态分析与质量评估

固定产品提交：`bc16a9fe981a346c4a2f57b56b8fbd0d2268c755`。增量起点：`9679cec2082d905df96186748c7413d3b72c4b8b`。本轮对照 [第三轮评估](../2026-11-09-round3/quality-review.md)，保留每个继承条目的处理依据。

JDK 25 下依次执行 Spotless、Checkstyle 聚合与 SpotBugs，分别于 **2026-11-09 20:18:46、20:19:32、20:22:31 +08:00** 完成，三个命令均为 `BUILD SUCCESS`。规则、排除文件、门槛和 Java 编译目标没有更改。

| 工具 | 本轮结果 | 原始记录 |
| --- | --- | --- |
| Spotless | PASS | `target/round4-spotless-check.log` |
| Checkstyle | 379 warning / 0 error | `target/round4-checkstyle.log`、`target/checkstyle-result.xml` |
| SpotBugs | 248 Medium / 0 High / 0 Low，Max effort、Medium threshold | `target/round4-spotbugs.log`、11 份模块 XML |

通过门禁不意味着没有维护性提示。各条提示的原始位置、报告指纹、disposition 和 reason 保存在 [Checkstyle 账本](checkstyle-assessment.json) 与 [SpotBugs 账本](spotbugs-assessment.json)。

## 本轮差量判断

SpotBugs 的 248 条按类型、类、字段和消息与第三轮全部匹配。新注入的真实 Bean Validator 没有新增缺陷模式提示。

Checkstyle 有 378 条按路径、规则、消息匹配旧评估；剩余一条是 `MessageTemplateService.save:120` 的 NPath 数值由 **7,344 → 36,720**，取代原条目，总数仍为 379。新增的短信供应商编码必填、审核状态上下界分支符合已有短信合同。按所选渠道验证生成实体，以及对可空列显式 SET 的白名单，保留完整表单清空、未选字段不干扰保存、跨渠道回滚和提交后缓存语义。52 项聚焦公开持久化/投递回归均通过，并包含在最终 899 项测试中。

该复杂度是实际维护成本，作为 `retained-maintenance` 保留；没有把它称为误报，没有为降低指标拆出空转发层、反射复制字段或放宽校验。继承提示也各自保留原依据，不统一算作误报。待人工评估条目为 0。

## 质量与证据限度

本轮三组问题都属于输入或持久化语义：合并入口绕过短信有效状态检查、MyBatis 默认跳过 null 使清空失效、DTO 绕过实体约束并伴有 schema 列宽偏差。静态分析不能代替公开接口上的行为回归。

最终 `mvn install -Daudit.postgres.port=9684 -Daudit.redis.port=8549` 于 **20:16:07** 通过：139 套 / 899 项测试，0 失败、错误或跳过，实际安装并调用当前 generator 生成 34 个实体。20:23 的收据交叉核对了逐套日志、模块总数和当时的 139 份 Surefire XML。后继运行覆盖了插件模块 84 份 XML，收口时仅 55 份仍匹配；原始日志与冻结收据继续保留，不将当前覆盖后的报告计作第四轮。两份已移除旧测试报告也明确排除。

9 个改动文件的 SHA256 与构建前快照一致，最终测试和静态门禁对应当前产品内容。H2/MyBatis/Spring 证明持久化与事务语义；MockMvc 证明单渠道邮件校验在写入前触发，未覆盖完整 HTTP 鉴权与全局响应体。短信使用内存 SPI，未真实投递。前端本轮无源码改动，Node 24 ESLint/Prettier 通过，其余门禁沿用未变源码的历史证据；浏览器限制仍存在。详见 [续审报告](CONTINUATION.md) 与 [验证记录](verification.json)。
