# Standards 第三轮续审（2026-11-09）

**固定产品起点：`587b4ae215375dd745dde0630ddbd2de5c94efbf`。本轮确认 2 项 P2：通知规则删除漏清并发新增收件人；短信模板局部更新允许将必需映射字段写成空白。均为源码确证，尚未在本轮执行回归；本 reviewer 没有运行 Maven、数据库、服务或网络操作。**

## 固定范围与规范

- 历史基线：`a39a3f3039ce7deafc3f547a25cc0dc4635c111a`。固定总比较：`git diff a39a3f3039ce7deafc3f547a25cc0dc4635c111a...587b4ae215375dd745dde0630ddbd2de5c94efbf`；两端均已解析，diff 非空。开始复核时 HEAD 与上述产品起点一致、工作树干净。
- 已核提交链（旧→新）：`e57bb02`、`07a48e8`、`ccae720`、`07c3e40`、`7be9a82`、`a9f0440`、`71ad84e`、`80601e1`、`21a2475`、`587b4ae`。后续若其他代理开始修复，下面行号和触发链仍指上述固定产品起点。
- 仅检查 `NotifyRuleController` 更新/删除的三表事务交错，以及 `SmsTemplateController` 局部更新有效状态。读取必要的 Service、迁移、事件注册表、发送路径及前端 API 以核实合同，没有延伸其他模块。
- 规范依据：根 `AGENTS.md:164–170,210–222`、`docs/CODING_GUIDE.md:62–63`；代码内公开合同、数据库迁移及客户端 API 补充触发依据。此前已读的其他指南仍保留上下文，相关模块未发现更具体的 `AGENTS.md`、`CONTRIBUTING` 或 `CODING_STANDARDS`。
- 沿用 code-review 的完整 Fowler 判断基线（Mysterious Name、Duplicated Code、Feature Envy、Data Clumps、Primitive Obsession、Repeated Switches、Shotgun Surgery、Divergent Change、Speculative Generality、Message Chains、Middle Man、Refused Bequest），仓库规则优先。本轮未另报审美/维护性建议，未重复工具已覆盖规则。
- 不重开前两轮已关闭的邮件模板、授权快照/父行锁/缓存问题，也不把此次残余声称为最近提交首次引入。只写本报告，未修改产品或测试、未提交。

## R3-S1 / P2：通知规则删除使用旧渠道快照，漏清并发新增收件人

**规则与公开合同。** `docs/CODING_GUIDE.md:62` 要求事务位于外部调用的 public Service；`:63` 要求多步写入与关联清理在同一事务中保持一致性。`AGENTS.md:210–212` 明确规则→渠道→收件人为三表关系，`:221–222` 公开同一规则的 PUT/DELETE，权限均为 `admin:system:notify:rule:edit`。前端 `astral-front/src/api/notify.ts:200–203` 直接提供这两个入口。

**位置。** `astral-system/src/main/java/com/astral/system/controller/NotifyRuleController.java:215–222` 的 DELETE 先无锁查询渠道列表，只按该快照逐渠道删收件人，再按 `rule_id` 删除全部渠道，最后删除父规则。相应 PUT 在 `:199–207` 先更新并锁住父行，再调用 `persistChannels`；后者 `:350–395` 可插入新渠道及其收件人。三个 CRUD 的事务仍在 Controller；`SysNotifyRuleServiceImpl.java:8–10` 是标准 CRUD 空壳，没有统一父行锁或补充清理。

**确定交错。** 公开 POST 先建立 `systemAlert` 规则，仅有 INAPP 渠道 A。该事件允许 EMAIL/SMS/INAPP，且没有必达渠道约束（`NotifyEventRegistry.java:79–89`）。

1. T1 DELETE 完成 `:216–217` 的渠道查询，得到 `[A]`，在首次删收件人前暂停；此时尚未取得父行或子行写锁。
2. T2 PUT 同一规则，保留 A 并增加 EMAIL 渠道 B 及合法 FIXED 收件人，整个更新事务成功提交。
3. T1 恢复，按旧列表只删 A 的当前收件人；随后 `:220–221` 的 `DELETE … WHERE rule_id=?` 会删 A、B，但 B 的收件人不在旧列表中；最后父规则删除并提交。

两个公开请求都返回成功，规则和渠道均已删除，B 的 `sys_notify_rule_recipient` 却永久成为孤儿。`V20261029001__notify_rule_channel_split.sql:24–53` 没有外键或级联删除；后续迁移没有补这些约束。这里并非声称 Controller 事务完全失效：即使其事务代理正常运行，先读旧列表且未与更新串行化的交错仍成立。按 `rule_id` 删除渠道已经覆盖新渠道，实际遗漏的是新渠道的收件人，不把影响夸大成此次时序下新渠道也残留。

**修复方向。** 将规则多表写入统一放到 public Service 事务；更新和删除在读取/修改子表之前以相同顺序锁定并确认父规则存在，再计算渠道集合和清理。仅给现有方法增加事务注解、仅重复查询一次收件人，都不能代替共享父行锁。

**公开回归。** 使用真实 PostgreSQL、真实 mapper 与 Spring 事务代理，经公开 create/update/delete 入口构造上述双事务时序；JDBC 只控制无锁渠道查询完成后暂停，不伪造返回列表。先证明旧版本可遗留 B 收件人，再验证修复后更新与删除串行化；删除完成时三表均无该规则及其渠道的残留。同步保留 update 的全量保存/显式 NULL 合同。既有 `NotifyRulePersistenceTest` 只有清空模板和平台的一项顺序保存/投递测试，不能计为此并发交错已验证。

## R3-S2 / P2：短信模板局部更新将必需映射字段写成空白

**规则与公开合同。** `AGENTS.md:164–170` 规定短信模板保存「事件→供应商模板编码」映射，本地不渲染正文；`systemAlert` 短信模板必配，缺模板会直接报错。`SmsTemplateController.java:139–160` 明确事件必须登记、供应商模板编码必填，create 对空白事件/编码拒绝。`astral-front/src/api/sms.ts:106–107` 更新参数为 `Partial<SmsTemplate>`，因此省略字段应保留存量，但不能把非空白必填合同理解为可写空白。

**位置与确定触发。** `astral-system/src/main/java/com/astral/system/controller/SmsTemplateController.java:83–100` 对请求本身调用 `validateTemplate(entity, false, id)`，然后只判断非 null 就写入。`:148–160` 把空白事件视为 `eventPresent=false`，且必填检查全部位于 `isCreate` 分支；`:175–189` 因而跳过该空白事件的注册表/唯一性验证。供应商模板编码在 update 路径没有任何非空白检查。

- 对已有合法 `systemAlert` 模板调用 `PUT /api/v1/admin/system/sms/template/{id}`，请求 `{"eventCode":" "}` 或 `{"eventCode":""}`，验证通过、空白值写库、缓存失效并返回成功。之后 `SmsServiceImpl.java:63–69` 按事件码找不到该模板，告警短信在本地直接报未配置；规则自动模板查询也失去原映射。
- 同一公开 PUT 提交 `{"providerTemplateCode":" "}` 或空串，同样成功。随后发送的 `SmsServiceImpl.java:144` 会把空白作为供应商模板编码交给 SPI；保存已经破坏“事件→供应商模板编码”的短信发送映射合同。此结论不依赖真实云供应商行为，也不宣称 MOCK 必然拒绝。

`V20261002003__sms_channel.sql:24–40` 只有 VARCHAR NOT NULL 与编码/事件唯一约束，空串和空白串均可通过；公开控制器没有 `@Valid`，且 `SysSmsTemplateServiceImpl.java:8–10` 没有下层补充校验。因此这是实际可保存的无效状态，不只是前端输入提示缺失。相同必填分支还允许把 templateCode/templateName 改为空白，计在同组根因内，不另加缺陷数。

**修复方向。** 更新须读取当前记录并按提交字段合并有效状态，统一校验必填编码/名称、注册事件、供应商编码及已有唯一性/审核状态合同；持久化继续只写允许且已提交的字段，避免回放存量覆盖并发更新。涉及读/校验/写时放入 public Service 事务，按需锁行，并让缓存失效在提交后发布。保留“省略字段不改”和合法可空字段的语义，不把全实体的创建校验直接套到未合并的 partial 请求。

**公开回归。** 使用公开 POST/PUT/GET 与真实 mapper 保存后读取，对四个必填字符串分别提交空串/空白串，断言明确拒绝且旧行不变；省略事件和供应商编码、只改名称或备注时应成功并保留存量。另覆盖合法事件/编码更新、真正重复冲突、无效审核状态。可用无网络的短信 SPI 记录参数及公开发送方法验证合法更新后拿到新编码；事件空白被拒绝后原事件仍可解析。不能只反射私有 validate 方法或断言 wrapper 的 SET 参数。

## 本轮结论

本轮 Standards **2 项确认缺陷，最高 P2**，当前固定产品起点均未关闭。没有执行新回归，不将前一轮 852 项通过视为本轮两条触发链已被覆盖。后续修复须以新的固定产品提交和公开持久化/并发日志另记关闭证据。
