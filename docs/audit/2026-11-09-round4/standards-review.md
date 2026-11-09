# 第四轮独立 Standards 复审（2026-11-09）

**Standards：PASS（仅本次固定增量）。确认规范违例 0 项；Fowler 启发式建议 0 项。**

## 固定引用与覆盖

- 增量基线：`9679cec2082d905df96186748c7413d3b72c4b8b`。
- 固定产品提交：`bc16a9fe981a346c4a2f57b56b8fbd0d2268c755`。
- 原始全项目基线：`a39a3f3039ce7deafc3f547a25cc0dc4635c111a`。

三个引用均可解析。实际使用 `git diff 9679cec2082d905df96186748c7413d3b72c4b8b...bc16a9fe981a346c4a2f57b56b8fbd0d2268c755`；唯一增量提交为 `bc16a9f fix: enforce validation and field clearing in message templates`，9 文件、343 行新增、22 行删除。

已完整读取上述 9 个文件：邮件实体与 schema、邮件 Controller、合并保存 DTO/Service，以及邮件 partial、短信 partial、合并持久化和变量四套测试。另核对真实 Controller 调用、前端完整表单载荷、单渠道 Service、短信读取与签名回退、生成器及 Flyway DDL。文件清单见 [构建前快照](F:/qtMusic/astral/target/round4-source-before-final.json)。9 份 SHA256 均匹配；固定产品提交到这些工作区文件的 diff 为空。

此前全项目覆盖继承 [接手验证](F:/qtMusic/astral/docs/audit/2026-11-08/verification.json)、[第二轮](F:/qtMusic/astral/docs/audit/2026-11-09/verification.json) 和 [第三轮](F:/qtMusic/astral/docs/audit/2026-11-09-round3/verification.json)，本次没有重做全仓逐行审阅。工作区另有 `QtPackHeaderProbe.java` 与 `QtPackHeaderProbeHttpTest.java` 未提交改动，不是本 reviewer 产生的，保留并排除在固定评审之外。

## 规范依据

完整读取 [AGENTS.md](F:/qtMusic/astral/AGENTS.md) 和 [CODING_GUIDE.md](F:/qtMusic/astral/docs/CODING_GUIDE.md)。适用合同来自 [COMPONENTS_GUIDE](F:/qtMusic/astral/COMPONENTS_GUIDE.md:277)、[PLUGIN_GUIDE](F:/qtMusic/astral/PLUGIN_GUIDE.md:124)、[INTEGRATION_GUIDE](F:/qtMusic/astral/INTEGRATION_GUIDE.md)、[README](F:/qtMusic/astral/README.md) 与 [迁移约定](F:/qtMusic/astral/astral-server/src/main/resources/db/migration/README.md:55)。核对 [POM](F:/qtMusic/astral/pom.xml:201)、[Checkstyle](F:/qtMusic/astral/checkstyle.xml)、[SpotBugs 排除规则](F:/qtMusic/astral/spotbugs-exclude.xml)；这些配置及前端源码在增量中未变。

测试边界对照 [既有 TEST-SEAMS](F:/qtMusic/astral/docs/audit/2026-11-07/TEST-SEAMS.md) 和 [本轮 TEST-SEAMS](F:/qtMusic/astral/docs/audit/2026-11-09-round4/TEST-SEAMS.md)。UI、部署与备份规范本轮无对应改动。Fowler 的十二项基线全部纳入判断，仓库规则优先，工具覆盖的问题不重复计入人工 findings。

## Findings 与判定

**确认规范违例：0。** [MessageTemplateService](F:/qtMusic/astral/astral-system/src/main/java/com/astral/system/notify/MessageTemplateService.java:121) 的公开保存保持 `rollbackFor = Exception.class`，符合 CODING_GUIDE 第 2 节；所选渠道使用真实生成实体约束，既有行仅显式 SET 可编辑列，保留 ID、由服务端写更新时间，提交后才清缓存。完整表单的可空值清空与单渠道 partial 的省略值保留各按其合同处理，符合 [AGENTS 更新白名单](F:/qtMusic/astral/AGENTS.md:365)。

[邮件名称 schema](F:/qtMusic/astral/astral-schema/src/main/resources/schema/sys_mail_template.json:27) 与生成实体均对齐已提交 DDL 的 `VARCHAR(64)`；[邮件 POST/PUT](F:/qtMusic/astral/astral-system/src/main/java/com/astral/system/controller/MailTemplateController.java:59) 补 `@Valid`，符合 [CODING_GUIDE §3.6](F:/qtMusic/astral/docs/CODING_GUIDE.md:111)。没有修改历史迁移或缩小真实数据库允许的长度；所选 SMS 的必需映射与状态校验没有引入未选字段阻断。

**Fowler 建议：0。** `.lambdaUpdate().eq(...).set(...)` 是仓库要求的字段白名单，不是跨对象导航的 Message Chains；不建议为两种实体的完整表单写入引入反射复制或空转发抽象。Checkstyle 已报告的 NPath **7,344 → 36,720** 保留为实际维护成本，不重复计为人工违例或误报；其依据见 [质量评估](F:/qtMusic/astral/docs/audit/2026-11-09-round4/quality-review.md)。

## 独立证据核验

- [原始 reactor 日志](F:/qtMusic/astral/target/round4-final-reactor.log) 的 139 个逐套统计、9 个有测试模块汇总与 [固定门禁账本](F:/qtMusic/astral/target/round4-final-gates.json) 完全一致：899 项，失败、错误、跳过均为 0，结束于 **20:16:07 +08:00**。日志确认安装当前 schema artifact 并生成 34 个实体。
- [聚焦 GREEN](F:/qtMusic/astral/target/round4-focused-green.log) 4 套 / 52 项通过，均能在该 reactor 日志找到；公开持久化与实际短信 SPI 观察覆盖拒绝、清空、列宽边界、回滚及缓存。邮件初次 RED 的 POST 缺 ID fill 属夹具失败；[RED2](F:/qtMusic/astral/target/round4-mail-validation-red2.log) 才同时建立 POST/PUT 产品失败。初次 GREEN 已触发校验，但分页 total 断言失败；[GREEN2](F:/qtMusic/astral/target/round4-mail-validation-green2.log) 保留公开无写入断言后通过。
- Spotless、Checkstyle、SpotBugs 的真实日志均为 `BUILD SUCCESS`，最后结束于 **20:22:31**。直接解析报告得到 Checkstyle 379 warning / 0 error、SpotBugs 248 Medium / 0 High；379 与 248 个账本条目均有 disposition 和 reason，无待评估项。门槛、排除项和 Java 25 编译目标未更改。
- 核验门禁账本引用的 162 份文件：**160 份指纹匹配，2 份被后续 QT 运行覆盖**。覆盖的是 [QtPackHeaderProbeHttpTest XML](F:/qtMusic/astral/astral-plugin/target/surefire-reports/TEST-com.astral.qt.service.QtPackHeaderProbeHttpTest.xml) 与 [QtPackHeaderProbeTest XML](F:/qtMusic/astral/astral-plugin/target/surefire-reports/TEST-com.astral.qt.service.QtPackHeaderProbeTest.xml)，修改时间均为 **20:40:08**。前者由固定运行的 3 项变为 7 项；当前 139 份 XML 合计 **903 项**，不能称为固定运行 899 项的 XML 全部仍匹配。其余 137 份本次 suite XML、原始 reactor/聚焦/静态日志及 artifact 指纹匹配；两份 11 月 8 日历史 AI XML 的源码缺失且不在本次日志，仍明确排除。原始账本未改写。

## 限制与交付

本 reviewer 仅做只读源码、规范、日志和指纹复核，仅写本报告；未运行构建、测试、服务或提交，未读取另一轴 reviewer 的 brief/report。H2/MyBatis/Spring 与 MockMvc 校验证据不代表 PostgreSQL 并发锁、完整 HTTP 鉴权/全局响应体或浏览器验收。短信使用内存 SPI，没有真实 SMTP/SMS/模型或生产操作。浏览器及替代诊断按原限制未执行，测试服务清理由父代理负责。

保存后以严格 UTF-8 解码回读本报告，并再次核验 9 份源码 SHA256 与固定提交对应关系；不匹配项为 0。上述 PASS 不覆盖另一个会话的 QT 改动或后续测试运行。
