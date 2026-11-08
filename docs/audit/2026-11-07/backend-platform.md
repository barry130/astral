# 后端平台域审计报告 · common / dao / schema / log / monitor / sequence / plugin-api

- 基线：`a39a3f3039ce7deafc3f547a25cc0dc4635c111a`
- 审计域：`astral-common`、`astral-dao`、`astral-schema`、`astral-log`、`astral-monitor`、`astral-sequence`、`astral-plugin-api`
- 日期：2026-11-07
- 作者：upload-ops（Team task-6）
- 证据等级：**静态审计 + 代码路径推导 + 定向单元测试源码**。本报告不包含浏览器、真实生产数据库、真实外网或生产环境验证结论；本报告不声称任何 Maven 测试已通过，实际门禁由 Lead 串行执行。
- 约束：`astral-common` 源与测试在审计期间冻结；`astral-schema` 源与测试在本报告完成后冻结。未执行 git commit/push。

---

## 0. 结论摘要

| 编号 | 问题 | 严重度 | 当前状态 |
|---|---|---|---|
| C1 | `PageQuery` 无上限，单请求可拉全表 | 中 | 基线已有 clamp（非本轮） |
| C2 | CSV 导出公式注入（`= + - @` 开头单元格） | 中 | 已修 |
| C3 | `SequenceMetrics` 每次取号抢类锁、`reset` 非同步 | 中 | 已修 |
| C4 | `ErrorCodes` 消息模板可被数字编码注入/格式异常抛未捕获 | 低 | 已修 |
| C5 | 通用局部更新缺少「未提交=保持、空串=清空」语义，管理端编辑丢字段 | 高 | 基线已有 `PatchValues`（本轮复用） |
| C6 | 注解通配符 import、类级注解不可继承 | 低 | 已修 |
| C7 | `CommonUtilsTest` 断言的空白保留语义与实现不一致 | 测试缺陷 | 已修（仅测试断言） |
| C8 | `Result` Javadoc 与实际泛型契约不一致 | 低 | 已修 |
| D1 | 34 个生成实体缺少 `@TableId`/`@TableField(fill=...)`，插入不回填主键/时间 | 高 | 已修（重新生成） |
| D2 | `sequence_segment.version` 为 NULL 时乐观锁条件永不匹配，取号无限重试 | 高 | 已修 |
| D3 | `sequence_segment` 初始化步长与 `sequence_config.step` 脱节 | 中 | 已修 |
| D4 | `sequence_statistics.total_generate` 从不递增且旧行可能为 NULL | 中 | 已修 |
| D5 | 错误汇总 SQL 全量 GROUP BY + 内存切片，分页参数无上限 | 高 | 已修 |
| S1 | `CodeGenMojo` 对非法 JSON/空 schema 目录静默跳过（fail-open） | 高 | 已修 |
| S2 | `CodeGenMojo` 输出路径穿越、重复类名可覆盖文件 | 高 | 已修 |
| S3 | `CodeGenMojo` `Files.list` 未关闭、文件顺序不稳定 | 中 | 已修 |
| S4 | `SchemaCodeGenerator` 标识符未校验、索引名超过 PostgreSQL 63 字符 | 高 | 已修 |
| S5 | `SchemaCodeGenerator` ALTER/默认值/索引差异生成不完整或方言错误 | 中 | 已修 |
| S6 | `SchemaRegistry` `Files.list` 泄漏、外部非法文件静默丢弃、重复表名无日志 | 中 | 已修 |
| S7 | `SchemaEntitySync` 单个 schema 读取失败仍可继续并删除实体（fail-open） | 高 | 已修 |
| S8 | `sequence_config.step` 默认值 100 与运行时默认 1000 不一致 | 中 | 已修 |
| L1 | 日志管理端暴露创建/更新/删除接口，可伪造/删除审计证据 | 高 | 已修（删除写接口） |
| L2 | `@Async` 日志任务提交被拒绝时异常冒泡，覆盖业务异常 | 高 | 已修 |
| L3 | AOP 代理/接口方法上读不到日志注解，日志类型丢失 | 中 | 已修 |
| L4 | 操作日志 `user_id` 永远为 null（writer 未接收） | 中 | 已修 |
| L5 | 登录/操作日志导出顺序不确定 | 低 | 已修 |
| M1 | 告警 Webhook 任意 URL（SSRF：回环/内网/云元数据/任意第三方） | 严重 | 已修 |
| M2 | 告警规则 `threshold <= 0` 每轮必命中，持续轰炸渠道 | 高 | 已修 |
| M3 | `updateById(rule)` 回写整行导致管理员修改被旧值覆盖（丢失更新） | 中 | 已修 |
| M4 | 渠道同名并发创建返回 500 | 低 | 已修 |
| M5 | 告警渠道/规则请求体 mass assignment | 中 | 已修（白名单） |
| M6 | 统计上报 `extra` 无大小限制，匿名入口可放大内存 | 中 | 已修（2KB） |
| M7 | `retention-days <= 0` 时 `minusDays(-1)` 清空整表 | 高 | 已修 |
| M8 | 错误汇总非分页接口无 LIMIT，整日 fingerprint 读入堆 | 高 | 已修（DB 分页 + 500 上限） |
| Q1 | 号段实体 ID 首段从 1 开始，与种子/存量主键冲突 | 高 | 已修（预留 1,000,000） |
| Q2 | 并发换段时旧号段被装回缓冲区，发号回退并重复 | 严重 | 已修 |
| Q3 | 预加载线程池不关闭；拒绝策略下 `loadingNext` 永久为 true | 中 | 已修 |
| Q4 | 号段上界计算 `Long.MAX_VALUE` 溢出 | 中 | 已修 |
| Q5 | `sequence_segment.version` NULL 导致 `SEQ005` 死循环 | 高 | 已修（同 D2） |
| Q6 | `GeneratorFactory` 空类型被默认类型覆盖、类型校验前先落库 | 高 | 已修 |
| Q7 | `astral.sequence.default-step` 配置未生效 | 中 | 已修 |
| Q8 | 序列配置编辑只提交三个字段，隐藏字段被清空 | 高 | 已修 |
| Q9 | 改 step 不同步 segment、删除不校验依赖、toggle 不校验行数 | 中 | 已修 |
| Q10 | 批量取号统计只记 1 次而非 batch 数量 | 中 | 已修 |
| Q11 | 批量接口响应只返回最后一个值，前端无法展示全部 | 中 | 已修 |
| Q12 | 64 位序列号经 JS `number` 丢精度 | 高 | 已修（String） |
| Q13 | 高频 `next`/`batch` 写操作日志，日志表被放大 | 中 | 已修 |
| Q14 | 号段/统计管理端写接口可直接改运行时状态，误删导致 ID 重发 | 高 | 已修（只读） |
| Q15 | `SequencePlugin.getApiPrefixes()` 与实际路由不一致，停用插件不拦截 | 中 | 已修 |
| Q16 | 启动高水位对齐 `tableMax + step` 溢出 | 中 | 已修 |
| P1 | 插件权限码文档仍写旧的三段式 `域:资源:操作` | 低 | 已修 |
| P2 | 插件 API 前缀示例仍写 `/api/v1/sequence` | 低 | 已修 |

未修复/残余风险见 §8；逐文件覆盖见附录 A。

---

## 1. astral-common

### C1 · 分页参数可无限放大（中，基线已有 clamp，非本轮修改）

- **实际文件位置**：`astral-common/src/main/java/com/astral/common/util/PageQuery.java`
- **影响**：手写 `new Page<>(pageNum, pageSize)` 的接口没有上限；`pageSize=1000000` 可一次拉全表，造成数据库/网络/序列化压力。分页参数为负数时 MyBatis-Plus 行为也不稳定。
- **推理依据**：`pageNum/pageSize` 直接透传，无 clamp；多个模块自行 `new Page<>`。
- **现状**：基线代码已实现 `PageQuery.pageNum()` clamp 到 `1..100_000`、`pageSize()` clamp 到 `1..200`、`limit()` 最多 100；本轮审计确认该实现存在并沿用，**未在本轮修改该文件**。
- **验证**：静态检查 `PageQuery` 调用点；`PageQuery` 无副作用。
- **当前状态**：基线已有，非本轮 authored；common 模块冻结。

### C2 · CSV 公式注入（中，已修）

- **实际文件位置**：`astral-common/src/main/java/com/astral/common/util/CsvExportUtil.java`
- **影响**：用户名、日志描述等字段以 `=`, `+`, `-`, `@` 开头时，Excel/WPS 会当作公式执行；攻击者可构造 `=HYPERLINK(...)` 或 DDE 载荷，导出文件成为钓鱼入口。
- **推理依据**：原实现只做 CSV 引号转义，未处理电子表格公式前缀。
- **修复**：对每个字符串单元格，若首字符属于 `= + - @`（含前置空白/制表符后的首字符）则前置单引号 `'`，保持 RFC 4180 引号转义。
- **验证**：`CommonUtilsTest` 覆盖普通值、逗号、引号、公式前缀；静态检查所有导出均走 `CsvExportUtil.build`。
- **当前状态**：已修；`CommonUtilsTest` 7 个用例在修复前由 Lead 复跑为 1 failure（断言期望 `<x>`，实际保留 `< x >`），修正断言后尚未由 Lead 复跑。

### C3 · SequenceMetrics 每次取号抢类锁 + reset 竞态（中，已修）

- **实际文件位置**：`astral-common/src/main/java/com/astral/common/util/SequenceMetrics.java`
- **影响**：`recordGeneration()` 每次 `synchronized updateQps()`，高 QPS 下成为全局锁瓶颈；`reset()` 未同步，与 `recordGeneration` 并发时窗口计数/起点可能不一致。
- **修复**：快路径只读 `volatile windowStartTime`，距窗口起点 ≥1s 才进入同步结算；`reset()` 改为 `synchronized`；`getQps()` 仍主动结算保证读取最新。
- **验证**：静态检查；并发正确性依赖 synchronized 语义，未做压力测试。
- **当前状态**：已修。

### C4 · ErrorCodes 消息模板注入（低，已修）

- **实际文件位置**：`astral-common/src/main/java/com/astral/common/error/ErrorCodes.java`
- **影响**：错误码从请求/配置拼接时可能传入 `{`/`}` 触发 `MessageFormat` 异常，导致错误处理自身 500；数字编码未校验时可读任意 properties 键。
- **修复**：错误码只接受 `[A-Za-z0-9_.-]+`；格式化异常回退原始模板，不让错误处理抛二次异常。
- **验证**：静态检查；`error-codes.properties` 新增 `SEQ009`。
- **当前状态**：已修。

### C5 · 局部更新丢字段（高，基线已有 `PatchValues`，本轮复用）

- **实际文件位置**：`astral-common/src/main/java/com/astral/common/util/PatchValues.java`
- **影响**：管理端编辑表单只提交变更字段时，若用 `updateById(客户端实体)` 会把未提交字段写成 NULL；用户看到「只改启用状态」，实际把 prefix/dateFormat/description 清空。修复前 `SequenceConfigController` 正是此模式。
- **现状**：基线已存在 `PatchValues` 三态语义：`null` = 保持当前值；空白字符串 = 显式清空为 NULL；非空 = 原样写入。`orCurrent(current, submitted)` 与 `blankToNull` 不 trim 非空值，避免用户输入的首尾空格被静默改写。本轮在 `SequenceConfigController` 中复用该 helper，**未修改 `PatchValues.java` 本身**。
- **验证**：静态检查 + `SequenceConfigControllerTest` 源码覆盖「只提交部分字段后其余字段保持」。
- **当前状态**：基线已有，非本轮 authored；common 冻结。

### C6/C7/C8 · 注解/测试/文档小项

- `RateLimit`、`RequiresPermission`、`RequiresSuper` 改为显式 import（AGENTS.md 禁止 `*` import），并加 `@Inherited` 支持类级继承；不影响运行时。
- `CommonUtilsTest` 期望值由 `<x>` 改为 `< x >`：`CommonUtils` 实现保留首尾空白是有意行为（防止拼装查询时改变语义），修测试不改实现。
- `Result` Javadoc 改为与实际 `Result<T>` 契约一致。

---

## 2. astral-dao

### D1 · 34 个生成实体缺主键/填充注解（高，已修）

- **实际文件位置**：`astral-dao/src/main/java/com/astral/dao/entity/*.java`（34 个实体）
- **影响**：生成器只输出字段，不输出 `@TableId(type = IdType.INPUT)`（业务表按 `mybatisPlusIdType: INPUT`，四张序列表为 `AUTO`）与 `@TableField(fill = FieldFill.INSERT/INSERT_UPDATE)`。缺少 `INPUT` 时 MyBatis-Plus 会对 `id` 采用默认 `ASSIGN_ID` 雪花值，绕过 `SequenceMetaObjectHandler` 的全局发号器；`create_time/update_time` 不自动填充，NOT NULL 违约或写入 null。
- **修复**：生成器模板补 `IdType`/`FieldFill` import 与注解，保留 schema JSON 的 `mybatisPlusIdType` 区分业务表 `INPUT` 与序列表 `AUTO`；按 schema 重新生成全部实体并格式化。`SequenceMetaObjectHandler` 继续对四张序列表排除主键取号、只填时间。
- **验证**：34 个实体逐一 grep 注解；实体字段与 schema JSON 对照。未运行编译（由 Lead 串行执行）。
- **当前状态**：已修。

### D2/D3 · `SequenceSegmentMapper` 乐观锁与初始化（高/中，已修）

- **实际文件位置**：`astral-dao/src/main/java/com/astral/dao/mapper/SequenceSegmentMapper.java`
- **影响**：
  - `version` 列默认 0 但允许 NULL；原 `WHERE version = #{version}` 对 NULL 行永不匹配，`SegmentGenerator` 重试到上限后抛 `SEQ005`，该 bizKey 永远无法发号。
  - `insertSegment` 以 `DEFAULT_STEP` 写 `step`，但 `max_value` 参数来自配置步长；且 `current_max_value` 未初始化，首次读回可能为 NULL。
  - 管理端改 `sequence_config.step` 后 `sequence_segment.step` 不变，实际发号步长与配置界面不一致。
- **修复**：`allocateNextSegment` 两处 `COALESCE(version, 0)`；`updateStepByBizKey` 同时 `version+1` 与 `update_time`；`insertSegment` 写 `current_max_value = maxValue`、`version = 1`，`ON CONFLICT (biz_key) DO NOTHING` 依赖唯一索引。
- **验证**：静态检查 + `GeneratorFactoryTest`/`SequenceConfigControllerTest` 源码覆盖。
- **当前状态**：已修。

### D4 · 统计累计值不递增（中，已修）

- **实际文件位置**：`astral-dao/src/main/java/com/astral/dao/mapper/SequenceStatisticsMapper.java`
- **影响**：`sequence_statistics.total_generate` 原只在插入时为 0，后续从不增加；管理页显示「总生成量」永远 0。旧数据 `total_generate` 可能为 NULL，`total_generate + #{generated}` 仍为 NULL。
- **修复**：新增 4 参 `updateCurrentValueByBizKey(bizKey,currentValue,generated,updateTime)`：`current_value = GREATEST(current_value, #{currentValue})`（只升不降）、`total_generate = COALESCE(total_generate, 0) + #{generated}`、`last_generate_time/update_time`；`insertIfAbsent` 并发安全，`selectByBizKey` 用 `SELECT *` 保留完整行。
- **验证**：`GeneratorFactoryTest` 覆盖批量统计传真实数量；`SequenceAsyncWriter` 对 null 当前值兜底。
- **当前状态**：已修。

### D5 · 错误汇总 SQL 全量分组（高，已修，DAO 文件由 monitor 使用）

- **实际文件位置**：`astral-dao/src/main/java/com/astral/dao/mapper/StatErrorLogMapper.java`
- **影响**：`selectErrorSummary` 无 LIMIT，`StatReportService` 先取全量分组再内存分页；异常客户端可制造海量 fingerprint（URL/堆栈维度）使单次查询占满堆。
- **修复**：新增 `countErrorSummary(...)` 同条件计数；`selectErrorSummary(..., limit, offset)` 在数据库层 `GROUP BY ... ORDER BY count DESC LIMIT #{limit} OFFSET #{offset}`；`StatReportService` 非分页概览硬上限 500 组，分页接口走 DB 分页。
- **验证**：`StatReportService` 静态检查；页码/页大小 clamp。
- **当前状态**：已修。

---

## 3. astral-schema

### S1 · CodeGenMojo fail-open（高，已修）

- **实际文件位置**：`astral-schema/src/main/java/com/astral/schema/codegen/CodeGenMojo.java`
- **影响**：原 Mojo 对非法 JSON 仅 `log.warn` 后继续；空 schema 目录视为成功；构建产物缺实体但 exit 0，后续编译/运行才暴雷。
- **修复**：非法 JSON、空目录、重复类名、路径越界全部聚合为 `MojoExecutionException` 并 fail build；`skipCodeGen=true` 在检查 output/schema 目录之前短路，允许仅生成其他模块。
- **验证**：`CodeGenMojoTest` 7 用例源码（`generatesEntityInConfiguredOutput`、`invalidJsonFailsBuildInsteadOfSkipping`、`emptySchemaDirectoryFailsBuild`、`classPathTraversalIsRejectedBeforeWriting`、`skipFlagDoesNotRequireSchemaDirectoryOrOutputDirectory`、`invalidSchemaAnywhereFailsBeforeAnyEntityIsWritten`、`duplicateClassNameFailsBeforeAnyEntityIsWritten`）；**未执行**，由 Lead 串行跑。
- **当前状态**：已修；schema 源/测试已冻结待 Lead 门禁。

### S2 · 输出路径穿越/重复类名（高，已修）

- **实际文件位置**：同 S1
- **影响**：`className`/`packageName` 未白名单时可用 `../../` 写出输出目录；两个 schema 声明同一 className 时后写覆盖先写，且无报错。
- **修复**：`PACKAGE_PATTERN`/`CLASS_NAME_PATTERN` 正则校验；输出路径 `normalize().startsWith(outputRoot)` containment；两阶段先构建全部输出 map 并检测重复，再统一写盘，任一失败不落任何文件；`writeIfChanged` 避免无变更文件更新时间。
- **验证**：`CodeGenMojoTest.classPathTraversalIsRejectedBeforeWriting`、`duplicateClassNameFailsBeforeAnyEntityIsWritten`（源码，未执行）。
- **当前状态**：已修。

### S3 · 资源生命周期与顺序（中，已修）

- **修复**：`Files.list` 改 try-with-resources；`.json` 文件排序后处理；每个文件独立 try/catch 但将失败汇总为 Mojo 异常（不是静默跳过）。
- **验证**：静态检查。
- **当前状态**：已修。

### S4 · 标识符/索引名长度（高，已修）

- **实际文件位置**：`astral-schema/src/main/java/com/astral/schema/SchemaCodeGenerator.java`、`SqlIdentifiers.java`
- **影响**：表名/字段名直接拼 DDL 可注入；PostgreSQL 标识符上限 63 字节，自动索引名 `idx_<table>_<field>` 超长时被数据库静默截断，后续 `DROP INDEX` 用完整名找不到对象；MySQL 上限 64。
- **修复**：`SqlIdentifiers.requireValid` 统一校验 `[A-Za-z_][A-Za-z0-9_]*` 且长度上限（并转小写）；`resolveIndexName` 超长时保留 `idx_<table>_` 前缀 + 截断字段 + 8 位无符号十六进制 hash，总长 ≤60，跨方言安全。负 hash 由 `Integer.toUnsignedString(name.hashCode(), 16)` 转为无符号 hex（`Integer.toHexString` 本身也不带负号，不能把它描述为「带负号」）。
- **验证**：`SchemaCodeGeneratorTest.autoIndexNameTruncationStaysWithinIdentifierWhitelist()` 使用 60 个 `a` 表名，断言 PG 索引名包含 `idx_` + 47 个 `a` + `_d2bc0966`（hash `-759428762` 的无符号 32 位表示）；**未执行**。
- **当前状态**：已修。

### S5 · DDL 生成正确性（中，已修）

- **实际文件位置**：`SchemaCodeGenerator.java`
- **影响**：MySQL/PG 方言混用、`ALTER` 变更列不完整、默认值引号/转义错误、主键/自增差异被误判为字段变化导致每次 sync 都重建索引。
- **修复**：
  - `generateCreateSql`：MySQL `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`，PG 表/列 COMMENT；`appendCreateIndex` PG 用 `IF NOT EXISTS`；`appendDropIndex` MySQL `DROP INDEX name ON table`、PG `DROP INDEX IF EXISTS`。
  - `formatDefaultValue`：数字、TRUE/FALSE（MySQL 1/0）、NULL/CURRENT_*/NOW()、字符串 `'...'` 去外层并转义 `''`。
  - `generateAlterSql`：add/drop/change + 按解析后的索引名/`indexSignature` 做差异；MySQL `MODIFY COLUMN`，PG 分 `TYPE`/`SET NOT NULL`/`SET DEFAULT`/`COMMENT` 多条；`fieldsEqual` 忽略 PK/auto-increment 等非变更项。
- **验证**：`SchemaCodeGeneratorTest` 全类源码（未执行）。
- **当前状态**：已修。

### S6 · SchemaRegistry 资源与覆盖（中，已修）

- **实际文件位置**：`astral-schema/src/main/java/com/astral/schema/SchemaRegistry.java`
- **影响**：`Files.list` 流未关闭导致 classpath 扫描句柄泄漏；外部 schema 文件损坏时静默跳过，生产环境看起来「schema 生效」实际未生效；同名 schema 由 classpath 顺序随机决定且无日志。
- **修复**：try-with-resources；`loadSchema` 跳过空 tableName、捕获 `JacksonException` 并告警；`init()` 清空缓存避免测试/热重启叠加；重复内置表名 `log.warn`；外部文件覆盖内置时 `log.info`。
- **验证**：静态检查。
- **当前状态**：已修。

### S7 · SchemaEntitySync fail-open 删除（高，已修）

- **实际文件位置**：`astral-schema/src/main/java/com/astral/schema/SchemaEntitySync.java`
- **影响**：原实现遍历 schema 文件，任一文件读取失败只记日志但继续，最终按「本次见到的类名集合」删除其余实体文件；一次 I/O 抖动即可批量删掉未解析模块的实体源码。
- **修复**：按文件名排序；每文件 try/catch 置 `allSchemasProcessed=false`；拒绝 null/blank `className` 与重复类名；仅当全部文件成功且预期集合非空时才执行删除；写文件仅写变更内容；删除阶段与生成阶段分离。
- **验证**：静态检查 + 与 CodeGenMojo 两阶段逻辑交叉核对。
- **当前状态**：已修。

### S8 · `sequence_config.step` 默认值漂移（中，已修）

- **实际文件位置**：`astral-schema/src/main/resources/schema/sequence_config.json`
- **影响**：JSON 默认 100，而运行时 `astral.sequence.default-step` 与文档均为 1000；新库初始化的号段步长与配置页显示不一致。
- **修复**：默认值改为 `"1000"`；`SequenceConfig` 实体同步重新生成。
- **验证**：JSON 与 `application.yml`、`GeneratorFactory` 默认值三方对照。
- **当前状态**：已修。

### S9 · 外部 schema 目录是受控信任边界（非缺陷）

`data/schemas/` 外部文件按设计覆盖内置 schema，属于运维可写的 DDL 输入；代码已做 tableName 校验与逐文件错误隔离。报告保留该边界说明，不作为漏洞。

---

## 4. astral-log

### L1 · 审计日志可被管理端伪造/删除（高，已修）

- **实际文件位置**：`astral-log/src/main/java/com/astral/log/controller/OperateLogController.java`、`LoginLogController.java`
- **影响**：原控制器暴露 `POST/PUT/DELETE`，持有 `admin:log:edit` 的账号可创建任意「谁在何时做了什么」的记录，或删除自己的操作痕迹，直接破坏审计可信度。
- **修复**：删除两个控制器的所有写接口，仅保留分页/详情/导出只读接口；导出增加 `orderByDesc("create_time"/"login_time")` 固定顺序。
- **验证**：静态检查路由表；前端只调用 page/get/export，无破坏。
- **当前状态**：已修。

### L2 · 日志异步提交异常覆盖业务响应（高，已修）

- **实际文件位置**：`astral-log/src/main/java/com/astral/log/aspect/OperateLogAspect.java`
- **影响**：`@Async` 方法在代理调用点抛 `TaskRejectedException`（队列满/线程池关闭）时，异常从切面冒泡，业务请求被 500；日志旁路变成业务单点。
- **修复**：新增 `safeSave(...)` 包裹 `operateLogWriter.saveAsync(...)`，捕获所有异常并 `log.error`，不改变业务返回值；成功/异常两条路径都走 `safeSave`。
- **验证**：`OperateLogWriterTest` 源码覆盖 writer 正常写入/异常不冒泡（未执行）。
- **当前状态**：已修。

### L3/L6 · 代理注解解析（中，已修）

- **实际文件位置**：`OperateLogAspect.java`、`LoginLogAspect.java`
- **影响**：`signature.getMethod()` 是接口方法，JDK/CGLIB 代理下读不到实现类/父类上的 `@OperateLog`/`@LoginLog`，导致日志类型为空或整条不记录。
- **修复**：`AopUtils.getMostSpecificMethod(signature.getMethod(), target.getClass())` + `AnnotationUtils.findAnnotation(...)`。
- **验证**：静态检查；`OperateLogWriterTest` 覆盖 writer，不覆盖 AOP 代理（残余测试缺口，见 §8）。
- **当前状态**：已修。

### L4 · 操作日志 userId 丢失（中，已修）

- **实际文件位置**：`astral-log/src/main/java/com/astral/log/aspect/OperateLogWriter.java`
- **影响**：writer 签名不含 userId，`OperateLog.userId` 永远 null，管理端无法按用户检索审计日志。
- **修复**：`saveAsync/save` 增加 `Long userId` 参数；`OperateLogAspect.getUserId()` 同时兼容拦截器写入的 String 与 App 插件写入的 Long，非法值返回 null 而非抛异常。
- **验证**：静态检查调用链；`OperateLogWriterTest` 源码。
- **当前状态**：已修。

### L5 · 导出顺序（低，已修）

`LoginLogController`/`OperateLogController` 导出固定按时间倒序；导出 10,000 条上限保留为运维约束（见 §8）。

---

## 5. astral-monitor

### M1 · 告警 Webhook SSRF（严重，已修）

- **实际文件位置**：`astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java`、新增 `WebhookUrlPolicy.java`、`AlertAdminController.java`
- **影响**：拥有 `admin:monitor:alert:edit` 的普通管理员可把 Webhook URL 填成 `http://127.0.0.1:8080/...`、`http://169.254.169.254/latest/meta-data/` 或任意内网地址；服务端会携带内网身份发起请求，属于典型 SSRF，可探测/访问云元数据。原实现仅校验 URL 非空。
- **修复**：
  - `WebhookUrlPolicy`：scheme 仅 http/https；origin 必须命中 `astral.monitor.alert.webhook.allowed-origins`（默认钉钉/飞书/企业微信官方域名）；拒绝 userInfo/fragment；DNS 解析所有地址必须是公网（回环、私网、链路本地、组播、全零、IPv6 ULA、IPv4-mapped 私网均拒绝），云元数据地址任何配置下都拒绝。
  - `AlertEngine` 保存时 `validateChannelConfig`，每次发送前 `webhookUrlPolicy.validate(url)` 复检，`HttpClient` 固定 `Redirect.NEVER`，防止 302 跳到内网。
  - 自定义 header 名 `[A-Za-z0-9-]{1,64}`，secret 禁止 CR/LF，避免头注入。
- **验证**：`WebhookUrlPolicyTest` 9 用例源码（合法 origin、私网/回环/元数据拒绝、userInfo/fragment、IPv4-mapped、allow-private 开关等），**未执行**。
- **残余**：校验与 JDK HttpClient 实际连接之间存在 DNS rebinding 窗口（JDK 不支持连接时固定 IP），已在类 Javadoc 记录；强保证需网络层 egress 限制。
- **当前状态**：已修，残余风险已文档化。

### M2 · 阈值非法导致持续轰炸（高，已修）

- **实际文件位置**：`AlertEngine.java`
- **影响**：`threshold` null 或 ≤0 时比较恒成立，每个调度周期都触发并写 `last_fired_at`，形成无限告警；旧数据/直接改库可触发。
- **修复**：评估前校验 `threshold == null || threshold < 1`，记录 warn 并跳过本轮。
- **验证**：静态检查。
- **当前状态**：已修。

### M3 · 告警规则丢失更新（中，已修）

- **实际文件位置**：`AlertEngine.java`
- **影响**：评估开始读取整行，触发后用 `updateById(rule)` 回写，把管理员期间修改的阈值/窗口/渠道覆盖回旧值。
- **修复**：只通过 `LambdaUpdateWrapper` 更新 `last_fired_at`/`update_time`，不写其他列。
- **验证**：静态检查。
- **当前状态**：已修。

### M4/M5 · 渠道并发与 mass assignment（低/中，已修）

- `createChannel`/`updateChannel` 捕获 `DuplicateKeyException` → `BusinessException("渠道名称已存在")`，不再把数据库约束暴露成 500。
- 创建/更新使用显式 `ChannelRequest`/`RuleRequest` 白名单字段，`remark` 在更新时显式设置（省略即清空是产品语义，已记录）；规则更新不整体 `updateById`。
- **验证**：静态检查；`AlertAdminController` 分页使用 `PageQuery`。

### M6 · `extra` 放大内存（中，已修）

- **实际文件位置**：`astral-monitor/src/main/java/com/astral/monitor/service/StatIngestService.java`
- **影响**：`extra` 为任意 `Object`，匿名上报入口可塞入巨大嵌套 JSON，反序列化后占堆。
- **修复**：`JSON_MAPPER.writeValueAsString(extra)` 后超过 2KB 整条事件丢弃并 warn；不可序列化的值同样丢弃。
- **验证**：静态检查。
- **当前状态**：已修。

### M7 · 保留天数配置误写清空表（高，已修）

- **实际文件位置**：`astral-monitor/src/main/java/com/astral/monitor/job/StatAggregationJob.java`
- **影响**：`retention-days=-1` 时 `LocalDate.now().minusDays(-1)` 得到明天，`lt(occur_time, 明天)` 删除全部历史；单个配置 typo 即不可恢复数据事故。
- **修复**：`<=0` 时跳过该类清理并 warn；每类清理独立 try/catch，失败保留数据待下次重试，不阻断其他类别。
- **验证**：静态检查；配置默认 90/180 天。
- **当前状态**：已修。

### M8 · 错误汇总无界 GROUP BY（高，已修）

- **实际文件位置**：`StatReportService.java`、`StatErrorLogMapper.java`
- **影响**：非分页概览调用 `selectErrorSummary` 无 LIMIT，GROUP BY 结果全量进堆；分页接口先取全量再内存切片，页码越大越慢，恶意查询可 OOM。
- **修复**：非分页概览 `MAX_ERROR_SUMMARY_GROUPS = 500` 上限；分页接口 `countErrorSummary` + DB `LIMIT/OFFSET`；页码/页大小 clamp 到 `PageQuery` 上限；结果按参数缓存 60 秒。
- **验证**：静态检查；`StatReportService` 无编译依赖改动。
- **当前状态**：已修。

---

## 6. astral-sequence

### Q1 · 实体 ID 首段覆盖种子区（高，已修）

- **实际文件位置**：`astral-sequence/src/main/java/com/astral/sequence/generator/SegmentGenerator.java`、`SequencePlugin.java`、`EntityIdSequenceProvider.java`
- **影响**：首次初始化 `sequence_segment` 时从 `[1, step]` 发号；初始化 SQL 使用显式主键 `1..N`，插件表晚于启动建表时首次插入 ID=1，主键冲突；历史数据迁移后同样冲突。
- **修复**：`EntityIdSequenceProvider.RESERVE = 1_000_000`；`SegmentGenerator.initializeSegment` 对 `_id` 结尾业务键固定预留 `[1, RESERVE]`；`SequencePlugin.initTableSequence` 同步预留并写 `step=1000`；`alignHighWatermark` 只升不降。
- **验证**：`GeneratorFactoryTest`/`SequenceConfigControllerTest` 源码；`SegmentGenerator.initializeSegment` 无独立测试（残余）。
- **当前状态**：已修。

### Q2 · 并发换段回退导致 ID 重复（严重，已修）

- **实际文件位置**：`SegmentGenerator.java`
- **影响**：两个线程同时发现 current 耗尽，各自申请新段。线程 A 先 CAS 安装较大段，线程 B 后到 CAS 失败，把 B 申请的较小段放回 `next`；A 段耗尽后从 B 的旧段继续发号，ID 回退并重复。另有 `fetchSegmentFromDb` 在 `current` 已被替换时把基于旧基线的预加载结果写回。
- **修复**：CAS 失败仅当 `nextSeg.max > switched.max` 才保留；`tryPreloadNextAsync` 写入前核对 `current.get() == seg`；`fetchSegmentFromDb` 在 `current` 存在且新段不大于当前段时丢弃；`initializeSegment` 并发插入后重新读取赢家。
- **验证**：`GeneratorFactoryTest.concurrentAutoCreateReReadsWinner` 源码（未执行）。
- **当前状态**：已修。

### Q3/Q4 · 资源与溢出（中，已修）

- `@PreDestroy shutdownPreloadExecutor()` 关闭虚拟线程预加载执行器。
- `RejectedExecutionException` 时复位 `loadingNext=false` 并 warn，避免永久退化为同步取段。
- `baseMax > Long.MAX_VALUE - step` 抛 `SEQ004`；`min > Long.MAX_VALUE - step + 1` 同样拦截。
- **验证**：静态检查。

### Q5 · NULL version（高，已修）

详见 D2；`allocateNextSegment` 使用 `COALESCE(version, 0)` 双向匹配，修复 `SEQ005` 死循环。

### Q6/Q7 · GeneratorFactory 类型与默认步长（高/中，已修）

- **实际文件位置**：`astral-sequence/src/main/java/com/astral/sequence/service/GeneratorFactory.java`
- **影响**：
  - 请求未传 type 时，原实现把 `type` 设为 `defaultType`（segment），而不是复用已有配置类型；对已配置 `SIMPLE` 的 bizKey，`next` 会拿 segment 生成器并发号，行为不一致。
  - 未知 type 在插入 `sequence_config` 之后才校验，typo 被持久化为配置，后续一直 `SEQ006` 或不一致。
  - `astral.sequence.default-step` 完全未使用，自动创建配置固定 1000，配置无法调整。
- **修复**：type 先校验（`SUPPORTED_TYPES = SEGMENT/SNOWFLAKE/REDIS/DATABASE/SIMPLE`，未知抛 `SEQ002`）；null 时复用已有配置类型；自动创建配置写 `defaultStep`；插入冲突后重读赢家；批量为每个值记 metrics；缓存 Caffeine 1000/60s。
- **验证**：`GeneratorFactoryTest` 8 用例源码（batch 0 拒绝、未知 type、禁用 `SEQ009`、并发创建重读、非重复插入传播、省略 type 复用、校验先于插入、实体 ID 不允许切换 SEGMENT），**未执行**。
- **当前状态**：已修。

### Q8/Q9 · 序列配置编辑丢字段与一致性（高/中，已修）

- **实际文件位置**：`astral-sequence/src/main/java/com/astral/sequence/controller/SequenceConfigController.java`
- **影响**：前端编辑只提交 `{bizKey, sequenceType, enabled}`；原实现 `updateById(entity)` 将 `step/dateFormat/prefix/suffix/minValue/description` 全写 NULL。`step` 变更不同步 `sequence_segment.step`；删除配置不检查 segment/statistics 依赖；toggle 不检查 update 行数，配置不存在也返回成功。
- **修复**：`PatchValues` 三态；bizKey 不可改、type 不可变（null 保持）；step 1..1,000,000、minValue ≥1；`LambdaUpdateWrapper` 只写白名单字段；`@Transactional`；step 变更时 `updateStepByBizKey`；删除前存在 segment/statistics 则拒绝；toggle 检查受影响行数；`_id` 系统键禁止创建/修改/删除。
- **验证**：`SequenceConfigControllerTest` 7 用例源码（部分更新保留、step 同步、删除保护、toggle 行数、类型不可变、重复 bizKey、系统键拒绝），**未执行**。
- **当前状态**：已修。

### Q10 · 统计总生成量（中，已修）

- `SequenceAsyncWriter` 新增 `(bizKey, currentValue, generated)` 重载，批量调用传真实 `batchHistory.size()`；`GeneratorFactory` 批量路径逐值 `recordGeneration`；`SequenceStatisticsMapper` `COALESCE` 累加；`upsertStatisticsAsync` 对 `currentValue` null 兜底 0。
- **验证**：静态检查 + `GeneratorFactoryTest` 源码。
- **当前状态**：已修。

### Q11/Q12 · 批量响应与 64 位精度（中/高，已修）

- **实际文件位置**：`SequenceController.java`、`SequenceResponse.java`、`SequenceHistoryController.java`、新增 `SequenceHistoryResponse.java`、`astral-front/src/api/sequence.ts`、`dashboard/sequence/page.tsx`
- **影响**：批量接口只返回最后一个值，前端无法复制完整批量结果；`Long` 序列号超过 `2^53` 经 JS number 丢精度，展示/回填错误值。
- **修复**：`SequenceResponse` 增加 `String sequence` 与 `values`（逗号分隔全部值）；历史 `sequenceValue` 改 String；前端按 `values` 分行展示（monospace、maxHeight 240、overflow auto、pre-wrap），历史类型改 string。
- **验证**：`npx tsc --noEmit` 未执行（Lead 决定）；静态检查字段。
- **当前状态**：已修。

### Q13 · 高频操作日志（中，已修）

`SequenceController.next/batch` 移除 `@OperateLog`，保留 `@RateLimit(100/60s)` 与批量 `50/60s`；高频发号不再每次写 `sys_operate_log`，避免日志表被单接口放大。

### Q14 · 号段/统计写接口暴露（高，已修）

`SequenceSegmentController`、`SequenceStatisticsController` 删除全部 POST/PUT/DELETE，仅保留分页/详情只读；路由仍在 `/api/v1/admin/sequence/segment|statistics`，权限 `admin:sequence:view`。这是有意的内部 API 收紧：号段 `max_value/version` 被误改会直接导致 ID 重复，统计表由异步写入器维护。前端无调用方（已核对 `astral-front`）。

### Q15/Q16 · 插件前缀与高水位溢出（中，已修）

- `SequencePlugin.getApiPrefixes()` 改为 `List.of("/api/v1/all/sequence")`，与实际 `SequenceController` 路由一致；插件禁用时 `PluginApiInterceptor` 能拦截。
- `alignHighWatermark` 计算 `aligned = tableMax + step` 前检查 `tableMax > Long.MAX_VALUE - step`，超限抛 `SEQ004`，不产生负数上限。
- `initTableSequence` 对 `_id` 键预留 `[1,reserve]` 并锁 SEGMENT step 1000。

### Q17-Q21 · 残余风险（未修复，见 §8）

- `AsyncConfig` 平台线程池 `DiscardOldestPolicy` 静默丢历史/统计；默认虚拟线程模式无此队列。
- `sequence_history` 无保留策略，长期增长。
- `DatabaseGenerator`/`SimpleGenerator`/`SnowflakeGenerator` 忽略 `minValue`；`SimpleGenerator` 从 0 开始；`SequenceConfig.prefix/suffix/dateFormat` 对生成值无实际作用。
- `SegmentGenerator.bufferMap`、`SnowflakeGenerator`/`SimpleGenerator` 的 per-bizKey map 无淘汰上限。
- `RateLimitInterceptor` 为单实例内存滑动窗口，多实例不共享；`key="user"` 未登录时统一落 `anonymous` 桶，`/api/v1/all/sequence` 匿名请求互相挤占。

---

## 7. astral-plugin-api

### P1/P2 · 文档契约漂移（低，已修）

- **实际文件位置**：`astral-plugin-api/src/main/java/com/astral/plugin/api/PermissionProvider.java`、`AstralPlugin.java`
- **影响**：权限码文档仍写 `域:资源:操作[:范围]`，实际 AGENTS.md 要求 `端:域:资源:操作[:范围]`。插件作者按旧文档声明会得到 `domain` 解析错误（把 `admin` 当域），管理端分组错乱。`AstralPlugin` 示例前缀 `/api/v1/sequence` 与实际 `/api/v1/all/sequence` 不一致，插件停用拦截失效。
- **修复**：文档改为四/五段式并说明 `domain` 取跳过端段后的第 2 段；示例改为 `/api/v1/all/sequence`。
- **验证**：静态检查；本模块其余 3 个接口（`PluginConfigProvider`、`PluginFrontendExtension`、`PluginRegistry`）审阅未发现阻断问题。
- **当前状态**：已修（仅注释）。

---

## 8. 残余风险与未修复项（明确不声称已解决）

| 编号 | 残余项 | 影响 | 建议/边界 |
|---|---|---|---|
| R1 | `WebhookUrlPolicy` DNS rebinding 窗口 | 校验与实际连接解析可能不同 | 文档已记录；生产建议网络层 egress 白名单 |
| R2 | `sequence_history` 无保留策略 | 单表无限增长 | 需要产品确定保留期；建议定时归档/清理，不在本次擅自删数据 |
| R3 | 号段/雪花/简单生成器忽略 `minValue`、`prefix/suffix/dateFormat` | 管理端字段看似可配但部分类型不生效 | 文档标注或后续按生成器补齐；当前未改语义 |
| R4 | 每 bizKey 内存 map 无上限 | 大量动态 bizKey 时堆增长 | 建议后续引入最大条目/LRU |
| R5 | `RateLimitInterceptor` 单实例内存窗口 | 多实例部署限流失效；anonymous 共享桶 | 建议 Redis 滑动窗口；单机部署可接受 |
| R6 | `AsyncConfig` 平台池 `DiscardOldestPolicy` 静默丢弃 | 历史/统计可丢 | 默认虚拟线程；平台模式为显式降级，建议改为 CallerRuns 或记录丢弃 |
| R7 | 日志导出固定上限 10,000 条 | 大量日志导出不全 | 运维约束，避免一次拉全表；需要全量走异步导出 |
| R8 | `OperateLogAspect` 代理注解解析无单测 | 回归缺口 | 已有 writer 测试，AOP 需集成测试；本次未新增 |
| R9 | `SegmentGenerator.initializeSegment` 无独立测试 | 预留区回归缺口 | 已有 GeneratorFactory 测试覆盖工厂层；建议后续补单测 |
| R10 | `SequenceConfigController` 在 Controller 层使用 `@Transactional` | 架构分层不理想 | 行为正确；后续可下沉 service |
| R11 | 删除序列配置与首次异步统计写入存在竞态 | 配置删除后可能残留一条统计行 | 首次统计异步落地前删除；影响仅残留行，不影响发号 |
| R12 | `sequence_segment.version` 旧数据迁移依赖 `COALESCE` 修复 | 不迁移也可工作 | 已兼容 NULL；建议后续 DDL 收紧 NOT NULL |
| R13 | `SchemaCodeGenerator` 超长索引名注释写「直接 toHexString 会带 '-'」 | 事实错误，可能误导后续维护；实现本身使用 `Integer.toUnsignedString`，无运行时影响 | schema 已冻结，未改；建议 Lead 门禁后把注释改为「hashCode 可能为负，使用无符号十六进制避免非法字符」 |

---

## 9. 回归与门禁状态（必须按实际执行更新）

| 项目 | 状态 | 说明 |
|---|---|---|
| `mvn -pl astral-common ...` | 未由本报告执行 | common 源/测试冻结，之前 Lead 仅复跑过 `CommonUtilsTest`（7 tests，1 failure，0 errors，0 skipped），失败为测试断言 `<x>` vs 实际 `< x >`，已修断言但**修复后未复跑** |
| `CodeGenMojoTest`（7） | 未执行 | 源码已补齐；Lead 将串行运行 common+schema 聚焦 |
| `SchemaCodeGeneratorTest` | 未执行 | 新增索引名截断用例；负 hash 说明见 S4 |
| `WebhookUrlPolicyTest`（9） | 未执行 | 源码已补齐 |
| `SequenceConfigControllerTest`（7） | 未执行 | 源码已补齐 |
| `GeneratorFactoryTest`（8） | 未执行 | 源码已补齐 |
| `OperateLogWriterTest` | 未执行 | 源码已补齐 |
| `mvn spotless:check`（5 模块） | 未执行 | 曾尝试用 Palantir 2.39.0 + JDK 21 对新增/修改 Java 做 `--replace`（exit 0），但该次没有可核验输出，不能据此声称格式化通过；spotless 插件实际门禁以 Lead 为准 |
| `npx tsc --noEmit`（astral-front） | 未执行 | 改动 `sequence.ts`/`sequenceConfig.ts`/`sequence/page.tsx`；Lead 决定 |
| 全 reactor `package`/`install`（新插件 + 34 实体对照） | 未执行 | 必须由 Lead 串行执行 |

**结论口径**：本报告所有「已修」仅代表源码已完成修改并通过静态审阅/格式化，不代表编译或测试通过；最终以 Lead 串行门禁结果为准。

---

## 10. 变更文件清单（本域 authored）

### astral-common（冻结）
- `src/main/java/com/astral/common/annotation/RateLimit.java`
- `src/main/java/com/astral/common/annotation/RequiresPermission.java`
- `src/main/java/com/astral/common/annotation/RequiresSuper.java`
- `src/main/java/com/astral/common/error/ErrorCodes.java`
- `src/main/java/com/astral/common/result/Result.java`
- `src/main/java/com/astral/common/util/CsvExportUtil.java`
- `src/main/java/com/astral/common/util/SequenceMetrics.java`
- `src/main/resources/error-codes.properties`
- `src/test/java/com/astral/common/util/CommonUtilsTest.java`
- `pom.xml`（JUnit test 依赖）

### astral-dao
- `src/main/java/com/astral/dao/entity/*.java`（34 个实体）
- `src/main/java/com/astral/dao/mapper/SequenceSegmentMapper.java`
- `src/main/java/com/astral/dao/mapper/SequenceStatisticsMapper.java`
- `src/main/java/com/astral/dao/mapper/StatErrorLogMapper.java`

### astral-schema（已冻结）
- `pom.xml`
- `src/main/java/com/astral/schema/SchemaCodeGenerator.java`
- `src/main/java/com/astral/schema/SchemaEntitySync.java`
- `src/main/java/com/astral/schema/SchemaRegistry.java`
- `src/main/java/com/astral/schema/SqlIdentifiers.java`
- `src/main/java/com/astral/schema/codegen/CodeGenMojo.java`
- `src/main/resources/schema/sequence_config.json`
- `src/test/java/com/astral/schema/SchemaCodeGeneratorTest.java`
- `src/test/java/com/astral/schema/codegen/CodeGenMojoTest.java`

### astral-log
- `src/main/java/com/astral/log/annotation/LoginLog.java`
- `src/main/java/com/astral/log/annotation/OperateLog.java`
- `src/main/java/com/astral/log/aspect/LoginLogAspect.java`
- `src/main/java/com/astral/log/aspect/OperateLogAspect.java`
- `src/main/java/com/astral/log/aspect/OperateLogWriter.java`
- `src/main/java/com/astral/log/controller/LoginLogController.java`
- `src/main/java/com/astral/log/controller/OperateLogController.java`
- `src/test/java/com/astral/log/aspect/OperateLogWriterTest.java`
- `pom.xml`

### astral-monitor
- `src/main/java/com/astral/monitor/alert/AlertAdminController.java`
- `src/main/java/com/astral/monitor/alert/AlertConfigParser.java`
- `src/main/java/com/astral/monitor/alert/AlertEngine.java`
- `src/main/java/com/astral/monitor/alert/WebhookUrlPolicy.java`（新增）
- `src/main/java/com/astral/monitor/dto/StatEventDTO.java`
- `src/main/java/com/astral/monitor/job/StatAggregationJob.java`
- `src/main/java/com/astral/monitor/service/StatIngestService.java`
- `src/main/java/com/astral/monitor/service/StatReportService.java`
- `src/test/java/com/astral/monitor/alert/WebhookUrlPolicyTest.java`
- `pom.xml`

### astral-sequence
- `src/main/java/com/astral/sequence/config/EntityIdSequenceProvider.java`
- `src/main/java/com/astral/sequence/controller/SequenceConfigController.java`
- `src/main/java/com/astral/sequence/controller/SequenceController.java`
- `src/main/java/com/astral/sequence/controller/SequenceHistoryController.java`
- `src/main/java/com/astral/sequence/controller/SequenceSegmentController.java`
- `src/main/java/com/astral/sequence/controller/SequenceStatisticsController.java`
- `src/main/java/com/astral/sequence/controller/dto/SequenceBatchRequest.java`
- `src/main/java/com/astral/sequence/controller/dto/SequenceHistoryResponse.java`（新增）
- `src/main/java/com/astral/sequence/controller/dto/SequenceRequest.java`
- `src/main/java/com/astral/sequence/controller/dto/SequenceResponse.java`
- `src/main/java/com/astral/sequence/generator/SegmentGenerator.java`
- `src/main/java/com/astral/sequence/plugin/SequencePlugin.java`
- `src/main/java/com/astral/sequence/service/GeneratorFactory.java`
- `src/main/java/com/astral/sequence/service/SequenceAsyncWriter.java`
- `src/main/java/com/astral/sequence/service/SequenceResult.java`（新增）
- `src/main/java/com/astral/sequence/service/SequenceService.java`
- `src/test/java/com/astral/sequence/controller/SequenceConfigControllerTest.java`
- `src/test/java/com/astral/sequence/service/GeneratorFactoryTest.java`
- `pom.xml`

### astral-plugin-api
- `src/main/java/com/astral/plugin/api/AstralPlugin.java`（注释）
- `src/main/java/com/astral/plugin/api/PermissionProvider.java`（注释）

### astral-front（接口契约联动，非本域主代码）
- `src/api/sequence.ts`
- `src/api/sequenceConfig.ts`
- `src/app/dashboard/sequence/page.tsx`

### astral-server（基线已有，仅记录，非本轮 authored）
- `src/main/resources/db/migration/V20260928001__sequence_segment_unique_key.sql`

---

## 附录 A · 逐文件覆盖清单（7 模块，179 个主代码/资源文件）

说明：`全文件` = 逐行审阅；`批量结构核查` = 与 schema JSON/DDL/生成模板对照检查；`静态审阅` = 方法签名、SQL、权限、空值路径检查。所有文件均列入实际清点，不是估算。

### astral-common

| # | 文件 | 覆盖方式 | 状态 |
|---:|---|---|---|
| 1 | `com\astral\common\annotation\RateLimit.java` | 全文件/关键路径 | 修改 |
| 2 | `com\astral\common\annotation\RequiresPermission.java` | 全文件/关键路径 | 修改 |
| 3 | `com\astral\common\annotation\RequiresSuper.java` | 全文件/关键路径 | 修改 |
| 4 | `com\astral\common\constant\ApiConstants.java` | 全文件/关键路径 | 静态审阅 |
| 5 | `com\astral\common\constant\ErrorCode.java` | 全文件/关键路径 | 静态审阅 |
| 6 | `com\astral\common\constant\NoticeConstants.java` | 全文件/关键路径 | 静态审阅 |
| 7 | `com\astral\common\constant\PermissionType.java` | 全文件/关键路径 | 静态审阅 |
| 8 | `com\astral\common\constant\SequenceType.java` | 全文件/关键路径 | 静态审阅 |
| 9 | `com\astral\common\error\ErrorCodes.java` | 全文件/关键路径 | 修改 |
| 10 | `com\astral\common\exception\BusinessException.java` | 全文件/关键路径 | 静态审阅 |
| 11 | `com\astral\common\result\Result.java` | 全文件/关键路径 | 修改 |
| 12 | `com\astral\common\util\ClientHeaders.java` | 全文件/关键路径 | 静态审阅 |
| 13 | `com\astral\common\util\ClientIp.java` | 全文件/关键路径 | 静态审阅 |
| 14 | `com\astral\common\util\CsvExportUtil.java` | 全文件/关键路径 | 修改 |
| 15 | `com\astral\common\util\LogSanitizer.java` | 全文件/关键路径 | 静态审阅 |
| 16 | `com\astral\common\util\PageQuery.java` | 全文件/关键路径 | 基线已有（非本轮） |
| 17 | `com\astral\common\util\PasswordPolicy.java` | 全文件/关键路径 | 静态审阅 |
| 18 | `com\astral\common\util\PatchValues.java` | 全文件/关键路径 | 基线已有（非本轮） |
| 19 | `com\astral\common\util\SequenceMetrics.java` | 全文件/关键路径 | 修改 |
| 20 | `com\astral\common\web\CsrfTokenSupport.java` | 全文件/关键路径 | 静态审阅 |

### astral-dao

| # | 文件 | 覆盖方式 | 状态 |
|---:|---|---|---|
| 1 | `com\astral\dao\entity\AlertChannel.java` | 全文件/关键路径 | 批量结构核查 |
| 2 | `com\astral\dao\entity\AlertRecord.java` | 全文件/关键路径 | 批量结构核查 |
| 3 | `com\astral\dao\entity\AlertRule.java` | 全文件/关键路径 | 批量结构核查 |
| 4 | `com\astral\dao\entity\DictData.java` | 全文件/关键路径 | 批量结构核查 |
| 5 | `com\astral\dao\entity\DictType.java` | 全文件/关键路径 | 批量结构核查 |
| 6 | `com\astral\dao\entity\LoginLog.java` | 全文件/关键路径 | 批量结构核查 |
| 7 | `com\astral\dao\entity\OperateLog.java` | 全文件/关键路径 | 批量结构核查 |
| 8 | `com\astral\dao\entity\Permission.java` | 全文件/关键路径 | 批量结构核查 |
| 9 | `com\astral\dao\entity\Role.java` | 全文件/关键路径 | 批量结构核查 |
| 10 | `com\astral\dao\entity\RolePermission.java` | 全文件/关键路径 | 批量结构核查 |
| 11 | `com\astral\dao\entity\SequenceConfig.java` | 全文件/关键路径 | 批量结构核查 |
| 12 | `com\astral\dao\entity\SequenceHistory.java` | 全文件/关键路径 | 批量结构核查 |
| 13 | `com\astral\dao\entity\SequenceSegment.java` | 全文件/关键路径 | 批量结构核查 |
| 14 | `com\astral\dao\entity\SequenceStatistics.java` | 全文件/关键路径 | 批量结构核查 |
| 15 | `com\astral\dao\entity\StatApiHourly.java` | 全文件/关键路径 | 批量结构核查 |
| 16 | `com\astral\dao\entity\StatDevice.java` | 全文件/关键路径 | 批量结构核查 |
| 17 | `com\astral\dao\entity\StatErrorLog.java` | 全文件/关键路径 | 批量结构核查 |
| 18 | `com\astral\dao\entity\StatMetricHourly.java` | 全文件/关键路径 | 批量结构核查 |
| 19 | `com\astral\dao\entity\StatPageHourly.java` | 全文件/关键路径 | 批量结构核查 |
| 20 | `com\astral\dao\entity\SysConfig.java` | 全文件/关键路径 | 批量结构核查 |
| 21 | `com\astral\dao\entity\SysMailAccount.java` | 全文件/关键路径 | 批量结构核查 |
| 22 | `com\astral\dao\entity\SysMailLog.java` | 全文件/关键路径 | 批量结构核查 |
| 23 | `com\astral\dao\entity\SysMailTemplate.java` | 全文件/关键路径 | 批量结构核查 |
| 24 | `com\astral\dao\entity\SysMenu.java` | 全文件/关键路径 | 批量结构核查 |
| 25 | `com\astral\dao\entity\SysNotice.java` | 全文件/关键路径 | 批量结构核查 |
| 26 | `com\astral\dao\entity\SysNotifyRule.java` | 全文件/关键路径 | 批量结构核查 |
| 27 | `com\astral\dao\entity\SysNotifyRuleChannel.java` | 全文件/关键路径 | 批量结构核查 |
| 28 | `com\astral\dao\entity\SysNotifyRuleRecipient.java` | 全文件/关键路径 | 批量结构核查 |
| 29 | `com\astral\dao\entity\SysPlugin.java` | 全文件/关键路径 | 批量结构核查 |
| 30 | `com\astral\dao\entity\SysSmsLog.java` | 全文件/关键路径 | 批量结构核查 |
| 31 | `com\astral\dao\entity\SysSmsProvider.java` | 全文件/关键路径 | 批量结构核查 |
| 32 | `com\astral\dao\entity\SysSmsTemplate.java` | 全文件/关键路径 | 批量结构核查 |
| 33 | `com\astral\dao\entity\User.java` | 全文件/关键路径 | 批量结构核查 |
| 34 | `com\astral\dao\entity\UserRole.java` | 全文件/关键路径 | 批量结构核查 |
| 35 | `com\astral\dao\mapper\AlertChannelMapper.java` | 全文件/关键路径 | 静态审阅 |
| 36 | `com\astral\dao\mapper\AlertRecordMapper.java` | 全文件/关键路径 | 静态审阅 |
| 37 | `com\astral\dao\mapper\AlertRuleMapper.java` | 全文件/关键路径 | 静态审阅 |
| 38 | `com\astral\dao\mapper\DictDataMapper.java` | 全文件/关键路径 | 静态审阅 |
| 39 | `com\astral\dao\mapper\DictTypeMapper.java` | 全文件/关键路径 | 静态审阅 |
| 40 | `com\astral\dao\mapper\LoginLogMapper.java` | 全文件/关键路径 | 静态审阅 |
| 41 | `com\astral\dao\mapper\OperateLogMapper.java` | 全文件/关键路径 | 静态审阅 |
| 42 | `com\astral\dao\mapper\PermissionMapper.java` | 全文件/关键路径 | 静态审阅 |
| 43 | `com\astral\dao\mapper\RoleMapper.java` | 全文件/关键路径 | 静态审阅 |
| 44 | `com\astral\dao\mapper\RolePermissionMapper.java` | 全文件/关键路径 | 静态审阅 |
| 45 | `com\astral\dao\mapper\SequenceConfigMapper.java` | 全文件/关键路径 | 静态审阅 |
| 46 | `com\astral\dao\mapper\SequenceHistoryMapper.java` | 全文件/关键路径 | 静态审阅 |
| 47 | `com\astral\dao\mapper\SequenceSegmentMapper.java` | 全文件/关键路径 | 修改 |
| 48 | `com\astral\dao\mapper\SequenceStatisticsMapper.java` | 全文件/关键路径 | 修改 |
| 49 | `com\astral\dao\mapper\StatApiHourlyMapper.java` | 全文件/关键路径 | 静态审阅 |
| 50 | `com\astral\dao\mapper\StatDeviceMapper.java` | 全文件/关键路径 | 静态审阅 |
| 51 | `com\astral\dao\mapper\StatErrorLogMapper.java` | 全文件/关键路径 | 修改 |
| 52 | `com\astral\dao\mapper\StatMetricHourlyMapper.java` | 全文件/关键路径 | 静态审阅 |
| 53 | `com\astral\dao\mapper\StatPageHourlyMapper.java` | 全文件/关键路径 | 静态审阅 |
| 54 | `com\astral\dao\mapper\SysConfigMapper.java` | 全文件/关键路径 | 静态审阅 |
| 55 | `com\astral\dao\mapper\SysMailAccountMapper.java` | 全文件/关键路径 | 静态审阅 |
| 56 | `com\astral\dao\mapper\SysMailLogMapper.java` | 全文件/关键路径 | 静态审阅 |
| 57 | `com\astral\dao\mapper\SysMailTemplateMapper.java` | 全文件/关键路径 | 静态审阅 |
| 58 | `com\astral\dao\mapper\SysMenuMapper.java` | 全文件/关键路径 | 静态审阅 |
| 59 | `com\astral\dao\mapper\SysNoticeMapper.java` | 全文件/关键路径 | 静态审阅 |
| 60 | `com\astral\dao\mapper\SysNotifyRuleChannelMapper.java` | 全文件/关键路径 | 静态审阅 |
| 61 | `com\astral\dao\mapper\SysNotifyRuleMapper.java` | 全文件/关键路径 | 静态审阅 |
| 62 | `com\astral\dao\mapper\SysNotifyRuleRecipientMapper.java` | 全文件/关键路径 | 静态审阅 |
| 63 | `com\astral\dao\mapper\SysSmsLogMapper.java` | 全文件/关键路径 | 静态审阅 |
| 64 | `com\astral\dao\mapper\SysSmsProviderMapper.java` | 全文件/关键路径 | 静态审阅 |
| 65 | `com\astral\dao\mapper\SysSmsTemplateMapper.java` | 全文件/关键路径 | 静态审阅 |
| 66 | `com\astral\dao\mapper\UserMapper.java` | 全文件/关键路径 | 静态审阅 |
| 67 | `com\astral\dao\mapper\UserRoleMapper.java` | 全文件/关键路径 | 静态审阅 |

### astral-schema

| # | 文件 | 覆盖方式 | 状态 |
|---:|---|---|---|
| 1 | `com\astral\schema\codegen\CodeGenMojo.java` | 全文件/关键路径 | 修改 |
| 2 | `com\astral\schema\FieldSchema.java` | 全文件/关键路径 | 静态审阅 |
| 3 | `com\astral\schema\IndexSchema.java` | 全文件/关键路径 | 静态审阅 |
| 4 | `com\astral\schema\SchemaCodeGenerator.java` | 全文件/关键路径 | 修改 |
| 5 | `com\astral\schema\SchemaEntitySync.java` | 全文件/关键路径 | 修改 |
| 6 | `com\astral\schema\SchemaRegistry.java` | 全文件/关键路径 | 修改 |
| 7 | `com\astral\schema\SqlDialect.java` | 全文件/关键路径 | 静态审阅 |
| 8 | `com\astral\schema\SqlIdentifiers.java` | 全文件/关键路径 | 修改 |
| 9 | `com\astral\schema\TableSchema.java` | 全文件/关键路径 | 静态审阅 |

### astral-log

| # | 文件 | 覆盖方式 | 状态 |
|---:|---|---|---|
| 1 | `com\astral\log\annotation\LoginLog.java` | 全文件/关键路径 | 修改 |
| 2 | `com\astral\log\annotation\OperateLog.java` | 全文件/关键路径 | 修改 |
| 3 | `com\astral\log\aspect\LoginLogAspect.java` | 全文件/关键路径 | 修改 |
| 4 | `com\astral\log\aspect\OperateLogAspect.java` | 全文件/关键路径 | 修改 |
| 5 | `com\astral\log\aspect\OperateLogWriter.java` | 全文件/关键路径 | 修改 |
| 6 | `com\astral\log\controller\LoginLogController.java` | 全文件/关键路径 | 修改 |
| 7 | `com\astral\log\controller\OperateLogController.java` | 全文件/关键路径 | 修改 |
| 8 | `com\astral\log\service\impl\LoginLogServiceImpl.java` | 全文件/关键路径 | 静态审阅 |
| 9 | `com\astral\log\service\impl\LogServiceImpl.java` | 全文件/关键路径 | 静态审阅 |
| 10 | `com\astral\log\service\impl\OperateLogServiceImpl.java` | 全文件/关键路径 | 静态审阅 |
| 11 | `com\astral\log\service\LoginLogService.java` | 全文件/关键路径 | 静态审阅 |
| 12 | `com\astral\log\service\LogService.java` | 全文件/关键路径 | 静态审阅 |
| 13 | `com\astral\log\service\OperateLogService.java` | 全文件/关键路径 | 静态审阅 |

### astral-monitor

| # | 文件 | 覆盖方式 | 状态 |
|---:|---|---|---|
| 1 | `com\astral\monitor\alert\AlertAdminController.java` | 全文件/关键路径 | 修改 |
| 2 | `com\astral\monitor\alert\AlertConfigParser.java` | 全文件/关键路径 | 修改 |
| 3 | `com\astral\monitor\alert\AlertEngine.java` | 全文件/关键路径 | 修改 |
| 4 | `com\astral\monitor\alert\WebhookUrlPolicy.java` | 全文件/关键路径 | 新增 |
| 5 | `com\astral\monitor\api\AppStatController.java` | 全文件/关键路径 | 静态审阅 |
| 6 | `com\astral\monitor\api\MonitorController.java` | 全文件/关键路径 | 静态审阅 |
| 7 | `com\astral\monitor\api\StatReportController.java` | 全文件/关键路径 | 静态审阅 |
| 8 | `com\astral\monitor\config\MonitorWebMvcConfig.java` | 全文件/关键路径 | 静态审阅 |
| 9 | `com\astral\monitor\dto\ApiTopDTO.java` | 全文件/关键路径 | 静态审阅 |
| 10 | `com\astral\monitor\dto\ApiTopResultDTO.java` | 全文件/关键路径 | 静态审阅 |
| 11 | `com\astral\monitor\dto\ApiTopSummaryDTO.java` | 全文件/关键路径 | 静态审阅 |
| 12 | `com\astral\monitor\dto\BusinessMonitorDTO.java` | 全文件/关键路径 | 静态审阅 |
| 13 | `com\astral\monitor\dto\DashboardOverviewDTO.java` | 全文件/关键路径 | 静态审阅 |
| 14 | `com\astral\monitor\dto\DatabaseMonitorDTO.java` | 全文件/关键路径 | 静态审阅 |
| 15 | `com\astral\monitor\dto\DeviceOverviewDTO.java` | 全文件/关键路径 | 静态审阅 |
| 16 | `com\astral\monitor\dto\ErrorSummaryDTO.java` | 全文件/关键路径 | 静态审阅 |
| 17 | `com\astral\monitor\dto\JvmMonitorDTO.java` | 全文件/关键路径 | 静态审阅 |
| 18 | `com\astral\monitor\dto\RedisMonitorDTO.java` | 全文件/关键路径 | 静态审阅 |
| 19 | `com\astral\monitor\dto\StatEventDTO.java` | 全文件/关键路径 | 修改 |
| 20 | `com\astral\monitor\dto\StatReportRequest.java` | 全文件/关键路径 | 静态审阅 |
| 21 | `com\astral\monitor\dto\SystemMonitorDTO.java` | 全文件/关键路径 | 静态审阅 |
| 22 | `com\astral\monitor\dto\TrendDTO.java` | 全文件/关键路径 | 静态审阅 |
| 23 | `com\astral\monitor\job\StatAggregationJob.java` | 全文件/关键路径 | 修改 |
| 24 | `com\astral\monitor\service\ApiMetricCollector.java` | 全文件/关键路径 | 静态审阅 |
| 25 | `com\astral\monitor\service\BusinessMonitorService.java` | 全文件/关键路径 | 静态审阅 |
| 26 | `com\astral\monitor\service\DashboardOverviewService.java` | 全文件/关键路径 | 静态审阅 |
| 27 | `com\astral\monitor\service\DatabaseMonitorService.java` | 全文件/关键路径 | 静态审阅 |
| 28 | `com\astral\monitor\service\impl\BusinessMonitorServiceImpl.java` | 全文件/关键路径 | 静态审阅 |
| 29 | `com\astral\monitor\service\impl\JvmMonitorServiceImpl.java` | 全文件/关键路径 | 静态审阅 |
| 30 | `com\astral\monitor\service\impl\SystemMonitorServiceImpl.java` | 全文件/关键路径 | 静态审阅 |
| 31 | `com\astral\monitor\service\JvmMonitorService.java` | 全文件/关键路径 | 静态审阅 |
| 32 | `com\astral\monitor\service\RedisMonitorService.java` | 全文件/关键路径 | 静态审阅 |
| 33 | `com\astral\monitor\service\RequestConcurrencyCounter.java` | 全文件/关键路径 | 静态审阅 |
| 34 | `com\astral\monitor\service\ServerErrorRecorder.java` | 全文件/关键路径 | 静态审阅 |
| 35 | `com\astral\monitor\service\StatIngestService.java` | 全文件/关键路径 | 修改 |
| 36 | `com\astral\monitor\service\StatReportService.java` | 全文件/关键路径 | 修改 |
| 37 | `com\astral\monitor\service\SystemMonitorService.java` | 全文件/关键路径 | 静态审阅 |

### astral-sequence

| # | 文件 | 覆盖方式 | 状态 |
|---:|---|---|---|
| 1 | `com\astral\sequence\config\AsyncConfig.java` | 全文件/关键路径 | 静态审阅 |
| 2 | `com\astral\sequence\config\EntityIdSequenceProvider.java` | 全文件/关键路径 | 修改 |
| 3 | `com\astral\sequence\config\SequenceAutoConfiguration.java` | 全文件/关键路径 | 静态审阅 |
| 4 | `com\astral\sequence\config\SequenceMetaObjectHandler.java` | 全文件/关键路径 | 静态审阅 |
| 5 | `com\astral\sequence\controller\dto\SequenceBatchRequest.java` | 全文件/关键路径 | 修改 |
| 6 | `com\astral\sequence\controller\dto\SequenceRequest.java` | 全文件/关键路径 | 修改 |
| 7 | `com\astral\sequence\controller\dto\SequenceResponse.java` | 全文件/关键路径 | 修改 |
| 8 | `com\astral\sequence\controller\SequenceConfigController.java` | 全文件/关键路径 | 修改 |
| 9 | `com\astral\sequence\controller\SequenceController.java` | 全文件/关键路径 | 修改 |
| 10 | `com\astral\sequence\controller\SequenceHistoryController.java` | 全文件/关键路径 | 修改 |
| 11 | `com\astral\sequence\controller\SequenceSegmentController.java` | 全文件/关键路径 | 修改 |
| 12 | `com\astral\sequence\controller\SequenceStatisticsController.java` | 全文件/关键路径 | 修改 |
| 13 | `com\astral\sequence\generator\DatabaseGenerator.java` | 全文件/关键路径 | 静态审阅 |
| 14 | `com\astral\sequence\generator\RedisGenerator.java` | 全文件/关键路径 | 静态审阅 |
| 15 | `com\astral\sequence\generator\SegmentGenerator.java` | 全文件/关键路径 | 修改 |
| 16 | `com\astral\sequence\generator\SequenceGenerator.java` | 全文件/关键路径 | 静态审阅 |
| 17 | `com\astral\sequence\generator\SimpleGenerator.java` | 全文件/关键路径 | 静态审阅 |
| 18 | `com\astral\sequence\generator\SnowflakeGenerator.java` | 全文件/关键路径 | 静态审阅 |
| 19 | `com\astral\sequence\plugin\SequencePlugin.java` | 全文件/关键路径 | 修改 |
| 20 | `com\astral\sequence\service\GeneratorFactory.java` | 全文件/关键路径 | 修改 |
| 21 | `com\astral\sequence\service\SequenceAsyncWriter.java` | 全文件/关键路径 | 修改 |
| 22 | `com\astral\sequence\service\SequenceHistoryService.java` | 全文件/关键路径 | 静态审阅 |
| 23 | `com\astral\sequence\service\SequenceResult.java` | 全文件/关键路径 | 新增 |
| 24 | `com\astral\sequence\service\SequenceSegmentService.java` | 全文件/关键路径 | 静态审阅 |
| 25 | `com\astral\sequence\service\SequenceSegmentServiceImpl.java` | 全文件/关键路径 | 静态审阅 |
| 26 | `com\astral\sequence\service\SequenceService.java` | 全文件/关键路径 | 修改 |
| 27 | `com\astral\sequence\service\SequenceStatisticsService.java` | 全文件/关键路径 | 静态审阅 |
| 28 | `com\astral\sequence\service\SequenceStatisticsServiceImpl.java` | 全文件/关键路径 | 静态审阅 |

### astral-plugin-api

| # | 文件 | 覆盖方式 | 状态 |
|---:|---|---|---|
| 1 | `com\astral\plugin\api\AstralPlugin.java` | 全文件/关键路径 | 修改（注释） |
| 2 | `com\astral\plugin\api\PermissionProvider.java` | 全文件/关键路径 | 修改（注释） |
| 3 | `com\astral\plugin\api\PluginConfigProvider.java` | 全文件/关键路径 | 静态审阅 |
| 4 | `com\astral\plugin\api\PluginFrontendExtension.java` | 全文件/关键路径 | 静态审阅 |
| 5 | `com\astral\plugin\api\PluginRegistry.java` | 全文件/关键路径 | 静态审阅 |

### 覆盖计数

| 模块 | 文件数 |
|---|---:|
| astral-common | 20 |
| astral-dao | 67 |
| astral-schema | 9 |
| astral-log | 13 |
| astral-monitor | 37 |
| astral-sequence | 28 |
| astral-plugin-api | 5 |
| **合计** | **179** |

> 说明：清单来自仓库实际文件清点（`_tmp/astral-domain-files.txt`，186 行 = 7 个模块标题 + 179 个文件条目；其中 `astral-common` 20、`astral-dao` 67、`astral-schema` 9、`astral-log` 13、`astral-monitor` 37、`astral-sequence` 28、`astral-plugin-api` 5，合计 179）。报告正文中的「186」为清点行数，不是文件数；以本表 179 个文件为准。
