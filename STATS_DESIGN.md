# 全端统计系统（自建采集 + Sentry 崩溃）需求与设计文档

> 版本：v1.1（已实现）
> 范围：`astral`（后端 Spring Boot + 前端 Next.js，由 A 开发）与 `qt-uniappx`（uni-app x App 端，由 B 开发）
> 目标读者：双方开发人员。本文档是唯一需求来源，接口契约以本文为准，双方可并行开发。

---

## 0. 已确认的关键决策（不要更改）

| # | 决策 | 内容 |
|---|---|---|
| D1 | 采集入口 | 后端公开接口 **`POST /api/v1/stat/report`**（匿名、免登录） |
| D2 | 实体主键 | 所有统计表实体走**全局序列**（`@TableId(IdType.INPUT)`，业务键=`{表名}_id`），**不用** `IdType.AUTO`，**不改** `SequenceMetaObjectHandler` 的排除集 |
| D3 | 菜单入口 | 前端走 **DB 菜单**（`sys_menu` 表加种子记录），不依赖 `layout.tsx` 的硬编码 fallback |
| D4 | App 端封装 | 采集工具 + Sentry 崩溃初始化**统一封装为一个 uni_modules 插件**（`uni_modules/qt-stat`），App 侧只 import 该插件 |
| D5 | 原生崩溃 | Sentry **自托管**（数据在自己服务器），只收原生崩溃；JS/UTS 运行期错误走自建后端（双通道） |
| D6 | 错误分工 | App 的 `onError`（JS/UTS 层可捕获的运行错误）→ 自建 `/api/v1/stat/report`；未捕获的原生崩溃（进程死亡）→ Sentry |

---

## 1. 背景与目标

原后端 API 统计功能（`/api/v1/statistics/**`）已删除。现重建一套**轻量级、数据自控**的全端统计：

- **设备统计**：新增设备、活跃设备、总设备数、PV（页面访问）、访问次数、启动次数、停留时长、错误数，支持"今日 vs 昨日"对比、按小时趋势、按平台（Android/iOS/Web）拆分。
- **接口统计**：服务端自动测量每个 API 的调用量、平均/最大耗时、错误率、状态码分布、Top 接口排行（还原被删功能的契约）。
- **错误统计**：JS/UTS 运行期错误、网络错误、业务错误的明细与分组。
- **原生崩溃**：交由自托管 Sentry 收集（Android `sentry-java` / iOS `sentry-cocoa`），不在自建后端实现崩溃采集。

### 非目标（本期不做）

- 不做留存/漏斗分析、用户行为轨迹回放。
- 不做 H5/Web 端采集（表结构已预留 `ut='web'`，后续可加）。
- 不引入 Kafka / ClickHouse 等大数据组件（量级不需要）。
- 不迁移 DCloud uni统计（`qt-uniappx` 的 `uniStatistics` 保持现状/关闭，数据不上报 DCloud）。

---

## 2. 总体架构

```
【采集端】
qt-uniappx (App)
  App.uvue 生命周期 ──► uni_modules/qt-stat（采集队列 + 批量上报 + Sentry 初始化）
前端网页 (Web，本期不做，预留 ut='web')
        │  匿名批量上报（10 秒或满批触发）
        ▼
POST /api/v1/stat/report          ← 公开接口（AuthInterceptor 放行，RateLimit 保留）
        │
【接入/存储】astral-server
  StatIngestController ─► StatIngestService ─► upsert 5 张统计表（PostgreSQL）
  ApiRequestMetricInterceptor ─► 内存累加器 ─► @Scheduled 每分钟落库
  StatAggregationJob ─► 清理过期数据（错误 N 天、小时桶 M 天）
        │
【报表】astral-front（admin 登录，satoken 鉴权）
  GET /api/v1/admin/stat/overview | trend | api/top | api/trend | error/page | error/summary
        │
【原生崩溃】自托管 Sentry（docker compose）◄── sentry-java / sentry-cocoa（qt-stat 插件内初始化）
```

### 数据流要点

1. App 端只在**生命周期/页面/错误**发生时调插件 API，插件内部攒批（默认 10s 或 50 条触发），失败回队列重试（上限 3 次），`hide` 时持久化队列到 storage，防崩溃丢数据。
2. 服务端**写时聚合**：不做逐行明细大表，直接 upsert 到"小时桶"表，插入频率 = 桶数 × 维度数，量极小。
3. 停留时长由**客户端计算**（`hide` 事件带 `duration` = 当前时间 − 上次 `show` 时间），服务端只累加，不做会话切割。
4. UV/新增/活跃靠**设备登记表**（一台设备一行，记 `first_date`/`last_date`），不需要逐事件去重。

---

## 3. 数据模型（PostgreSQL）

> DDL 以增量迁移脚本形式新增：`astral-server/src/main/resources/sql/migrations/V{序号}__{描述}.sql`，手动应用（见该目录 README.md）。
> 启动不再自动执行 SQL（`spring.sql.init.mode: never`）；全新库先应用 V1–V3 基线，既有库登记基线版本后按序追加。

### 3.1 `stat_device` 设备登记表（一台设备一行）

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 序列取号（`stat_device_id`） |
| device_id | VARCHAR(64) NOT NULL | 客户端匿名设备ID（UUID），**UNIQUE** |
| ut | VARCHAR(16) NOT NULL | 平台：`app-android` / `app-ios` / `app-windows` / `web` |
| app_version | VARCHAR(32) | App 版本号 |
| model | VARCHAR(128) | 设备型号 |
| os | VARCHAR(64) | 操作系统版本 |
| first_date | DATE NOT NULL | 首次上报日期 → **新增设备** |
| last_date | DATE NOT NULL | 最近上报日期 → **活跃设备** |
| last_active_time | TIMESTAMP | 最近活跃时间 |
| create_time / update_time | TIMESTAMP | 自动填充 |

索引：`UNIQUE(device_id)`、`idx(first_date)`、`idx(last_date)`。

> 指标口径：**新增设备** = `first_date = 当天` 的行数；**活跃设备** = `last_date = 当天` 的行数；**总设备数** = 全表行数。

### 3.2 `stat_metric_hourly` 小时指标桶

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 序列 |
| bucket_hour | TIMESTAMP NOT NULL | 整点（如 `2026-08-30 19:00:00`） |
| ut | VARCHAR(16) NOT NULL | 平台 |
| app_version | VARCHAR(32) NOT NULL DEFAULT '' | 版本 |
| pv | BIGINT DEFAULT 0 | 页面访问次数 |
| visits | BIGINT DEFAULT 0 | 访问次数（冷启动计 1 次） |
| launches | BIGINT DEFAULT 0 | 启动次数 |
| total_duration_ms | BIGINT DEFAULT 0 | 停留时长累加（毫秒） |
| error_count | BIGINT DEFAULT 0 | JS/UTS 错误次数 |
| create_time / update_time | TIMESTAMP | 自动填充 |

约束：`UNIQUE(bucket_hour, ut, app_version)`。

### 3.3 `stat_page_hourly` 页面小时桶（页面排行用）

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 序列 |
| bucket_hour | TIMESTAMP NOT NULL | 整点 |
| ut | VARCHAR(16) NOT NULL | 平台 |
| page | VARCHAR(256) NOT NULL | 页面路由 |
| pv | BIGINT DEFAULT 0 | 该页访问次数 |

约束：`UNIQUE(bucket_hour, ut, page)`。

### 3.4 `stat_error_log` 错误明细

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 序列 |
| fingerprint | VARCHAR(64) NOT NULL | 错误指纹（`MD5(type + message + 首行栈)`），用于分组 |
| error_type | VARCHAR(16) NOT NULL | `js` / `network` / `biz` / `crash`(预留，正常走Sentry) |
| message | VARCHAR(1024) | 错误信息 |
| stack | TEXT | 堆栈（可空） |
| page | VARCHAR(256) | 发生页面 |
| ut | VARCHAR(16) | 平台 |
| app_version | VARCHAR(32) | 版本 |
| os / model | VARCHAR | 系统/机型 |
| device_id | VARCHAR(64) | 设备 |
| release | VARCHAR(64) | 发布标识（预留 Sentry release 对齐） |
| occur_time | TIMESTAMP NOT NULL | 发生时间（取事件 `ts`） |
| create_time | TIMESTAMP | 入库时间 |

索引：`idx(fingerprint)`、`idx(occur_time)`、`idx(error_type)`。
留存：默认 **90 天**，由定时任务清理。

### 3.5 `stat_api_hourly` 接口小时桶（服务端自动测量）

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 序列 |
| bucket_hour | TIMESTAMP NOT NULL | 整点 |
| uri | VARCHAR(256) NOT NULL | 接口路径（不含 query） |
| method | VARCHAR(8) NOT NULL | GET/POST/... |
| status | SMALLINT NOT NULL | HTTP 状态码 |
| call_count | BIGINT DEFAULT 0 | 调用次数 |
| sum_ms | BIGINT DEFAULT 0 | 耗时累加（毫秒，avg = sum/count） |
| max_ms | INT DEFAULT 0 | 单桶最大耗时 |
| create_time / update_time | TIMESTAMP | 自动填充 |

约束：`UNIQUE(bucket_hour, uri, method, status)`。

### 3.6 实体 JSON（astral-schema，启动自动生成实体）

在 `astral-schema/src/main/resources/schema/` 下新增 5 个 JSON（格式照抄 `sys_login_log.json`）：

| 文件 | tableName | className | moduleName |
|---|---|---|---|
| `stat_device.json` | stat_device | StatDevice | monitor |
| `stat_metric_hourly.json` | stat_metric_hourly | StatMetricHourly | monitor |
| `stat_page_hourly.json` | stat_page_hourly | StatPageHourly | monitor |
| `stat_error_log.json` | stat_error_log | StatErrorLog | monitor |
| `stat_api_hourly.json` | stat_api_hourly | StatApiHourly | monitor |

**统一主键写法（重要，遵循 D2）**：

```json
{
  "columnName": "id",
  "fieldName": "id",
  "fieldType": "Long",
  "jdbcType": "BIGINT",
  "comment": "主键ID",
  "isPrimaryKey": true,
  "isAutoIncrement": false,
  "mybatisPlusIdType": "INPUT"
}
```

`createTime`/`updateTime` 字段加 `"isAutoFill": "INSERT"` / `"isAutoFill": "UPDATE"`。
DDL 中 `id BIGINT PRIMARY KEY`（**不要** BIGSERIAL，ID 由序列填充）。

---

## 4. 接口契约（双方并行开发的依据）

统一返回包装 `Result<T>`（`com.astral.common.result.Result`，`code=200` 成功），前端 axios 封装见 `astral-front/src/api/client.ts`。

### 4.1 `POST /api/v1/stat/report`（公开，匿名）

请求体：

```json
{
  "events": [
    {
      "evt": "launcher",              // launcher | show | hide | page | error | custom
      "ts": 1756515600000,            // 事件发生时间毫秒
      "deviceId": "8f3a1c2e-....",   // 客户端持久化随机UUID
      "ut": "app-android",            // app-android | app-ios | app-windows | web
      "appVersion": "3.0.0",
      "model": "Pixel 8",
      "os": "Android 15",
      "page": "pages/home/index",     // page 事件必填；其它可空
      "duration": 84000,              // hide 事件必填：距上次 show 的毫秒
      "ch": "official",               // 渠道（可空）
      "errorType": "js",              // error 事件必填：js|network|biz
      "message": "TypeError: ...",    // error 事件必填
      "stack": "at ...",              // error 可选
      "extra": {}                     // 任意扩展（JSON对象，≤2KB）
    }
  ]
}
```

响应：`Result<Void>`（`code=200`）。**处理失败也不影响客户端**——客户端失败即回队列重试。

服务端处理规则（逐事件）：

| 事件 | 动作 |
|---|---|
| `launcher` | upsert 设备表；`stat_metric_hourly.visits+1, launches+1` |
| `show` | upsert 设备表（更新 last_date） |
| `hide` | `total_duration_ms += duration` |
| `page` | `pv+1`；`stat_page_hourly.pv+1` |
| `error` | `error_count+1`；插 `stat_error_log`（算 fingerprint） |
| `custom` | 只进 `extra` 透传（本期不聚合，预留） |

**限制（服务端强校验）**：`events` 数量 ≤ 200；单事件 `extra` 序列化后 ≤ 2KB；`stack` ≤ 16KB；超限返回 400 或静默丢弃超限部分（建议丢弃并记日志）。

**安全**：
- `AuthInterceptor` 放行此路径（匿名上报，见 §5.2）；
- 保留 `RateLimitInterceptor` 限流（防刷）；
- 采集接口自身**不计入**接口统计（见 §5.3 排除清单）。

### 4.2 报表接口（admin，登录后可访问）

#### `GET /api/v1/admin/stat/overview`

参数：`date`（yyyy-MM-dd，默认今天）、`ut`（`all`/`app-android`/`app-ios`/`app-windows`/`web`，默认 `all`）。

```json
{
  "code": 200,
  "data": {
    "date": "2026-08-30",
    "today":     { "newDevices": 27,  "activeDevices": 1441, "totalDevices": 46759, "pv": 13631, "visits": 2043, "launches": 2102, "avgDurationMs": 84000, "errorCount": 12 },
    "yesterday": { "newDevices": 196, "activeDevices": 4069, "totalDevices": 46732, "pv": 83357, "visits": 10560, "launches": 10900, "avgDurationMs": 92000, "errorCount": 40 }
  }
}
```

口径：`avgDurationMs = total_duration_ms / max(visits,1)`；`yesterday` 为 date-1 同口径。

#### `GET /api/v1/admin/stat/trend`

参数：`metric`（`pv`/`visits`/`launches`/`errorCount`）、`date`、`ut`、`gran`（本期固定 `hour`）。

```json
{ "code": 200, "data": {
    "hours":    ["00:00","01:00", ..., "23:00"],
    "today":    [0,0, ..., 57, 0],
    "yesterday":[12,3, ..., 88, 5]
} }
```

**无数据的小时必须补 0**，数组长度恒 24。

#### `GET /api/v1/admin/stat/api/top`（兼容被删接口的字段契约）

参数：`limit`（默认 10）、`date`（可选，默认今天）。

```json
{ "code": 200, "data": [
  { "apiPath": "/api/v1/user/login", "apiMethod": "POST",
    "callCount": 1200, "successCount": 1180, "failureCount": 20,
    "avgTime": 45, "maxTime": 830 }
] }
```

> 字段名与旧前端 `statistics/page.tsx` 使用的 `apiPath/apiMethod/callCount/successCount/failureCount/avgTime/maxTime` 保持一致。
> 口径：`failure` = `status >= 400`；按 `callCount` 降序。

#### `GET /api/v1/admin/stat/api/trend`

参数：`uri`、`method`、`date`。返回 `{ hours: [...], callCount: [...24], avgMs: [...24] }`（补零）。

#### `GET /api/v1/admin/stat/error/page`

参数：`pageNum`、`pageSize`、`errorType`（可选）、`appVersion`（可选）、`fingerprint`（可选，看同一错误明细）。
返回 `Result<Page<StatErrorLog>>`（MyBatis-Plus 分页）。

#### `GET /api/v1/admin/stat/error/summary`

参数：`date`。按 `fingerprint` 分组：

```json
{ "code": 200, "data": [
  { "fingerprint": "a1b2...", "count": 32, "affectedDevices": 8,
    "errorType": "js", "sampleMessage": "TypeError: ...", 
    "firstSeen": "...", "lastSeen": "...", "topAppVersion": "3.0.0" }
] }
```

---

## 5. astral 后端修改点（A 开发）

### 5.1 SQL 种子（以增量迁移脚本追加：`sql/migrations/V{序号}__*.sql`）

1. §3 的 5 张表 DDL（幂等）。
2. **DB 菜单种子（D3）**——沿用现有种子风格（现有菜单 id：1、4~15、20、24，勿冲突）：

```sql
-- 数据统计菜单（/dashboard/statistics）
INSERT INTO sys_menu (id, parent_id, name, icon, path, permission, sort, visible, type)
VALUES (30, 0, '数据统计', 'BarChartOutlined', '/dashboard/statistics', 'statistics:view', 2, 1, 0)
ON CONFLICT (id) DO NOTHING;
```

3. **权限种子**（对齐 `sys_permission` 现有风格，permission_code → url）：

```sql
INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort)
VALUES (30, 'statistics:view', '查看数据统计', '/api/v1/stat/**', NULL, 1, 30)
ON CONFLICT (id) DO NOTHING;
```

> 开发时确认：若项目内"路由→权限"强校验依赖 `sys_permission.url`，此行必须；同时核对 id=30 未被占用（写前 `SELECT` 检查，或选用确定未用的 id）。

### 5.2 `WebMvcConfig`（`astral-server/.../config/WebMvcConfig.java`）

- `authInterceptor.excludePathPatterns` **追加** `"/api/v1/stat/report"`（匿名上报入口）。
- 注册新拦截器（见 5.3），排在 `authInterceptor` **之后**（需要 `userId` 属性）：

```java
registry.addInterceptor(apiRequestMetricInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns(
            "/api/v1/stat/report",              // 采集入口不统计，防自举
            "/swagger-ui/**", "/v3/api-docs/**",
            "/swagger-ui.html", "/doc.html",
            "/actuator/**"
        );
```

### 5.3 新增文件清单

| 模块 | 文件 | 内容 |
|---|---|---|
| astral-schema | `src/main/resources/schema/stat_*.json` ×5 | §3.6 |
| astral-dao | `com.astral.dao.mapper.StatDeviceMapper` 等 ×5 | `@Mapper extends BaseMapper<T>` |
| astral-monitor | `dto/StatEventDTO`、`dto/StatReportRequest` | 入参（`@Valid`，含 §4.1 限制） |
| astral-monitor | `dto/DeviceOverviewDTO`、`dto/TrendDTO`、`dto/ApiTopDTO`、`dto/ErrorSummaryDTO` | 出参 |
| astral-monitor | `service/StatIngestService` + `impl` | 批量处理、设备 upsert、桶累加、fingerprint |
| astral-monitor | `service/StatReportService` + `impl` | §4.2 六个查询 |
| astral-monitor | `service/ApiMetricCollector` | 接口指标**内存累加器**（`ConcurrentHashMap`），供拦截器调用 |
| astral-monitor | `api/StatIngestController` | `POST /api/v1/stat/report`，`@Tag/@Operation` |
| astral-monitor | `api/StatReportController` | §4.2 六个 GET |
| astral-server | `interceptor/ApiRequestMetricInterceptor` | `preHandle` 记起始时间；`afterCompletion` 交 collector（uri、method、status、耗时、userId） |
| astral-server | `config/ScheduleConfig` | `@EnableScheduling`（**项目目前没有**，需新增） |
| astral-monitor | `job/StatAggregationJob` | ① 每分钟 flush 接口指标到 `stat_api_hourly`；② 每天清理过期数据（错误>90天、小时桶>180天，天数可配） |

### 5.4 实现要点（按现有约定）

1. **桶累加必须防并发丢计数**：项目无 XML mapper，用**注解 SQL upsert**（MyBatis-Plus `@Insert` + PostgreSQL `ON CONFLICT ... DO UPDATE SET pv = stat_metric_hourly.pv + EXCLUDED.pv`）。设备表同理（`ON CONFLICT (device_id) DO UPDATE SET last_date=...`）。禁止"先 select 再 update/insert"的竞态写法。
2. **接口指标先攒后写**：拦截器只写内存累加器（纳秒级开销），`@Scheduled(fixedDelay=60000)` 每分钟 flush upsert 一次，DB 写入量 = uri×分钟。
3. **取号**：实体 `IdType.INPUT`，插入时 id 为 null 即由 `SequenceMetaObjectHandler` 自动以 `{表名}_id` 取号（`GeneratorFactory` 首次自动建配置），**不需要任何额外注册**；upsert 注解 SQL 里不写 id 列时注意：新行 insert 需要服务端先生成 id（`segmentGenerator.next` 不直接暴露给业务，**建议**：upsert 分两步——先 `SELECT ... WHERE 唯一键`，无则 `insert`（id 走自动填充），有则 `UPDATE ... SET pv = pv + ?`；低频桶写入可接受；或注解 SQL 用 `INSERT ... SELECT WHERE NOT EXISTS` + 单独 UPDATE，开发时定稿并在代码注释说明）。
4. **异步**：上报处理用 `@Async`（已有 `@EnableAsync`），失败记日志不影响响应。
5. **清理任务**：`@Scheduled(cron = "0 0 4 * * ?")` 每天凌晨清理。
6. **开关**：`application.yml` 增加 `astral.stat.enabled: true`、`astral.stat.error-retention-days: 90`、`astral.stat.hourly-retention-days: 180`，Collector/Job 上 `@ConditionalOnProperty`。

### 5.5 验收标准（后端）

- [ ] 启动后 5 张表自动创建；`sequence_config` 出现 `stat_*_id` 业务键（自动创建）。
- [ ] 未登录 `POST /api/v1/stat/report`（§4.1 样例）→ 200，`stat_device`/`stat_metric_hourly`/`stat_page_hourly` 出现数据。
- [ ] 未登录 `GET /api/v1/admin/stat/overview` → 401。
- [ ] 请求若干业务接口后 `GET /api/v1/admin/stat/api/top` 返回正确计数；`/api/v1/stat/report` 自身不出现在结果里。
- [ ] `trend` 返回 24 点补零数组。
- [ ] 同一错误重复上报 → `fingerprint` 相同，`error/summary` 聚合正确。

---

## 6. astral-front 修改点（A 开发）

| # | 文件 | 修改 |
|---|---|---|
| 1 | `src/api/statistics.ts`（新增） | DTO 类型 + `getOverview/getTrend/getApiTop/getApiTrend/getErrorPage/getErrorSummary`，全部走 `request.get(...)`（`@/api/client`，自动带 satoken） |
| 2 | `src/app/dashboard/statistics/page.tsx`（重写） | antd `Tabs` 三个页签：**设备统计**（今日/昨日卡片行 + ReactECharts 24 小时趋势线，`metric` 可切换）、**接口统计**（保留现有 Top 表格 + 柱图/饼图，改调 `/api/v1/admin/stat/api/top`）、**错误统计**（`error/summary` 表格 + 明细分页抽屉） |
| 3 | `src/app/dashboard/layout.tsx` 第 74 行 | fallback `menuConfig` 里 `{ key: '/dashboard/statistics', label: 'API统计' }` → `label: '数据统计'`（与 DB 菜单名一致；仅 fallback 生效时可见，**入口以 DB 菜单为准**） |

> 页面权限：菜单可见性由 `sys_menu.permission = 'statistics:view'` 控制（`layout.tsx` 已有过滤逻辑），页面自身无需重复校验；接口 401 由 `client.ts` 统一跳登录。

**验收标准（前端）**：
- [ ] 有 `statistics:view` 权限的账号在侧边栏看到「数据统计」（DB 菜单驱动），三个页签可切换。
- [ ] 无该权限的账号看不到菜单。
- [ ] 趋势图 24 点、tooltip 正常；接口统计 Top 表格数据与后端一致。

---

## 7. qt-uniappx 修改点（B 开发）

> 遵守 `qt-uniappx/AGENTS.md`：Vue3 组合式 API + `.uvue` + UTS services + Vapor 模式；原生行为放 UTS 服务并 `#ifdef`；禁 nvue/Vuex/`plus.*`/旧原生插件。
> **例外说明**：统计插件自带轻量 `uni.request` 上报（匿名、不进 token 刷新体系），这是对"网络统一走 `services/http.ts`"约定的一次**有意例外**，原因：上报必须独立于登录态、离线可用。插件 URL 仍由 App 侧从 `services/http.ts` 的 `resolveUrl()` 取得传入。

### 7.1 新增 uni_modules 插件（D4）：`uni_modules/qt-stat`

```
uni_modules/qt-stat/
├── package.json                      # uni_modules 标准清单（id: qt-stat）
├── readme.md                         # 用法 + 配置说明（给后续维护者）
├── utssdk/
│   ├── interface.uts                 # 跨端统一类型与接口定义
│   ├── app-android/
│   │   ├── index.uts                 # 采集实现 + Sentry-Android(sentry-java) 初始化
│   │   └── config.json               # 依赖声明：io.sentry:sentry-android（maven）
│   ├── app-ios/
│   │   ├── index.uts                 # 采集实现 + Sentry-Cocoa 初始化
│   │   └── config.json               # CocoaPods 依赖：Sentry
│   └── web/index.uts                 # （预留）H5 端采集，本期可空实现
```

**导出 API（`interface.uts` 定义，跨端一致）**：

```ts
// 初始化（App.uvue onLaunch 第一行调用，Sentry 必须最先初始化）
export function initQtStat(options: QtStatOptions): void
// QtStatOptions: {
//   ingestUrl: string      // 必填，如 resolveUrl("stat/report")
//   sentryDsn: string      // 自托管 Sentry DSN；空串=不启用Sentry
//   release: string        // 发布标识（建议 versionName），Sentry release 与之对齐
//   channel: string        // 渠道
//   debug: boolean
//   batchIntervalMs: number   // 默认 10000
//   maxBatchSize: number      // 默认 50
// }
export function qtTrack(evt: string, page?: string|null, extra?: UTSJSONObject|null): void
export function qtTrackPage(page: string): void
export function qtTrackError(errorType: string, message: string, stack?: string|null, page?: string|null): void
export function qtStatFlush(): Promise<boolean>   // 立即清空队列（hide 时调用）
```

**插件内部职责**：

1. **deviceId**：`uni.getStorageSync("qt-stat-device-id")`，无则生成随机 UUID 并 `setStorageSync`。**禁止**采集 IMEI/OAID 等设备标识（合规）。
2. **队列**：内存队列 + 满批/定时触发；`hide` 时把队列持久化到 storage（key `qt-stat-queue`），`launch` 时恢复并先 flush；失败重试 ≤3 次，队列上限 500 条（超出丢最旧）。
3. **事件封装**：`ut`（`app-android`/`app-ios`，取 `uni.getAppBaseInfo().uniPlatform`）、`appVersion`（`getAppBaseInfo().appVersion`）、`model/os`（`uni.getDeviceInfo()`）、`ts`。
4. **时长计算**：`show` 记录时间戳，`hide` 时 `duration = now - lastShowTs` 随事件上报。
5. **所有 API 必须 try/catch 包裹**，任何统计异常不得影响 App 主流程。
6. **Sentry**：`sentryDsn` 非空时在各端初始化（Android：`Sentry.init(dsn)` 并 `setRelease(release)`；iOS 同理），**只在 `initQtStat` 里做一次**；崩溃归属 Sentry，插件不把 crash 事件发给自建后端。

### 7.2 `App.uvue` 接入（唯一需要改的业务文件）

```ts
import { initQtStat, qtTrack, qtTrackError, qtStatFlush } from '@/uni_modules/qt-stat'
import { resolveUrl } from '@/services/http'

onLaunch(() => {
  // ...现有逻辑不动...
  initQtStat({
    ingestUrl: resolveUrl("stat/report"),
    sentryDsn: "https://xxx@your-sentry.example.com/1",  // 建议放 config.local.ts，不提交仓库
    release: "3.0.0",
    channel: "official",
    debug: false,
    batchIntervalMs: 10000,
    maxBatchSize: 50,
  })
  qtTrack("launcher")
})
// 新增
onShow(() => { qtTrack("show") })
onHide(() => { qtStatFlush() })     // 现有 persist 逻辑保留，追加此行
// 新增
onError((err) => {
  qtTrackError("js", String(err?.message ?? err), String(err?.stack ?? ""))
})
```

> 配置里的 DSN/开关建议放 `services/config.local.ts`（已 gitignore）的读取方式，避免真实 DSN 入库。

### 7.3 页面访问埋点（可选，第一期至少埋主要页面）

在需要统计的页面 `onShow` 里调 `qtTrackPage("pages/xxx/xxx")`。首期范围：`home`、`player`、`search`、`playlist`、`profile`（5 个主页面即可）。

### 7.4 验收标准（App）

- [ ] 冷启动 → 10 秒内后端收到 `launcher` 事件（后端日志/表可查）。
- [ ] 切后台 → `hide` 带 `duration`，且队列已持久化；杀进程重启不丢队列。
- [ ] 断网状态下操作 → 恢复网络后队列补报成功。
- [ ] 主动抛出一个未捕获 UTS 异常 → `stat_error_log` 出现记录（fingerprint 稳定，重试上报两次指纹一致）。
- [ ] 主动制造一次原生崩溃（调试期）→ 自托管 Sentry 收到事件，release 与 `initQtStat` 传入一致，堆栈已符号化（上传 dSYM/mapping 后）。
- [ ] 上报失败不影响任何页面功能（全部静默）。

---

## 8. Sentry 自托管部署（A 负责部署，B 只接 SDK）

1. 服务器上 `git clone https://github.com/getsentry/self-hosted` → 按官方 `install.sh` 用 docker compose 拉起（含 Postgres/Redis/Kafka/ClickHouse/Snuba）。
2. 建项目：`qt-music-android`（平台 Android）、`qt-music-ios`（平台 iOS），取两个 **DSN**。
3. 符号表（**不进安装包**，是上传到 Sentry 服务器的调试文件，缺它堆栈是乱码）：
   - Android：每次 release 构建，上传 `mapping.txt`（ProGuard/R8 混淆映射）+ NDK `.so` 符号（如有）；
   - iOS：上传 **dSYM**；
   - 上传工具：`sentry-cli`（CI 集成，`SENTRY_URL` 指向自托管地址）。
4. ⚠️ `QTmusic_nuve` 开了 `confusion` 代码混淆——混淆与符号化不冲突（mapping.txt 即为反解），但必须保证**每次构建的 mapping 与版本号一一对应归档**。

---

## 9. 里程碑与依赖关系

| 里程碑 | 内容 | 负责 | 依赖 |
|---|---|---|---|
| M1 | 后端：5 表 DDL + 实体 JSON + Mapper + 采集/报表接口 + 拦截器 + 定时任务（§5） | A | 无，可立即开工 |
| M2 | 前端：api 模块 + 三页签报表页 + DB 菜单种子生效（§6） | A | M1 的接口契约（§4，可先 mock 并行） |
| M3 | App：`uni_modules/qt-stat` + `App.uvue` 接入 + 5 页面埋点（§7） | B | 仅 §4.1 契约（可 mock 并行）；联调需 M1 |
| M4 | Sentry 自托管部署 + 符号上传流水线（§8） | A | 无 |
| M5 | 联调验收：§5.5 + §6 + §7.4 全过 | A+B | M1~M4 |

**联调注意**：App 的 `services/config.ts` 当前 `USE_DEV = false`（指向生产）。联调统计时切 `USE_DEV = true`（`API_BASE_URL_DEV`），**联调完必须改回**，避免污染生产数据。

---

## 10. 风险与开放问题（开发时确认，不阻塞开工）

1. `sys_permission.url` 是否参与运行时强校验（还是仅做展示/管理）——写种子前在 `astral-auth` 里确认一次（`StpInterfaceImpl.getPermissionList` 的消费方）。
2. `RateLimitInterceptor` 对 `/api/v1/stat/report` 的默认阈值是否够用（10s 一批的量级大概率没问题，压测时看一眼）。
3. MySQL prod profile 无自动建表——生产发布时需手动执行 DDL（或后续把 DDL 纳入 MySQL 初始化）。
4. 菜单/权限种子 id（30/31 等）写前需 `SELECT` 确认未占用。

---

## 附：给 B（qt-uniappx 开发）的最小上手清单

1. 新建 `uni_modules/qt-stat`（结构见 §7.1），先做**纯采集版**（不接 Sentry，DSN 传空串），跑通 §7.4 前四条验收。
2. `App.uvue` 按 §7.2 接入（这是你唯一要动的业务文件）。
3. 用 mock/本地后端对照 §4.1 契约自测字段与限制（≤200 条/批、extra ≤2KB）。
4. Sentry（M4 就绪后）填真实 DSN + 上传符号，补 §7.4 最后一条。

