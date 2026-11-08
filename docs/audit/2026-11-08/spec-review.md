# Astral Spec 复审：2026-11-08 接手检查

基线：`a39a3f3039ce7deafc3f547a25cc0dc4635c111a`，检查开始时等于 HEAD；本次检查对象是 `git diff <base> --` 与未跟踪的新文件。`git log <base>..HEAD` 为空不能证明工作区无改动。需求依据为 `docs/audit/2026-11-07/SPEC.md`，附加具体行为契约来自 `AI_MODULE_DESIGN.md`、`AGENTS.md` 和公开接口说明。

## Spec 核心结论

**尚不能给出全仓通过结论。** SPEC第35行要求确认缺陷修复闭环；旧S1–S10为历史快照，当前差量核对及关闭边界见C1–C5。

- **新增19项未收口源码确认项：** 优先处理账户旧状态覆盖、会话撤销误报成功、存储诊断泄露与孤儿文件；其余涉及上传限额、通知隔离/清空、停用告警、序列缓存、统计和知识检索。每项附具体契约、条件与最小公开回归入口，见C1/C2。C3另列尚需证明的候选。
- **AI请求生命周期：** A1–A4记录审批身份、恢复结果、状态竞争和用量/额度的当前复核约束，由Lead继续修复；本轮不重复实现或宣称已关闭。“正常恢复必然双扣请求数”不成立。
- **已有关闭证据：** S5/S6、模板N2-F1/F2，以及通知表首建和26001/29001升级阻塞M7已完成专项验证（迁移真实PostgreSQL11项）。公共HTTP传输、SQL AST、凭据/指标/序列原问题的定向关闭见C5；不将其旧快照重新报错。

Worker/Telegram服务、部署脚本、管理前端范围覆盖及最终真实codegen/reactor/质量门禁仍缺收口证据，见C4。reviewer仅读源码和Lead日志，没有运行测试、服务或数据库，也未改实现。此报告不是全仓逐文件独立终审。

## 当前源码证据与修复接缝

### S1：审批策略、工具结果与恢复（R11 当前确认未关闭）

- 契约：`AI_MODULE_DESIGN.md:704,815-850,1058-1065,1808` 要求由用户确认写操作、执行后返回结果、独立幂等及取消语义；`SPEC.md:28,35` 要求确认缺陷实施并回归。
- `AiAdminToolController.java:220-229` 未按原approvalStatus核对USER_CONFIRM本人，批准一律改为ADMIN_CONFIRM、拒绝写入REJECTED；`:221-280` 不校验请求终态，已取消的待批调用仍可执行。既有工具行CAS与原请求者执行权限咽喉有效，不能沿用“同一行并发双执行”或“审批人权限自动替代请求者”的旧叙述。
- 恢复不读取真实结果：approve只改tool-call行再resume；正常新请求的assistant.content仍为空，`history:817-819`在读取toolCalls前直接跳过它，后续结果被当孤儿丢弃。即使补上content，原占位“需要确认”仍未被真实结果替换。首个待批调用还会中断同轮余下调用；复用同一assistant覆盖blocks会丢先前工具轮。
- 审批卡事件先于原worker落WAITING发布，快速审批可与旧worker重叠；request状态写没有CAS，取消可被markWaiting/markSucceeded覆盖。可实施状态约束、身份门禁与公开start→approve→resume夹具见A1/A3/A4。

### S2：用量与额度生命周期（R12 当前确认未关闭）

- 契约：`AI_MODULE_DESIGN.md:1062,1193-1201,1531,1575` 要求取消保留已产生费用、真实消耗结算、只退未用预占、操作原子幂等且带agentId。
- 每次run无条件reserve；审批暂停return走失败release，已产生费用归零。恢复插入新usage，详情只取最新行、汇总按行数虚增请求数；最终request tokens也只剩最后run。正常顺序恢复的日/月请求数通常先退后加，不能称必然双扣。
- quota.reserve已在REQUIRES_NEW提交，而usage.reserve在try外；usage插入失败无清理。quota结算/释放没有幂等身份、事务或失败传播，day成功month失败可部分记账；重复release可扣负数并释放其他在跑请求的共享计数。
- 额度账本输入使用输入字符数、输出使用answer字符/2，不采用provider真实Token；monthlyCostLimit仅解析，cost列仅初始化0。最大输出/上下文限制也未用于当前ChatRequest，输出预占传0而结算不带上限，已配置硬限制不能保证。可实施账本/成本约束与测试见A2–A4。

### S3：HTTP/MCP 传输（历史快照，当前关闭依据见C5）

- 契约：`AI_MODULE_DESIGN.md:905-910,1806` 私网隔离及请求/工具结果大小限制。
- `HttpToolExecutor.java:411-415` 使用 `BodyHandlers.ofByteArray` 完整收体后比大小；`McpClient.java:231` 使用 `ofString` 完整收体。maxResponseBytes 目前不能约束峰值内存。
- `HttpToolExecutor.guardHost:268-297` 解析并审核地址，执行时 `:378-412` 又交给独立 JDK HttpClient 连接。MCP 同用此校验后独立连接。源码证实没有地址固定；实际 DNS rebinding 未运行。
- 接缝：有界传输读取/订阅器以超长分块流验证在上限停止；可注入 DNS/连接边界验证只连接审核地址并保留原 hostname/SNI。storage 的 PinnedDnsResolver 不能自动替 AI 路径完成此约束。

### S4：SQL 与执行快照（历史快照，SQL关闭及快照跟进见C5）

- SQL 具体风险：`SqlToolExecutor.java:162-200` 只校验词、行数、超时和输出列标识符，`:203-245` 甚至剥掉双引号标识符；`readRow:392-407` 仅按 label 筛输出。源列 `password AS name`、危险函数仍可到 JDBC。当前建改/试跑已要求超管，因此不再复用旧“普通 tool:edit 直接取密钥”攻击叙述。
- 兼容要求：`AI_MODULE_DESIGN.md:914-933` 及现有测试明确允许 `astral.sys_user` 的安全投影/CTE，禁止所有 `sys_*` 会错误删减既有功能。
- 依赖核实：根 POM 管理 mybatis-plus-jsqlparser 3.5.17，实际 JSqlParser 5.2 已缓存；只有 server 模块直接引入，plugin 应显式声明其解析器依赖。
- 建议：真实占位符词法替换后解析恰一条 Select，递归 CTE/UNION/子查询/条件/窗口等表达式，按源 Column 过滤敏感列、按 Function 纯函数允许集校验、拒绝 SELECT 星号和未知执行节点，保留 count(*)。表允许范围应是服务器配置的收紧条件，不应让同一工具自报 `tables` 便自我授权。不能只 TablesNamesFinder 后忽略表达式。
- 公共测试：`validate/create/execute`；保留正常 sys_user 安全列、带引用标识符、CTE、count；拒绝敏感列改别名、WHERE 子查询取敏感数据、危险及 schema-qualified 函数、多语句、写 CTE。
- 快照：`AiToolService.planHash:725-735` 只含 executor_config/risk/approval。MCP config 持 serverId/toolName，服务器 url/headers 在独立表；修改它们不改变计划摘要，但 executor 已用新服务器。契约 `AI_MODULE_DESIGN.md:413,1702,1808`，应覆盖实际目标配置/版本身份。

### S5：模板重绑定（原缺陷及 N2 两项后续缺陷均已关闭）

- 契约：`AGENTS.md:157-162` 后台改模板即生效；`SPEC.md:35` 修复确认的数据/业务错误。
- 首轮事实：改编码/事件删除旧渲染行后仍持旧对象 updateById，导致模板消失。当前 `MessageTemplateService.java:151-200` 仅删除取消的渠道，所有仍选中的 EMAIL/INAPP/SMS 原 ID 保留；`:301-344` 冲突查询排除自己的原 ID，同时保持其他模板的 code/channel 和 event/channel 约束；save/delete 都声明 `rollbackFor=Exception.class`。
- `MessageTemplatePersistenceTest` 真实公开 save→list，MyBatis+H2+Spring 事务代理覆盖重绑定 ID/内容、只改编码、自身事件不冲突、取消单渠道及第二渠道失败回滚；原四项已全过。后续并发取消渠道与提交前缓存回填另有 RED→GREEN 证据，当前六项通过，见 N2。

### S6：通知成功与限额（原缺陷已关闭）

- 契约：`NotifyPublisher.java:38-50` 逐条结果、每日上限；`SPEC.md:23,35` 不伪造成功、确认业务缺陷闭环。
- 当前 `InappServiceImpl.java:133-135` 不吞 mapper 异常，并拒绝 insert 行数不为1；`NotifyPublisher.java:262-292` 把实际失败转成单条 `ok=false`，外层继续后续收件人。异步入口仍包住事件级失败。
- `NotifyPublisher.java:61-74,339-360` 用单个 Lua 完成 GET/限额比较/INCR/TTL；`:265` 在发送前获得许可，`:283-289` 失败时按本次捕获的完整 key 归还。日期不重新计算，无法确认预占时不发送，归还异常不会替换原投递失败或放宽额度。
- `NotifyPublisherDeliveryTest` 通过公开 publish 和真实 InappService 验证 mapper失败、发送中并发竞争、失败归还、成功占用、收件人隔离、GLOBAL共享与预占未知。五项通过，其中三个使用独占loopback Redis且没有跳过；该原缺陷范围关闭。跨午夜、归还异常及insert=0目前只有代码证据，未列成已实测边界。

### S7：告警配置凭据（历史快照，当前关闭依据见C5）

- 契约：`docs/CODING_GUIDE.md:86-91` 凭据不得入 response；`SPEC.md:21,35` 安全边界与修复。
- `AlertAdminController.channelList:73` 直接返回 AlertChannel；`AlertChannel.java:28` 的 config 没有任何 Jackson 出参保护；`AlertEngine.java:288-293` 将 config.secret 作为认证头。这已补足旧报告的实体依赖，确认 viewer 可得到凭据字段。
- 接缝：合成 secret 标记、真实 controller + Jackson3 序列化，普通 view 响应不得含标记；编辑回显需保留/清除/替换语义，不能把掩码写回真实凭据。

### S8：指标桶（历史快照，当前关闭依据见C5）

- 契约：`ApiMetricCollector.java:26-32` 宣称触顶后内存/落库行数不会无界增长；`SPEC.md:35` 修复实际质量问题。
- `ApiRequestMetricInterceptor.java:63` 将原始 requestURI 作为维度；`ApiMetricCollector.java:62-68` 超限仅去版本，URI 每次不同仍 computeIfAbsent。
- 接缝：公开 interceptor afterCompletion 传 matched pattern；公开 record/drain 验证超容量不同路径不增加无限键，且已有桶计数保留。需真实硬边界，不只改注释。

### S9：序列边界（历史快照，当前关闭依据见C5）

- 契约：`AGENTS.md:128-134` `_id` 强制号段、类型锁定；DatabaseGenerator.batch 公开固定数量返回契约。
- `GeneratorFactory.java:299,308-326` 首建重复键分支漏请求类型与胜出类型比较；`_id` 省略 type 且默认 SIMPLE 可写入非 SEGMENT。默认配置是 segment、实体填充通常直走 Segment 是重要影响边界。
- `DatabaseGenerator.java:103` `v<=Long.MAX_VALUE;v++` 永无正常结束。正常最大值并非问题，只有合法端点 MAX_VALUE 触发。
- 接缝：public nextResult/batchResult 的数据库冲突替身；公开 batch("orders",1) 返回 MAX_VALUE 在有外部期限的隔离进程测终止，避免测试自身卡死。

### S10：空库迁移（首建缺表及条件性存量升级问题均已关闭）

- 契约：迁移 README 第8-14行声明全新空库全链自动初始化；SPEC 第35-36行要求问题修复与真实构建验证。
- 原缺陷：`V20261001007__notice_channel_platform_multi.sql:24` 裸 ALTER sys_notice；更早迁移没有 CREATE 该表。`V20261002006:7-11` 继续 ALTER/建索引，`V20261018001:33-35` 还直接 UPDATE。唯一建表在 `V20261027001__sys_notice_to_host.sql:30`，首轮无 callback 兜底。
- 当前新增 `beforeEachMigrate__ensure_sys_notice.sql`，仅对当前schema缺表建立完整25列，不改已有表或历史版本。Lead真实PG负对照复现1007缺表；初次成功日志执行71个版本、validate、第二次零迁移，见M6。后续旧库/基线/并发等夹具及26001/29001兼容回调共11项通过，见M7；本轮未发现需阻断的确定迁移实现缺陷。

## 已排除与未覆盖

- 当前 `astral-blog/tests/browser-gate.mjs:291-319` 已验证普通 Tab 插入、选区缩进/反缩进、Escape 后原生跳焦，和当前 editor 契约一致；旧冻结报告 SPEC-FINAL-001 不适用于本次当前树。
- R12 的“通常重复扣日/月请求次数”已由 finally 释放路径反证，报告不沿用。
- 本次没有重读全仓全部差量、全部 UI/部署/第三方工作器源码，不给出完整覆盖率或独立全仓通过结论。所有新发现由源码推理，执行验证仍由 Lead 负责；未修改任何实现文件。

Spec首轮发现十组，最高P1；后续定向复核关闭S5/S6、两个模板P2、S10首建缺表及M4两项条件性升级问题。其他项的最新关闭状态需各自定向复核。Standards轴由另一独立reviewer输出，不在此合并排名。

## 迁移链专题补充：只读设计复核

M1–M5保留设计与检查过程，其中“建议矩阵”等指初始方案；当前空库实施证据见M6、兼容回调及完整11项执行证据见M7。当前共有71个 `V*.sql`，包括工作区尚未跟踪的两条AI迁移。已按版本读过语句链并核对表先建后用；reviewer没有连接、初始化或启动任何数据库，也没有改实现、迁移、配置或测试文件。

### M1：`sys_notice` 提前依赖和最终结构

契约：`AGENTS.md:88-93` 指定 PostgreSQL/Flyway 全链初始化；迁移 `README.md:8-14` 承诺只预建数据库即可，`:52-58` 要求新版本递增且发布后旧脚本只增不改；`SPEC.md:20,28,35-36` 要求 schema 契约和可核验回归。

`V20261027001` 前真正访问该表的迁移恰有以下五条；其他搜索命中只在注释中。

| 文件（位于 `astral-server/src/main/resources/db/migration/`） | 当前操作与必要字段 | 空库行为 |
| --- | --- | --- |
| `V20261001007__notice_channel_platform_multi.sql:24-33` | `channel` 改成 VARCHAR(64)，旧 app/pc/空值映射，设置默认值 | 无表守卫，第一处失败 |
| `V20261002006__unify_notice.sql:7-11` | 加 `read_time`、`scene`；建 `(user_id, read_time)` 索引 | 无表守卫 |
| `V20261009001__platform_harmony.sql:55-59` | `channel` 扩成 VARCHAR(96) | 有 `to_regclass` 守卫 |
| `V20261018001__user_role_change_notify.sql:33-35` | 按 `scene` 改 `channel`、`update_time` | 无表守卫 |
| `V20261020001__notice_read_seconds_terms_version.sql:26-34` | 增 `read_seconds`、`terms_version` 并注释 | 有表守卫 |

`V20261027001:30-58` 才建立完整的 25 列，`:59-64` 建六个业务索引，`:77-101` 注释每列。由于 `CREATE TABLE IF NOT EXISTS` 不补现有表的缺列，只为早期 ALTER 建三四列仍会在后续索引/COMMENT 失败。提前建立的形状应逐项保持这 25 列的类型、默认值、可空性和 BIGINT 输入主键；没有外键或数据库 sequence 依赖。`V20261029003__notice_sender.sql:8-15` 再增 nullable `sender VARCHAR(48)` 并回填反馈来源，最终应为 26 列；`V20261029006__notice_type_personal_fix.sql:5-9` 只改三类个人通知数据。

### M2：推荐的最小兼容修复

新增默认自动发现的 SQL callback `db/migration/beforeEachMigrate__ensure_sys_notice.sql`，限定当前 schema 判表，仅当缺表时建立上述 25 列。保留所有 `V*.sql` 原字节和 checksum；不补早期历史版本、不用 `outOfOrder`、`repair`、忽略校验、插件初始化器或启动前手动建表。README 应记录回调的历史原因、DDL 来源以及它不承担通用数据恢复。

可直接实施的内容如下，DDL 来源为 `V20261027001:30-58`。不提前插入数据、索引或注释，仍由原迁移处理它们。

```sql
DO $$
BEGIN
    IF to_regclass(format('%I.%I', current_schema(), 'sys_notice')) IS NULL THEN
        CREATE TABLE IF NOT EXISTS sys_notice (
            id BIGINT PRIMARY KEY,
            channel VARCHAR(96) NOT NULL DEFAULT 'app-android,app-ios',
            notice_type VARCHAR(16) NOT NULL DEFAULT 'announce',
            scene VARCHAR(64),
            user_id BIGINT,
            feedback_id BIGINT,
            display BIGINT NOT NULL DEFAULT 4,
            title VARCHAR(128) NOT NULL,
            content VARCHAR(65535),
            url VARCHAR(512),
            is_show BIGINT NOT NULL DEFAULT 1,
            is_top BIGINT NOT NULL DEFAULT 0,
            dialog_closable BIGINT NOT NULL DEFAULT 1,
            first_login_only BIGINT NOT NULL DEFAULT 0,
            marquee BIGINT NOT NULL DEFAULT 0,
            read_seconds BIGINT,
            terms_version VARCHAR(32),
            effective_start TIMESTAMP,
            effective_end TIMESTAMP,
            version_min BIGINT,
            version_max BIGINT,
            audience VARCHAR(16) NOT NULL DEFAULT 'ALL',
            read_time TIMESTAMP,
            create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        );
    END IF;
END $$;
```

版本与执行时序已从**本机实际依赖字节码**核实，而非凭常识推断：Spring Boot 4.1.2 管理 Flyway 12.4.0、PG JDBC 42.7.14；`application.yml:45-57` 设定 location=`classpath:db/migration`、schema/default-schema=`astral`、baseline-on-migrate=true、baseline-version=20260914001、placeholder-replacement=false。

- `Flyway.lambda$migrate$3`：校验 → 创建 schema → 判空/必要时 baseline → 创建 history → `DbMigrate.migrate`。
- `DbMigrate.migrate` 在 `migrateAll` 之前触发 BEFORE_MIGRATE；因此它在 baseline 决定之后，**不会因回调建表造成 init 被误跳过**。此前对该顺序的疑虑已撤回。
- `DbMigrate.migrateAll` 的默认 group=false 路径在 history lock 内运行 `migrateGroup`；`doMigrateGroup` 设置当前 schema / migration info 后，先 BEFORE_EACH_MIGRATE，再运行当前版本 SQL。
- `DefaultCallbackExecutor.onEachMigrateOrUndoEvent` 使用 migration connection；`SqlScriptCallbackFactory` 默认发现 location 下事件名开头的 SQL callback，描述后缀有效，执行使用 context.connection。上述普通 DO 块可处于迁移事务。
- 单纯 `beforeMigrate` 虽然时序合法，但发生在迁移锁之前；两个初次启动可能在 CREATE catalog 操作上竞争。选择 `beforeEachMigrate` 可沿用迁移锁，无需自加锁。没有待执行迁移时不运行此回调。
- `PostgreSQLConnection.changeCurrentSchemaTo` 会把目标 schema 放在原 search_path 前，保留原路径中的 public 等。守卫需限定 `current_schema()`，否则 public 同名表可能使 `to_regclass('sys_notice')` 误判；CREATE 的目标为当前 schema。

效果：空 schema 在初次迁移事务中得到兼容表；已有 `astral.sys_notice` 时回调不改列、不改业务数据，后续原版本正常执行。迁移1007暂时把空表 channel收窄到64，9001再扩回96；重复增列都有 IF NOT EXISTS。更窄的备选是 Java `Callback`，仅在 BEFORE_EACH_MIGRATE 且版本=20261001007 时执行同一 DDL，但增加注册代码；本问题无需该额外抽象。

### M3：可用的隔离工具与验证矩阵

已只读发现 `F:/qtMusic/local/pg/pgsql/bin/` 中 `postgres.exe`、`initdb.exe`、`pg_ctl.exe`、`psql.exe`、`pg_isready.exe`；`--version` 确认 PostgreSQL **16.4**。Flyway/PG JDBC 对应 jar 已在 `.m2` 缓存。首轮仓库无 Flyway/PG 迁移夹具；后续 Lead 新增 `FreshDatabaseMigrationTest`，见 M6。未发现可复用的 Testcontainers runtime 或 embedded-postgres 库。

Lead 可用新建的独占测试临时目录初始化 PG16.4，绑定单独 loopback 端口和合成用户/数据库，测试后在 finally 停止该专属进程。**不复用已有 `F:/qtMusic/local/pgdata`，不启动 Astral Spring 应用**，这样不触及真实数据库、Redis、插件或凭据。Windows 后台启动应隐藏窗口；清理前需校验绝对路径仍位于独占测试目录。

建议使用真实 Flyway 公共入口的 JUnit 集成测试或独立 Java runner，匹配生产配置。只为旧行为的负对照及构造旧 fixture 设置 `.skipDefaultCallbacks(true)`；正式通过断言必须保留默认回调发现。

```java
Flyway flyway = Flyway.configure()
        .dataSource(syntheticLoopbackUrl, syntheticUser, syntheticPassword)
        .locations("classpath:db/migration")
        .schemas("astral")
        .defaultSchema("astral")
        .baselineOnMigrate(true)
        .baselineVersion("20260914001")
        .placeholderReplacement(false)
        .load();
var result = flyway.migrate();
var validation = flyway.validateWithResult();
```

| 合成场景 | 必须核验的可观察结果 |
| --- | --- |
| 原迁移负对照：空库，回调关闭 | 在 V20261001007 发生 `sys_notice` 不存在；记录实际失败版本，不能只断言任意异常 |
| 全新数据库，astral schema 尚不存在 | 71 个版本 SQL 成功，包括 init；无 BASELINE 记录、无失败/待执行版本，validate成功；允许 Flyway 自身 SCHEMA 标记 |
| 已预建但完全空的 astral schema | 同样完整执行 init，不能错误自动 baseline |
| public 有同名哨兵表，astral 为空 | 在 astral 创建正确表；public 的结构与合成数据哈希保持不变 |
| 历史失败后重试 | 先无回调执行至1006，原1007失败；启用回调后从1007继续，先前成功版本/checksum不变 |
| 有历史通知的旧库 | 无回调迁移至1006；手动创建历史通知25列去掉 scene/read_time/read_seconds/terms_version、channel16默认app的合法旧形状并插入 app/pc/web/all 合成通知；启用回调继续，ID、标题、正文保留，平台映射符合旧脚本 |
| 后期事件转换 | 在2006完成、18001未开始的 fixture 中追加 scene=feedbackAdminReplied 等行，升级后 channel=all、sender=feedback；另有普通公告与个人场景行验证29006只变更指定类型 |
| 没有history的合法旧库 | 用原 init脚本构造基线结构/种子，放入合法旧通知；migrate确有一次BASELINE并跳过init，增量链成功，既有ID/业务内容保留 |
| 最新版本库再次迁移 | migrationsExecuted=0；表结构、业务行、已成功版本checksum不变；validate成功 |
| 两个独立Flyway实例并发首启 | 只能由一方执行各版本；无回调CREATE竞争，无重复成功版本；最终结构/数据与单线程同构 |

所有成功场景最终核验 26 列、channel VARCHAR(96)、sender VARCHAR(48)、六个指定业务索引及主键。期望业务索引为 `idx_notice_channel`、`idx_notice_type`、`idx_notice_user`、`idx_notice_show`、`idx_notice_create_time`、`idx_sys_notice_user_read`。回调触发次数/字符串包含只能补充诊断，不能代替 PostgreSQL 全链与业务行断言。旧脚本修复前后的文件 SHA-256 与现存成功history checksum均应一致；测试不调用 repair。

### M4：其他迁移的当前结论

对71个版本按顺序静态检查，未再确认第二处**纯空库**必败点。`qt_user_daka`、`qt_app_update` 的早期插件表引用均有守卫；部分脚本中的硬编码字典冲突已由后续版本修复。init的字典类型/字典项显式ID分别88/107个且无重复。`pg_get_serial_sequence` 对输入型BIGINT主键返回NULL不能直接推成 `setval` 必然启动异常，本报告不沿用该猜测。首轮只有静态证据；后续真实PG空库执行记录见 M6。

以下两项原为另外确认的**有条件存量库升级失败**，不混称空库错误；当前已通过窄版本callback及真实PG回归关闭，原触发条件保留如下：

1. **P1 已有 qt_tester 时26001仍尝试插入。** `V20260926001__qt_permission_codes.sql:11-14` 把 NOT EXISTS 放在没有 GROUP BY 的 MAX 聚合输入过滤中；当编码存在时，空输入仍输出一行 `id=1,qt_tester`，会撞主键/编码唯一键。原init没有该编码，所以纯空库不触发。合成fixture应在26001之前放入已存在的编码及角色关联，再复现；修复不可通过删原权限行丢失绑定，且必须在旧SQL执行前兼容处理，后置新版本来不及。
2. **P1 多条找回密码规则时29001主键碰撞。** `V20261029001__notify_rule_channel_split.sql:118-127` 对每条缺EMAIL的 `changePasswordByEmail` 规则分配同一个 scalar MAX(id)+1，`:129-135` 对多个缺收件人的EMAIL渠道重复同样错误。旧模型允许不同rule_name对应同一event，两个合法旧规则即可触发，前段row_number搬迁不能修正后段。纯初始种子只产一条目标规则，不触发。合成fixture分别覆盖多条非EMAIL旧规则、多个缺收件人的EMAIL渠道；修复应保留规则/渠道身份与路由，在历史SQL之前按确定顺序补齐唯一ID，不能修改已发布checksum或简单删重规则。

上述两项的窄化兼容方案见M5，实施及真实PG关闭证据见M7；reviewer没有代替Lead做实施。已有报告F04只被用作定位，原SQL条件已独立重读确认。

### M5：26001/29001 的最小兼容方案（已实施；关闭证据见 M7）

方案为一个Java Flyway `Callback`，仅处理 `BEFORE_EACH_MIGRATE_STATEMENT`，按**版本、完整脚本名、已知整条SQL**匹配下面三条语句。使用 `context.getConnection()` 直接执行安全等价语句，成功后抛公共 `FlywayBlockStatementExecutionException` 跳过该条原语句；任何SQL失败正常上抛并回滚，不能捕获失败后继续。历史 `V*.sql` 字节与checksum保持不变；原DDL、旧数据搬迁、DROP列、history写入仍由原脚本及Flyway完成，无管理员预处理或持久辅助对象。

Flyway12.4.0实际jar已只读核实公共API：`Callback`、`Event.BEFORE_EACH_MIGRATE_STATEMENT`、`Context.getMigrationInfo/getConnection/getStatement`、`Statement.getSql`、`org.flywaydb.core.api.exception.FlywayBlockStatementExecutionException(ErrorDetails,String)`。后者可使用 `new ErrorDetails(CoreErrorCode.ERROR, "已执行历史语句兼容替代")`；生产实现只依赖 `org.flywaydb.core.api.*`，无需新增依赖。

内部字节码核实的执行语义：`DefaultCallbackExecutor.onEachMigrateOrUndoStatementEvent`传入当前migration连接与statement；`handleEvent`将上述专用异常原样传递；`DefaultSqlScriptExecutor.executeStatement`在JDBC执行原SQL前触发事件、专门捕获该异常并返回null，外层继续下一语句。普通SQL异常仍使当前迁移失败。整个过程处于当前迁移事务；没有调用独立连接或提前commit。此判断针对项目默认非batch执行配置，不能冒称任意自定义batch配置均验证过。

Spring Boot4.1.2的Flyway自动配置会收集 `Callback` bean，当前类已注册 `@Component`，并在AstralApplication的com.astral扫描范围中。**独立 `Flyway.configure()` 不读取Spring bean**，当前两类测试的configuration均显式 `.callbacks(new HistoricalSeedCompatibilityCallback())`，且保留默认SQL callback发现，使sys_notice修复继续生效。匹配只允许三条完整已知SQL，规范化针对固定原语句的整行注释/空白/末尾分号；29001前面的DO块不匹配INSERT前缀。版本和脚本符合、目标INSERT前缀符合但整句不同会明确失败，不执行宽泛替换或忽略校验。

**26001：** 将原聚合改成标量子查询，NOT EXISTS作用于最外层。存在编码时不产生行，原权限ID、名称、附加属性和角色关联完全不动；不存在时仍插入当前最大ID+1。

```sql
INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort, status)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_permission) + 1,
       'qt_tester', '轻听测试', NULL, NULL, 0, 41, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'qt_tester');
```

**29001：** 仅最后两个INSERT的ID投影替换为以下表达式，其余列、模板选择、WHERE及NOT EXISTS原样保持：

```sql
-- sys_notify_rule_channel，原118–127行
(SELECT COALESCE(MAX(id), 0) FROM sys_notify_rule_channel)
    + ROW_NUMBER() OVER (ORDER BY r.id)
-- sys_notify_rule_recipient，原129–135行
(SELECT COALESCE(MAX(id), 0) FROM sys_notify_rule_recipient)
    + ROW_NUMBER() OVER (ORDER BY c.id)
```

只给新行分配互异ID，不修改原渠道/收件人ID或引用。**不能在BEFORE_EACH_MIGRATE提前补EMAIL行**：原72行的旧渠道搬迁要求“rule尚无任何渠道”，先补EMAIL会使原SMS/INAPP路由搬迁被跳过。BEFORE/AFTER临时触发器可以完成类似干预，但需要额外DDL/权限/清理及表形状复制；精确语句callback更小，无需该方案。事后callback或后置新版本不能补救已经失败的旧语句。

初始建议的公开Flyway入口验证矩阵如下，具体哪些断言已执行以M7为准，不能把建议表的每个细节都视作已有独立测试：

| 场景 | 断言 |
| --- | --- |
| 三个错误分别关闭Java callback负对照 | 26001已有编码失败；29001多缺EMAIL行失败；29001多缺收件人行失败，具体版本和唯一键异常相符 |
| 26001已有编码及自定义角色引用 | 迁移到25001后添加合成原行，启用callback仅升到26001；原ID/属性/关联不变、仅一条编码；已有history checksum不变 |
| 26001缺编码 | 正常新增恰一条；ID与原脚本无冲突时语义一致 |
| 两条同event非EMAIL旧规则 | 升到29001前插入SMS和INAPP规则及各自收件人；升级后原路由、模板/启用状态/平台/收件人保留，各添互异EMAIL渠道及email载荷收件人 |
| 部分升级形状，两个已有EMAIL缺收件人 | 构造新表与已有EMAIL行且旧rule.recipient_type已不存在，避免前段DO已先搬入收件人；验证最后INSERT真正添加不同ID，原渠道ID不变 |
| 已有EMAIL及自定义收件人、稀疏大ID | 原行及引用不改、不增重复项；新增ID大于原MAX且互异 |
| 替代语句真实失败 | 合成CHECK只拒绝新EMAIL行；失败后整个29001回滚，旧列/原行仍在、无成功history；去除fixture约束后重试成功 |
| SQL与Java callback共同全链、二次迁移 | 空库71版成功、validate、pending=0、第二次migrationsExecuted=0，无helper残留；历史脚本SHA及成功history checksum不变，不调用repair |

26001的全链延伸需按业务语义断言：30001原迁移**有意**把qt_tester授权转换为两个beta权限，再删旧权限/关联；不能要求全链后旧qt_tester物理行仍在。使用自定义角色避免30005重设ADMIN/USER默认权限干扰，并断言授权语义保留。

### M6：S10 已实施及真实PG关闭证据

当前 `beforeEachMigrate__ensure_sys_notice.sql:1-35` 与 M2方案一致，使用current_schema限定判断、完整25列且不改变已有表。`FreshDatabaseMigrationTest.emptyDatabaseMigratesAllVersionsWithoutRewritingHistory` 使用明确指定loopback端口、合成astral_audit用户及UUID schema；执行真实Flyway migrate/validate、pending=0、二次migrate=0，并finally仅清理该测试schema。

只读Lead最初日志：`target/takeover-migration-red.log:464-471` 在1007复现 `relation "sys_notice" does not exist`；`target/takeover-migration-green.log:586` 成功应用71个版本至20261107002，`:588,592` 验证72个条目（含Flyway SCHEMA标记），`:595` 此测试1项、0失败/错误/跳过，`:617` BUILD SUCCESS。对应XML含 `audit.postgres.port=9684`；reviewer没有连接该实例。日志里的另一项 `DatabaseGeneratorPostgresTest` 是独立测试，不算作本迁移类覆盖。该首次执行仅证明空库首建缺表修复；后续扩展覆盖如下。

### M7：兼容回调与11项真实PostgreSQL定向复审（关闭 M4 两项）

契约仍为迁移README的自动初始化、只增不改历史版本，以及 `SPEC.md:21,35-36` 的数据保留、失败不伪装及可核验回归。本次完整阅读 `HistoricalSeedCompatibilityCallback`、两类迁移测试及Lead执行日志，没有运行Maven/PG。

**实现核验：** callback `:47-63` 固定三条替代规则，其中29001的replace仅操作私有常量，不改任意输入SQL；`:66-72,114-117` 限定事件、版本及完整脚本名；`:84-91` 对原语句做完整匹配，目标INSERT内容漂移抛错。`:93-99` 使用迁移连接执行后再发跳过信号，SQL异常包装为正常FlywayException；没有在catch中吞掉失败、另开连接或commit。所有其他DDL/搬迁语句继续由原脚本执行。`@Component`位于应用扫描范围，两类独立测试也显式注册callback。对固定基线运行 `git diff <base> --name-only -- 'astral-server/src/main/resources/db/migration/V*.sql'` 无输出，确认已有版本文件未修改；没有调用repair、验证忽略或新增持久helper。

**原缺陷负对照：** `target/takeover-migration-three-red.log:1091` 为4项中3错误，分别在`:1098,1164,1232`明确报 `sys_permission_pkey`、`sys_notify_rule_channel_pkey`、`sys_notify_rule_recipient_pkey`。失败版本为26001及29001，三者均记录回滚；第4项空库通过。`target/takeover-migration-three-green.log:1095,1117` 为相同四项全过、无跳过、BUILD SUCCESS。不是只断言任意异常，也没有把首个失败掩盖后续触发条件。

**最终执行：** `target/takeover-migration-compatibility.log:1574,3163` 分别为FreshDatabaseMigrationTest **5/5**、NoticeMigrationCompatibilityTest **6/6**，`:3167,3185` 合计**11项、0失败/错误/跳过、BUILD SUCCESS**。对应两个Surefire XML逐项列出同名测试，`audit.postgres.port=9684`；日志标明loopback PostgreSQL16.4，采用UUID隔离schema及合成用户。源码断言覆盖如下：

| 测试 | 实际核验范围 |
| --- | --- |
| Fresh.emptyDatabaseMigratesAllVersionsWithoutRewritingHistory | schema尚不存在时完整71版本、validate、pending=0、当前版本非空、再次零迁移 |
| Fresh.existingTesterPermissionAndRoleReferenceSurviveHistoricalSeed | 25001后放入原qt_tester与自定义角色，target26001后原ID/名称/角色关联保留；继续全链得到两项beta权限授权 |
| Fresh.multipleLegacyRulesKeepTheirRoutesAndGainDistinctEmailChannels | 两个同event的SMS/INAPP旧规则升级成功；原渠道类型、启用标记/平台与收件人值保留，新增两个EMAIL渠道及email收件人；主键约束保证新ID不冲突 |
| Fresh.partiallySplitRulesKeepChannelIdsAndGainDistinctRecipients | 预建拆分表、两个已有EMAIL且旧recipient列已删除；原channel ID950001/950002保持，新增互异递增recipient ID及PAYLOAD_FIELD=email |
| Fresh.replacementFailureRollsBackMigrationAndAllowsCleanRetry | 合成CHECK拒绝新增EMAIL，迁移失败后渠道写入全部回滚、29001无history、旧SMS/INAPP字段及行仍在；移除fixture约束后重试、validate通过 |
| Notice.precreatedEmptySchemaIsNotBaselined | 预建空schema仍执行init，不出现BASELINE |
| Notice.retriesOriginalMissingTableFailureWithoutChangingSuccessfulChecksums | 关闭默认SQL callback重现1007失败且无失败版本history；启用后重试成功，先前成功版本checksum集合不变 |
| Notice.existingNoticesKeepContentAndReceivePlatformAndEventConversions | 旧通知ID/title/content保留，app/pc/web/all映射正确；feedback回复变all/sender=feedback，角色通知变notice，普通公告仍announce |
| Notice.legacyDatabaseWithoutHistoryBaselinesAndPreservesNotices | 手工原init形成合法存量库且无history，自动BASELINE一次并跳过SQL init；原通知保留，后续全链成功 |
| Notice.publicTableDoesNotHideMissingNoticeTableInTargetSchema | public同名单列哨兵结构/内容不变，目标schema得到完整通知表 |
| Notice.concurrentStartupAppliesEachVersionOnlyOnce | **预建空schema**上两个独立Flyway实例同时启动，实际应用总数等于SQL history行数，无重复版本 |

每个Notice场景还统一validate、pending=0，断言最终26列、channel96/sender48、六个业务索引及主键，并再迁移零版本且history checksum不变。回滚/重试日志见`:1290,1300`；原缺表再现/恢复见`:3021,3155`。

**审阅结论：** S10与M4两项条件性升级问题均有对应实现和真实PG回归，可关闭；本次未确认需要阻断的新增实现缺陷。边界应如实保留：这是合成隔离PG验证，未启动完整Spring应用或操作真实存量库；并发测试不是两个实例同时创建尚不存在的schema；已有自定义收件人、所有元数据字段及Bigint极值没有逐项独立断言，不能把建议矩阵扩写成这些边界已实测。它们不构成本次已确认缺陷的重新打开理由。

## 通知与模板定向复核：当前修复及证据

### N1：原 S5/S6 的关闭依据

已重读三个实现、两个新增测试及相关 Mail/Sms 服务/事务接缝；只读 Lead 已执行的 `target/takeover-notify-template-final.log` 和 Surefire XML，没有重跑测试、启动服务或连接 Redis/DB。日志在 2026-11-08 11:44:14 完成，`:347,361` 为 **23 tests，0 failures/errors/skips，BUILD SUCCESS**。分解为：

| 类 | 项数 | 实际边界与断言 |
| --- | --- | --- |
| `MessageTemplatePersistenceTest` | 4 | UUID命名H2内存库、真实MyBatis mapper和Spring事务代理；同时换code/event保留EMAIL/INAPP原ID与内容、只换code不与自身event冲突、第二渠道CHECK失败回滚第一渠道、取消EMAIL只保留INAPP |
| `MessageTemplateVariableTest` | 4 | mock持久层的既有变量校验回归；不冒称数据库测试 |
| `NotifyPublisherDeliveryTest` | 5 | 真实publisher/InappService，mapper边界替身；其中3项使用Lettuce+真实Redis执行Lua，验证限额1时发送中竞争被拒、失败归还/成功占用/不同收件人隔离、GLOBAL共享；另2项验证插入异常失败结果和预占null时拒绝 |
| `UserRoleNotificationTest` | 10 | 既有角色变更通知邻接回归；本次仅核验执行汇总，没有重新审阅其全套行为 |

通知 Surefire XML 显示 `audit.redis.port=8549` 且五项 skipped=0；测试连接固定为127.0.0.1，`target/notify-redis-audit/redis.conf` 及receipt对应此端口、独占目录、禁持久化。Lead说明后端为Redis5；本 reviewer 未通过连接查询版本。必须区分“通知五项测试”与“其中三项真实Redis测试”。

旧行为负对照也有对应失败：`takeover-template-red.log:231` 重绑定后列表实际0而期望1；`takeover-template-rename-red.log:229` 自身绑定导致异常；`takeover-notify-red.log:232` mapper失败仍ok=true；`takeover-notify-quota-red-3.log:229` 发送中第二次应false却true。最终成功日志与当前断言相符，足以关闭这几条具体原缺陷。

尚未覆盖的执行边界包括SMS重绑定保留ID、真正的其他模板冲突、仅换event、insert=0、跨午夜失败、归还Redis异常。它们不等于已确认实现错误，也不以测试条数替代这些证据。现有S5/S6原触发路径的修复可确认，不代表整个通知域已通过。

### N2：模板两项后续 P2（均已关闭）

**N2-F1 原触发条件：并发取消渠道后，零行更新仍报告保存成功。** 契约 `MessageTemplateService.java:29-31` 与 `MessageTemplateSaveDto.java:8-10` 明确 channels 是期望状态，`SPEC.md:21,35` 禁止伪造成功并要求确认业务错误闭环。修复前 `save` 读取原行后忽略 save/updateById 的boolean。合法交错为：T1读取EMAIL+INAPP；T2取消INAPP并提交；T1写EMAIL成功，对已删除INAPP的update=0，仍返回成功且提交EMAIL。期望的双渠道只剩一个，`rollbackFor`不处理无异常的零行结果。

当前 `MessageTemplateService.java:180-184,199-203` 已检查所有所选渠道的save/updateById结果，false时抛明确并发修改 `BusinessException`，使本次事务回滚，不盲目插回另一位管理员已删除的行。`MessageTemplatePersistenceTest.concurrentChannelRemovalCannotSilentlyCommitPartialSave:241-259` 使用真实MyBatis Executor拦截器，在第一事务读后写前安排另一线程调用公开save取消INAPP并提交；断言第一save失败、EMAIL原内容保留、另一事务的INAPP删除保留。负对照 `target/takeover-template-concurrent-red.log:231` 为期望异常但未抛出；当前六项成功证据见下，关闭此缺陷。

**N2-F2 原触发条件：事务提交前失效缓存，可被并发发送重新填入旧模板。** 契约 `AGENTS.md:157-162` 为“后台改模板即生效”；`MailServiceImpl.java:42-45` 说明管理写操作主动立即失效，`SPEC.md:35` 要求修复可确认的业务错误。修复前 `MessageTemplateService.save/delete` 在事务返回前调用两个evict，此时尚未commit。另一个线程在evict之后、commit之前调用 `sendSystemAlert`，通过Mail/Sms缓存路径读旧已提交版本并缓存60秒；随后管理保存返回成功，但告警仍使用旧内容。普通NotifyPublisher按ID直接读，不受此缓存路径影响。

当前 `MessageTemplateService.java:207,224,227-239` 的save/delete统一调用evictAfterCommit，注册 `TransactionSynchronization.afterCommit` 后失效Mail/Sms缓存；无事务同步时立即失效，回滚不触发afterCommit。`MessageTemplatePersistenceTest.committedTemplateReplacesVersionCachedByConcurrentDelivery:220-238` 使用真实SmsServiceImpl、真实MyBatis/H2及合成SmsProvider SPI记录模板码，在外层真实事务保存NEW后并发发送一次缓存OLD，提交后再发送必须NEW。负对照 `target/takeover-template-cache-red.log:231` 实际 `[OLD,OLD]`，修复后 `target/takeover-template-cache-green.log:232,250` 五项通过、BUILD SUCCESS。断言覆盖真实缓存行为，不仅验证evict调用；此缺陷关闭。Mail与delete/rollback边界共享实现但本次没有各自独立运行证据，不能称已逐一实测。

最新合并日志 `target/takeover-menu-green.log:234` 为模板持久类**6项、0失败/错误/跳过**，`:242,256` 本次模板变量+菜单邻接总11项且BUILD SUCCESS。两项均有旧实现失败、当前实现通过的Lead执行记录，本reviewer只读复核且未执行测试。S5/S6与N2-F1/F2当前均关闭，不代表整个通知域全部行为已终审。

## AI 请求生命周期定向复核：R11/R12

本节为2026-11-08当前实现的只读结论及待实施建议，不是已修复或已运行测试的声明。Java路径除另标外均相对 `astral-plugin/src/main/java/com/astral/ai/`；schema为 `astral-plugin/src/main/resources/sql/ai-schema.sql`。Provider传输及SQL执行安全由Lead另行处理。

### A1：R11 当前确认问题与反证

契约：`AI_MODULE_DESIGN.md:704` 要求写操作由用户确认；`:815-850,1808` 要求计划、确认、重检权限/对象版本、幂等执行、审计并返回结果；`:1060-1065,2117` 要求请求去重、取消停止后续副作用及独立工具幂等。`SPEC.md:21,28,35` 禁止假成功并要求确认问题闭环。

| 严重级别与问题 | 当前源码、触发与影响 |
| --- | --- |
| P1 确认身份/原策略未执行 | `controller/AiAdminToolController:208-229` 只有admin审批权限注解，未按原 `approvalStatus=USER_CONFIRM` 校验operator等于request.userId；其他拥有审批权限的管理员可代替用户确认或拒绝。operator使用只读请求属性的nullable helper。批准后原策略被覆盖ADMIN_CONFIRM，拒绝把结果REJECTED写入策略字段。`AiAppController`没有与USER_CONFIRM对应的调用者确认入口。 |
| P1 取消后仍可执行待批工具 | 同控制器`:221-280` 读取request但不检查状态；先取消WAITING请求，再批准仍能进入invoke。`resume`稍后退出CANCELLED不能撤回已发生副作用。 |
| P1 工具结果无法可靠送回模型 | `service/AiChatOrchestrator:180-189` 创建空content的assistant；`:383-434` 增量只进事件，`:648-660` 工具轮仅写blocks。`:817-819` 在解析toolCalls前跳过空content，后续`:862,871-915`把工具结果作为孤儿丢弃，因此普通工具轮也受影响。approve只更新tool-call行再resume（controller`:248,285-290`），没有真实结果/拒绝消息或TOOL_COMPLETED事件。即使修复空content过滤，`:508-513`的“需要确认”占位仍不是实际结果。 |
| P1 工具轮身份、多个调用及历史不完整 | `executeTools:471-514` 在首个待批调用直接return，同轮余下调用未落库/执行；`AiToolCallEntity`无provider调用ID、assistant消息ID或轮次关联，不能按toolCode安全回填同名多调用。所有轮复用同一assistant，`:657`覆盖先前声明，finalize`:682-689`再覆盖为UI blocks，工具转录从历史丢失。 |
| P1 恢复与原worker/取消竞争 | 审批事件`:495-507`先于待批占位、request.markWaiting`:299`，快速审批可在旧worker尚未暂停时启动第二worker。resume`:211-212`无认领；run`:232-244`只排除CANCELLED后直接markRunning；`AiRequestService:191-207,221-248,253-263`为读后updateById，waiting/success/failure/cancel不使用预期状态条件，终态可能被并发旧读覆盖。 |

**不能继续使用的旧结论：** approve/reject现有WAITING_APPROVAL条件更新（`:236-244,269-277`）已保证同一工具行只有一次成功认领；不是“两个并发审批必定执行两次”。执行时 `orchestrator.invoke:571-579` 已按**原请求者**实时检查权限，不能声称用审批者权限替代执行身份。缺失/变化的planHash也已拒绝（controller`:260-265`）；MCP关联配置是否纳入摘要是另一独立缺口。没有找到“ADMIN_CONFIRM审批人必须不同于请求者”或“必须拥有请求者所有资源权限”的规范，不应新增该限制。

`tool/AiToolTypes.AiToolContext`只有requestId，没有独立工具执行键；现有工具行CAS也不能保证新模型轮重放时业务幂等。要求是稳定逻辑调用身份、持久结果与副作用处理器可消费的幂等键，不是将全部同名调用永久禁用。`AiToolResult.summary`契约明确为给模型的简短结论，payload属于结构化审计；恢复时应发送有界安全summary和结果状态，不能为补断链把原始敏感payload全量交给模型。

UI补充：`astral-front/src/components/ai/useAiChat.ts:233-291`的事件分支没有处理tool.approval_required；`src/api/ai.ts:830`虽定义approve，前端搜索没有调用者。这是公开用户确认链的后续接线范围；本节测试建议先证明后端生命周期，不冒称UI可操作性已验证。

### A2：R12 当前确认问题与反证

契约：`AI_MODULE_DESIGN.md:1062,1193-1201`要求保留已产生费用、预占后按真实用量结算、仅释放未使用部分及原子扣减；`:1531,1575`要求幂等账本、策略不回写历史与agent维度；`:1060`要求按用户/agent/key去重。

| 严重级别与问题 | 当前源码、触发与影响 |
| --- | --- |
| P1 暂停/失败/取消丢费用，恢复丢累计值 | `orchestrator:246-254`每次run重新reserve且局部tokens归零；待批return`:298-301`走finally失败分支`:338-339`。`AiUsageService.release:75-83`将已完成provider的费用置0；resume再插usage行，最后request tokens仅本次run，`fail:758`更固定写0。usage.findByRequest`:89-97`只取最新行，summarize`:127-145`按行数计算请求数。 |
| P1 usage插入失败泄漏已提交预占 | `AiQuotaService.reserve:280`为REQUIRES_NEW；orchestrator`:249`返回时已提交，紧接`:250`usage.reserve在try`:257`外。此处数据库失败直接进入runSafely.fail，没有quota.release，day/month计数与本机并发名额残留，且provider尚未调用。清理器最多兜底部分内存，不修复数据库计数。 |
| P1 结算/释放不原子、不幂等且吞失败 | `AiQuotaService:338-357`没有事务，day/month分两次写、捕获后只记日志；第二次失败可留下部分记账。Reservation没有request/调用幂等身份，重复release可使计数负数；`:360-365`按共享user:agent计数释放，还能误减其他在跑请求名额。usage.settle/release也无状态CAS或幂等判断。 |
| P1 实际Token及月费用上限无执行效果 | reserve`:298-317`将输入字符数记为input_tokens、输出预占0；settle`:341-343`按answer字符/2追加输出且limits全部null。`monthlyCostLimit`仅在`:210-214`解析，reserved_cost/settled_cost只在`:398-399`初始化0，没有其他写入；版本override`:225-235`也未解析月费用。配置月费用上限不能阻止provider调用。 |
| P1 单次上下文/输出硬限制未送入provider | limits已计算maxContext/maxOutput并钳制硬上限，但orchestrator构建ChatRequest`:353-373`只调用routeMaxTokens（`:1161-1165`为route或model值）。历史仅按消息条数限制；配额输出预占0，当前调用可先超预算，后续请求才可能被计数挡住。 |
| P2 最终价格漂移及完成状态领先账务 | `AiUsageService.computeCost:188-200`结算时读取当前model单价，未记录调用时价格快照。orchestrator`:331-339`先finalize成功消息/请求再账务结算；usage更新抛错后runSafely会再fail，可能出现成功事件、失败请求和零Token冲突。 |
| P2 start幂等仍产生错误及孤儿消息 | orchestrator`:151-168`先查询，再在独立调用中append用户消息，最后create请求。并发相同key的输家被schema`:382`唯一键挡住，但没有返回赢家，已追加消息未回滚。create`:75`存原key，find`:94-100`却trim查询，空白或带边缘空格的key也会产生重复键/漏查。 |

**反证和证据边界：** 正常顺序审批暂停会先退请求计数、恢复再加，最终日/月request_count通常为1，因此“正常恢复必然双扣日/月请求数”不成立；真正重复的是usage行及其汇总请求数，真实消耗则被少算。reserve的日成功、月额度拒绝会抛异常并回滚其REQUIRES_NEW事务，不能与无事务的settle/release混为一谈。request唯一键确实存在，不能声称并发start必然创建两个请求/发起两次模型调用。当前provider SPI的onError只有异常，失败调用可能没有真实tokens；应保留已知阶段和明确估算/未知状态，不能伪造精确费用，也不能一律写0。

schema`:419-437`目前允许同一request多条usage，既有暂停数据可能已经如此。不能直接添加UNIQUE(request_id)并删历史行；应新增独立请求账本，或在保留原审计前提下明确聚合迁移，再约束新生命周期。汇总也不能把不同currency直接相加后仅返回最后一个currency（当前`:133-137`正是如此）。

### A3：最小可实施约束

以下是修复必须满足的行为，不要求采用特定类名或重构全套架构。

1. **请求只允许一个执行者。** 使用持久CAS/租约认领QUEUED→RUNNING及WAITING_APPROVAL→RUNNING；结束、暂停和取消必须校验同一worker/预期状态，终态不可重开。整轮工具计划、稳定provider调用ID/assistant轮身份、待批状态应先持久化，再暴露可审批事件。自动可执行调用及所有待批调用都要完整记录；同轮仍有未决调用时不重启模型。审批和取消共用短事务认领边界，取消先提交则不得新派发工具；已在执行的外部操作不能靠取消撤回，应保存结果并停止后续步骤。不得持数据库事务等待模型、HTTP或人工确认。
2. **审批规则在共享服务落实。** 统一使用AiCaller.requireUserId；USER_CONFIRM只有请求所有者可批准或拒绝，普通用户入口只允许其自身USER_CONFIRM；ADMIN_CONFIRM保留现有声明式admin:ai:tool:approve权限门禁。原要求策略不被改成结果枚举，结果进call.status、actor进approvedBy/At。保留执行时原请求者权限检查及计划快照复验。结果和TOOL_COMPLETED落库后才尝试请求级恢复CAS。每个工具轮声明和结果永久保留、空content声明有效，最终回答单独保存，不能按toolCode猜测对应关系。
3. **请求与阶段账务有幂等身份。** 使用稳定requestId和每次provider调用/阶段唯一键；在一个短事务中创建请求账本与day/month预占，quota API须带requestId，不能留下“quota独立提交后usage才插入”的窗口。每个已完成provider调用的实际Token/费用仅累计一次，保存调用时model/provider/价格/currency及捕获的周期行ID。等待审批释放闲置并发和未用的未来预占，保留已消费额度及逻辑请求身份；恢复重新获得运行名额和必要的下一次调用预占，不新计一次请求。
4. **结算失败可恢复且对外状态一致。** 成功、拒绝、取消、失败都结算已知消耗，只归还未用估计值；day/month和账本状态必须同事务CAS更新，重放settle/release不扣负数、不改变已完成账单、不释放其他请求的名额。账务落地与完成事件需有一致顺序，或明确可重试的账务待处理状态，不能日志吞错后报告全部成功。进程重启恢复依靠账本/租约，不把已开始但结果未知的副作用盲目重放；调用幂等键交给handler。退款沿捕获的周期ID，跨日/月不改成当前日期键。
5. **每次provider调用前检查预算。** 依据实际要发送的消息/上下文估计输入，输出maxTokens钳制到策略、进程硬上限、模型/route能力及剩余额度；原子比较当月settled_cost+reserved_cost+本次估计与monthlyCostLimit，连同输入/输出Token预占后才调用。完成后以真实值替换该次估计，真实费用即使超过估计也必须记录并阻止后续超预算调用。版本override只能收紧；明确零价格免费模型与未知价格，保留已有合法免费模型支持；不同币种分开核算或有明确转换规则。
6. **start的消息与幂等创建为一个原子结果。** key统一trim，空白归null；请求及所属用户/assistant消息、创建事件同事务保存。唯一键竞争输家在失败事务之外读取已提交赢家并返回，不留下孤儿消息；仅真正创建者在提交后调度worker。不能靠捕获DuplicateKeyException后在已中止的PostgreSQL事务继续查询。存量带空格key应有兼容策略，不能静默丢审计。

### A4：公开start→approve→resume回归夹具与关闭条件

**待实施的最小夹具：** 直接调用公开start和公开审批入口，由实际异步executor恢复，保留真实AiChatOrchestrator、AiRequestService、AiUsageService、AiQuotaService、AiConversationService、AiEventService以及审批共享服务/controller。配置查询可以用合成agent/version/route/model及无知识命中的替身；执行边界用 `AiProviderRegistry(List.of(recordingProvider))` 和记录合成副作用的AiToolHandler，无外网。权限咽喉使用真实AiToolPermissionGate、PermissionChecker与可变StpInterface，使执行前撤权可观测；admin入口权限断言用真实PermissionAspect代理，不能直接调用controller后假称注解门禁已测。

持久层复用 `astral-system/src/test/java/com/astral/system/notify/MessageTemplatePersistenceTest:71-136` 的SqlSessionTemplate、SpringManagedTransactionFactory、DataSourceTransactionManager、TransactionInterceptor组合；异步线程必须各取事务绑定session，不能共享AiMcpServerPersistenceTest的单个SqlSession。基于真实AI schema建所需表和唯一键，测试MetaObjectHandler分配合成ID/时间。可先H2验证普通公开链：预置day/month行以避开PG advisory锁函数；行创建、原子预算/并发及回滚最终仍用隔离真实PG公开mapper/服务入口验证。此建议不授权reviewer运行数据库或测试。

既有可复用边界：AiChatOrchestratorPermissionTest的MutablePermissionSource/RecordingTool，AiAdminToolControllerPermissionTest的Sa-Token/权限Aspect夹具。ApproveCasTest/ApproveSnapshotTest只证明各自mock边界；AiChatOrchestratorHistoryTest直接构造Message测试私有配对算法，不能发现真实消息行空content被跳过；AiQuotaServiceTest和AiUserQuotaMapperSqlTest不等同真实账务事务执行。新测试需保留这些既有断言并补完整公开链。

**确定的两阶段provider脚本：** 第一次返回 `ChatResult("", "tool_calls", 100, 10, List.of(ToolCall("call_A", "fixture_tool", "{}")))`；等待审批后调用公开approve。第二次记录收到的ChatRequest.messages并返回合法结构化答案 `{"answer":"done","blocks":[{"type":"markdown","content":"done"}]}`、input=50/output=5，避免无blocks触发第三次兜底。每千Token输入价格1、输出价格2、同一currency时，阶段1费用0.120000；最终累计input150/output15、费用0.180000、逻辑请求1、day/month真实消耗一致、未用预占0。取消发生在审批前时，不执行handler/第二次provider，保留0.120000。用latch/future/真实MyBatis写拦截器安排竞争，等待账本终态而非仅request SUCCEEDED，finally关闭orchestrator和quota调度器。

| 建议公开回归组 | 必须断言的结果 |
| --- | --- |
| 成功批准、空content工具轮 | 真实provider第二次收到匹配call_A的assistant声明及实际成功summary；无“需要确认”伪结果；阶段1在暂停时已保留，最终账本为上述0.180000，事件与request终态一致。 |
| 拒绝、权限及策略 | 拒绝不调handler，恢复模型看到明确拒绝结果，原USER_CONFIRM策略保留；他人/缺身份不能批准或拒绝USER_CONFIRM，owner可处理；admin权限真实AOP检查，执行前撤销请求者资源权限则handler不运行。 |
| 取消及终态竞态 | WAITING取消后批准无handler/恢复；按latch安排cancel与finish/wait交错，取消不被旧worker覆盖；终态resume不新发模型/扣额。 |
| 多调用、多轮、快速/并发批准 | 第一项待批时同轮其余项仍持久；同名不同provider ID分别对应一结果，各handler至多一次；最后未决调用完成前不resume，完成后只resume一次；下轮与最终历史保留所有工具轮。 |
| usage写入失败 | 在预占与usage写之间注入真实持久失败，provider调用0、day/month无残余、运行名额归还；maxConcurrent=1下后续请求可正常开始。 |
| 已消费后失败/取消、幂等对账 | 第一轮100/10后第二轮失败或取消，已知0.120000仍计入；重复settle/release/reconcile不重复记账、不扣负数；第二周期写失败时整单回滚，重试仅记一次。 |
| 真正预算与历史价格 | 月费或当日输出只够一个调用时，并发请求只能一个获得预占，另一个provider调用0；实际ChatRequest.maxTokens不超约束；暂停/改价/恢复时旧阶段单价不变；注入Clock验证跨日/月按既定周期归属。 |
| start去重 | 同key并发、边缘空格、空白key按声明语义处理；相同有效key仅一逻辑请求及一套消息、输家返回赢家、模型只一次。 |

上述矩阵均为**关闭条件/建议**，当前尚未执行，不计入GREEN数。reviewer未改任何AI实现、schema或测试，也未运行Maven、DB、Redis或浏览器。

### A5：审批执行的持久认领/完成分界（实施中，只读设计检查）

本项响应Lead对正在修改代码的定向检查，**不是A1–A4或整轮验收**。首次读取的`AiChatOrchestrator.approve:239-302`在同一个`requestService.transaction`内锁request、把call从WAITING_APPROVAL改为RUNNING、调用外部handler，再写结果/消息/事件。外部副作用完成后，后续SQL、序列化异常或进程退出会使认领与结果一起回滚；再次approve能重复调用。该风险适用于HTTP/MCP/邮件等不参加本地事务的副作用，不应泛化为同事务内纯DB写也必然残留。契约：`AI_MODULE_DESIGN.md:619,1065,1817`禁止重试重复副作用。

**已观察到修复方向：** 14:48:55版本已拆claim transaction、无显式事务的invoke、finish transaction，新增`execution_token`，finish按callId/token/RUNNING条件更新。对当前无外层事务的控制器入口，原“后续写失败→认领回到WAITING”路径已在源码层面消除；本轮没有运行测试，不据此声明持久故障语义完整通过。

最小实施约束：

1. **claim短事务：** 同锁序request→call，验证请求状态、确认人/策略、原请求者权限、计划hash、结果消息与assistant归属；CAS WAITING_APPROVAL→RUNNING，保存执行token/确认人/时间，提交后才允许副作用。现`AiRequestService.transaction:50-51`默认REQUIRED，仅拆lambda不保证存在外层事务时已提交；独立事务或明确拒绝/挂起外层事务应成为入口契约，不能只自调用带注解方法。确定的消息缺失应在副作用前报错。
2. **执行期：** 不跨外部调用持有request锁/连接。claim已提交便视为在途；取消在claim前获胜时零副作用，在claim后只能阻止后续新步骤，不能假定已发远端调用可撤回。发送前再读取消可缩小窗口，但不是与dispatch原子的保证。保留业务服务本身必要事务，不能因移走编排大事务破坏其局部原子性。
3. **finish短事务：** 按同callId/token/RUNNING进行条件更新且检查行数；真实工具结果、对应tool消息、完成事件和可恢复QUEUED状态一起提交，提交后调度。请求已CANCELLED仍保存迟到结果/审计，但不得改回运行。重复同token finish只读已持久结果，不重复事件/消息/模型调用；已知结果写入失败可重试finish，绝不重新invoke。
4. **崩溃/超时未知：** 已提交RUNNING而无完成结果不能退回WAITING，也不能因lease超时换token重发。可暂留RUNNING并明确待核对，或增加UNKNOWN并让`queueResume`一并排除；当前队列条件只排除WAITING_APPROVAL/RUNNING。没有可靠下游查询/去重时，应保留不确定事实和人工核对入口。HTTP/MCP当前将异常归FAILED，响应丢失可能已执行；不能把这个失败结果交给模型后生成新call当作安全重试。
5. **持久队列与独立幂等：** finish提交后、afterCommit提交线程池前退出可留下QUEUED。恢复器只认领QUEUED并使用现worker token CAS，不重放RUNNING工具。`execution_token`仅防旧执行者写结果，`AiToolContext`当前还无独立稳定操作键；用持久callId派生键并传给支持去重/查询的handler才可能安全重试，随机JSON-RPC id或参数hash不能替代业务幂等。纯本地DB工具可在已提交claim之后将业务变更和finish置于同一短事务；远端无法凭本地事务获得exactly-once。

现有`AiApprovalLifecycleTest`具备真实`SqlSessionTemplate`、`SpringManagedTransactionFactory`、`DataSourceTransactionManager`及公开start→controller.approve入口，可直接扩展以下合成故障点，无需真实外部服务：

| 关闭证据 | 最小测试安排与必须观察 |
| --- | --- |
| 认领确实提交且不持锁跨调用 | FixtureTool进入latch；另一连接读到RUNNING+token，并能完成取消；释放latch后真实结果保留且请求不复活。 |
| 副作用后中断不重放 | FixtureTool计数+1后抛合成Error（当前invoke只捕获Exception），首调失败后持久状态仍RUNNING；再次公开approve或恢复不再调用工具。只能称故障点模拟，不能称杀进程验证。 |
| 完成写故障 | 工具正常返回后，对真实消息/事件写注入一次失败；认领不回WAITING，外部次数仍1；已知结果的finish重试最终只写一份结果/事件并恢复一次。 |
| 提交后的调度故障 | finish已提交后模拟executor拒绝/重建编排实例，QUEUED通过持久恢复被claim一次，工具次数保持1。 |
| 结果未知与确定未发区分 | 合成远端已计数但响应丢失；记录未知并阻止自动重发/模型重做，查询可证实结果后再完成。若handler声称幂等，重复同稳定键必须只产生一次效果。 |

上述为当前实施建议及关闭条件；未运行Maven、测试、DB、Redis或网络，未修改实现/schema/migration。

### A6：聊天审批/恢复/取消前端定向 Spec 复审（实施中，限定范围）

**范围：** 当前`astral-front/src/components/ai/{useAiChat.ts,sse.ts,ChatPanel.tsx}`差量及其直接调用的API、消息分页、权限和请求详情入口；读取快照为2026-11-08 15:15–15:17版。Lead仍修改后端和前端，本节不关闭A1–A5、不重新审阅其他UI。只读代码和现有测试，未运行Node/Maven、服务、浏览器、DB或网络。依据`AI_MODULE_DESIGN:1018,1026,1058-1065,1750,1779,2115-2118`，恢复须保持消息/请求身份，停止和审批须可执行且如实反馈。

**本轮待处理范围如下，均为源码可构造交错或输入，不是已执行RED：**

| ID / 级别 | 可复现行为、当前证据 | 最小修复/关闭条件 |
| --- | --- | --- |
| F01 / P2 迟到发送或列表操作切回旧会话 | `useAiChat:560-650`发送等待POST时没有view/operation校验；`ChatPanel:390,412`仍可切换/新建。发送A→切B→A响应：`:612`覆盖runningRef，`:638`watch(A)，新patchMessage会把A的回复/审批卡插进B；失败分支`:642-646`也可能标记B末条失败。`loadConversations:442-460/reload:465-483/newConversation:492-505/removeConversation:526-539`同样未保护异步结果，旧加载/新建/删除响应可以夺回当前选择。 | 全部UI写入绑定view+operation generation，发送中同步认领防重入，旧完成只能更新其明确归属的数据。合成延迟adapter安排send(A)→select(B)、两次new、reload→select、delete(A)→select(B)反序完成，断言当前B消息/请求/选中项和错误不被旧操作改写。仅给loadMessages加guard不够。 |
| F02 / P2 停止会误停新请求或报告未发生的取消 | `stop:545-557`只在await前取requestId，成功后直接abort当前controller/finishRun(CANCELLED)。cancel(A)挂起→切B并恢复B→A成功会中断B且把B助手改CANCELLED。send已设running但`:612`尚未获得id时点击停止，不发cancel便本地收尾，稍后POST回包重新运行。另cancel接口返回实际status，终态SUCCEEDED会原样返回，但当前`:548,557`忽略响应并强标取消。 | 停止绑定原view/request/controller，异步返回前复核；建请求阶段记录cancelIntent，id到达后向服务端取消，不能只abort建请求而假定未创建；按服务端真实终态显示。延迟send/cancel和返回SUCCEEDED三组React fixture验证新请求不中断、旧请求确实取消、成功答案不伪装取消。 |
| F03 / P2 同请求重新进入仍接纳过期事件 | `pollOnce:354-357,369-373`仅对requestId和alive判断；A→B→A时第一次A的axios轮询未被detach取消，迟到后与当前A相符。`handleEvent:247-249`只抬高游标，不丢弃sequence≤cursor的事件，旧delta可重复追加，旧approval.required可把已完成卡片改回待确认；旧poll还会与新SSE并行调度。 | watch/poll均捕获独立订阅generation；序号按request去重，结束后旧订阅不影响状态。延迟旧A poll，在回到A且新流已消费更高序号/完成审批后才返回旧batch，断言文本不重复、审批不回退、没有第二轮询链。 |
| F04 / P2 历史分页丢最新答案且恢复不补回 | `AiConversationService.messages:152-155`DESC LIMIT(size+1)后先升序sort；`AiAdminChatController.messages:211-218`再subList(0,size)，丢掉最新条而非lookahead，nextCursor也取剩余最新。例如31条请求limit30返回1..30，漏31。`useAiChat.loadMessages:425-433`若最新请求已终态且事件≤100，丢弃首batch、不watch，所以最新助手答案刷新即消失。 | 先按DESC截正确page与最旧cursor，再按展示顺序升序；31+条含最新完成答案公开分页与React reload夹具验证最新存在、第二页不重复不漏。历史30条上限不是丢最新一条的理由。 |
| F05 / P2 管理员审批提示指向不存在入口 | `ChatPanel:598,616`对无tool:approve权限的请求者隐藏按钮并提示管理员去请求详情确认；`admin/RequestPanel:435-484`只有状态/参数/结果，无approve/reject。组件中仅ChatPanel调用approve，管理员无法通过requireOwned聊天接口打开他人会话。合法请求者无审批权而计划ADMIN_CONFIRM时，UI无法完成指定审批流程。 | 请求详情增加适当权限及内容访问门禁的ADMIN_CONFIRM确认/拒绝入口，保留USER_CONFIRM本人规则；以请求者U和审批者A两种会话/权限fixture验证U不能管理员确认、A能处理U的ADMIN_CONFIRM、A不能代批USER_CONFIRM，操作后重新读取真实状态。契约`:815-850,1750,2145-2149`。 |
| F06 / P2 合法请求所有者不能使用停止 | `ChatPanel:665-667`为所有running显示停止，`aiChatApi.cancel:976-977`调用管理取消；`AiAdminChatController:295-305`只允许admin:ai:request:cancel并按admin=true取消。持config:view+assistant:use且智能体获授权的所有者可以发起，但无全局cancel权限时停止必被拒绝。 | `AI_MODULE_DESIGN:1061`明确所有者或获授权管理员可取消。提供owner取消入口/等价所有者鉴权，保留管理员全局取消权限边界；UI使用匹配API，不能通过扩大全局权限或只隐藏停止掩盖合同缺口。验证owner成功、无关用户失败、授权管理员成功，前端失败保留运行状态。 |
| F07 / P2 刷新待审批生成伪助手消息 | `loadMessages:414`过滤TOOL，但`:425,430`选择最新任意role记录并把其id作为assistantMessageId。实际待批时最新通常为TOOL；后端tool.approval.required没有messageId，`:251,322-323`回退该TOOL id，`:218-221`会重新插入role=ASSISTANT的空消息。 | 恢复以明确ASSISTANT消息ID/服务端恢复快照为准，工具事件只更新工具轨迹，不依赖任意最后消息作助手。fixture含USER10/ASSISTANT11/TOOL12待审批，reload+重放事件后只能有10/11及正确卡片，不能创建伪ASSISTANT12。 |
| F08 / P2 SSE的CRLF分块边界丢帧 | `sse.ts:41`仅在单chunk内把CRLF归一。合法结束空行的第二个CRLF跨chunk时，例如首块结尾`data: {...}\r\n\r`、次块`\n`，累积缓冲成为`...\n\r\n`，`:44`找不到双LF；后续帧可与其合并，`:85-94`JSON失败后以空对象交付。仅LF的正常Spring流不触发，条件是传输提供CRLF并在该处切块。 | 增量解析跨块保留CR状态或在完整累积缓冲上规范化；用真实createSseDecoder枚举每个字节切分点，LF/CRLF同样输出每帧一次且payload一致，再经openEventStream假ReadableStream验证stream.end按序交付。此项未声称当前生产代理已触发。 |

**已有正确行为与测试边界：** 当前审批按toolCallId去重并以view guard保护响应；简单A→B的loadMessages已有view guard；`patchMessage`能插入恢复轮的新assistant ID；SSE已把stream.end交给hook，并从统一完成路径回落轮询。未发现前端admin布尔值绕过服务端策略/身份检查，界面权限不是最终授权。现`tests/ai-chat-lifecycle.test.cjs`运行真实hook/React/API客户端/SSE解析器，但只提供空messages、即时send/approve及单流正常完成；它不证明上述恢复、权限UI或交错条件。应复用其`createSourceLoader`、真实React act与延迟HTTP adapter/ReadableStream，不替换hook实现；消息分页/owner权限需对应公开后端契约验证。

本轮工作仅追加本节，**待处理为F01–F08及其最小回归**；Lead已接收send/stop generation、分页和管理员审批入口建议。其他UI、后端整轮生命周期、样式/字典全面验收均不在此次结论中。

## 全项目覆盖与未收口清单：当前差量核对

本节按Lead最新分工核对两日audit、当前源码和已有日志，**排除A1–A4请求生命周期**；Provider/R3由Lead推进，不重复认领实现。HEAD仍为固定基线，当前`git diff <base> --name-only`共303个已跟踪变更文件，另有未跟踪源码/测试；此数只是差量盘点。下列行号是本轮读取快照，父线程继续改动时需以新源码和对应证据更新。没有执行Maven、测试、DB、Redis、网络或浏览器，仅修改本报告。

### C1：优先处理的源码确认项

“确认”指已完整核对表中因果链，包含明确列出的并发/配置前提；不是生产事故或已执行RED的声明。所有条目均受`SPEC.md:28,35`“确认缺陷实施并有可核验验证”约束，表内另列具体业务合同。

| ID / 优先级 | 当前未收口行为与源码依据 | 具体契约、最小关闭证据 |
| --- | --- | --- |
| C01 / P1 账户旧状态覆盖 | `astral-auth/.../controller/ProfileController.java:95-112,166-174,191-200`分别读完整User后更新密码或TOTP；`astral-system/.../controller/UserController.java:331-346`重置密码同样加载整行后updateById，`UserServiceImpl:45-51`继续传完整实体。User无乐观版本。另一事务在此间停用账号、调整类型或更换凭据后，本次无关更新可能重新写回旧状态。不是客户端任意字段赋值。 | `AGENTS.md:364-373`要求白名单SET、不以旧行补回遗漏字段。真实MyBatis事务夹具安排读取→另一事务改变状态→原更新，确认仅本操作字段入SET；涉及凭据变更时，对依赖的旧凭据状态还须按预期状态CAS，竞争失败不回放旧密钥。 |
| C02 / P1 存储诊断泄露 | `astral-plugin/.../storage/service/HttpUploadGateway.java:654-679`先截140字符，再替换完整secret；secret长于剩余空间或跨截断边界时前缀保留。`:595,602`经probe返回，`HttpStorageConfigService:410-417,429-432`还持久化lastTestMessage并响应给channel:test调用者。命名secret允许2KiB，条件可达。 | `docs/THIRD_PARTY_STORAGE_GROUP_DESIGN.md:165-166,820`禁止错误正文/凭据反射进入普通API/日志。公开probe→配置test，以合成secret跨截断点、长值及常见编码回显，响应和存储摘要都不得含材料；优先固定安全原因。不能只证明正常上传safeCode安全。 |
| C03 / P1 条件性孤儿文件 | `StorageFolderService:384-407`以tree/security锁检查空目录后删除；`StorageFileService:170-219,238-281`仅锁uploadId，普通SELECT检查目录后插文件。`UploadOperationService:579-594/495-522`及`UploadOperationRecoveryJob:150-204`按历史folderId登记也不共享目录锁/检查。DDL`V20260914001:1609`及后续迁移无folder外键。删除可在旧直传读后插前、或CUSTOM_HTTP发送/恢复前完成，随后文件行指向不存在目录。 | `STORAGE_REQUIREMENTS.md:15`仅可删除空目录；`StorageFileService:1134`明确登记必须仍存在/启用。公开delete与两类register、REMOTE_SUCCEEDED恢复以真实事务/锁交错，最后无孤儿且成功远端结果仍可追踪。最小修复须协调删除与在途操作/最终登记，不能直接丢弃已成功远端对象。 |
| C19 / P1 单会话撤销误报成功 | `astral-auth/.../service/impl/TokenServiceImpl.java:61-65`把DAO查询失败转换为空列表；`:238-247`仅搜索前20000个键，未命中统一返回null；`:216-220`随即当已失效成功返回，`TokenController:72-74`向管理员返回成功。故存储故障或目标落在扫描上界外时，活跃会话可完全未登出却显示已撤销。 | `SPEC:21,35`禁止伪造成功/健康状态，方法`:207-211`只为实际已失效会话提供幂等。真实TokenService配合合成SaTokenDao，从公开revoke验证查询故障、超出搜索上界、确实不存在三种情形；未知不能当成功，保持不透明指纹及凭据保密，也不能恢复无界全库扫描。 |
| C04 / P2 存储DNS无截止 | `HttpUploadGateway:381`同步hostResolver；`:404,431`之后才设置attemptMs截止，`PinnedDnsResolver.resolvePublicIpv4`使用系统getAllByName。公共BoundedHttpTransport修复未用于此独立上传实现。 | 设计`:113,509,686-689`要求单次/总预算、有限解析并发、迟到不得连接。公开send/probe注入忽略中断resolver，短预算及时返回、解析稍后完成不得连接、残留解析数量有界。 |
| C05 / P2 存储每日限额不是统一消费事实 | `UploadPolicyService:210-230`只数现存file行；callback/browser各自uploadId锁后检查（`StorageFileService:199-201,269-271`），不取得`UploadQuotaService:71`同一folder:actor额度锁，也不纳入新操作HELD。两旧上传或新旧混用可超卖。另`StorageFileService:794`实际物理删除，实体无TableLogic，上传→删除→再上传即可减少当日旧链计数。新CUSTOM_HTTP已有operation CONSUMED，不能误称其删除也必定退款。 | `UploadPolicyService:205-207`宣称已成功次数含删除；设计`:573-579,983(T19)`要求新旧链统一、无超卖、消费归属固定。限额1下旧旧/旧新并发仅一个成功占用；成功后删除、移动和重放不重置消费。需消费记录/统一锁，不能仅改注释或恢复已不采用的产品软删。 |
| C06 / P2 意图数上限竞争失效 | `UploadOperationService.create:126-164`先count，随后insert；`enforceIntentBudget:741-762`用户2/全局64计数无共同锁或原子认领。不同幂等键并发都可通过；传输许可不限制CREATED行。 | 设计`:446`明确“每用户最多2、全局最多64”。公开create用不同key并发跨最后名额，断言存储未过期意图数不越界，重复key仍返回原结果。 |
| C07 / P2 通知隔离不完整 | `astral-system/.../notify/NotifyPublisher.java:110-123`规则渠道查询、收件人查询及ROLE展开在deliver的try之外。第一项依赖异常直接退出publish，后续健康规则/渠道不投递；publishAsync外层catch只截住事件。 | `AGENTS.md:209-216`、本类`:143`要求逐规则/渠道隔离。公开publish两规则/渠道，首项收件人查询或ROLE解析异常，返回失败项且第二项合成SPI仍被调用；再验证实际async入口。现有S6只覆盖发送阶段。 |
| C08 / P2 通知整体保存无法清空 | `NotifyRuleController:364,368,372`把templateId=null/platform空归null后updateById；`astral-dao/.../SysNotifyRuleChannel.java:25-26,38`无ALWAYS，按MP默认NOT_NULL跳过，因此显式模板无法切回自动匹配，旧平台筛选仍在。校验`:280`允许null，入口可达。 | `NotifyRuleSaveDto:7-12,38-39`整体覆盖、null=事件×渠道自动；`AGENTS.md:213-214`同契约。复用真实MyBatis/H2保存已有绑定，公开update清空，再从DB及真实publisher验证自动模板/平台结果；不要只捕获Java对象。 |
| C09 / P2 邮件模板降级选错渠道 | `MailServiceImpl.resolveTemplate:92-102`scene分支限定EMAIL，code fallback只按templateCode做selectOne。同code允许EMAIL/INAPP共存，可多行异常或把INAPP当EMAIL发送。 | 本方法`:88-90`EMAIL解析合同及`MessageTemplateSaveDto`多渠道合同。真实mapper+公开sendSystemAlert/发送入口，以旧无scene的同code两渠道/仅INAPP数据验证仅EMAIL或明确缺失；用合成SMTP边界。 |
| C10 / P2 邮件/短信权重可放大至OOM | `MailServiceImpl:175-183`、`SmsServiceImpl:117-125`按正Integer权重逐次add再shuffle。保存控制器、实体和DDL均无上限；高权重在发送一个消息前即分配O(sum weights)引用。 | `docs/CODING_GUIDE.md:145-152`有界资源；公开加权发送合同应保留故障转移及不重复供应商。改用不展开的加权选择，公开合成SPI以极大合法权重完成有限选择并验证候选最多一次；不要真的尝试OOM作为RED。 |
| C11 / P2 已停用告警渠道仍发送 | `astral-monitor/.../alert/AlertEngine.java:88-130,159-176,227-235`仅查启用rule，对channel只判断非null，未读enabled；`AlertChannelMapper`是无额外过滤的BaseMapper。后台确实持久化enabled。 | 启用/停用控制字段`AlertAdminController:187,226`的用户可见语义。公开evaluate+命中rule+enabled=0渠道，合成Email/Sms/Webhook边界无发送，并约定审计/冷却表现；手动testChannel是否允许单独保留其明确测试语义。 |
| C12 / P2 局部更新回放旧状态 | `SequenceConfigController:253,269-299`把遗漏字段从existing补齐后全部SET；`AlertAdminController.updateRule:357,383-385`在未传enabled时SET旧enabled。并发停用可被仅编辑别项的请求覆盖。告警channel的相同问题已修，勿重开。 | `AGENTS.md:369-371`及局部更新“未提交保持”。真实mapper控制读写交错，遗漏enabled/隐藏序列字段时不出现对应SET，另事务已提交值保留；已提交字段仍可按接口语义清空。 |
| C13 / P2 序列配置缓存提交前失效 | `SequenceConfigController.update:251,320`事务提交前evict；`GeneratorFactory:73-84,339-342`缓存60s。间隙中的取号可重新缓存旧启用/步长配置，保存成功后仍用旧值。 | factory即时配置生效合同；复用N2-F2真实事务/缓存夹具，保存未提交→并发取号填旧缓存→提交→再次取号观察新值/停用。现有controller测试验证调用evict不证明时间正确。 |
| C14 / P2 统计缓存键/查询不一致 | `StatReportService:98-100,156-175`cache key trim ut，loader仍传原ut；`:207-209,429-436`SQL绑定原ut。`StatReportController:49-54,63-70`原样转发，无前置归一。`android`/` android `共享key却查不同数据，先访问者污染60s结果。 | service`:40-41,74`统一归一合同。公开overview/trend两种调用顺序均给相同正确结果；mapper执行实际参数，保留version/all行为。 |
| C15 / P2 系统内存指标实际为JVM堆 | `SystemMonitorServiceImpl:45-60`把heap.max/used写入totalMemory/usedMemory/availableMemory；`SystemMonitorDTO:8-31`另有heapUsed/heapMax却未设置。dashboard`:373-380`将其显示为系统内存，另有独立JVM堆卡。 | DTO系统指标合同、SPEC体验真实性。注入可区分的系统/JVM采样值，通过公开getSystemInfo和DTO验证字段语义与不可用值；不伪造0为健康。 |

### C2：AI知识域的独立未收口项

本节不涉及审批、用量、Provider或SQL。已重新读`AiKnowledgeService`相关完整方法及控制器/DTO，旧knowledge-01只用作定位。

| ID / 优先级 | 当前确认与影响 | 最小关闭证据 |
| --- | --- | --- |
| C16 / P2 检索/列表无界 | `AiKnowledgeService:414-415`一次读绑定源全部chunk正文，`:421-430`内存排序完成后才裁topK；`:159-163`列表取全部文档content，controller`:58-60,161-164`还逐源调用用于计数。单文档400k但源内文档数无界，每次聊天可加载整库。`AI_MODULE_DESIGN:1351`允许关键词实现，不要求特定向量库；`CODING_GUIDE:145-152`要求有界资源。 | 公开search的大量合成文档fixture，保证有界候选/分页且结果排序确定；不能用任意LIMIT导致末尾唯一相关文档静默丢失。来源列表用聚合计数和分页，避免加载正文。 |
| C17 / P2 已配置最低相关度无效 | controller`AiAdminAgentController:348-358`保存minScore，DTO`AiAdminDtos:468`明确“最低相关度”；search/rank`:382-492`从不读取binding.minScore，所有一个词命中的chunk进入候选。默认30与DDL0.2也未统一语义。 | 公开保存绑定→search：相同词命中在阈值两侧正确过滤，不同source按各自binding过滤，topK/citation仍受上限；明确与存量值兼容的分数尺度，不只删除配置控件。 |
| C18 / P2 保存文档静默截断 | `AiKnowledgeService:193`直接截content前400000字符，controller`:135`返回“已保存并完成索引”。DTO文档content只有@NotBlank，调用者不知正文丢失；hash以截断结果计算。 | `SPEC:13,21,35`错误反馈与数据保全。公开保存超界正文明确拒绝且原文档/索引不变，或明确产品允许的告知机制；边界内内容/hash完整。不要把有损成功作为容量保护。 |

索引任务另有明确规范缺口：`AI_MODULE_DESIGN:1351`要求任务可重试、可取消，当前controller仅sync和单job GET，syncSource在一个事务同步跑全部文档（`:244-294`），RUNNING未在处理中提交、没有cancel入口。部分失败最终SUCCEEDED表达是否应引入PARTIAL需确定对外状态合同，不能仅凭失败计数宣称全部成功是业务正确。`search`未检查source/doc READY，规范仅允许完成且授权版本参与；需用公开可达的未完成文档状态夹具确定影响。以上尚缺完整运行路径，列为待实施/定向证明，不冒充已观察故障。

### C3：仍需有界验证的高价值候选与已知差异

| 模块 / 候选 | 当前证据与所缺关闭条件 |
| --- | --- |
| monitor可选启动 | `AlertEngine:52`只在stat.enabled=true创建，`AlertAdminController:53-61`无相同条件且强依赖它；`application.yml:196-197`支持STAT_ENABLED=false。未找到另一定义替代该依赖，但本轮未启动context；以真实组件+mapper替身的ApplicationContextRunner证明false配置可启动并符合告警可用性约定。 |
| token清理/全端重置 | `TokenServiceImpl:268,296`仍对整个store作无界searchData，`:279`将timeout=-1当过期，`:305`吞单条logout失败。需真实SaTokenDao替身公开clean/revokeAll验证永久会话哨兵与失败结果，且分批有界；默认有限timeout下不声称已误踢永久会话。C19前述单会话撤销失败为另一个已确认项。 |
| 系统采样任务资源 | `DatabaseMonitorService:43-76`、`RedisMonitorService:35-68`每次起virtual-thread probe，Future.get(3s)只限等待，无可见认领上限/取消。驱动本身可能有deadline，故需阻塞替身并发公开getInfo证明残留数量，再定是否修复；不连接真实DB/Redis。 |
| 统计采集异步ack | `AppStatController:43-48`先调用void @Async再success，`StatIngestService:75-99`在worker因信号量/异常丢弃。必须明确best-effort ack还是可重试接收合同，再测真实async代理饱和；不假设每条埋点必须持久化。 |
| 配置/观测文档 | `application.yml:251-258`logging/pattern缩进在management下，标准logging.level/pattern.console未配置；`AlertEngine:333`normalizeMetric仍用默认locale（tr-TR小写i会错）。数据库示例仍推`astral.database.*`但AGENTS指定spring.datasource。补实际binding/合成locale验证和对应文档纠偏，不以“文档写了”当运行证据。 |
| QT/CMS/插件剩余领域 | backend-domains最后仅对十项修复闭环，其余不少条目仍为模式读。Qt邮箱直接修改`QtUserService:457`未有二次确认的策略约定；CMS详情每访问`views=views+1`（Post:226/Page:150）是写放大事实，严重性需负载证据；不能把二者直接当已证明漏洞。插件生命周期其他bulk、CMS权限/公开输出与元数据一致性需领域复核和公开测试。 |
| AI配置发布不变量 | draft修改/发布以及绑定替换为先读状态再写，当前表无版本CAS；controller有requireDraft正向门禁，不能称完全未保护发布版本。需要两事务公开发布与更新/绑定竞争证明冻结快照、指针及引用完整性；知识、模型/route删除引用等旧provider报告候选不能由Provider凭据GREEN顺带关闭。 |

### C4：范围与最终门禁证据缺口

依据`SPEC.md:11,27,30,34-39,61`，实现缺陷关闭之外仍有以下具体交付工作。这里的“未见”限定于本轮已盘点的两日audit和target日志，不等同断言历史上从未有人读过。

| 范围 | 现有证据 | 尚需收口 |
| --- | --- | --- |
| `cloudflare/storage-worker`、`telegram-server` | Git自有文件含worker.js/worker.security.test.mjs、server.mjs/worker.mjs/server.test.mjs及部署文件；两日audit除SPEC范围声明未见该领域独立报告。 | 请求鉴权/HMAC、upload/callback/origin/cache、删除/重试和上游限额的语义审阅，现有合成测试与Java契约对齐；不发真实Telegram/对象存储请求。 |
| 部署/脚本/架构文档 | `deploy/docker-compose*.yml/update.sh`、`scripts/pack.*、patch-notice.py、gen-plugin-schema-json.py、FlywayRename.java、probe.bat`、根run脚本均在受版本控制范围。 | 逐项覆盖与破坏性操作/工作目录/环境/打包范围审阅；有风险脚本仅合成临时目录验证。未发现受版本控制的.github工作流，不能虚构CI绿灯。 |
| 管理前端全域 | 当前110 Node回归通过；类型日志无诊断，Lead记录exit0；lint0错/2warn；build成功。Form及10调用者报告主要证明类型/运行等价，storage8源有专项覆盖，ToolPanel格式修复另有报告。 | 全导航/权限/加载失败/表单/多端范围矩阵仍需补齐；`target/takeover-frontend-format.log`2026-11-08 10:54仍41文件失败。两个Hooks警告需按实际生命周期判断，不能仅无error即宣称全部UX通过。 |
| Blog当前候选 | 历史118源是分阶段累计覆盖，不是同一人独立全读；当前server48、React/DOM36均通过且生产构建成功。 | 最终当前diff两轴核对；真实IME/物理wheel/layout/download/cookie/cross-tab仍未接受。SPEC明确扩展未就绪禁止手动CDP/Playwright，不以这些环境限制阻止源码/DOM必要修复。 |
| Java最终门禁 | 新专项真实MyBatis/H2、Redis、PG与传输loopback有有效证据；`audit-server-spring7-cache-...0636-tests.log`18个server测试及reactor SUCCESS，但`:94`明确Code generation skipped。`audit-focused-...0510-domain-storage-green.log`plugin104项通过后server编译失败，后续server修复不反向把该整日志改称成功。 | 最终当前schema generator实际生成/安装、不跳codegen的全部reactor test/build；统一Spotless、Checkstyle、SpotBugs，实质发现处理。不能把挑选-Dtest的reactor成功当全部测试通过。当前未见满足此组合的最终新日志。 |
| 最终交付与Git | HEAD仍a39a3f3，303跟踪修改及新增文件尚未形成审阅后的提交。已有两轴均是明确范围的定向结论。 | 固定基线→最终当前提交两轴复审、最新缺陷账本和范围覆盖、实际本地commit记录；禁止push/发布。 |

### C5：不能继续当作未修事项的历史结论

- **S3公共HTTP/MCP/告警传输**：最新Standards追加已确认HTTP-TR-F1–F5关闭，`takeover-http-review-green.log:60-66,289-297,333-342,358`合计62项通过。DNS固定、解析纳入deadline、有界体积、错误凭据、IPv6元数据/公网精确网段和URL空白兼容已闭环；C04仅针对另一个storage gateway。
- **S4 SQL**：Standards已核AST-F1–F3关闭，`takeover-sql-columns-green.log`旧14+新28共42项通过。当前真实列白名单、表达式递归/限定、未知复合JDBC值拒绝存在，不能引用本报告最初关键词过滤快照重新报同一漏洞。MCP planHash当前已调用planFingerprint（`AiToolService:750-757`）；Lead正在R3验证，原“仅有三字段”不再是当前源码事实。
- **S7/S8/S9及权限、模板、迁移**：告警/Provider凭据保留清除、指标双溢出桶及路由pattern、序列类型/_id/Long.MAX_VALUE及真实PG UPDATE RETURNING、菜单遗漏字段、权限domain、角色通知、schema默认INPUT均有定向报告/日志；S5/S6/N2/M7本报告已关闭。新的C07/C08/C11/C12/C13不是这些已关闭原路径的重复报告。
- **旧storage残余#1“Provider HEAD/DELETE无请求超时”不成立**：S3/Oss签名request有10s、测试8s；Cos/Upyun HEAD/DELETE有8s，已复读具体builder。旧#16“changeVisibility整行竞争”也不成立，当前`StorageFileService:690,716-724`共锁+status/version条件更新且检查行数。已有CUSTOM_HTTP远端不可删除是明确产品边界，不擅自增加不存在的第三方API。
- **旧知识K05的“捕获DB异常便可提交删除后的半索引”不能直接采信**：PostgreSQL SQL异常会中止当前事务；需区分应用异常与SQL异常、实际代理事务及rollback行为后再定。全量SQL异常恢复不是现有源码已证明的数据丢失事故。旧K08“必然HTTP500”也需真实advice解析，已有IllegalArgumentException映射，不能照抄。
- **旧博客Tab缺陷已过时**：当前browser driver已有普通Tab/选区缩进、Escape后native跳焦断言；未执行真实浏览器是证据边界，不能将已修源码合同继续列为功能删减。readAck客户端本地语义、Worker共享HMAC可信主体、单实例设计不自动升级为越权漏洞。

本节是可执行的剩余清单及证据盘点，**没有宣布全项目或任一未验证域验收通过**。C01–C19的公开回归建议未执行，不能计入GREEN；只有明确引用的既有日志保留其原验证范围。

## A7：F01–F08修复复核与剩余前端边界（2026-11-08再次只读）

本次重新读取`useAiChat.ts`15:44:23、`ChatPanel.tsx`15:34:31、`RequestPanel.tsx`15:36:32、`sse.ts`15:40:09、`api/ai.ts`及`AiAdminChatController`15:38:39。**以下替代A6对原触发路径的待修状态；不删除历史结论，也不宣布AI整体验收。** 仅阅读指定源码、测试和既有日志，无构建/测试/服务/网络执行。

### A7.1：原F项逐项状态及证据范围

| 原项 | 当前源码结论 | 已有测试能证明与不能证明的范围 |
| --- | --- | --- |
| F01 | 跨view旧send/create/list/reload/remove回写已有guard；sendRef同步认领阻止同view发送重入，detach复位loading。原send(A)迟到覆盖B路径已修。 | `target/takeover-ai-ui-races.log`第3项用真实hook+React+Axios adapter证明迟到send不会watch旧7号请求、B消息不变。未覆盖create/list/delete反序及同view加载→发送，后者见A7-N1。 |
| F02 | stop捕获view/request并在await后复核；无requestId阶段记录cancel intent，回包后调用owner cancel；正常cancel返回非CANCELLED时继续接收真实终态。原误停B/伪造成功终态路径有源码修复。 | 第3项证明取消响应跨A→B→A不abort新订阅；未测试POST建请求阶段停止、cancel失败、返回SUCCEEDED三条分支，不得将3 GREEN扩展为它们已执行。 |
| F03 | poll/watch均携view，`handleEvent:251-254`丢弃已消费正序号；旧A轮询跨离开再返回不再被接纳。 | 当前3项没有强制SSE失败后悬挂旧poll，也未重复输送sequence；源码修复成立，定向运行证据尚缺。 |
| F04 | 管理和App控制器现从升序rows尾部取size条，cursor取page.first，limit最多MAX-1以留lookahead。原漏最新/错cursor已修。 | `AiApprovalLifecycleTest.messagePagesKeepLatestAnswerAndUseOldestDisplayedIdAsCursor`通过真实MyBatis/H2+App公开controller验证35条取6..35、下一页1..5；`takeover-ai-messages-green.log`1项0失败、后续ledger日志38项通过。管理controller相同代码已读，但该分页测试实例化的是App controller，不是两端各执行。 |
| F05 | RequestPanel新增ADMIN_CONFIRM且请求/call均待批、hasPerm审批权的确认/拒绝按钮；它位于正文权限区域，USER_CONFIRM不能代批。ChatPanel改用统一usePerm。原管理员无UI入口已修。 | 3项前端测试只挂hook，没有挂RequestPanel/ChatPanel，故按钮权限组合、实际点击与刷新未获DOM证据；新增响应刷新存在A7-N2。 |
| F06 | `aiChatApi.cancel`已指向`/admin/ai/chat/requests/{id}/cancel`；cancelOwn声明assistant/qa use并调用`requestService.cancel(...,false)`，由requireOwned守所有者。全局admin cancel保留独立权限。 | 第3项adapter匹配任意`/cancel`后缀，未断言owner具体路径；现有Java生命周期测试直接调用request service，未找到新cancelOwn控制器的鉴权集成测试。源码满足原owner缺口，不能据此声称拦截器权限已执行验证。 |
| F07 | loadMessages在同request中选最新role=ASSISTANT作为fallback，不再使用TOOL id。原伪助手路径已修。 | 现3项history为空或仅一个ASSISTANT，未提供USER/ASSISTANT/TOOL待批恢复fixture；应补实际恢复后只存在真实助手ID。另首次terminal事件批次丢弃见A7-N3。 |
| F08 | 先将buffer+chunk合并再CRLF归一，跨chunk的CRLF正确衔接；增加1MiB缓冲限制。 | `takeover-ai-sse-red.log`有原分块空结果RED，`takeover-ai-sse-green.log`及ui-races第1项GREEN。原CRLF边界缺陷关闭；测试固定一种多chunk拆分，不代表每个字节切分/大帧已验证。 |

`target/takeover-ai-ui-races.log`只有3项、0失败、0跳过，内容分别是CRLF分块、审批后新assistant ID正常完成、迟到send/cancel不替换当前会话；它是真实前端模块的合成HTTP/DOM测试，**不是浏览器/真实权限/真实后端验证**。

### A7.2：当前仍可触发的三个遗漏（P2）

- **A7-N1：同view历史加载覆盖新发送。** `loadMessages:418-440`的guard仅判断view；`ChatPanel:646,672`不在loadingMessages时禁止输入/发送，hook`:588`也不检查恢复是否完成。操作：select(B)，让B历史HTTP挂起；发送新问题，POST成功并开始新请求；再返回旧历史。`:431`覆盖刚新增的用户/助手消息，若历史latest仍活跃，`:438-440`还覆盖runningRef并订阅旧请求。最小修复二选一：恢复期间hook与UI共同禁止send；或消息加载记录独立operation generation、send使旧加载结果失效且保留已展示历史。不要只增加view比较。回归用真实hook延迟messages GET，与send POST交错，断言新消息、新requestId和唯一订阅保持。
- **A7-N2：新管理员审批完成能重新打开旧详情。** `RequestDetailBody:352-358`await审批后调用闭包onChanged，父层`:331-332`把它绑定当时focus.id；`openDetail:116-127`本身也无请求generation。审批A挂起→关闭或打开B→A响应，onChanged又openDetail(A)，打开旧modal/覆盖B。仅发生在显示层，不应误报成批准了错误call。修复父层用selectedId+epoch持有者约束详情读/审批后刷新，modal关闭也使epoch失效；旧mutation可记录其自身成功，但不得改变选择。挂真实RequestPanel，用延迟approve/detail分别覆盖“关闭”和“切B”，断言不重新打开A、不替换B。
- **A7-N3：消息快照与终态事件之间的竞争丢最终答案。** `loadMessages:418`获取PENDING/空内容快照；后端随后完成；`:434`events返回包含message.completed的terminal=true批次。`:436`只处理非terminal分支，整批事件被丢弃，仍显示旧PENDING/空正文，直到再次刷新。与F04的31条分页无关。修复消费该首批并同步cursor/助手身份，或确认terminal后重新读权威消息；切勿盲目把旧快照当终态内容。fixture返回PENDING11后给completed11+terminal=true，断言无需第二次reload便显示最终正文、run停止；另覆盖非terminal首批不重复delta。

以上三项已发Lead。未扩展其他UI，也未重复Provider或币种问题；A1–A5后端仍在变化，不能由本节代为关闭。

## D：C01–C19当前有效性与最小实施次序复核

本次逐项重读当前调用链、实体和相关配置；**19项仍有效，尚无本轮修复实现可关闭**。C节原源码位置/具体契约继续有效。下面补充可直接实施的最小约束，避免只有风险名称；所有回归建议均未由reviewer执行。

### D1：优先四项P1

| ID | 当前仍成立的关键点 | 最小实施方案与验证 |
| --- | --- | --- |
| C01 账户旧状态覆盖 | Profile改密/setup/enable仍以完整User updateById；管理员重置密码同样传完整实体。User无@Version。 | 用白名单wrapper分别只SET本操作字段；密码只BCrypt一次并以读取的旧hash作CAS，TOTP验证后以原secret/启用状态作CAS；0行明确冲突，不能报成功。管理员重置仍仅改凭据/临时标志，不回写status/type。totpDisable虽已白名单SET，依赖旧凭据校验也应CAS。真实MyBatis拦截读后另事务改禁用/凭据，验证没有旧值复活。 |
| C02 存储诊断泄露 | HttpUploadGateway:671先截140字符，:674才替换完整secret；probe还保存/返回该内容。 | probe错误只返回固定安全码和服务器生成说明，禁反射任意上游正文。仅把脱敏提前仍不能证明编码/部分回显安全。公开probe→HttpStorageConfigService合成跨截断长secret/编码回显，同时检查API与lastTestMessage，真实上传safeCode路径保持。 |
| C03 目录删除产生孤儿文件 | delete只检查file，不看operation；旧登记只锁uploadId，新commit/recovery直接使用历史folderId。 | 统一目录锁键/顺序；新operation创建、claim接收、commit与recovery都在短事务内重新确认目录，delete同锁检查CREATED/RECEIVING/SENDING/REMOTE_SUCCEEDED/UNKNOWN_OUTCOME，存在在途拒删。旧callback/browser最终登记亦同锁并重查；旧票据期间的目录占用及远端成功后的异常需保留可恢复记录，可与C05持久账本合用，不能依赖扫描Redis所有票据或直接丢弃远端结果。并发delete/register和REMOTE_SUCCEEDED恢复断言无失效folderId、无不可追踪成功对象。 |
| C19 撤销误报成功 | Token scan DAO异常→空列表；前20000键未命中→null→成功。 | 最小修复让查询失败明确失败；未命中但扫描达到上界时报告“无法确认”，仅已证明完整检索且无目标时幂等成功。长期可加入按指纹索引的受保护会话解析，但不必须先重构才能阻止假成功。保持指纹接口、不吐原token、不取消扫描上界。真实service+合成DAO验证异常/命中/截断未命中/完整无目标四类。 |

### D2：资源、投递和数据一致性P2

| ID | 当前状态与精确实施约束 |
| --- | --- |
| C04 DNS截止 | HttpUploadGateway:381仍同步resolver，deadline从:404/431才开始。把总预算从DNS前计时，resolver用有限专属executor/拒绝队列，超时后检查迟到结果不可连接；不得每请求new线程或以取消Future假定DNS已停。公开send/probe阻塞resolver测及时返回、迟到0连接、残留并发有界。 |
| C05 统一每日消费 | UploadPolicyService仍数现存file，旧登记不取新quota锁；StorageFileService:794物理删行可顺序退款。建立按uploadId唯一的持久旧链消费记录（初始folder/actor/day固定），旧成功登记+消费同Tx；所有新旧链同folder:actor/day锁并用同一usage口径，新operation HELD/CONSUMED及旧消费均计入，移动/删除/重放不退款、不双计。可回填仍存在的旧文件，迁移前已物理删除的历史不能凭空恢复，应诚实记录兼容边界。限额1并发旧旧/旧新、删除后重传、移动及重复回调验证。 |
| C06 意图2/64上限 | create仍count后insert且无共锁。最窄全局intent事务锁围住reclaim、幂等复查、两级count和insert，避免两种上限分别锁导致漏窗口；只做短本地事务，不跨DNS/上游上传。不同key竞争最后名额，重复key返回原结果而非额外占用。 |
| C07 通知解析隔离 | NotifyPublisher:111-123的渠道/收件人/ROLE解析仍在deliver catch外。分层包住每规则channel读取、每channel recipient解析，记录该层失败PublishResult后继续其他健康层；最外规则列表读取失败不能伪造“无规则”。公开publish与真实async代理各验证第一项失败、第二项合成SPI仍被调用。 |
| C08 全量通知保存清空 | NotifyRuleController:364/368设null，:372 updateById在实体NOT_NULL下跳过。已有行用明确wrapper SET templateId/platform（包括null）及其他完整保存字段，按id/ruleId约束并检查行数；无需全局把所有实体字段改ALWAYS。真实MyBatis save→read→publisher证明显式模板转自动、平台清空。 |
| C09 邮件fallback渠道 | MailServiceImpl:101-102 templateCode查找仍缺EMAIL。与scene分支统一channel=EMAIL，保持legacy code兼容；不要用LIMIT1隐藏跨渠道错选。真实mapper加同code EMAIL/INAPP和仅INAPP，通过公开发送观察选中或明确缺失。 |
| C10 权重内存放大 | Mail/Sms仍把正Integer权重展开成sum(weight)引用。改成不放回的加权选择：long累加剩余权重、按随机阈值挑一次并移除候选；内存O(渠道数)，避免int溢出，保留失败切换和每候选最多一次。极大权重的合成SPI验证有限资源，不运行真实OOM负对照。 |
| C11 停用告警仍发送 | AlertEngine.fire:159-176只检查channel非null。自动evaluate分支在投递前检查enabled，记录明确“渠道停用”处理结果并明确冷却计数；手动testChannel的测试语义单独保留。公开evaluate命中规则+停用渠道，所有合成发送边界0调用。 |
| C12 遗漏字段回放 | SequenceConfigController:269-299把旧值补回并全SET；AlertRule enabled相同。对遗漏字段不发SET；序列需要持锁读当前组合以验证step/min/type等跨字段约束，响应返回最终持久值，显式清空与遗漏用现PatchValues合同区分。不要用实体@Version作所有控制器同时升级的替代。真实mapper读/写交错验证别人停用不被覆盖。 |
| C13 序列缓存提交前失效 | SequenceConfigController事务内:320 immediate invalidate，factory仍TTL60s。将所有配置写入口失效统一挂真实事务afterCommit，无事务时立即失效；同事务回滚不宣称新配置生效。复用模板缓存真实事务夹具：未提交期间另线程缓存旧值，提交后首个取号必须新值/停用。 |
| C14 统计键/SQL不一致 | overview/trend keyPart trim ut而SQL原值eq。入口一次normalize ut，再同时用于key和所有loader/filter；维持all/null/version语义。两个调用顺序的公开overview/trend合成mapper参数与结果一致，避免只断言key字符串。 |
| C15 主机内存标成JVM堆 | SystemMonitorService:53-60仍把heap max/used填系统字段，独立heap字段未填。通过com.sun.management.OperatingSystemMXBean获取主机总/可用内存，JVM heap分别填自己的字段；不支持时用明确未知值而非假0健康。可注入两组不同采样值，公开DTO断言语义。 |

### D3：知识域P2（不含Provider、币种或A生命周期）

| ID | 当前状态与精确实施约束 |
| --- | --- |
| C16 检索和列表无界 | search仍读取全源chunk正文、内存全排序并N+1载文档；listDocuments仍读全部content。保持当前关键词语义的最小第一步可keyset分批读取候选并维护有界top-K heap、按批join标题/READY信息，不把所有chunks/doc缓存保留；这只保证内存，最终扫描工作量还须候选索引/查询预算，不得声称已全链有界。可用数据库全文/关键词候选排序后top-K并建相应索引，但须明确词法兼容，不能任意LIMIT漏末尾唯一相关文档。列表改分页且不SELECT content，计数用聚合。大量合成chunk验证末尾相关命中、峰值批大小、稳定排序。 |
| C17 minScore无效 | controller仍保存minScore，search/rank不读；rank分数是覆盖率×100+加权，schema默认0.2、服务默认30。按每binding阈值过滤后再总top-K；明确一种分数尺度并用新迁移/版本语义兼容旧值，不通过猜测“≤1都乘100”误改用户配置，也不删除控件逃避实现。公开保存绑定→search阈值两侧、不同source、默认/历史值验证。 |
| C18 静默截断正文 | saveDocument仍substring到400000，DTO仅@NotBlank。服务层在任何文档/索引写前拒绝超限（DTO加@Size作早期错误，但不能仅靠controller），说明当前上限；哈希对完整获准内容计算。公开保存超界并验证原文档/索引不变，400000边界内完整保留。 |

实施次序建议：D1四P1先行，其后C05/C06/C07/C08/C10/C11（消费/资源/副作用），再C04/C09/C12–C18。各项只使用合成边界与隔离数据；没有把旧报告或未运行测试当作关闭证据。本次只追加本报告，未修改实现、测试、schema、迁移，也未执行提交。
## E：C3剩余插件、QT/CMS、网关与部署的有界补审（2026-11-08）

### E.1：核心结论与证据口径

本轮对插件生命周期、QT媒体/签到/包头探测、CMS树与可见性、Cloudflare/Telegram网关、部署CI及打包入口补读。确认的优先问题是：禁用QT误封其他App接口；重复媒体完成可删除当前文件；Worker重放上传可用第二份内容覆盖第一份文件的边缘缓存；打包排除遗漏部署凭据位置；官方Nginx复制方案包含非法指令上下文。其余确认项与最小关闭证据如下。Cloudflare的300秒撤销与7天缓存文档相互冲突，单PoP purge不能证明全局秒级撤销，应先统一承诺，再测同一预算。

本节只记录已向Lead发送的发现；没有继续扩大搜索。**“确认”指已复读当前源码并建立具体触发链，不表示本轮执行过复现或回归。** 所有新增验证均为待执行建议。未运行构建、测试、服务或网络，未读取真实`.env`/凭据，未修改实现或提交。共享工作区仍在变化，位置对应本轮读取快照。Lead已告知C01与C02/C04有新增GREEN、C19正在修复，本节不重复判定这些路径，也不以先前D节静态快照否定后续修复。

通用规范依据为`docs/audit/2026-11-07/SPEC.md:11–12,21–22,27–30,34–39`：覆盖全部自有源码、历史报告仅作线索、保护现有能力与安全约束、使用隔离证据完成关闭。下表同时列出各域的更具体契约。

### E.2：插件生命周期——确认项

| ID/优先级 | 当前源码位置、契约与触发链 | 最小修复约束与关闭证据 |
| --- | --- | --- |
| E-P01 / P1 | `QtPlugin.java:52–54`将`/api/v1/app`整体声明为QT前缀；`PluginApiInterceptor.java:35–43`对禁用插件逐前缀`startsWith`拦截。禁用可选QT后，已启用CMS的`/api/v1/app/cms/post/...`及其他宿主App接口一起报PLUGIN001。与QT插件自身的路由归属、`CmsPlugin.java:43–44`只拦自身命名空间的隔离契约冲突。 | 根据真实QT控制器列出所属路由，前缀按路径段匹配；不以App根路径代表QT。实际QtPlugin/CmsPlugin+拦截器夹具：QT禁用时QT接口拒绝，CMS及宿主App正常；CMS独立禁用反向验证。 |
| E-P02 / P2 | `PluginRegistryImpl.java:88–90,109–111`先调用hook并变更内存态，再保存；`PluginStateRepository.java:43–60`把数据库写失败全部吞掉。启停可对调用者报成功，但重启恢复旧持久态。该repository首次插入还使用`MAX(id)+1`，不能用内存map并发安全推导持久写安全。 | 数据库错误必须可见；明确期望持久态与实际运行态，序列化同一插件转换，失败不能伪装成功。使用项目ID机制/原子upsert替代MAX+1。真实repository故障夹具覆盖写失败、两个首次保存竞争及重新加载；仅mock repository不能关闭此项。 |
| E-P03 / P2 | `PluginRegistryImpl.java:32–39`注册时`onEnable`暂时失败会立即持久化false，后续启动据此跳过启用。旧D-PL-01的手动enable/disable异常修复没有改变这一启动分支，不能据此宣称该历史问题全部关闭。 | 保留用户期望启用状态，单独记录启动运行失败，使下一次启动或显式重试可恢复；不要把瞬态失败当用户禁用。首次hook失败、第二次新registry启动成功的夹具，应证明没有写入持久false；同时保留手动失败路径原有回归。 |
| E-P04 / P2 | `PluginApiInterceptor.java:24,31–33,52–57`仅凭任意路径末尾`/enable`、`/disable`或`/toggle`就放行，未限制管理路径和方法。`AppCmsController.java:88,116`接受`{idOrSlug}`，Post/Page DTO仅限制slug长度，可创建slug=`enable`。禁用CMS后匿名GET该已发布内容绕过插件停用拦截。 | 豁免限定真实`POST /api/v1/admin/plugin/{pluginId}/enable|disable`等明确管理端点，不扩大到任意同名slug。实际路由+拦截器测试：禁用CMS后GET该slug拒绝，授权管理POST仍可重新启用。不要把此显示内容路径误报成绕过CMS编辑权限。 |

位置前缀：插件core文件均在`astral-plugin/src/main/java/com/astral/plugin/core/`；QtPlugin在同模块`com/astral/qt/`，CmsPlugin及CMS控制器在`com/astral/cms/`。

### E.3：QT——确认项

| ID/优先级 | 当前源码位置、契约与触发链 | 最小修复约束与关闭证据 |
| --- | --- | --- |
| E-Q01 / P1 | `QtMediaService.java:216–230,239–255,282–294,315–332`公开apply/complete重复处理同一uploadId时，旧URL等于新URL，仍调用`recycleOldFile`；`:517`起对Telegram永久URL解析publicId并申请删除。正常网络重试可把刚作为当前头像/封面保存的同一文件删掉，与`:216`“重复apply无副作用差异”的明确幂等契约冲突。 | 以稳定文件身份比较新旧引用，同一文件不回收；不能只靠新uploadId字符串判断。真实公开apply/complete连续调用两次，assert当前引用存在、当前publicId从未进入删除，异文件替换才回收旧文件。Telegram路径和其他存储路径分别保持原行为。 |
| E-Q02 / P2 | `QtMediaService.java:339–348`清除封面把picUrl设null后`updateById`；`QtLikePlaylist.java:26`无ALWAYS策略，当前默认NOT_NULL会跳过该列。因此seq推进但旧封面仍在数据库，与coverClear操作契约冲突。 | 使用受uid/pid/platform及有效行状态约束的显式SET NULL，并检查更新行数；不要全局修改实体null策略。真实MyBatis保存非空封面→公开clear→重新查询，证明pic_url为空且同步seq正确。 |
| E-Q03 / P2 | `QtDakaService.java:41–49,66–78`先确认今天未签到，再在写入今天记录前从今天查连续天数，必得0，正常连续签到始终发最低档；与服务Javadoc定义的连续签到分档积分不符。`:105–115`还用当月集合判断昨天，月初昨天不在集合时把已有连续签到显示为0。 | 奖励基于截至昨天的真实连续天数，并明确既有分档是否包含当天；显示连续天数直接跨月查询，不能用当月集合否定昨天。注入Clock或固定日期夹具覆盖连续7/30/365天边界、月初/年初、断签、重复当日不重复奖励。 |
| E-Q04 / P2 | `QtPackHeaderProbe.java:36–40,51,64,120–124`承诺仅取包头8KB，但`BodyHandlers.ofByteArray()`会完整消费忽略Range的响应，且NORMAL重定向没有逐跳目标校验；`QtSourceService.mergeArtifacts`允许管理员配置URL，create/update异步探测、publish同步探测均进入此链。确定的资源问题：远端忽略Range时内存读取无8KB上限。触发者需QT管理权限，不是匿名入口。 | 复用已有`BoundedHttpTransport`的总deadline、解析与字节上限；如产品需要重定向，应显式有限逐跳校验并保留合法下载重定向，不能直接禁掉现有能力。合成传输测试必须覆盖忽略Range的大体积响应、缓慢body、重定向目标策略，以及有效包头解析；仅纯字符串解析测试不能证明I/O有界。 |

本节QT位置前缀为`astral-plugin/src/main/java/com/astral/qt/service/`，实体在同模块`com/astral/qt/entity/`。E-Q04的内网访问影响还取决于明确采用的管理员URL策略；不能在未定该契约前升级成匿名SSRF结论。

### E.4：CMS——确认项

| ID/优先级 | 当前源码位置、契约与触发链 | 最小修复约束与关闭证据 |
| --- | --- | --- |
| E-C01 / P2 | `CmsPageService.java:133–164`只校验目标页；`:171–186`构造breadcrumb时读取祖先，只排除不存在/删除，不检查可见性。公开子页可以挂在private/hidden父页下，匿名详情返回父页id/title/slug；有效分享目标也会带出未授权祖先。违反`docs/CMS_PLUGIN.md:33–39`私密可见性及“分享只授权目标”契约。 | 祖先按请求者真实可见性过滤或在不可见处停止；分享token不可扩大为整棵祖先树授权。真实service/controller夹具覆盖公开子页+私密父页、管理员、分享私密子页+未分享私密父页，明确无祖先元数据泄露。 |
| E-C02 / P2 | `CmsShareService.java:174–209`及`CmsCommentService.java:182–218`用同一个`Map<Long,String>`合并文章和页面标题，先放post后放page。`docs/CMS_PLUGIN.md:14,83–85`规定各表独立`表名_id`序列与targetType/targetId，因此post.id与page.id相同是正常数据，混合列表会把文章标题覆盖成页面标题。 | 按targetType+targetId复合键或分表map查标题。创建同ID、不同标题的post/page及两种share/comment，经公开管理列表断言各targetTitle正确；只测试两类ID不同不能关闭。 |
| E-C03 / P2 | `CmsPageService.java:247–262`及`CmsCategoryService.java:201`起的checkParent仅走50层后直接成功，没有拒绝尚未遍历完的链。可顺序创建超过50层合法树，再把根移到最深后代，检查到不了根而允许成环，随后buildTree缺根导致子树消失。违反`docs/CMS_PLUGIN.md:224`后端兜底防环契约；无需并发即可触发。 | 使用visited集合检查整条祖先链，或明确深度上限且达到上限即拒绝；树写入与校验需同一短事务/共同锁避免并发A→B/B→A绕过。最小关闭证据先做51层以上根移动拒绝与树保持完整，再补两个并发重挂和删除/挂子节点竞争。 |
| E-C04 / P2 | `CmsCommentService.java:147–160`在submitLog超过1000时只删除过期记录，并无容量淘汰。一个窗口内持续新IP会保留全部key，且每个新请求扫描增长中的map。旧领域报告称“1000硬上界”并不符合当前源码；违反`docs/CODING_GUIDE.md:151`容量上限+过期要求。 | 使用有明确maximumSize和expireAfter策略的有界缓存，单IP计数原子更新，明确满容量下的限流语义。大量合成IP与可控时钟验证条目上限、过期、同IP并发限制及处理工作量，不能用真实OOM作为测试。 |

本节服务位置前缀为`astral-plugin/src/main/java/com/astral/cms/service/`。未找到能覆盖这些新增公开触发链的现有定向证据，不以旧报告“CMS已审”替代本轮关闭。

### E.5：Cloudflare上传与授权缓存——确认行为及契约待决项

| ID/优先级 | 当前源码位置、契约与触发链 | 最小修复约束与关闭证据 |
| --- | --- | --- |
| E-W01 / P1，确认 | `cloudflare/storage-worker/src/worker.js:373,384–430`将当前上传body及locator按callback返回的publicId/version预热。后端`StorageFileService.registerFromCallback:177–183`发现同uploadId已存在时返回原登记结果，不校验这次locator是否同一份文件。重复有效票据上传另一份字节后，数据库仍指原文件，Worker却把第二份字节写到原文件URL缓存，违反`DEPLOYMENT.md:321`“预热不影响正确性”和登记幂等契约。 | 预热必须使用权威登记结果的文件身份，能确认当前body/locator与登记一致才允许落到该publicId/version；重复请求不得以新body污染旧身份。完整避免重复Telegram副作用还需发送前持久claim，不能声称仅跳过预热已解决远端孤儿。真实worker.fetch+合成Telegram/后端幂等callback+内存Cache，复用同ticket上传两种body，原永久URL始终为第一份；callback失败不得预热。 |
| E-W02 / P2，行为与文档冲突确认；目标撤销预算待决 | `worker.js:43–52,554–557,579,600–624`公开缓存7天、元数据1小时，/p命中字节先返回，purge使用当前`caches.default.delete`，不能清除其他PoP。`README.md:41–42`承诺300秒撤销；`DEPLOYMENT.md:320,348`又说明7天TTL、旧链接过期失效，同时声称主动purge把撤销收敛到秒级。这些承诺不能同时成立。当前实现的7天残余与后者部分说明一致，不能忽略该文档而直接把所有旧缓存访问判作新的无条件越权。 | 先明确并统一实际撤销预算。若承诺短时间撤销，内容字节缓存可长，但每次对外返回要按预算重验当前授权/版本，元数据TTL与客户端max-age同样不能超预算；仅换新版本URL或向一个PoP发purge不足以关闭。两份独立Cache模拟PoP，均预热后撤销，只purge其一；在约定时限后两边旧URL均不可继续放行。若产品接受长残余窗口，必须一致说明，并删除不能兑现的全局秒级保证。 |

本轮没有改动CF或服务器版本，也没有访问Cloudflare。`worker.security.test.mjs`的10项主要是源码正则断言，不足以证明重放、独立PoP、缓存时间或授权时序。不得把E-W02误套到明确不使用共享边缘Cache的telegram-server。

### E.6：打包、部署与CI——确认项

| ID/优先级 | 当前源码位置、契约与触发链 | 最小修复约束与关闭证据 |
| --- | --- | --- |
| E-D01 / P1 | 仓库打包脚本`pack.sh:57–84`、`pack.bat:51`按工作树tar归档，仅排除部分已知本地配置。文档支持的`telegram-server/.env`、`cloudflare/storage-worker/.dev.vars`、`telegram-server/cloudflared/*.json`等不在排除范围；gitignore对tar无效。存在这些正常部署文件时会随源码包外发。此项没有检查实际文件是否存在，更未读取任何值。 | 优先从明确源码清单打包，并保留所需模板和已修改源码；或落实统一凭据/环境/构建目录排除，输出置于源码树外。临时隔离目录中放无害sentinel，执行两种打包入口，只检查归档成员：源码与模板存在，`.env`/`.dev.vars`/cloudflared凭据、依赖目录和构建输出不在包内；不拿真实部署目录做泄露验证。 |
| E-D02 / P1 | `deploy/nginx/10-astral-sites.conf:115,210`在location内include`00-astral-http.conf`，但原文件`deploy/nginx/00-http-level.conf:15,18,22`包含仅允许http上下文的limit_req_zone/map。`DEPLOY_GUIDE`所示复制方案据此会在nginx -t失败。旧`deploy/nginx-reverse-proxy.example.conf:47`也把limit_req_zone放在server内，不能作为可运行回退方案。 | 将纯add_header片段与http级zone/map分开，location只include可合法出现的headers片段，更新复制路径与样例。用隔离配置及临时测试证书执行nginx -t，覆盖官方两个站点和保留的旧样例；不启动或reload真实服务。 |
| E-D03 / P2 | `.cnb.yml:44–87`分别生成各服务的日期-SHA tag，后续path过滤只发布发生变化的服务；`deploy/docker-compose.registry.yml:21,26,30`却把同一个`${TAG}`用给三个镜像。`deploy/update.sh:13–38`支持指定CI日期tag更新/回滚。仅前端提交时该tag的backend/blog未生成，官方固定tag更新将pull失败。 | 为每次可部署版本生成完整release manifest，引用每个服务既有不可变digest/tag；或统一tag发布三个服务。不能只证明latest可用。模拟仅前端/仅后端/仅博客改动，验证所生成部署清单的每个镜像身份存在且可回滚；使用假registry/命令记录即可先做离线回归。 |
| E-D04 / P2 | `astral-blog/Dockerfile:38–41`把NEXT_PUBLIC_SITE_URL与NEXT_PUBLIC_ADMIN_URL默认编译为localhost:3001/3000；官方`deploy/docker-compose.yml:119–122`及`.cnb.yml:79–83`博客构建只传CMS_API_BASE。`src/lib/site-url.ts`供robots/sitemap等使用，`src/components/session-menu.tsx:20`使用后台URL，故官方产物会带localhost链接；运行时只设CMS_API_BASE无法改浏览器bundle。 | 把两项公共站点URL纳入compose与CI构建输入并写明其构建时语义，或引入可验证的运行时公共配置，不把生产域名写死进源码。合成域名配置矩阵与构建产物/公开sitemap、后台链接断言均应证明没有localhost回退；须由Lead执行构建验证，本轮未构建。 |

E-D01故意仅记录凭据文件路径模式，未验证工作区真实部署文件；这是确定的归档规则遗漏及其条件触发链，不是宣称已经发生泄露。E-D03不把每次CI都发布所有镜像定为唯一方案，关闭目标是官方部署指向一个完整且可回滚的镜像集合。

### E.7：覆盖记录、候选边界与既有GREEN能证明的范围

| 范围 | 本轮读到的入口与深度 | 证据边界 |
| --- | --- | --- |
| 插件 | core注册/持久化/拦截/控制器等六文件、AstralPlugin SPI、QtPlugin/CmsPlugin、PluginRegistryImplFailureTest。 | 已建立上述四条链；不是插件任意第三方实现的生命周期验收。 |
| QT | QtDakaService、QtGithubAccelService、QtPackHeaderProbe、QtMediaService及相关DTO/mapper/entity，QtSourceService创建/编辑/发布与探测相关路径；结合既有控制器、版本匹配和反馈报告。 | 本轮只落盘已发送的媒体/签到/探测问题；没有扩大到新的账号或全部收藏同步并发审计。探测目标策略影响为候选，8KB资源合同违背为确认。 |
| CMS | 八个service；其中Post/Comment/Share按调用链定向读取，其他相关服务通读；AppCmsController、Page/Category/Export管理入口、DTO/entity/schema及CMS_PLUGIN文档。 | 可见性、树、复合身份、限流四项确认；不把片段阅读描述为所有CMS方法逐行完成。未增加未验证的slug并发、导出内存或发布状态候选。 |
| Cloudflare | worker.js全文件、worker.security.test.mjs全文件，README与DEPLOYMENT相关授权/缓存契约，simulate-worker.py。 | 纯源码断言不能替代真实worker.fetch行为；simulate-worker.py会写Redis并访问后端，不是可直接纳入无副作用GREEN的单测，本轮未执行。 |
| Telegram服务器 | server.mjs、worker.mjs上传/下载/删除生命周期、server.test.mjs、Dockerfile/compose/deploy.sh和README关键约束。 | 源码有固定origin、入站大小/并发边界及外部错误归一化；现有两项测试中的真实本地HTTP+fake fetch覆盖仅限其断言。本轮不新增未向Lead确认的服务器发现，不宣称安全头、所有失败重试或整个网关已关闭。 |
| 部署/CI/脚本 | 两个compose、update.sh、两组nginx配置及common片段、.cnb.yml/web_trigger、三份Dockerfile/dockerignore、启动bat、pack sh/bat、probe.bat；patch-notice.py/FlywayRename.java及gen-plugin-schema-json.py部分。 | 历史一次性工具与自动运行链已区分；未执行手工迁移/探测/生成脚本。部分脚本阅读不是完整可执行验证，未把host范围image prune等额外候选列为已确认生产事故。 |

已读`target/audit-focused-20261107-0510-domain-storage-green.log`：PluginRegistryImplFailureTest为3项（约`:590`），QtAdminControllerSafety为4项、QtVersionCheck为4项、QtArtifactOverlay为5项、QtSourceReport为2项（约`:592–600`）；plugin汇总104项零失败（约`:638`），但后续server编译失败。因此只能保留这些原测试的局部GREEN，不能称整个构建通过，更不能据此关闭E-P03启动持久化、E-Q01重放删除、E-Q04网络读取或E-C01–04。

当前18组记录中，E-W02是已确认的行为/契约冲突，具体授权残余窗口的产品选择待定；E-Q04更广的内网访问影响明确保留为候选。其余均给出具体可触发路径，不以猜测真实部署环境、历史结论或未执行测试充当事实。必要的关闭顺序建议：E-P01/E-Q01/E-W01/E-D01/E-D02先行，其余按持久化、可见性、资源与部署可用性推进。**本节不宣布C3或全项目验收完成。**

## F：QT/CMS/知识检索与AI产品闭环定向复审（2026-11-08）

### F.1：核心结论与状态覆盖

本轮原问题C16–C18、E-Q03–Q04、E-C01–C04，以及A7-N1–N3的具体触发路径已有实现与对应定向证据，按下表关闭这些路径。多币种汇总和UNKNOWN人工核对已有真实持久化回归；不能据此宣布整个AI生命周期、所有权限组合或全项目验收完成。当前仍确认三个P2：切换智能体版本的迟到知识绑定响应会污染当前版本；重建索引会回写旧知识源配置；超过30天的UNKNOWN用量没有界面查询/核对入口。它们均有当前源码因果链，尚无本轮执行的RED/GREEN。

规范依据：`docs/audit/2026-11-07/SPEC.md:11–13,21–22,27–30,34–39`要求保留真实业务能力、处理加载与错误恢复、依当前代码确认问题并用隔离证据关闭。本节基于固定基线`a39a3f3039ce7deafc3f547a25cc0dc4635c111a`的当前工作树与未跟踪文件；之前C/D/E/A7的“当前未修”描述是旧快照，本节对应条目优先。没有扩大到本轮未指定的插件、网关、部署或其他UI。

### F.2：原问题逐项关闭依据

| 条目 | 当前实现与契约核对 | 已读取的最小关闭证据及边界 |
| --- | --- | --- |
| E-Q03 签到奖励/跨月 | `QtDakaService.java:39–46,63–78,121–131`注入Clock，正常签到以截至昨天的连续天数加今天决定分档；显示连续天数直接跨月查询昨天，不再以当月集合判定昨天不存在。Javadoc明确包含本次签到。 | `QtDakaPersistenceTest`6项使用真实MyBatis/H2：前序6/29/364天分别获7/30/365档，0天最低档，月初/年初连续与断签，重复当日不重复发奖。`target/takeover-qt-green.log:285`为6项零失败。此处不宣称其他补签策略或并发幂等全部验收。 |
| E-Q04 8KB探测边界 | `QtPackHeaderProbe.java:150–171`复用`BoundedHttpTransport.prefix`，读取上限8KB后取消，≤5次重定向共用总截止时间并逐跳校验目标，阻止HTTPS降级；公开IP策略允许明确配置的私有主机，但元数据目标仍拒绝。保留合法下载重定向。 | `QtPackHeaderProbeHttpTest`3项真实隔离HTTP覆盖忽略Range且声明1GB响应只取前缀、慢body、逐跳目标与重定向上限；未授权跳转目标零访问。`takeover-qt-green.log:59,295,314`为共享传输4+探测3+签到6=13项成功。这里没有访问真实上游，也不把字符串解析单测当I/O证据。 |
| E-C01 面包屑可见性 | `CmsPageService.java:180–194`以visited终止环，祖先必须符合当前`CmsVisibility.isVisible`；分享页沿同一构造路径，不把目标分享权推广到未授权祖先。管理员自身可见性仍由既有规则决定。 | `CmsPersistenceTest`真实PostgreSQL+真实mapper/事务代理验证公开子页与私密/隐藏祖先、管理员以及分享链。原匿名泄露链关闭；不是任意CMS权限矩阵的全覆盖。 |
| E-C02 类型与ID冲突 | `CmsShareService.java:188–202`、`CmsCommentService.java:200–209`分别保存postTitles/pageTitles，查询按targetType取对应map，符合两表独立序列契约。 | 同ID、不同标题的文章/页面同时进入分享与评论管理列表，断言各自标题；不再以两类ID恰好不重合为前提。 |
| E-C03 深树与写并发 | Page/Category的checkParent沿完整祖先链并检查visited；创建、重挂与删除在同一事务获得各树的PostgreSQL advisory锁。`CmsTreeMapper`通过`CmsMapperConfig`扫描；buildTree迭代挂接，不因合法深树构造递归溢出。 | `CmsPersistenceTest`共10项含55层树根移入后代拒绝、相反方向并发重挂、删除与创建子节点竞争，两种树均覆盖。`takeover-cms-throttle-red.log:262`中这10项GREEN，但该文件整体在`:284`为BUILD FAILURE（当时限流RED），不得写成整次构建通过。 |
| E-C04 评论容量/并发限流 | `CmsCommentService.java:42–45,145–168`同步维护有界LinkedHashMap和各IP队列，容量10,000满时拒绝新IP，不淘汰仍有效计数；按最后接纳时间清理已过期条目，时间源单调，配置窗口与次数有上限。 | `CmsCommentThrottleTest`2项验证满容量仍保留既有IP限制、过期回收、同IP40个并发仅5次通过；`takeover-cms-alert-green-2.log:297,317`为2项GREEN及整体成功。该日志另有AlertRule1项，合计3，不冒充CMS3项。 |
| C16 知识检索/列表有界 | `AiKnowledgeService.java:156–166,398–406`文档列表分页1–100且不SELECT正文，计数聚合；`AiKnowledgeChunkMapper.java:20–53`用1/2/3-gram GIN候选、STRPOS精确匹配和SQL排序，在数据库限定各源topK/全局引用上限并设置3秒查询超时。同步按ID每批20篇读取，不把全源正文长期留在Java集合。 | `AiKnowledgePersistenceTest`5项中包含20,000无关chunk+末尾唯一命中，英文/CJK单字与双字查询、实际EXPLAIN索引、返回≤20行及稳定排序，另有205文档第二页metadata/总数。`takeover-knowledge-bounded-green.log:246,265`为5项真实PG GREEN。索引候选和截止预算不等于所有数据分布恒定耗时，亦未证明全量同步是异步任务。 |
| C17 分源minScore | SQL按binding阈值过滤后分源topK，再合并总topK；分值统一归一到0–1，默认0.2。`V20261108004__knowledge_bounded_search.sql`记录score_scale，对旧>1分数按143转换一次、已有0–1值保留，避免重复迁移和新旧默认误猜。AgentPanel提供0–1阈值输入并真实传参。 | 上述PG夹具覆盖不同源阈值两侧、旧30转换成约0.209790，旧0.2/0.8保留、迁移重复执行保持不变，新值30拒绝且旧绑定不受损。原“字段保存但检索不读取”关闭；新跨版本异步缺陷另列F-N1。 |
| C18 正文完整与编辑恢复 | `AiKnowledgeService.java:198–199`在任何文档/索引写之前拒绝>400,000字符，边界内按完整正文计算hash；metadata与正文详情API分离。KnowledgePanel等待完整body后才允许编辑/保存，关闭重开时丢弃旧响应，明确显示上限，不再用textarea截断原文。 | PG夹具验证超限不改变旧文档/索引、400,000边界正文/hash完整；`tests/ai-knowledge-editor.test.cjs`实际React/jsdom+Axios验证分页metadata后取完整正文和关闭重开迟到响应，`takeover-knowledge-dom.log`1项通过。未把该DOM测试称为浏览器验收，也未声称它覆盖提交POST正文的全部路径。 |
| A7-N1/N2/N3 恢复与弹层竞态 | `ChatPanel`与`useAiChat`在历史恢复期间共同拦截发送，保留草稿；首批事件即使terminal也消费完成正文。`RequestPanel.tsx:90–97,127–138,344–346`每次打开产生独立generation、关闭失效，详情组件key包括generation，审批后刷新只属于原持有者，故同ID重开也隔离旧完成。 | `takeover-ai-panel-green.log`2项实际DOM证明同ID重开不受旧审批刷新、历史加载期间Enter保留草稿且无POST。`takeover-ai-ui-restore-green.log`4项真实hook/解码器测试含PENDING消息快照后terminal completed批次立即显示最终正文。原A7三触发链关闭，不将这几项扩大成全部聊天交互验收。 |

路径说明：QT/CMS服务位于`astral-plugin/src/main/java/com/astral/{qt,cms}/service/`；知识服务/mapper位于同模块`com/astral/ai/`，迁移在`astral-server/src/main/resources/db/migration/`；前端相关组件在`astral-front/src/components/ai/`及其`admin/`目录。日志和测试均只读取，未由本审阅成员执行。

### F.3：AI多币种、UNKNOWN用量与工具人工核对

- `AiUsageService.java:52–104,258–271`保存每阶段价格/币种快照，按币种独立汇总已结算金额、保留预占和UNKNOWN次数；多币种时不再输出可以误加的单一总金额。`AiUsageMapper.java:27–47`在SQL聚合并按request_id去重，请求数不是阶段数；时间采用左闭右开窗口。`UsagePanel`逐币种展示并说明估算与待核对预占。CNY月成本额度只计CNY，不把USD数值当CNY；有月成本上限时模型必须具备完整CNY价格，其他币种仍可用于无该上限策略。此处不要求虚构汇率换算。
- `AiUsageService.java:173–193`只允许终态请求的未核对UNKNOWN阶段，锁定请求/用量，校验操作者、依据、非负实际token以及原始价格/额度周期快照；按实际消耗修正额度并保存一次性核对审计。重复、活跃请求、空依据不被接受。`V20261108005`加入用量核对字段和索引，`08006`加入状态字典并维护序列水位；schema/DTO/API同步存在。
- `AiChatOrchestrator.java:426–457`对终态请求UNKNOWN工具结果进行一次性人工核对，记录操作者、时间、依据，写入TOOL消息及事件；它不再次调用工具，也不恢复失败请求的模型执行，与新UI明确说明一致。`08007`补工具核对字段，RequestPanel以专属权限展示入口。用量/工具控制器具有独立reconcile权限声明及操作日志；本轮没有找到真实权限拦截器验证这两个新权限的定向用例，因此只确认声明与UI门控，未宣称全角色运行验证。
- `target/takeover-ai-summary-green.log:332–353`原24生命周期+3用量=27项，`takeover-ai-reconcile-green.log:257–276`用量4项；最新`takeover-ai-reconciliation-green.log:335–356`为25生命周期+4用量=29项、零失败并BUILD SUCCESS。已读真实PG夹具：并发首次reserve、币种/价格校验、分币种及窗口、UNKNOWN实际结算/拒绝覆盖、工具核对不重放并记录结果。日志内故障注入异常是测试输入，不另报生产错误。
- 这些GREEN不是最终全reactor或端到端浏览器验收。用量/工具核对表单本轮没有专用DOM测试，08006/08007也不能仅凭上述业务夹具宣称最新全Flyway空库/存量链已经再次完整运行。最终门禁应由Lead结合后续统一日志核对。

### F.4：仍可触发的三项P2与最小修复/回归

| ID | 当前源码与具体因果链 | 最小关闭约束 |
| --- | --- | --- |
| F-N1 / P2：迟到知识绑定可写入另一版本 | `astral-front/src/components/ai/admin/AgentPanel.tsx:853–855`复用无版本key的KnowledgeBinding；`:1038–1052`异步load无generation检查，无条件setBindings；`:1077–1089`save使用当前version.id。操作：A版本GET挂起→选草稿B→B响应先回→A迟到；当前props为B而bindings变成A，点击保存便用B端点保存A的source/minScore/topK。`antd-compat/Overlay.tsx:388–489`同knowledge tab key保留组件，故不是必然卸载后无害setState。违反SPEC加载恢复/数据正确性，非纯显示闪烁。 | 按版本key重建组件，或请求generation与已加载owner version共同约束读/保存；切换时清空旧状态并在当前版本未加载完成时禁止保存。真实AgentPanel DOM+延迟Axios安排A/B反序完成，断言B展示与最终PUT body始终为B值。当前知识编辑器DOM只覆盖文档正文，不能关闭此项。 |
| F-N2 / P2：索引同步回写旧知识源配置 | `AiKnowledgeService.java:254–256`先读取source快照后获得文档源锁；`:296–299`只意图更新状态却对该旧实体updateById。`updateSource:120–149`不取得同源锁。操作：同步索引运行中，管理员把enabled改false或改名并提交；同步结束以旧实体把enabled=true/旧名称写回。检索SQL实际以source.enabled过滤，因此停用可被撤销并重新参与检索，违反配置保存契约。 | 同步结束使用显式wrapper仅SET本操作拥有的lastSyncTime/status/updatedBy，不回放其他源配置；需重新读返回值时读已持久值。真实PG+mapper拦截/latch暂停重建，公开updateSource停用改名后放行同步；断言配置保留且search不再返回该源。现5项知识PG测试未覆盖syncSource与配置更新交错。 |
| F-N3 / P2：旧UNKNOWN无法在界面核对 | `UsagePanel.tsx:71,84`list/summary都不传from/to，`:266–283`只有智能体/用户筛选；`AiAdminUsageController.java:253–262`默认从29天前零点至明天。`:233–236`的“核对用量”只出现在已列出的UNKNOWN行。因此31天前的UNKNOWN在次月拿到账单时无UI路径可发现/核对；API虽支持显式旧日期，但界面没有对应控件。这是新人工核对闭环的实际可达性缺口，不是历史记录丢失。 | 提供可见日期范围并同时传list/summary，或提供跨年龄、分页的UNKNOWN待核对队列；可保留单次统计≤366天预算并允许选择更早窗口。实际UsagePanel DOM+公开controller隔离夹具放入近期及31天前UNKNOWN，证明旧记录可查、显示正确时间范围并进入核对。不要只把默认窗口改得更长以再次推迟问题。 |

三项均已向Lead发送；以上是可重现的源码推理及待执行验证，**未伪称本轮跑过复现**。当前更改仍在继续，应以Lead后续实现和新增GREEN更新其状态。

### F.5：纠偏、覆盖与最终验收边界

撤回本轮过程中短暂提出的“summary默认30天而list默认全量”观察：公开`AiAdminUsageController`对两者均使用相同parseStart/parseEnd，实际公开默认窗口一致。F-N3仅依据缺少日期控件、默认窗口与逐行核对入口的组合，不沿用该错误前提。也不把score_scale迁移保持旧0–1阈值、人工工具核对后请求仍FAILED、明确不自动重放工具判成缺陷。

已读本轮改动的上述QT/CMS服务及关键实体/mapper/事务扫描、真实持久化和限流测试；知识service/mapper/DTO/控制器/08004/schema与KnowledgePanel/AgentPanel；AI用量service/mapper/聚合与DTO/价格/额度相关链、UNKNOWN恢复/审批认领完成和人工核对路径、08005–007及两个新表单；RequestPanel/ChatPanel/useAiChat相关差量和前端夹具。完整读取相关定向日志，未把测试名、历史报告或单个SUCCESS替代断言检查。

未运行构建、测试、数据库、服务、网络或浏览器；未读取真实凭据；只追加本报告，没有改实现/测试/迁移、没有提交。本轮结论仅覆盖指定原触发路径与上述三个新增确认项，**不宣布整个AI生命周期或全仓最终Spec审阅已完成**。

## G：全仓 Spec 终审准备与既有问题收口（2026-11-08，提交前快照）

### G.1：核心结论（本节状态优先于前文旧快照）

**本轮仍为提交前有界复审，尚不宣布全仓 Spec 通过。** F-N1–N3已具备当前实现、公开表单/真实持久化证据；S1/S2请求生命周期的原缺陷及C01–C19、E节原确认触发路径也已有修复和定向回归，具体范围如下。已核最新全reactor为794项零失败/错误/跳过，实际生成34个宿主实体并安装，但该日志早于本节最后一批修复。

本轮从原C3候选补证四组确认问题：统计开关关闭造成Controller缺依赖；插件按原始URI漏拦矩阵参数/context path；既有知识索引任务可取消合同未实现；草稿修改/绑定与发布之间没有共同事务冻结边界。前两项已有真实RED及实现变化，后两项Lead已接手。配置示例/日志层级和App点对点已读合同差异另明确交回，不用“全通过”遮盖；Standards新发现仍由其独立报告裁定。

规范依据为`SPEC.md:11–13,21–22,27–30,34–39,61`及各行具体合同。审阅只读源码、差量、测试和已有日志，唯一写入为本报告；未执行构建、测试、服务、DB、Redis、网络或浏览器。HEAD仍为`a39a3f3039ce7deafc3f547a25cc0dc4635c111a`，最终提交hash尚待Lead提供。

### G.2：F-N1–N3及人工核对闭环

| 原项 | 当前实现与已读取证据 | 当前判定 |
| --- | --- | --- |
| F-N1 跨版本知识绑定迟到覆盖 | `astral-front/src/components/ai/admin/AgentPanel.tsx:851,858`对ToolBinding/KnowledgeBinding均以`active.id`作为key；换版本会卸载旧状态。`tests/ai-panel-lifecycle.test.cjs:86–118`实际加载AgentPanel和Axios入口，挂起A版本响应，进入草稿B并加载其值，再放行A，点击公开保存，精确断言B端点的body仍为B的source/minScore/topK。 | 原污染另一版本并提交的路径关闭。没有声称覆盖每种同版本刷新竞争。 |
| F-N2 索引同步回放旧配置 | `AiKnowledgeService.updateSource:129–152`仅SET提交字段，sync结束只更新lastSyncTime/status/updatedBy/updateTime。`AiKnowledgePersistenceTest:121–137`在真实PG/MyBatis重建读取后暂停，另一线程经公开updateSource改名并停用，放行后两值保留且search为空。 | 原停用被重建撤销的路径关闭。后续持久异步任务改造仍需保持此白名单。 |
| F-N3 历史UNKNOWN不可达 | UsagePanel显式日期范围同时用于list/summary，并显示生效范围；`ai-panel-lifecycle.test.cjs:121–157`查询2020年窗口找到UNKNOWN，空核对不发请求，填写后发送精确token和note。 | 旧账单可达性及表单路径关闭；不是所有历史数据自动可核算的承诺。 |
| 工具人工核对与独立权限 | 同测试`:160–192`实际RequestPanel选UNKNOWN并核对，只发送`/tool-calls/{id}/reconcile`，没有approve/重放；真实PermissionAspect+PermissionChecker的`AiReconciliationPermissionTest`证明view/approve不等于reconcile，MockMvc拒绝负token/空note，操作者取request身份而非body伪造值。 | 表单/权限运行证据补齐。权限夹具的业务service为mock；实际PG核对是另一层证据，不冒称完整宿主认证端到端。 |

`target/takeover-ai-forms.log`为5项真实React/jsdom通过；`takeover-ai-reconciliation-permission.log:272–291`为1项通过。`takeover-startup-knowledge.log:298,756,778`为知识6项PG和真实Boot初始化1项通过。以前F.3的“未见表单/权限专项”和F.4“仍未修”不再适用于上述当前状态。

### G.3：原S/A/C条目关闭矩阵与边界

| 条目 | 当前代码/回归所证明的具体关闭范围 |
| --- | --- |
| S1/S2、A1–A5（R11/R12） | `AiChatOrchestrator.approve:321–498`在无外层事务中先独立提交RUNNING+executionToken认领，再执行外部工具，最后独立保存结果；持久认领不随外部异常回滚成可重放待批。USER_CONFIRM核原请求者，ADMIN_CONFIRM核专属入口/权限；稳定provider toolCallId与TOOL消息记录结果，全batch未决时不恢复。`AiRequestService`workerToken/lease及状态CAS保护取消/终态/恢复；下一助手创建已并入同request锁事务。`AiUsageService.reserveStage/settleStage/releaseUnsentStage`连同捕获的日/月行和请求锁保持事务、阶段幂等、真实token/费用；未调用provider释放，已发出未知保留UNKNOWN。真实PG `AiApprovalLifecycleTest`30项覆盖公开start→approve→实际异步resume、并发审批/同key首次创建、取消、崩溃后认领、批工具、恢复、月费用、输出预算、并发额度及结果保存失败；`AiUsagePersistenceTest`4项补分币种、核对及窗口。原路径关闭；不能推导外部工具“恰好一次”或未知成本为零。 |
| S3/S4、R3 | HTTP/MCP/Provider与Webhook当前使用固定解析地址、有界正文/截止的传输；SQL走AST源列/函数允许策略并保留合法安全投影。MCP计划摘要覆盖实际渲染headers和目标，坏凭据配置仅使该handler不可用。沿用前序本审阅与Standards的明确分工；最新reactor保留HTTP4、Provider4、SQL14+28、MCP17等原断言通过，不将测试名代替前序已读断言。 |
| S5/S6、N2、C07–C10 | 模板保留选中渠道原ID，排自身冲突、写入行数、事务回滚与afterCommit失效；Publisher按规则/渠道/收件人隔离、Redis Lua发送前预占并用捕获key退款；INAPP失败不报成功。通知整保存显式SET NULL，真实MyBatis保存→再读→publisher用自动模板验证；邮件code回退限定EMAIL；Mail/Sms用long总权重不放回抽样，内存随候选数增长。reactor的模板6、Publisher8、规则1、Mail2、Sms1均GREEN。 |
| S7/S8/S9、C11–C15 | 凭据安全投影/编辑语义，指标匹配路由+双溢出桶，序列首次竞争类型/_id与MAX_VALUE边界均保留。停用告警渠道自动evaluate零发送且记录失败；告警/序列遗漏字段白名单与提交后缓存失效；统计key/SQL使用同一normalize；主机内存和JVM堆分开，未知为null。当前实际mapper/服务夹具分别为AlertChannel3、ApiMetricCollector2+拦截器1、GeneratorFactory10+Database边界1/PG1、AlertDelivery3、AlertRule1、SequenceConfig7/持久化1、StatReport1、SystemMonitor2通过。统计关闭启动另见G-N1。 |
| C01/C19和token清理候选 | Profile/admin凭据白名单与旧凭据CAS已落地，真实MyBatis2项安排并发状态/凭据改变；TokenService前缀检索20001作截断哨兵，查询失败/未穷尽不能报撤销成功，cleanup保留NEVER_EXPIRE。真实Sa-Token逻辑+隔离DAO4项含永久会话及全端撤销失败。QT其他User写入口属于Standards新QT-USER-F1，不能被C01顺带关闭。 |
| C02–C06及存储后续项 | 网关诊断仅可信状态、DNS计入总预算且残留解析有界；目录锁覆盖新操作和旧票据持久占位；charge保持归属且删除/移动不退款；全局意图锁计入SENDING。`LegacyUploadReceiptService`保存远端事实、重放额外对象独立清理、失败推进队列、权威locator匹配后才终结ticket；发送授权与SENDING共短事务，拒绝退款与file状态原子。沿用Standards ST-REV-F1–F11已核断言，当前`StoragePersistenceTest`26项真实PG再次GREEN，包括其后媒体5个参数化/普通用例；不能相加为26个独立存储缺陷或真实云端验收。 |
| C16–C18、A6/A7 | 具体关闭维持F.2：分页metadata/完整正文、1/2/3gram索引候选及数据库topK/超时、分源0–1阈值；前端请求generation、待批恢复/取消、message分页、同ID重开与加载草稿。知识6PG、知识编辑DOM1、面板5以及总前端121GREEN是不同层级且相互重叠。索引“可取消任务”是另一个合同，不被有界同步实现关闭。 |
| S10/M7与后续迁移 | 原历史migration/checksum未改；仅缺表SQL callback及窄版本、精确statement Java callback补兼容。最新完整Flyway13项（Fresh6、Notice6、PluginLedger1）覆盖空库、已有表、无history、旧失败重试、公有schema哨兵、并发首启、条件种子、失败回滚/授权及全局ID。实际Boot初始化/重启与fallback执行两次补启动顺序/幂等。业务PG手建DDL与全链Flyway证据分别归因；没有读取真实生产数据。 |

以上“关闭”均针对原已确认因果链，早期D节“19项仍未修”仅是历史快照。原安全S1–S5、auth/system AS01–AS07、core F01–F05及此前字段/迁移修复按其已有领域报告与本节定向重读交叉确认；不宣称这一张表等于所有源码逐行新读。

### G.4：E节插件、QT/CMS、网关、部署的当前关闭依据

| 原项 | 当前实现与最小证据；不扩大证明范围 |
| --- | --- |
| E-P01 | QtPlugin只声明真实QT所属路由，PluginIsolationTest证明禁QT不阻宿主通知/AI/storage及CMS，QT自身拒绝。 |
| E-P02/P03 | Registry在数据库初始化后恢复desired与actual状态；持久写错误传播、标准全局ID/upsert；启动hook失败不把desired覆盖false，必要插件失败阻止伪健康。失败fixture6项加真实PG PluginAndLedger和双Boot context的持久状态/ID/重启证据互补。未声称跨实例热更新或失败hook后的每种重启组合。 |
| E-P04 | 原任意slug=enable/disable/toggle豁免已限为准确管理POST，原2项fixtureGREEN；矩阵/context-path新遗漏另记G-N2，不能只以普通raw URL测试宣布整条插件门禁关闭。 |
| E-Q01/Q02 | QtMedia同一稳定publicId不回收，用户/playlist写入受锁且显式清空封面；`StoragePersistenceTest:805–836`真实公开avatar/cover direct/proxy各重放两次，file仍AVAILABLE且无删除任务，coverClear后真实列NULL、seq=2。这5个执行案例含在26项中。 |
| E-Q03/Q04 | 维持F.2：跨月签到/奖励6真实MyBatis；共享BoundedHttpTransport前缀8KB及逐跳预算/策略3实际loopback HTTP。授权私有主机是显式配置选择，不误报成匿名任意SSRF。 |
| E-C01–C04 | 维持F.2：祖先可见性、post/page复合身份、全链visited与共同树锁由真实PG10项证明；评论容量/过期/40并发只5通过由2项证明。详情浏览量写放大、跨实例限流策略未升级为无证据事故。 |
| E-W01/W02 | Worker仅callback明确contentMatches=true时预热本次body；下载先授权metadata再读缓存，固定300秒授权过期时间传递到客户端max-age，不能另一个PoP再续满300秒。README/DEPLOYMENT统一残余窗口，单PoP purge不被宣称全局秒撤销。3项实际worker.fetch+fake fetch/Cache行为包含重放、跨PoP预算及出站前大小/MIME；10项源码断言另列，Telegram-server另2项真实本地HTTP+fake upstream。总15GREEN，不是15个真实Cloudflare网络场景。 |
| E-D01 | pack.py统一两个入口，用Git自有工作树清单，排除env/密钥/部署凭据及依赖/产物，保留example/template。`scripts/test_pack.py`真实合成Git与两个入口归档，断言危险路径未进入且读取当前修改内容。没有打开真实凭据。 |
| E-D02 | headers片段与http级zone/map分开，sites和旧样例`nginx -t`均在隔离路径/合成证书成功。未启动/reload真实服务。 |
| E-D03 | release脚本同一不可变tag先构建/推送三镜像，再提升latest；`scripts/test_release.py`假docker记录前端/后端/博客单域变更均完整推送，任一immutable push失败不提升latest。没有实际推镜像或声称registry可用。 |
| E-D04 | compose/CI/Dockerfile均传构建时公共SITE/ADMIN URL，缺失/不合法origin提前拒绝。`takeover-blog-public-urls.log`与对应驱动证明隔离生产HTTP canonical/sitemap/robots为合成公共域名，实际client chunk含admin站点链接。该验证不是浏览器或真实域名请求。 |

### G.5：终审新确认项、Lead处理中及最小验收条件

| ID / 级别 | 规范、已确认因果链与本轮状态 | 必要关闭证据 |
| --- | --- | --- |
| G-N1 / P2，等同MON-STD-F1 | 原`AlertEngine:52`条件`astral.stat.enabled=true`，Controller强注入却无同条件；官方STAT_ENABLED=false可导致缺Bean，违反配置可运行合同。`takeover-final-races-red.log`真实context出现NoSuchBeanDefinitionException；当前Controller已新增一致条件。 | 真实context true/false两态GREEN并明确disabled时管理API不创建。新实现出现不等于已验收；最终reactor需覆盖它。 |
| G-N2 / P2 | 原PluginApiInterceptor:28/39按getRequestURI作段前缀，Spring能将`/api/v1/app/cms;x/post/news`映射到CMS，但raw前缀不匹配，context path同理。违反插件禁用控制合同；非CMS编辑权限越权。真实MockMvc同路由matrix/context RED已见。当前拦截器已改cachedPath和BEST_MATCHING_PATTERN。 | 同一个真实MockMvc夹具证明禁用拒绝、启用正常、管理POST可用；覆盖空与非空context path。普通直接preHandle测试不能独自关闭。 |
| G-N3 / P2，原C2明确合同缺口 | `AI_MODULE_DESIGN.md:1351`明确“索引任务必须可重试、可取消”。原AiKnowledgeService.syncSource一个事务处理全源，RUNNING/jobId处理期间不可见，controller只有sync/job GET，无取消。批量20只控制Java工作集，没有实现任务取消。Lead明确补持久异步重建/取消/重试及前端状态，不降低原文合同。 | 提交QUEUED/jobId后返回，worker短批/独立事务认领并检查取消，重试有持久身份且不与同源旧worker重复提交；公开API→实际worker→数据库→UI状态回归。取消/失败不得把未完成文档当READY供检索；保留F-N2配置不回放及原bounded search/正文断言。 |
| G-N4 / P2，与AI-REF-F1部分同源 | `AI_MODULE_DESIGN.md:251,280,284,1411`规定已发布版本/绑定不可变并须校验。`AiAgentService.updateDraftVersion:351–385`读DRAFT后整实体updateById；publish可先提交PUBLISHED+指针，迟到编辑再写回DRAFT及内容。Controller replaceTools:293、replaceKnowledge:345只在service事务外requireDraft；发布后仍可delete+insert绑定。publishVersion:228校验与publish事务分开，可校验旧配置后发布新配置。此为原C3候选的完整当前因果链。 | 编辑/绑定替换/校验+发布共用同一版本/agent锁边界，获锁后复核状态，白名单SET不回放status/旧route，锁序一致。真实PG公开入口安排发布胜出后的编辑/绑定拒绝、验证与修改交错不能发布未验证配置。已读首版AiAgentConcurrencyTest日志三项全是fixture缺client_types NOT NULL错误，**不计业务RED**；Lead正在修夹具/实现。 |

以上四组已即时通知Lead。Standards本轮另确认QT-USER-F1、SSE计数MON-STD-F2、locale和配置文档项，仍保持其独立编号/证据；不在Spec中复制为新的发现数量。本轮没有新增“为了优化”要求。

### G.6：既有差异/候选的明确处置边界

- **配置文档/属性（确认P3，已通知）**：`application-database-examples.yml:10–19,126`仍宣传不存在的astral.database并推荐MySQL生产，和AGENTS的PostgreSQL/spring.datasource冲突；`application.yml:251–258`logging与pattern嵌在management，不能绑定标准日志属性。与Standards CFG-F1/F2同项，待其修复/实际YAML绑定证据。不是推测生产已经泄密。
- **App点对点已读（明确合同差异，待Lead裁定/实现）**：AGENTS:183–185说点对点read_time；当前`NoticeServiceImpl:131–141`countUnread返回候选size、readAck只日志，AppController公开契约又写“前端缓存/本期仅日志”。all/notify/inapp实际持久已读是反证，广播本地缓存也应保留。已建议只对本人点对点写read_time/过滤未读，真实mapper+App入口验证，不把广播改成全局已读。不能把它称跨用户越权或直接当已关闭。
- **AS10模板变量规则**：现AGENTS:161–162明确变量子集即可，与现service/test一致，旧“必须全部payload”冲突已消除。
- **core F07枚举默认差异**：历史index_job QUEUED vs字典PENDING、message SUCCEEDED vs COMPLETED为schema/字典差异，实际服务大多显式状态；已交Lead在新增任务迁移里核对，不能为修文案改历史checksum，也未证明正常消息都因默认值坏掉。
- **继续保留候选而非已确认生产故障**：DB/Redis可选probe等待3秒后底层任务积累、stats异步ACK与best-effort语义、Snowflake部署节点分配/冻结时钟、按bizKey长期状态增长、历史notice过渡数据保存策略、QT邮箱身份二次验证、CMS views写放大。原报告明确依赖部署/业务合同/运行证据；没有这些证据不得虚报漏洞。简单LRU淘汰序列状态可能重发ID，不能当无风险修复。可选平台线程池丢弃策略、历史/日志保留期和旧存储远端删除能力亦不能擅自变更为新需求。
- **纠偏**：旧存储HEAD/DELETE全无timeout不成立，S3/Oss10s、Cos/Upyun8s；changeVisibility已有目录/文件锁、状态和version CAS；Segment读取minValue已存在。不能把原报告的这些泛化描述继续列为待修。成功operation遗留failureCode目前被COMMITTED UI忽略，最多API整理；默认配置停用检查尚无已证实活跃consumer，不提升成阻断。

### G.7：最终门禁、差量范围与继承归因

1. `target/takeover-final-reactor.log`在2026-11-08 21:53:09完成BUILD SUCCESS：模块汇总14+17+2+29+60+22+20+586+44=**794**，零失败/错误/跳过。`:141–177`实际运行schema generator产出34实体，server完成Boot repackage和本地install。这个总数不再叠加各专项日志重复计数。它早于G-N及Standards末轮修复，不能替代更新后统一门禁。
2. 管理前端最终Node日志121/121，Blog84/84，生产构建日志完成；Blog公共URL验证、worker/server15、归档/release2、两个Nginx配置检查各自成功。front格式日志是`--write`输出，不把它自动称最终check；空ESLint日志本身不能证明exit0。最新Checkstyle/SpotBugs和后续代码变更后的质量门禁仍由Lead提供，审阅不自行执行。
3. 按固定基线盘点当前所有根模块差量及未跟踪文件；读取前序报告/本轮相关实质hunk并把关闭依据映射到当前源码。前序覆盖按其原领域负责人/阅读深度归因，不能因为跨模块diff存在就写“本轮逐行复读全仓”。最初采样410个tracked文件、后续共享修改继续增加；此数只是盘点，非覆盖率。
4. 对Blog旧Spec的118份源清单实际重算当前SHA：**115相同，3变化**（Dockerfile、browser-gate、build-isolated）。三份差异均已读，另读新增URL校验/产物验收脚本。browser-gate已补普通Tab/块缩进/ShiftTab及Escape后原生跳焦，原SPEC-FINAL-001关闭；未删除Emoji/语法/同步能力来规避要求。115份沿用分阶段独立审阅证据，**不是本reviewer新的FULL115**。真实IME、物理滚动/布局、Cookie跨tab与下载浏览器行为仍未接受；禁止以旧CDP线索包装browser GREEN。
5. HEAD仍等于基线、`base..HEAD`为空，而WIP/untracked明确非空。本次只处理授权repo。未跟踪`__pycache__/*.pyc`为生成物，已提示Lead排除；最终提交应同时包含必需schema/migration/测试/脚本及报告，再按实际commit差量两轴复核。未执行stage/commit/push，最终Spec验收不提前签发。

### G.8：同轮末尾收到的新定向证据（不覆盖后续改动）

- **G-N1关闭原开关触发链**：当前AlertAdminController与AlertEngine使用相同ConditionalOnProperty。已完整读取`MonitorOptionalContextTest`，实际ApplicationContextRunner注册两真实组件及mapper替身，分别断言false时两者不创建、true时均创建且context成功；另一个locale用例恢复默认locale。`target/takeover-final-races-green.log:250`三项GREEN，其中统计开关两项。不是完整AstralApplication启动测试。
- **G-N2关闭原matrix/context-path触发链**：当前PluginApiInterceptor使用Spring cachedPath及BEST_MATCHING_PATTERN并保持段边界、仅管理POST豁免。已完整读取`PluginIsolationTest`，实际MockMvc路由fixture对普通/矩阵URL、空/`/astral` context逐一断言禁用拒绝、启用返回内容、管理POST可用；`:404`三项GREEN，包含两项既有隔离/slug用例。路由fixture使用真实映射和拦截器，但没有把它描述为CMS业务层HTTP端到端。
- **G-N4部分关闭/继续收口**：`AiAgentConcurrencyTest`修正fixture后`:300`三个实际PG用例GREEN，证明遗漏route不回放已删除引用、发布胜出后迟到编辑失败、发布不回放旧name/route。当前AiAgentService编辑/发布使用agent→version锁和白名单SET，Controller已加事务、校验在锁内；工具/知识绑定service新增FOR UPDATE读取版本状态。最后这些绑定/Controller变化在共享工作树继续产生，当前三项service测试未覆盖其公开入口竞争，不用同一3GREEN声称新绑定和校验竞争已经执行。
- `takeover-final-races-green.log`整体仍为**BUILD FAILURE**（`:449`）：QtAdminControllerSafetyTest四项中一项ERROR；Monitor4、AI并发3、生命周期30、知识6、Plugin3、Qt用户3是局部GREEN，不能隐去该失败或把文件名green当结论。该日志早于后续绑定/知识任务改动，最终统一门禁仍待Lead。

本次终审准备已完成持久化。后续限定复核上述已知修复、对应新增证据及最终本地提交hash的实际差量，不继续扩展新功能。

## H：G-N3 / G-N4 实现限定复核（2026-11-08 23:18，提交前）

本轮只读当前持久索引任务、08008、发布锁与对应测试/日志，不扩域、不执行测试。**G-N4原并发缺陷已关闭；G-N3标准Flyway路径已实现持久提交、逐文档处理、取消/重试和管理端状态，但插件auto-schema存量库兜底还存在一个确认的升级错误H-N1。** 已即时告知Lead；不提前签发最终提交Spec PASS。下述结论仅对应当前共享工作树，最终提交hash复核仍待进行。

### H.1：确认遗漏 H-N1（P2，G-N3兼容边界）

- **合同与支持路径**：`AI_MODULE_DESIGN.md:1351`要求任务可重试、可取消且不得使用未完成文档；`AiSchemaInitializer.java:17–19,32–44`明确以`sql/ai-schema.sql`支持插件直接运行于存量库。该兼容入口属于当前承诺，不是新增部署模式。
- **当前因果链**：`ai-schema.sql:249–250`直接给旧job表增加`last_document_id/end_document_id DEFAULT 0`，没有08008的历史任务终结转换。旧`RUNNING`行随后被`AiKnowledgeService.processNextIndexDocument:321–339`认领，以`id <= 0`查不到任何文档，直接写`SUCCEEDED`且total降为旧processed；旧`QUEUED`既不被worker选择，也不符合cancel的PENDING/RUNNING和retry的FAILED/CANCELLED条件，无法通过任务操作恢复。其余新任务可独立提交，故不夸大为整个索引永久不可用。
- **标准迁移是反证边界**：`V20261108008__knowledge_durable_jobs.sql:1–14`已把旧QUEUED/PENDING/RUNNING转FAILED并保留历史记录，同时增加活动任务索引、统一状态默认；使用全链Flyway的标准服务不触发上述旧任务误报。
- **最小关闭方案**：兜底脚本首次引入cursor时，在同一幂等升级块内将旧未完成任务置FAILED，补同等默认/必要索引；不能每次启动无条件终结所有PENDING/RUNNING，否则会破坏新durable任务的恢复语义。用08008前真实表形+旧RUNNING/QUEUED运行实际`AiSchemaInitializer.init()`，断言旧任务可重试且未伪造完成；再提交/推进新任务，第二次init须保留job身份、状态和游标。历史Flyway checksum不改。

### H.2：G-N3新实现与证据边界

1. `AiKnowledgeService.java:253–280`持有source事务advisory锁，返回同源活动任务或新PENDING id，并记录提交时文档上界与计数。`retryJob:305–311`保留旧终态、创建可审计新任务；`cancelJob:291–302`只CAS活动状态。`processNextIndexDocument:314–385`每次独立30s事务，以FOR UPDATE SKIP LOCKED认领job、同source advisory锁协调文档写入，一篇文档的chunks/READY/游标原子提交；取消等待当前文档事务后生效，后续认领不再处理该job。进程退出前未提交的事务由数据库回滚，下次可从已提交游标继续。
2. `AiKnowledgeIndexWorker.java:22–55`独立单线程executor，每tick最多20次、单实例running标记，跨实例靠job行锁而非本机标记；停机中断线程，AI总开关和插件门禁先检查。没有要求远端副作用的exactly-once机制：当前处理是本地数据库事务。
3. `saveDocument/deleteDocument:190–249`与worker共用source advisory锁；重建只白名单写chunkCount/status/indexedAt，不回放文档旧正文。source完成写也只更新索引字段；F-N2并发停用/改名保护仍由真实PG测试保留。检索mapper仍要求document READY、source enabled、binding enabled；未完成单文档chunks不能从未提交事务被读取。
4. `KnowledgePanel.tsx:417–507`恢复latestJob，活动任务1500ms轮询，失败保留错误与重试读取入口；取消/重试切换jobVersion并暂停旧轮询，卸载/切源key防止迟到结果改当前源。`:629–687`显示真实计数/终态与权限约束，不把提交显示成完成。`ai-knowledge-editor.test.cjs:68–97`的真实DOM案例证明恢复RUNNING、取消、重试PENDING及按钮状态，**没有等待定时轮询发生，也未执行真实浏览器**。
5. `AiKnowledgePersistenceTest.java:143–205`新增PG用例覆盖提交可见/活动任务幂等、取消留进度+新任务重试、失败保留旧chunks、new worker对象tick从游标继续。new worker是重建对象，**不是进程强杀/重启实测**。失败注入在document读取返回时、chunks删除前，因此该用例证明任务失败可重试和旧数据不变；delete/insert中途失败的原子性由事务代码支持，不能把这个fixture说成已经实测删除后回滚。非必修扩域，仅记录证据深度。
6. `PluginStartupContextTest.java:89–100`实际ResourceDatabasePopulator执行两次并验GIN索引，但测试先完成全Flyway，故不能覆盖H-N1的08008前存量库兜底。当前base SQL、entity、schema均已有cursor；schema/default为PENDING/COMPLETED，08008对历史数据库完成对应转换，不需改旧迁移。

### H.3：G-N4原触发链关闭

- `AiAdminAgentController.java:45,230–233`外层rollbackFor事务保持`lockVersionForPublication → requireValid → publish`整个调用；`AiAgentService.java:410–417`agent→version获锁后返回当前version，编辑`:354–407`再验DRAFT且仅白名单SET，发布不把旧整实体写回。Controller的updateVersion+工具替换也共享外层事务。
- `AiToolService.java:582–678`所有replace/add/remove先FOR UPDATE读version状态再验DRAFT；`AiKnowledgeService.java:407–444`绑定替换采用同一版本行锁和状态校验。因此绑定变更不能在发布持锁的验证之后提交，也不能在发布完成后改已发布绑定。当前调用图未发现这些路径反向等待agent锁造成必然死锁。
- 已完整读取`AiAgentConcurrencyTest.java:177–270`七个实际PG/真实mapper/事务代理案例：原三个遗漏route不回放与编辑发布竞争，加公开controller工具/知识替换遇发布胜出拒绝、获得当前锁定version才校验、实际requireValid之后迟到编辑须等待发布提交并拒绝。验证服务是真实对象（仅在调用返回后安排线程），不是mock valid替身。它关闭本次确认的version/绑定并发因果链；不声称对所有可变Provider、工具元数据做全局快照证明，也不扩成新需求。

### H.4：现有执行日志核验

- `target/takeover-jobs-green.log:421,430,432,451`知识10、工具审批7、Qt安全4及其反应堆**BUILD SUCCESS**，先前G.8中Qt夹具ERROR已由后续日志取代；该文件中的旧AI并发仍3，不混作7。
- `target/takeover-publication-final.log:332,478,2097,2523,2545`当前已于23:07:17完整**BUILD SUCCESS**：AI并发7、知识10、FreshDatabaseMigration6、真实Boot startup1均零失败/错误/跳过，另有通知1。迁移包括08008空库执行成功；没有历史活动job转换断言，不将空库GREEN自动等同存量语义验证。
- `target/takeover-knowledge-ui-green.log`2/2 DOM通过，边界如H.2。以上是读取Lead已经执行的证据，本reviewer没有运行构建、数据库、服务或浏览器。

### H.5：追加确认 H-N2（P2，G-N3任务UI忙状态）

- **具体触发**：当前`KnowledgePanel.tsx:417–431,489–506,648–657`中，先前任务读取/操作失败会显示jobError及“刷新任务状态”。用户此时发起取消/重试，在mutation仍未返回时可点击该刷新按钮。`loadJob:419`与`changeJob:491`递增同一个jobVersion，刷新抢走mutation generation；mutation响应在`:497`被丢弃，finally`:505`亦因version不等而不释放syncing。
- **结果与合同**：组件未卸载且仍在同一知识源时，syncing持续为true；`:444`不再轮询、`:674,683`取消/重试持续禁用。再次刷新不会释放busy。必须重挂组件才能恢复，破坏本次G-N3的可取消/可重试交互。此为当前可确定的竞态，不是要求额外后台任务功能。
- **最小关闭证据**：操作期间禁用/guard手动刷新，或把read generation与mutation busy所有权分开；真实DOM先制造读取错误，再defer取消/重试响应、尝试刷新，最后resolve响应，断言busy释放、最终任务状态与按钮可恢复。原2项DOM测试未安排此交错。

### H.6：本轮有效结论与统一门禁更新

本轮有效未关闭清单为**H-N1插件fallback旧任务兼容、H-N2任务UI忙状态竞态**；G-N4原发布/绑定并发链已由当前实现和7项PG用例关闭。H段标题时间仅为记录标签，本段记录时点见下行。

Lead随后通知：`release-reactor`于23:12:56实际BUILD FAILURE，`NoticeMigrationCompatibilityTest.concurrentStartup`暴露Flyway空history自动baseline竞态。该通知在H.4专项GREEN之后到达，本reviewer本轮没有重新阅读该失败日志或扩大追根范围，按Lead已确认结果记录；**专项publication GREEN不能替代这一失败的统一门禁**。Lead将顺序修H-N1、H-N2和baseline并发后重跑总门禁，本reviewer不运行构建、不修改产品，等待最终本地提交hash的实际差量复核。
记录时点：2026-11-08 23:18:41 Asia/Shanghai。
