# AI 知识库分片审阅 · knowledge-01

只读分片报告。范围仅限「AI 知识源 / 知识文档 / 分片索引 / 索引任务 / 智能体知识绑定」这一条纵切，**不是** task-14 收口，**不是** AI 域全量结论，**不是** 全项目结论。

- 仓库：`F:/qtMusic/astral`（cwd 即仓库根）
- 基线参照：`docs/audit/2026-11-07/SPEC.md`（固定基线 `a39a3f30…`、只本地、不改兄弟项目）、`TEST-SEAMS.md`（公开边界，不复制私有实现）
- 本次未运行任何 Maven / 后端 / API / DB / 浏览器 / 真实凭据操作；未改任何 Java/TS/测试/schema/POM/lock；未做 Git/task/PLAN/记忆写入
- 本报告与同名 `.json` 是本次唯一写盘产物

## 1. 覆盖事实（12 个文件，全部读完，无版本切分）

12 个文件在**完整阅读前后**各计算一次 SHA-256，逐个相等 ⇒ 单一版本，无需切分重读。行数为原始行/非空白行。

| # | 文件（相对仓库根，统一前缀 `astral-plugin/src/main/java/com/astral/ai/`） | raw | nonblank | SHA-256（读前=读后） | readRanges（可见覆盖） |
|---|---|---|---|---|---|
| 1 | `service/AiKnowledgeService.java` | 712 | 666 | `21941517003A1F8ECBDC81AE2C866BEA6736507ACCFA17E01FAB62DCA9E9FA07` | 1-80, 81-160, 161-240, 241-320, 321-400, 401-480, 481-560, 561-640, 641-712 = 712/712 |
| 2 | `controller/AiAdminKnowledgeController.java` | 222 | 202 | `0771E5D6B7BC6C664D9C3B9A51E59A59B9BDA066F11017BB7A56BC9CAE8EFD5A` | 1-80, 81-160, 161-222 = 222/222 |
| 3 | `entity/AiAgentKnowledgeEntity.java` | 54 | 41 | `3939AE1AB330CF3B3076517976502FD534D10FDAC72F318CE669F931EDBFDEE0` | 1-54 = 54/54 |
| 4 | `entity/AiKnowledgeChunkEntity.java` | 46 | 35 | `1D61362BB21DBAA64AF50666438A4F74EEFAEE74E27864413F8A67B49A3E6E77` | 1-46 = 46/46 |
| 5 | `entity/AiKnowledgeDocumentEntity.java` | 62 | 46 | `14AB488DCE83C425BCFDC2533CC9445570F48C722C5C1A4D2A1504AE8CB716B2` | 1-62 = 62/62 |
| 6 | `entity/AiKnowledgeIndexJobEntity.java` | 59 | 44 | `42E6782D2EEE8B2EC24FF20284BE50B12BBE68211E5B1485DE9A8AC8F8571155` | 1-59 = 59/59 |
| 7 | `entity/AiKnowledgeSourceEntity.java` | 65 | 48 | `B595EF833340880A475B33BE5CA1FDDC124B0C914AADD6F4CE6C050BE63FA0A0` | 1-65 = 65/65 |
| 8 | `mapper/AiAgentKnowledgeMapper.java` | 11 | 9 | `CD251ED861A1F069E02D7D75AF3318078DBA6022745938E6CD29FA4930D55D18` | 1-11 = 11/11 |
| 9 | `mapper/AiKnowledgeChunkMapper.java` | 11 | 9 | `BAD02B792B76CBEF57A2C5F4201D233E17634055E5144EB902F982E03395314B` | 1-11 = 11/11 |
| 10 | `mapper/AiKnowledgeDocumentMapper.java` | 11 | 9 | `FAD0863E30ABC30A1F3DCFC0B9B3A4259045B072E014B98C1E6A52E8820BB3D3` | 1-11 = 11/11 |
| 11 | `mapper/AiKnowledgeIndexJobMapper.java` | 11 | 9 | `60C764F0E753175485FB6131546627D1D261C4C9E37A323A75F23A310F75CDB8` | 1-11 = 11/11 |
| 12 | `mapper/AiKnowledgeSourceMapper.java` | 11 | 9 | `86B704D91937A30BB89845D46DA87829D969A0DD547F36D89143F9045E611C78` | 1-11 = 11/11 |

合计 1,275 原始行 / 1,135 非空白行，**12/12 完整覆盖，0 个部分覆盖目标文件**。

每个 mapper 都是 `@Mapper interface X extends BaseMapper<Entity>`（8-11 行），无自定义 SQL、无 XML 片段、无列投影；实体全部 `@TableId(type = IdType.INPUT)` + `createTime=INSERT` / `updateTime=INSERT_UPDATE` 填充（`AiKnowledgeChunkEntity` 只有 INSERT，符合分片只增语义）。

### 为定位可达性而额外打开的最小片段（**部分读取，不计入上述 12 个文件的完整覆盖**）

`service/AiChatOrchestrator.java:252-273, 928-975`；`tool/QaToolProvider.java:362-401`；`controller/AiAdminAgentController.java:300-369`；`controller/AiAppController.java:278-299`；`config/AiSeedInitializer.java:290-359`；`dto/AiAdminDtos.java:380-434`；`db/migration/V20261019002__ai_schema.sql:176-270`；`V20261019001__ai_dict.sql`（检索式定位）；`common/AiConstants.java`（检索式定位）。这些只用于证明调用链与 DDL 事实，不对外声称读完。

## 2. 结论摘要

| 级别 | 数量 | 主题 |
|---|---|---|
| HIGH | 2 | 索引任务状态失真（成功/失败/进度不可信）；检索全表加载且分片表缺 `source_id` 索引 |
| MEDIUM | 5 | `min_score` 配置从不生效且两套量纲；`syncMode` 死配置；分片删/插+吞异常导致零分片脏态；列表面板读全量正文且 N+1/无分页；超长正文静默截断；绑定重复/枚举未校验导致 500 与字典外值 |
| LOW | 5 | 索引任务无法列举/知识源无法删除；`hasReadyKnowledge` 误报能力位；SHA-256 失败静默降级 + 错误摘要落原始异常串；`refreshSourceCounters` 冗余计数查询 |

共性判断：这条纵切的**主路径是通的**（保存即建分片、检索能按绑定取回、引用字段齐全、权限码已注册、SQL 全参数化、客户端引用投影未泄露内部路径），问题集中在**状态可信度、资源上界、配置是否真的生效**三类，而不是"功能缺失"。代码里 `min_score` / `sync_mode` 这两处属于典型的「界面/表结构已登记、运行期从不读取」的配置幻觉 —— 与 AGENTS.md 强调的"声明式、可核验"取向相反。

## 3. 逐条发现（均已在当前源码行号确认）

### K-01 HIGH · 索引任务把"部分失败"报成 SUCCEEDED，且 RUNNING 状态对外永不可见

- 位置：`service/AiKnowledgeService.java:281-284`
  ```java
  job.setStatus(failed > 0 && processed == 0
          ? AiConstants.IndexJobStatus.FAILED
          : AiConstants.IndexJobStatus.SUCCEEDED);
  ```
  只要有一个文档成功，`failed>0` 仍写 `SUCCEEDED`；`failedCount`/`errorSummary` 与 `status` 自相矛盾。
- 位置：`service/AiKnowledgeService.java:244-256` + `:287` —— 整个 `syncSource` 在**一个** `@Transactional` 里插入 job（RUNNING）、跑完全部文档、再改成终态，中间**没有** flush/独立事务。并发会话读 `ai_knowledge_index_job` 只能看到提交后的终态行，`RUNNING` 是死值。
- 位置：`controller/AiAdminKnowledgeController.java:150` —— `Result.success("已提交", toVo(knowledgeService.syncSource(...)))`：文案声称"已提交"（异步语义），实际是**同步跑完才返回**，大知识源下请求会长时间挂住。
- 位置：`controller/AiAdminKnowledgeController.java:153-157` —— 只有 `GET /knowledge/index-jobs/{jobId}`，**没有**列表接口，管理员拿不到 jobId（`sync` 返回体给了，但页面刷新后无从查起），也无法看历史失败。
- 位置：状态词表不一致 —— `common/AiConstants.java:220-230` 定义 `PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED`，而 DDL `V20261019002__ai_schema.sql:237` 默认值是 `'QUEUED'`（字典 `ai_index_job_status` 里也没有 QUEUED：`V20261019001__ai_dict.sql:92-96`）。当前代码总是显式写 status 所以不炸，但任何"省略 status 的插入"（脚本修数、后续新路径）都会落进字典外的值。
- 可达性：`POST /api/v1/admin/ai/knowledge/sources/{id}/sync`（`@RequiresPermission admin:ai:knowledge:sync`，权限码已在 `AiPlugin.java:134-136` 注册）→ 服务同步执行 → 返回终态 VO。
- 影响：管理员看到"成功"而实际若干文档未重建索引；失败文档的 `status` 只在其成功分支被写成 READY（`:266-269`），失败文档保留旧值，"哪些文档没索引上"在 UI 上不可判定。属于**状态可见性 + 业务结论失真**，也是把"部分失败"洗成"成功"的审计风险。
- 建议（不在本分片实施）：①三态改为 `SUCCEEDED / PARTIAL / FAILED`（或 `failed>0 → FAILED` 并保留 processed/failed 计数）；②job 行独立事务先提交 RUNNING，末尾再更新终态，或干脆改成真正的异步任务；③补 `GET /knowledge/index-jobs?sourceId=` 分页；④DDL 默认值与 `IndexJobStatus` 对齐（迁移里改默认或删默认）。

### K-02 HIGH · 检索把"所有启用知识源的全部分片"一次性读进内存，且 `ai_knowledge_chunk` 没有 `source_id` 索引

- 位置：`service/AiKnowledgeService.java:414-418`
  ```java
  List<AiKnowledgeChunkEntity> chunks = chunkMapper.selectList(new LambdaQueryWrapper<AiKnowledgeChunkEntity>()
          .in(AiKnowledgeChunkEntity::getSourceId, sources.keySet()));
  ```
  无 `limit`、无分页、无 `document.status='READY'` 过滤、无 `token_count` 上界。真正的裁剪发生在**把全部行读出来之后**：`:429-430` 只裁最终命中条数（`min(MAX_CITATIONS=20, ...)`，见 `AiConstants.java:384`）。
- 位置：DDL `V20261019002__ai_schema.sql:219-230` —— 分片表只有 `idx_ai_knowledge_chunk_doc(document_id)`，**没有 `source_id` 索引**，而上面这条查询的谓词正是 `source_id IN (...)` ⇒ 全表顺序扫描。
- 量级推导（用本服务自己的常量）：`CHUNK_SIZE=700`、`MAX_DOCUMENT_CHARS=400_000`（`:50-56`）⇒ 单文档最多约 570+ 分片；100 篇文档 ≈ 5.7 万分片。每次对话轮次（`AiChatOrchestrator.java:262` 每轮 invoke 都调用 `retrieveKnowledge`，`:937-940`）都会把这批行 + 其 `content` 全部物化，再逐条 `toLowerCase` 做词面计数（`:438-456`）。
- 位置：`service/AiKnowledgeService.java:460-463` 还存在 N+1：每个分片按 `documentId` 回查文档，仅靠一个 `cache` Map 去重（同一文档只查一次），跨文档数量线性放大。
- 可达性：管理端与 App 端对话都会走到（`AiChatOrchestrator.java:262` 无条件调用，未按 `clientType` 分流）；另外 `QaToolProvider.java:376` 的文档检索工具走同一方法（其工具声明限定 `ASTRAL_FRONT` 客户端，见 `:83,109,135,193,247,282,318`，故 App 用户不直接触发该工具，但对话内建的 `retrieveKnowledge` 仍然生效）。
- 影响：知识库一旦长到几千分片，单次对话即出现高延迟与堆压力，多用户并发时是 OOM 面；由于无状态过滤，索引失败的文档只要旧分片还在（见 K-05）也会被召回。
- 建议：①补 `source_id`（乃至 `(source_id, document_id)`）索引；②按 `topK` 反向做候选裁剪（先按 document READY + 词命中计数在 SQL 侧或两级检索里裁剪，再取正文）；③`search` 只投影评分需要的列，正文按命中 id 二次取，且二次取也限量。

### K-03 MEDIUM · `min_score` 入库、可编辑、可复制，但检索从不使用；代码默认值(30)与库默认值(0.2)量纲不一致

- 位置：检索侧完全没有分数下限 —— `service/AiKnowledgeService.java:382-431`（`search`）、`:434-493`（`rankChunks`）只按 `hitTerms >= MIN_HIT_TERMS(=1)`（`:59`）过滤，命中即返回；全仓 `minScore` 的出现只有"实体字段 / DTO / 赋值 / 复制"，没有任何比较：`entity/AiAgentKnowledgeEntity.java:39`、`controller/AiAdminAgentController.java:358`、`service/AiAgentService.java:513`、`dto/AiAdminDtos.java:468-489`。
- 位置：`service/AiKnowledgeService.java:708-711` `defaultMinScore()` 返回 `new BigDecimal("30")`，全仓唯一使用点是种子 `config/AiSeedInitializer.java:411`；而 DDL `V20261019002__ai_schema.sql:258` 的列默认是 `DECIMAL(18,6) DEFAULT 0.2`，DTO 注释（`AiAdminDtos.java:468`）也写"最低相关度"。
- 打分尺度对照：`service/AiKnowledgeService.java:467-476` 的分数是 `coverage*100 + min(occurrences,20)*1.5 (+12 标题命中) (+1 章节)`，即 0~130 量级；`0.2` 与 `30` 显然不是同一刻度。
- 可达性：`PUT /api/v1/admin/ai/agent-versions/{versionId}/knowledge` → `AiAdminAgentController.java:341-364`（`req.minScore()` 直接落库，无范围校验）→ `AiKnowledgeService.replaceBindings:317-342`。而检索链路对同一字段零引用。
- 影响：管理员按 UI 调"最低相关度"完全不改变召回结果；单个二元词命中（例如只命中一个二字词）的噪声分片仍会被塞进 prompt 并作为"引用"呈现，正是 `defaultMinScore()` 注释里声称要过滤的东西。两套量纲还意味着**将来真正接上过滤时**，种子绑定(30)与 API 绑定(0.2)的行为会完全不同。
- 建议：统一刻度和单位（要么 0-1 归一化，要么 0-100），在 `search`/`rankChunks` 末尾按 `binding.minScore` 过滤，并在 DTO 上加 `@DecimalMin/@DecimalMax` 与量纲注释；迁移里把存量 `0.2` 归一到新刻度。

### K-04 MEDIUM · `syncMode`（MANUAL/AUTO）是死配置：只落库、只回显，全仓无人读取

- 位置：写入 `service/AiKnowledgeService.java:112`（默认 `MANUAL`）、`:148-150`（更新）；DTO `dto/AiAdminDtos.java:386-395`；回显 `controller/AiAdminKnowledgeController.java:82,101,174`。
- 全仓检索结果是**只有 setter 与 VO 拼装**：无 `getSyncMode` 调用、AI 包内无 `@Scheduled`、无自动同步入口。`AUTO` 语义没有任何实现。
- 影响：管理员把同步方式改成 AUTO 后不会有任何自动同步发生，属于"界面承诺 > 实现"的配置幻觉；由于 `syncSource` 只能手动触发（K-01），知识库会静默陈旧。
- 建议：要么实现调度（按 `syncMode` 与 `lastSyncTime` 触发），要么先移除该字段/在 UI 上标注"预留"，避免误导。

### K-05 MEDIUM · 重建分片是"先删后插"，且逐文档异常被吞 ⇒ 可能出现"零分片 + 陈旧 chunk_count + 任务却成功"

- 位置：`service/AiKnowledgeService.java:498-521`
  ```java
  chunkMapper.delete(... documentId = ...);   // 499-500
  ...
  for (String piece : pieces) { ... chunkMapper.insert(chunk); }  // 505-519
  ```
  删除与插入之间没有事务边界保护（同处调用方事务），一旦插入阶段抛错（唯一键/字段超长/连接中断），已删除的分片不会回来。
- 位置：`service/AiKnowledgeService.java:263-278` 逐文档 `catch (Exception e) { failed++; ... }` **吞掉**异常继续；成功分支才写 `chunkCount/status/indexedAt`（`:266-269`）。所以失败文档 = 分片被删 + `chunk_count` 仍是旧值 + `status` 仍是旧值；随后 `:281-284` 还可能给整任务写 `SUCCEEDED`（K-01）。
- 位置：`service/AiKnowledgeService.java:220-222`（保存路径）同样在 `reindexDocument` 之后二次 `updateById` 回写 `chunkCount`，异常时靠 `@Transactional` 回滚救回，但 `syncSource` 的吞噬路径不受该保护。
- 影响：知识库出现"文档列表显示有 N 个分片，实际 0 个"的脏态，检索静默少召回；这也是 K-02 里"失败文档旧分片仍被召回"与"零分片"两种相反脏态的来源。
- 建议：①先写新分片到临时状态/新版本号，再删旧（或整文档事务 + 失败即回滚该文档）；②失败时把 `document.status` 写 `FAILED` 并记 `errorSummary`；③失败不再被计为 SUCCEEDED（与 K-01 同修）。

### K-06 MEDIUM · 列表接口把 `content`（TEXT，最大 40 万字符/篇）整列读出来再丢弃，且每源一次查询、无分页

- 位置：`service/AiKnowledgeService.java:159-163` `listDocuments` 用 `documentMapper.selectList(wrapper)`，**无列投影** ⇒ 选中 `content`（DDL `:208` `content TEXT`）。
- 位置：`controller/AiAdminKnowledgeController.java:56-62` 遍历全部知识源 → `:161-165` 每个源再 `listDocuments` 求 `chunkCount` 之和 ⇒ 1 + N 次查询，且每次把该源全部文档正文读进堆只为累加一个整数。
- 位置：`controller/AiAdminKnowledgeController.java:108-117` 文档列表同样无分页、无投影，返回的 VO（`dto/AiAdminDtos.java:433+`）不含 `content`，正文读完即丢。
- 影响：管理页打开慢、DB 与 JVM 内存被无谓消耗；文档越多越线性恶化（40 万字符 × 文档数的瞬时驻留）。`documentCount` 已经落在 `ai_knowledge_source`（`entity/AiKnowledgeSourceEntity.java:49-50`），而分片总数没有落库，才会每次现算（见 K-12）。
- 建议：①`listDocuments` 改为显式列投影（排除 `content`）；②分片总数落到 `ai_knowledge_source`（或一次 `GROUP BY source_id` 聚合）；③列表接口分页。

### K-07 MEDIUM · 超长正文被静默截断到 400,000 字符：无校验、无提示、hash 按截断后内容计算

- 位置：`service/AiKnowledgeService.java:56` `MAX_DOCUMENT_CHARS = 400_000`；`:193`
  ```java
  String body = content.length() > MAX_DOCUMENT_CHARS ? content.substring(0, MAX_DOCUMENT_CHARS) : content;
  ```
  随后 `:194` `sha256(body)`、`:210` 落库 `body`。
- 位置：`dto/AiAdminDtos.java:407-413` `KnowledgeDocumentSaveReq.content` 只有 `@NotBlank`，**没有 `@Size(max)`** ⇒ 请求被接受，超限部分无任何反馈。
- 位置：`controller/AiAdminKnowledgeController.java:135` 仍返回 `"已保存并完成索引"`。
- 影响：粘贴大文件时尾部内容永久丢失且当事人不知道；`content_hash` 与原始文档不再对应，引用可回溯性（服务 javadoc `:40-42` 声称的"所有引用都必须能回溯到具体文档 + 章节"）在超限文档上不成立；检索只能命中前半段。
- 建议：要么在 DTO 加 `@Size(max=400000)` 直接 400/320 拒绝，要么截断时返回明确提示并记录 `truncated=true`；同时把上限外置为配置（当前是硬编码常量，属"不可配置的上界"）。

### K-08 MEDIUM · 知识绑定重复项直接 500；`citationPolicy`、`sourceType`、`syncMode` 均可写入字典/枚举外的值

- 位置：`service/AiKnowledgeService.java:317-342` `replaceBindings` 先 `delete` 再逐条 `insert`，**不去重**；`controller/AiAdminAgentController.java:346-363` 直接按请求数组构造，同一 `sourceId` 出现两次即触发唯一索引 `uk_ai_agent_knowledge (agent_version_id, knowledge_source_id)`（DDL `:264`）→ 事务回滚 → 全局异常处理器映射为 500 `COMMON001`，而不是可读的 400。
- 位置：`service/AiKnowledgeService.java:335-338` `citationPolicy` 只做 `toUpperCase`，未校验取值；DDL `:259` 默认 `'REQUIRED'`，调用方也会传 `OPTIONAL`（`AiSeedInitializer` 的种子绑定与前端）。
- 位置：`service/AiKnowledgeService.java:108`、`:136-138`、`:112`、`:148-150` 对 `sourceType`/`syncMode` 也是"任意大写字符串照收"；而字典里 `ai_knowledge_source_type` 只有 7 个值（`V20261019001__ai_dict.sql:64-70`）、`ai_knowledge_status` 5 个值（`:72-76`）。
- 影响：①重复绑定 = 500，前端只能显示"系统错误"；②字典外枚举会污染知识源类型，让 `QaToolProvider` 的 `sourceTypes` 过滤（`QaToolProvider.java:388-392`，按 `API_DOCUMENT/PROJECT_DOCUMENT/DEPLOYMENT_DOCUMENT/…` 白名单）静默漏掉该源；③`citationPolicy` 若将来被消费，非法值会变成未定义行为。
- 建议：服务侧对 `sourceId` 去重、对枚举做白名单校验（或直接复用字典校验），非法输入抛业务异常（code 300/320）而不是让 DB 唯一键变成 500。

### K-09 LOW · 索引任务只能按 id 查、知识源没有删除入口、任务行随每次启动增长

- 位置：`controller/AiAdminKnowledgeController.java:153-157` 仅 `GET /knowledge/index-jobs/{jobId}`；服务里没有 `listJobs`，也没有 `cancel`。
- 位置：`service/AiKnowledgeService.java` 无 `deleteSource`（全文件 712 行确认）；只有 `deleteDocument`（`:228-235`）。知识源一旦建错只能改不能删，其下文档也无法级联清理。
- 位置：`config/AiSeedInitializer.java:326` 每次启动都 `syncSource(...)` ⇒ 每次启动新增一条 `ai_knowledge_index_job` 行并全量重分片（含删除 + 重插），无保留策略/清理。启动耗时与分片写放大随知识库增长。
- 影响：可运维性缺口（无法清理、无法审计历史失败、无法取消误触发的全量重建），以及冷启动成本。

### K-10 LOW · `hasReadyKnowledge()` 只看"有启用的绑定"，被用作 App 能力位 ⇒ 能力声明与实际召回不一致

- 位置：`service/AiKnowledgeService.java:702-706`
  ```java
  return bindings.stream().anyMatch(b -> Boolean.TRUE.equals(b.getEnabled()));
  ```
  不看知识源 `enabled/status`、不看文档数、不看分片是否存在。
- 位置：对照 `service/AiKnowledgeService.java:403-412` —— 真正检索时会把 `source.enabled=false` 的源剔除，于是"能力位=true、召回=空列表"是可达状态。
- 消费点：`controller/AiAppController.java:285-296`（`capabilitiesOf` 用该值作为 `citationRequired` 能力位回给 App）与 `controller/AiAdminChatController.java:424`（管理端对话前的判断）。
- 影响：App 侧被告知"本智能体要求引用/具备知识库"，实际每次提问都零引用；反之知识源已空但仍宣称可用。名称（`hasReady…`）与实现不符。
- 建议：改为 "存在启用绑定 且 该源 enabled 且 存在 READY 文档且分片 count>0"，或改名 `hasEnabledBinding` 并让能力位用真实判据。

### K-11 LOW · `sha256` 失败时静默降级为 `String.hashCode()`；`errorSummary` 落原始异常消息

- 位置：`service/AiKnowledgeService.java:687-700` `catch (Exception e) { return Integer.toHexString(text.hashCode()); }` —— 不记日志、长度/熵都与 SHA-256 不同（32 位 vs 64 位十六进制），`content_hash VARCHAR(64)`（DDL `:209`）虽能存下，但"同一内容同 hash"的语义被破坏，且碰撞概率陡增。`MessageDigest.getInstance("SHA-256")` 在 Java 上不可用属于环境损坏，应当 fail-fast 而不是降级。
- 位置：`service/AiKnowledgeService.java:274` `errors.add(document.getDocKey() + ": " + e.getMessage())`、`:286` 写入 `error_summary`（`VARCHAR(1024)`，DDL `:243`），再经 `controller/AiAdminKnowledgeController.java:200-212` 直出 `IndexJobVo`。原始异常串可能包含 SQL 片段/驱动细节，仅管理端可见，故风险有限，但会污染审计记录且不可本地化。
- 建议：hash 失败直接抛（或至少 `log.error` 并返回 null/明确标记）；`errorSummary` 存错误码 + 文档键，明细只进日志。

### K-12 LOW · `refreshSourceCounters` 每次保存都多算一次 `ai_knowledge_chunk` COUNT，结果只进日志

- 位置：`service/AiKnowledgeService.java:571-583`
  ```java
  Long chunks = chunkMapper.selectCount(... sourceId ...);   // 574-575
  ...
  log.debug("[AI] 知识源计数 source={} docs={} chunks={}", ...);  // 582
  ```
  只有 `documentCount` 被回写（`:580-581`），`AiKnowledgeSourceEntity` 里没有分片数计数字段（`:49-50` 仅 `documentCount`）⇒ 这次 COUNT 纯冗余。
- 连带：管理端为了展示分片总数只能在 `controller/AiAdminKnowledgeController.java:161-165` 用"逐源拉全量文档 + 累加 chunkCount"凑数（K-06 的一半原因）。
- 建议：删除冗余 COUNT，或在表中正式加 `chunk_count` 并回写；列表接口改成聚合查询。

## 4. 已核对且**未**发现问题的点（反向证据，避免误报）

1. **SQL 注入**：全部查询走 `LambdaQueryWrapper` 参数绑定；唯一的原生片段是 `service/AiKnowledgeService.java:199` 的 `.last("limit 1")`，为常量字面量、不含用户输入 ⇒ 未发现注入面。
2. **权限声明**：控制器类级 `@RequiresPermission("admin:ai:config:view")`（`controller/AiAdminKnowledgeController.java:34`）+ 方法级 `admin:ai:knowledge:{view,edit,sync}`（`:55,65,71,88,109,120,139,147,154`），符合 AGENTS.md「声明式、方法级优先」；三个权限码都由插件声明注册（`AiPlugin.java:134-136`），不存在"注解里的码在库里不存在"的启动期补登记漂移。
3. **操作人归属**：`controller/AiAdminKnowledgeController.java:40-50` 读 `request.getAttribute("userId")`，与 `astral-server/src/main/java/com/astral/server/interceptor/AuthInterceptor.java:181,212` 实际写入的键**一致** ⇒ HTTP 路径上 `created_by/updated_by` 会被填上（仅种子/内部调用为 null）。这一项不存在"恒为 null"的缺陷。
4. **客户端引用投影**：`service/AiChatOrchestrator.java:962-969` 构造 `AiAppDtos.Citation` 只带 `sourceId/sourceType/title/section/version/matchedText`，**不含** `path`；即 `AiCitationHit.path`（`service/AiKnowledgeService.java:366`）只用于管理端。内部文档路径未随 App 引用外泄。（`matchedText` 是正文摘录，最长 500 字符，见 `AiConstants.java:382`，属 RAG 引用所需的既定产品行为，不作为缺陷。）
5. **保存路径的事务性**：`saveDocument`（`:179-225`）整体 `@Transactional(rollbackFor = Exception.class)`，分片重建异常会回滚文档与分片，不会留下半成品；`deleteDocument`（`:228-235`）同样先删分片再删文档并刷新计数。
6. **绑定写入的前置校验**：`replaceBindings` 对每个绑定都 `requireSource(...)`（`:328`），不存在的知识源不会落库；`retrievalTopK` 被夹到 `[1,50]`（`:333-334`）。
7. **实体与 schema 约束一致**：5 个实体均为 `@TableId(INPUT)` + 时间字段填充，与 AGENTS.md 的全局序列约束一致；DDL 的唯一索引 `uk_ai_knowledge_source_code`、`uk_ai_knowledge_document_key`、`uk_ai_agent_knowledge`（`V20261019002__ai_schema.sql:197,216,264`）与代码里的幂等查询/替换语义基本配套（缺口见 K-08 的"未去重"）。

## 5. 成熟同类项目对照

> 环境限制：本会话的 `web_search` / `web_fetch` provider 未注册（`web_search` 返回 `configured web provider "ddg" is not registered`；`web_fetch` 返回 `configured web provider "fs-quality-fetch" is not registered`），**本次未能取回任何外部页面正文**。因此下表的 URL 是拟引用的官方文档地址，**内容未经本次会话核验**，只作为可迁移做法的主张来源，不作为已证事实；请 Lead 在有网环境补验后再引用。

| 目标做法 | 拟引用官方来源（**未取回**） | 与本分片的对应差距 |
|---|---|---|
| 分块入库应有明确的大小/重叠配置，并对超长输入在**摄入边界**拒绝或显式标注截断 | AWS Bedrock Knowledge Bases 的分块文档 `https://docs.aws.amazon.com/bedrock/latest/userguide/kb-chunking-parsing.html` | K-07：本实现硬编码 700/120 与 40 万上限，超限静默截断 |
| 检索应是"有界召回"：先按索引/过滤裁剪候选，再取正文；向量/关键词索引需覆盖查询谓词 | Azure AI Search 的分块与向量化指南 `https://learn.microsoft.com/en-us/azure/search/vector-search-how-to-chunk-documents` | K-02：按 `source_id` 全量拉取 + 缺 `source_id` 索引；K-03：相关度下限未参与裁剪 |
| 索引/摄取是**异步作业**，作业状态与进度独立可查，部分失败要与成功区分 | Azure AI Search 索引器状态与执行历史 `https://learn.microsoft.com/en-us/azure/search/search-how-to-indexer-status` | K-01：同步执行 + RUNNING 不可见 + 部分失败写成功 + 无列表 |
| 知识文档/知识源应有归属、生命周期与删除语义，避免只增不删 | Azure AI Search 索引/数据源生命周期 `https://learn.microsoft.com/en-us/azure/search/search-how-to-load-search-index` | K-09：无删除/无级联/无任务清理 |

仅从"设计意图"层面（本仓库自身可核验材料）：`service/AiKnowledgeService.java:33-43` 的 javadoc 明确写了"所有引用都必须能回溯到具体的文档 + 章节"以及"后续接入 Embedding 时只需替换 `rankChunks` 的打分部分"。K-03（配置的下限不生效）与 K-07（截断后 hash 与原文不再对应）正好破坏这两条自述承诺，属于"文档声称 > 实现"的偏差，可独立于外部对照成立。

## 6. 残余限制（明确不作为已完成证据）

- 未运行 `mvn compile/test`、Spotless/Checkstyle/SpotBugs；上述均为静态源码判断，**不含编译或运行证据**。
- 未启动后端、未连数据库、未发任何 HTTP 请求；DDL 结论来自迁移脚本原文，未在真实库上 `EXPLAIN` 验证"缺少 `source_id` 索引导致顺序扫描"的实际执行计划（K-02 的执行计划部分是依据谓词与索引定义的推断，索引缺失本身是 DDL 事实）。
- 未做浏览器/前端核验，`AiAdminKnowledgeController` 对应的管理页表现（K-01 文案、K-06 加载耗时）未观察。
- 未审计 `AiChatOrchestrator` 全量（1,805 行）与 `AdminToolProvider`（1,165 行）；本报告对它们的引用只限上述标注的最小片段，**不构成对这些文件的覆盖结论**。
- 未覆盖：Prompt/模型/额度/审批、AI 请求-消息持久化、MCP/HTTP 工具传输、前端 AI 页面。这些属于其他分片。
- 未复核 Lead 正在进行的修复（Orchestrator 权限组 GREEN6、AI 请求元数据泄露的 `AiAdminRequestController` 分流）；本分片未触碰、也未据其结论下判断。

## 7. 对 Lead 的交接建议（按性价比排序）

1. K-01 + K-05 一起修：索引任务的终态语义与"部分失败"必须可信，否则管理端所有"同步成功"都是不可判定的。
2. K-02 加索引 + 有界召回：这是唯一会随数据增长而恶化的性能/OOM 面。
3. K-03/K-04 二选一：要么让 `min_score`/`sync_mode` 真正生效，要么从 UI/表结构上撤下 —— 现状是"配置幻觉"，比缺失更容易误导运营。
4. K-06/K-07/K-08 属可快速收口的输入与查询卫生：DTO 加 `@Size`、列表加投影与分页、绑定去重 + 枚举白名单。
5. K-09~K-12 可在同一次改动里顺带处理（删冗余 COUNT、加 job 列表、加源删除、修 `hasReadyKnowledge` 语义与命名）。