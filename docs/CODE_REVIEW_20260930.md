# astral 代码质量审阅报告

> 审阅日期：2026-09-30
> 审阅范围：`F:/qtMusic/astral`（后端 11 个 Maven 模块 + 前端 `astral-front`）
> 技术栈：Spring Boot 4.1.0 / Spring Framework 7.0.8 / JDK 25（虚拟线程已开启）/ MyBatis-Plus 3.5.17 / PostgreSQL / Redis(Lettuce) / Sa-Token 1.46 / Next.js 16.3.8 + React 19.2
> 说明：`com.astral.dao.entity.*` 为代码生成产物，`target/`、`node_modules/` 不在审阅范围。

---

## 一、量化基线

| 指标 | 数值 |
|---|---|
| 后端 Java 源码 | 11 模块 / 371 文件 / **32,192 行** |
| 前端 TS/TSX 源码 | `astral-front/src` 97 文件 / **21,220 行** |
| Controller / Service 类 | 48 / 55 |
| `@Transactional` 标注 | 25 处 |
| `catch (Exception\|Throwable)` | **129 处** |
| `@RateLimit` 标注 | 5 处 |
| **单元测试文件** | **0** |
| 前端 `any` 出现次数 | 276 |

最大文件（重构候选）：`SchemaCodeGenerator.java`(849) > `StorageFileService.java`(793) > `QtLikeService.java`(593) > `QtSourceService.java`(575) > `FeedbackNoticeService.java`(486) > `TableSchemaController.java`(477)；前端 `antd-compat/Inputs.tsx`(1658) > `plugin/storage/page.tsx`(1140) > `dashboard/layout`(1030)。

**最刺眼的一条基线：整个工程 0 个测试文件。** 所有下述并发、边界、性能结论目前都只能靠人工推理保证，任何一次重构都没有安全网。

---

## 二、整体质量总结

| 维度 | 评级 | 结论 |
|---|---|---|
| 代码结构与可读性 | **B+** | 模块边界设计成熟，注释质量显著高于同类项目；扣分在 Controller 严重过胖（部分含业务逻辑与 mapper 直用）、重复控制器、存储商 if-else 蔓延 |
| 错误处理与边界情况 | **C+** | 129 处宽 catch、大量参数未校验、若干「只 then 不 catch」、错误静默化；0 测试使问题不可回归 |
| 模块解耦与可维护性 | **B-** | 宿主硬编码插件内部类、qt 直连 storage 插件 mapper、客户端头契约四处独立实现 |
| 异常与容错机制 | **C+** | 多处无幂等、无重试、无超时；统计 flush 无异常隔离导致整分钟数据丢失 |
| 资源管理与并发安全 | **B-** | 虚拟线程适配良好、无 ThreadLocal 泄漏、无 SimpleDateFormat 误用；但存在无界内存 Map、可丢更新的 drain、签到/回执的「先查后插」竞态 |
| 性能（复杂度 / 热点） | **C+** | 每请求鉴权链 4~5 次 Redis 往返、Token 列表全库扫描、每次取号回源查 config、多处 N+1 与全量落库 |

**综合评级：B-（约 72/100）**

一句话定性：**架构分层与安全设计是加分项，工程纪律（测试、边界、异常隔离）与热路径效率是主要失分项。** 当前系统在正常流量下可稳定运行，但在「并发冲击 + 依赖抖动」两类场景下缺乏韧性，且存在若干可被直接利用的资源耗尽面。

---

## 三、P0 阻断级问题（建议本周内处理）

### P0-1 邮件账号 SMTP 授权码明文回传前端
**位置**：`astral-system/src/main/java/com/astral/system/controller/MailAccountController.java:31-41`
**现象**：分页与详情接口直接返回 `SysMailAccount` 实体，该实体含 `password` 字段（即邮箱 SMTP 授权码，`MailServiceImpl.java:205` 取用）。已复现确认。

```java
public Result<Page<SysMailAccount>> page(...) { return Result.success(accountService.page(...)); }
public Result<SysMailAccount> getById(@PathVariable Long id) { return Result.success(accountService.getById(id)); }
```

**影响**：任何持有 `admin:system:mail:view` 权限的人可批量导出全部邮箱授权码。一次越权读取即等价于全站邮件通道被接管（可发钓鱼信、可耗尽额度），且授权码通常长期有效、不易轮换。
**建议**：响应改用 DTO 并剔除 `password`（或返回固定掩码）；更新接口以「空密码 = 不修改」语义处理；同时清理日志与 `operate_log` 中的同类字段。

### P0-2 图片像素上限在解码之后才校验（解压炸弹）
**位置**：`astral-plugin/src/main/java/com/astral/storage/service/StorageFileService.java:293-300`（配合 `:280`、`:340-367`）
**现象**：`full` 校验时先 `ImageIO.read(...)` **完整解码**，再判断 `width*height > maxPixels`。已复现确认：

```java
java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new ByteArrayInputStream(head));
if (image != null && (long) image.getWidth() * image.getHeight() > policy.maxPixels()) { ... }
```

同时 `fetchContentBytes(file, full ? -1 : 4096)` 在 `full` 分支传 `-1`，落到 `in.readNBytes(Integer.MAX_VALUE)` —— 整文件载入堆。

**影响**：PNG/JPEG 是压缩格式，「小文件、巨型画布」的构造图（如 50000×50000 单色 PNG，实际仅几百 KB）可让单次解码分配数 GB 堆。校验逻辑本意是防这类攻击，却在**攻击生效之后**才拦截。上传回执路径若可被外部触发，即为确定的堆内存 DoS。
**建议**：改用 `ImageReader` 只读元信息（`reader.getWidth(0)/getHeight(0)`）先校验再决定是否解码；`full` 分支改为流式读取并设硬上限；`maxPixels` 默认值收紧。

### P0-3 前端全站无错误边界
**位置**：`astral-front/src/app/` —— 已确认不存在 `error.tsx` / `global-error.tsx` / `not-found.tsx`
**现象**：63 个 `'use client'` 组件共处同一棵客户端树，任何一处渲染期异常都无捕获点。
**影响**：一个缺失的可选链（例如后端返回 `null` 而组件直接 `.map()`）就能让整个后台白屏，用户无重试路径，只能清缓存或等待发版。结合「0 测试」与「调用链普遍不校验返回体形状」，这是**高概率而非低概率**事件。
**建议**：补 `app/error.tsx` + `app/global-error.tsx` + `app/dashboard/error.tsx`，带「重试 / 返回」按钮与错误上报；顺手核对所有列表渲染的 `?? []` 兜底。

### P0-4 接口指标采集：维度无界 + drain 丢更新
**位置**：`astral-monitor/src/main/java/com/astral/monitor/service/ApiMetricCollector.java:37-41,46-53`
**现象**（两条独立缺陷）：
1. 桶键含 `appVersion`，而 `ClientHeaders.MAX_VERSION` 只做**截断到 32 字符**，未做格式白名单（`ut` 有白名单，`appVersion` 没有）。已复现确认。
2. `record()` 在 `computeIfAbsent(...).add()` 中，`add()` 位于 map 原子段**之外**；`drain()` 用 `metrics.remove(key, value)` 摘对象。已复现确认。

```java
metrics.computeIfAbsent(key, k -> new ApiMetricValue()).add(costMs); // add 在原子段外
metrics.forEach((key, value) -> { if (metrics.remove(key, value)) drained.put(key, value); });
```

**影响**：
- 缺陷 1：`/api/v1/app/**` 匿名可访问，攻击者每请求换一个 `X-App-Version` 即可生成无限 key → 内存持续膨胀，且每分钟 flush 出大量 DB 行（保留 180 天），同时污染 `stat_api_hourly` 全部版本维度统计。
- 缺陷 2：某线程已持有 value 引用、在 `remove` 之后才 `add()`，该计数落在已脱链对象上，定时任务读不到 → **静默丢计数**。类注释宣称「原子移除避免丢数据」，实际不成立。

**建议**：`appVersion` 增加与 `ut` 同口径的正则白名单（非法归空串）；`drain()` 改为整体替换 map（`AtomicReference<ConcurrentHashMap>` 换取新实例），让旧 map 不再被写入。

---

## 四、分模块问题清单

### 4.1 鉴权与会话层（astral-server / astral-auth）

**[P1] `astral-server/.../interceptor/AuthInterceptor.java:131` — `/api/v1/app/**` 整片匿名放行**
已复现确认，注释声称「宽前缀放行已移除」，但紧接着用 `startsWith(APP_PUBLIC_PREFIX)` 又开了一个更宽的口子：

```java
if (uri.startsWith(APP_PUBLIC_PREFIX) && !uri.startsWith(QT_USER_PREFIX_NEW)) { return true; }
```

**影响**：`/api/v1/app/feedback`、`/api/v1/app/message`、`/api/v1/app/stat` 全部默认匿名，鉴权被下推给各插件（feedback 因此不得不自建 `FeedbackAuthInterceptor`）。今后任何新增 `/app/**` 接口**默认对外**，与 P0-4 的维度膨胀形成组合风险。
**建议**：改为显式白名单集合，与 `PUBLIC_PATHS` 合并管理。

**[P1] `AuthInterceptor.java:116,215-232` — 每请求鉴权链 4~5 次 Redis 往返**
已复现确认：`renewIfNeeded` 先 `getLoginIdByToken` 再 `getTokenTimeout`（`:221,224`），随后流程中又有 `isLogin()`（`:155`）、`getLoginIdDefaultNull()`（`:172,197,253`）、`getSession(false)`（`:255`）；`PermissionCache.read` 再各读一次 session + `VERSION_KEY`。

**影响**：注释中「每请求仅一次 Redis 读」的目标不成立。这是**每个 API 请求都走的热点路径**，且 `REDIS_DB=7` 被本地与线上共用 —— 单请求放大 4~5 倍延迟，QPS 上升时 Redis 成为系统性瓶颈。`PermissionAspect.hasAll` 逐权限码调用 `hasPermission`，把放大再乘 N 倍。
**建议**：合并为一次 token 会话读取（token→loginId→session 一次拿全）；`hasAll` 复用同一份权限列表。

**[P1] `astral-auth/.../service/impl/TokenServiceImpl.java:52,60-121` — 在线 Token 列表全库扫描 + N+1**
已复现确认：

```java
List<String> allKeys = dao.searchData("", "", 0, -1, true); // 取全部键
for (String key : allKeys) { ... StpUtil.getLoginIdByToken(tokenValue) ... }
```

**影响**：`searchData(..., 0, -1, ...)` 拉取整个 Redis 库的全部键，再对每个 token 做 1~3 次往返。管理端翻一次页即全库扫描 + O(N) 网络往返，并与线上业务共享同一 Redis 实例 —— 单次操作可拖慢整个共享实例。同时 `:139-144` 的 `(pageNum-1)*pageSize` 未校验，`pageNum=0` 会 `IndexOutOfBoundsException` → 500。
**建议**：改用 `SCAN` 游标分页；分页参数做 `max(1,..)` 与上限钳制；中长期落一张在线会话表。

**[P1] `astral-auth/.../security/RsaKeyManager.java:26,107` — 登录失败计数是无界本机内存**
`ConcurrentHashMap<String, FailureRecord>` 对任意用户名（含不存在者）建条目，无容量上限、无过期清理。
**影响**：用随机用户名刷登录即可让堆无限增长（内存 DoS）；多实例下锁定策略按实例数放大且重启即清零，防爆破能力实际很弱。
**建议**：换 Caffeine（`maximumSize` + `expireAfterWrite`）；锁定计数下沉 Redis 以获得跨实例一致语义。

**[P1] `application.yml:169` / `WebMvcConfig.java:26` — 监控与文档端点绕过鉴权**
`management.endpoints.web.exposure.include=health,info,metrics,prometheus` + `show-details=always` 且 springdoc 全开，而鉴权拦截器只 `addPathPatterns("/api/**")`。
**影响**：`/actuator/prometheus`、`/actuator/metrics`、`/v3/api-docs`、`/swagger-ui.html` 匿名可读，暴露 JVM/DB/Redis 内部指标与全部接口清单，为攻击者提供完整踩点信息。
**建议**：生产环境关闭或单独加鉴权（`/actuator/**` 纳入保护 + 独立口令），springdoc 仅在非生产 profile 开启。

**[P2] `AuthInterceptor.java:7` — 宿主硬编码依赖插件内部类**
`import com.astral.qt.common.QtRestResp;` 及 `QT_USER_PREFIX_*`、`STORAGE_*_PREFIX` 常量写在宿主拦截器里。
**影响**：宿主与 qt/storage 插件强耦合，插件禁用或移除即编译失败；插件路由知识散落在宿主，新增插件必须改宿主。
**建议**：抽 `PluginAuthAdvice` SPI，由插件自行注册 401 响应结构与路径前缀。

**[P2] `LoginUserTypeResolver.java:67` — 入参 `loginId` 被忽略**
方法内实际取 `StpUtil.getSession(false)`（当前线程会话），`loginId` 仅用于空判。
**影响**：签名与语义不符。当前恰因调用方永远是当前用户而未暴露，但定时任务或代查他人场景会**静默取回错误用户的 `user_type`**。
**建议**：改走 `getSessionByLoginId(loginId, false)`。

**[P2] `RsaKeyManager.java:86,98-104` — PKCS#1 v1.5 填充 + 可区分错误码**
`Cipher.getInstance("RSA")` 即 `RSA/ECB/PKCS1Padding`；解填充失败抛 `AUTH004`、密码错抛 `AUTH002`，构成填充 oracle。私钥以明文落盘 `data/rsa-key.pair`，无权限加固。
**建议**：改 `OAEPWithSHA-256AndMGF1Padding`；`AUTH004`/`AUTH002` 合并为同一响应；私钥文件权限收紧。

> **该层未发现问题的维度**：资源管理与并发安全（无 `SimpleDateFormat` 误用、无未关闭流、`DateTimeFormatter` 不可变）；JDK 25 已由 JEP 491 解除 `synchronized` 的载体线程固定，`PermissionCatalog` 的 `synchronized` 在虚拟线程下无 pinning 隐患。事务边界清晰——本层登录只写会话不写库。

---

### 4.2 业务模块 · qt

**[P1] `astral-plugin/.../qt/service/QtSourceService.java:176-181` — 平台筛选在分页之后执行，结果与 total 双错**
已复现确认：

```java
List<QtSourceRelease> records = releaseMapper.selectPage(page, wrapper).getRecords();
for (...) { if (platform != null && !platformMatches(r, platform)) { continue; } ... }
```

`platform` 是 CSV 存储、未下推 SQL。
**影响**：某页可能被筛空、真实匹配行落在后续页而**永远翻不到**（功能性 bug，不是性能问题）；`voPage.total` 仍是 SQL 全量数，管理端列表与统计对不上，用户会误判发布包数量。
**建议**：平台条件下推为精确匹配（如 `platforms LIKE '%,1101,%' OR platforms LIKE '1101,%'`，注意首尾边界），或改用关联表；至少修正 `total` 为过滤后计数。

**[P1] `astral-plugin/.../qt/service/QtDakaService.java:66` — 签到「查后插」无唯一约束，并发重复计分**
`monthDays.contains(date)` 判重后 `this.save(daka)`，无事务；表仅有 `idx_qt_daka_uid_data`（`qt-schema.sql:106`，**非唯一**）。
**影响**：双击或请求重放可写入两行同日记录 → 积分与连续天数翻倍，且无法回滚。这是**可直接被普通用户利用**的资损/数据污染面。
**建议**：加 `UNIQUE(uid, data)` 唯一索引，捕获冲突后返回幂等结果；方法加 `@Transactional`。

**[P1] `astral-plugin/.../qt/dto/QtUploadLikeListDto.java:14` — 全量同步负载无上限**
`playlistList` / `songList` 无 `@Size` 约束，`uploadLikeList` 以单条多值 `INSERT` 落库。
**影响**：超万条时单语句参数超过 PostgreSQL 上限（约 65535）直接报错，**整个同步失败**；同时全量结果驻留内存。用户曲库越大越容易触发，表现为「同步莫名其妙失败」。
**建议**：两个 list 加 `@Size(max=...)`；`insertBatch` 按 500/批切分。

**[P1] `astral-plugin/.../qt/service/QtMediaService.java:68` — qt 业务线直连 storage 插件 mapper（越界耦合）**
注入 `StorageFolderMapper` 并直接 `insert/selectOne`，`ensureMediaTree` 每次调用做 2 次 query 且可能建目录。
**影响**：storage 表结构或逻辑变更会连带 qt **编译失败**，插件边界被击穿；每次取凭证多 3~4 次 DB 往返。
**建议**：把「按名解析/创建约定目录」封装进 `StorageFolderService` 暴露接口，qt 只调 service。

**[P1] `astral-plugin/.../qt/service/QtUserService.java:222` — 邮件频控非原子，可绕过**
```java
if (Boolean.TRUE.equals(hasKey(rateK))) throw ...;
set(rateK, "1", RATE_TTL);
```
**影响**：并发或重放下两次请求都命中空窗，同邮箱短时间连发验证码，可被刷爆邮件额度（结合 P0-1 的凭据泄露，攻击成本极低）。
**建议**：改为 `setIfAbsent(rateK, "1", RATE_TTL)`，返回 `false` 即拒绝。

**[P2] `astral-plugin/.../qt/service/QtLikeService.java:126` — 删除歌单级联为循环内单条 SQL**
```java
for (tuple : delPlaylists) songMapper.softRemoveAllByPlaylist(...);
```
**影响**：解绑 N 个歌单即 N 次 DB 往返（典型 N+1）。仓库中已有 `softRemoveAllByPlaylistBatch` 未被使用。
**建议**：复用批量方法，一次搞定。

**[P2] `astral-plugin/.../qt/service/QtGithubAccelService.java:118` — 探活阻塞在 ForkJoinPool.commonPool**
`CompletableFuture.supplyAsync(() -> probeOne(...))` 未指定执行器，内部是阻塞式 `client.send`。
**影响**：`commonPool` 并行度 = CPU 核数，节点多或外部挂起时会占满公共池，**影响其他所有使用 `parallelStream` 的代码**（跨模块故障扩散）。项目已开虚拟线程，此处却未用。
**建议**：显式传 `Executors.newVirtualThreadPerTaskExecutor()`，并加整体超时。

**[P2] `astral-plugin/.../qt/service/QtSourceService.java:196` — 版本号生成仅单实例 `synchronized` 互斥**
```java
synchronized (VERSION_LOCK) { nextVersionCode(); ... }
```
靠 `uk_qt_source_release_code` 唯一键兜底，但**无重试**。
**影响**：多实例并发时后到实例抛唯一键异常，建包失败（注释已承认此限制）。
**建议**：改 `SELECT ... FOR UPDATE` 或 PG advisory lock 跨实例串行；或捕获唯一冲突后重取号重试。

**[P2] `astral-plugin/.../qt/service/QtLikeService.java:557` — getChanges 截断却回传全局 maxSeq**
`changes` 单页限 500，但 `vo.setMaxSeq(selectUserMaxSeq(uid))` 返回真实全局最大值。
**影响**：客户端若把 `maxSeq` 当游标推进，**超过 500 条的变更被永久跳过**（静默数据丢失，用户表现为「收藏对不上」）。
**建议**：截断时 `maxSeq` 返回本页末条 seq，或增加 `hasMore` 标志并写进契约。

**[P2] `astral-plugin/.../qt/controller/QtAdminController.java:103,212` — Controller 承载业务逻辑并直用 mapper**
`validateUpdateLinks`、默认值填充、`userMapper`/`qtUpdateMapper` 增删改查全在 Controller；`changeUserState` 未校验 `state` 合法值。
**影响**：逻辑无法复用与单测（本项目 0 测试，进一步锁死）；公告、版本、加速节点各自重复一套默认值代码；`changeUserState` 可写入非法状态。
**建议**：下沉到 `QtAppService`/`QtSourceService`，Controller 仅做参数绑定与调用。

**[P2] `astral-plugin/.../qt/controller/QtUserController.java:172` — 与新控制器近 200 行整段复制**
`QtUserController` 与 `QtAppUserController` 的 `currentUserId()` 及全部端点实现逐字重复，仅路径不同。
**影响**：改一处漏一处，两套行为必然漂移（安全修复只修一边即留下漏洞）。
**建议**：旧类改为委托新类，或直接关停旧路由。

**[P2] 其他（合并）**
- `qt/service/QtUserService.java:88,91`：登录「用户名不存在 / 密码错误」文案可枚举用户，建议统一提示。
- 宽 catch：`QtMediaService.java:294` 吞并发建目录异常、`QtAppNoticeService.java:129` 循环内 `catch(Exception)` 掩盖真错。
- 硬编码枚举：`"APP"`(`QtAdminController:74`)、`"stable"`(`:171`)、`"add"/"remove"/"song"`(`QtLikeService:276,432`)、`USER_TYPE_APP` 多处重复，建议抽常量/枚举。

---

### 4.3 业务模块 · feedback

**[P2] `astral-plugin/.../feedback/service/FeedbackNoticeService.java:118,160` — 全表捞出后 Java 过滤，`countUnread` 重复全量**
`selectList(is_show=1)` 后在 Java 侧依次过滤 channel / 时间窗 / 版本 / 人群；`countUnread` 又调 `listMessageCenter` **再全量一遍**。
**影响**：通知量增长后每次 App 请求全表扫描 ×2，纯放大。（注：`is_show`/`notice_type`/`create_time` 已有索引 `idx_notice_show/type/create_time`，可直接下推。）
**建议**：至少让 `countUnread` 复用同一批结果；中期把可下推条件放回 SQL。

**[P2] `astral-plugin/.../feedback/service/FeedbackService.java:293,370` — 看板 20+ 次单查 COUNT**
`countByWrapper` 被调用约 13 次，近 7 天再循环 7 次 COUNT；`countUnreplied` 还全量拉 pending 再 `IN` 查回复。
**影响**：单次看板请求数据库往返数十次；`IN` 列表随积压量线性膨胀，积压越多越慢（正反馈恶化）。
**建议**：合并为 `GROUP BY status/type` 单查 + 日期 `date_trunc` 聚合；未回复改用 `NOT EXISTS` 子查询计数。

**[P2] `astral-plugin/.../feedback/service/FeedbackService.java:56,183` 及 `FeedbackNoticeService.java:206`**
- `:56` 静态 `HashMap` 可被后续误改，建议 `Map.of`/`Map.copyOf` 固化。
- `:183` / `:206` 用 `endDate + " 23:59:59"` 拼串比较 TIMESTAMP，依赖字符串比较语义，建议传 `LocalDateTime` 或改上界开区间 `&lt; end+1d`。

---

### 4.4 业务模块 · storage

**[P1] `astral-plugin/.../storage/service/StorageFileService.java:280,360-366` — 内容校验全量载入内存**
已复现确认（`full` 分支传 `-1` → `readNBytes(Integer.MAX_VALUE)`）：

```java
var response = VERIFY_HTTP_CLIENT.send(builder.build(), ofInputStream());
try (var in = response.body()) { return in.readNBytes(limit > 0 ? limit : Integer.MAX_VALUE); }
```

**影响**：`maxSize` 可经 config/文件夹策略放大，`full` 校验时大文件并发回执造成堆叠加（与 P0-2 同一链路，两者应一并整改）。
**建议**：magic 只需前 4KB（现有 `limit=4096` 分支已正确）；`full` 改流式读取并设硬上限。

**[P1] `StorageFileService.java:63,360-366` — 校验用 HTTP 客户端无任何超时**
`HttpClient.newHttpClient()` 未设 `connectTimeout`，`send` 也未 `.timeout(...)`。
**影响**：Provider/Worker 卡死时回执请求**无限挂起**，接口长期不返回，连接与内存被持续占用（最终传导为线程/连接池耗尽）。
**建议**：复用带 `connectTimeout` 的 client，并对每次请求显式设超时。

**[P1] `astral-plugin/.../storage/controller/StorageUserController.java:47-57` — X-Forwarded-For 无条件信任**
`clientIp()` 直接取 XFF 首段；同文件已注入的 `trustedProxies`(`:32`) **从未使用**。
**影响**：登记/审计 IP 可被任意伪造，与项目内 `ClientIp` 工具「从右取首个不可信跳」的正确设计相悖，风控与审计数据失真。
**建议**：改调 `ClientIp.resolve(xff, realIp, remoteAddr, trustedProxies)`。

**[P2] `StorageFileService.java:649-705`、`StorageConfigService.java:238-353` — 存储商分派 if-else 蔓延**
HEAD/DELETE/options/公开 URL/密钥字段各有 4~6 段 `isS3Family / COS / OSS / UPYUN` 分支。
**影响**：新增存储商需改 5+ 处；**漏改会静默走错分支**（`deleteObjectByProvider` 甚至无 `else` 兜底），属于典型的「重构时不会编译报错」的隐患。
**建议**：抽 `ProviderAdapter`（head/delete/presign/options）按 type 注册到 Map 分派。

**[P2] `StorageFileService.java:134-139,192-197` — 幂等「先查后插」竞态**
`selectOne(upload_id)` 判重后再 `insert`，未捕获唯一键冲突。
**影响**：Worker 与浏览器并发重复回调时双方都通过判重，插入抛 `DuplicateKeyException` → 500，而非幂等返回已有记录。客户端会看到「上传成功但接口报错」，进而重试，进一步放大。
**建议**：捕获 `DuplicateKeyException` 后重查返回，或用 `INSERT ... ON CONFLICT DO NOTHING`。

**[P2] rootMessage 复制 5 份**
`StorageConfigService.java:393`、`S3ObjectService.java:333`、`CosApiService.java:324`、`OssApiService.java:229`、`UpyunApiService.java:306` —— 逐层 unwrap cause 的同名私有方法各写一份。**建议**：抽到 `astral-common` 的 `ExceptionUtils`。

---

### 4.5 astral-sequence（发号器）

**[P1] `astral-sequence/.../service/GeneratorFactory.java:108,236` — 每次取号都回源查 config**
`next()` / `batch()` 首行 `getOrCreateConfig` → `configMapper.selectByBizKey(bizKey)`。
**影响**：号段模式的全部意义就是降低 DB 压力，现在**每次取号仍有一次 SELECT**，高频调用时 DB 反而成为瓶颈——这是设计目标与实际实现的背离，也是本项目最典型的「热点路径上的无谓往返」。
**建议**：按 bizKey 缓存配置（Caffeine 或 Map+TTL），仅未命中时回源。

**[P1] `astral-sequence/.../config/SequenceMetaObjectHandler.java:113-116` — 每次实体插入追加一次异步统计写**
填 id 后调 `updateStatisticsAsync`（`insertIfAbsent` + `update` 两条 SQL）。
**影响**：**所有**业务插入都额外增加 2 条异步 SQL，全站写放大；executor 无队列/限流，突发时抢占 DB 连接。
**建议**：统计值改为批量/定时刷新，或每 N 个号才落库一次。

**[P1] `astral-sequence/.../generator/SegmentGenerator.java:134-153,166-193` — 换段无 single-flight**
段耗尽时多线程各自 `fetchSegmentFromDb`（乐观锁最多重试 10 次），无单飞保护；预加载段被 CAS 丢弃时 DB 已推进。
**影响**：(a) 并发羊群式重复 DB 往返，段耗尽瞬间形成 DB 尖刺；(b) 被丢弃的段造成**永久跳号**（对单号有连续性要求的业务是数据问题）。
**建议**：换段走 `preloadLock.tryLock` 单飞，未抢到者直接读 current；丢弃段回写 next 以复用。

**[P2] `SegmentGenerator.java:15-19`、`SnowflakeGenerator.java:65,75`、`SimpleGenerator.java:32`**
`SegmentGenerator` 有 5 个 import 重复两遍（脏代码）；三个生成器的 bizKey→状态 Map **只增不删**，bizKey 基数大时构成常驻内存泄漏。**建议**：清理 import；状态 Map 加容量上限或 LRU。

---

### 4.6 astral-monitor（统计监控）

> 缺陷 1、2 见 P0-4。

**[P1] `astral-monitor/.../job/StatAggregationJob.java:56-99` — flush 无异常隔离，单桶失败丢整分钟数据**
`drain()` 后直接在循环里 `incrementApi` / `insert`，全程**无 try/catch**。
**影响**：任一桶抛异常（DB 抖动、单行约束冲突）即中断整个方法，而 `drained` 已从内存摘除且不再重试 → **本分钟全部接口指标永久丢失**。数据是静默消失的，监控本身失效而无告警。
**建议**：循环内按桶 try/catch 记 warn 并继续；失败桶回填 collector 或改批量 upsert 保证不丢。

**[P1] `astral-monitor/.../api/AppStatController.java:41-47` — 匿名上报入口无频率限制、无批量写入**
白名单放行（`AuthInterceptor:106-107`）且无 `@RateLimit`（拦截器仅在注解存在时限流，`RateLimitInterceptor:56-61`）；`processBatch` 逐事件「先 UPDATE 后 INSERT」。
**影响**：**单个请求即可放大最多约 600 次 SQL**（200 事件），且跑在无界虚拟线程上。生产 Hikari pool=5、1C2G，一次突发即可打穿连接池导致全站不可用。这是当前**最高性价比的攻击面**。
**建议**：该路径加 IP 级限流；事件先在内存按桶聚合再批量 upsert（batch）；异步执行器加信号量上限。

**[P2] `astral-monitor/.../api/StatReportController.java:80,111-112`（同类 `LogController.java:55`）— 分页/limit 参数未校验**
`limit`、`pageNum/pageSize` 全为裸 `int`，无 `@Min/@Max`，`LIMIT #{limit}` 直传。
**影响**：`pageSize=1000000` 可拉爆结果集与内存；负数直接 500。
**建议**：加 `@Min(1) @Max(200)`，`limit` 上限 100。

**[P2] `astral-monitor/.../service/StatReportService.java:61-71,82-87` + `DashboardOverviewService.java:71` — 概览固定 6 次 count**
对今日/昨日各做 3 次 `selectCount`（含 `totalDevices` 的 `first_date<=?`），仪表盘 30s 轮询又复用一次。
**影响**：单次请求 6 次统计扫描，`stat_device` 越大越慢，且每 30 秒重复一次，属于可持续优化的固定开销。
**建议**：用 PG 的 `COUNT(*) FILTER (WHERE ...)` 一条聚合一次取回三值。

**[P2] `astral-dao/.../StatErrorLogMapper.java:37-48` — 错误汇总内嵌两组相关子查询（N 组 × 2）**
每个 fingerprint 组都带 `sampleMessage`、`topAppVersion` 两个相关子查询，各自再按 `fingerprint + occur_time` 过滤；而 `idx_stat_error_fingerprint` **仅单列**。
**影响**：子查询仍需回表按时间过滤；当日 fingerprint 多时近似 N+1，是统计页最慢的一环。
**建议**：建 `(fingerprint, occur_time)` 复合索引；或改 `DISTINCT ON (fingerprint)` / 窗口函数一次算完。

**[P2] `astral-monitor/.../service/StatIngestService.java:34,267-272` — 时区依赖服务器本地时区，ts 无区间校验**
`ZONE = ZoneId.systemDefault()`；`toLocalDateTime` 仅拦 `ts<=0`，未来/远古时间戳原样落桶。
**影响**：(a) 容器按 UTC 部署时，桶边界与国内统计口径**错位 8 小时**，报表全部对不上；(b) 伪造 `ts` 可污染任意历史/未来小时的桶，破坏统计可信度。
**建议**：显式固定统计时区为配置项（如 `Asia/Shanghai`）；对 `ts` 做合理区间（如 ±7 天）裁剪或丢弃。

---

### 4.7 astral-log（操作日志）

**[P2] `astral-log/.../aspect/LoginLogAspect.java:111,120,122` — 登录热路径同步落库 + 3 条 INFO 日志**
`saveLoginLog` 在登录主线程同步 `insert`，且逐句 `log.info("保存登录日志…" / "调用logService…" / "登录日志保存成功")`。
**影响**：每次登录（**含撞库失败**）多一次 DB 往返与 3 行日志，登录接口吞吐被日志拖累，日志文件在爆破场景下快速膨胀。
**建议**：走 `OperateLogWriter` 同款独立 `@Async` Bean 异步写；删除三条调试 INFO。

**[P2] `astral-log/.../aspect/OperateLogAspect.java:90,92,187,202` — 主线程序列化请求/响应并全量落库**
请求参数与响应结果在主线程 `writeValueAsString` 后整段写入 `operate_log`；注解还挂在 `/sequence` 这类高频接口（`SequenceController.java:30`）。
**影响**：序列化开销落在业务线程；响应体（可能含分页大对象、邮箱/密码等敏感字段）被**原样持久化**——与 P0-1 叠加后，凭据可能同时存在于业务表与日志表两处。
**建议**：序列化移入异步线程并限制长度；对密码/授权码字段脱敏过滤；热路径不加 `@OperateLog`。

---

### 4.8 astral-system（系统管理）

> 凭据回显见 P0-1。

**[P2] `astral-system/.../mail/MailServiceImpl.java:131-139` — 额度先扣后退不了**
第 3 步 `tryConsumeQuota` 先占额度，第 4 步才校验模板是否存在，后续发送失败也不回退。
**影响**：错模板或全部账户发送失败都白扣一次配额，用户正常验证码可能被「吃掉」（可复现的客诉来源）。
**建议**：校验收敛到扣额度之前；`sendWithAccount` 抛错时在 catch 中 `decrement` 回补 Redis 计数。

**[P2] `astral-system/.../controller/TableSchemaController.java:409,439` — 更新/生成 ALTER 未校验表名，与其他接口不一致**
`getSchema`(`:79`)、`deleteSchema`(`:164`) 调 `invalidTableName`，而 `updateSchema`、`generateAlterSql` 直接传路径变量。
**影响**：路径穿越被 `SchemaRegistry.resolveSchemaFile` + `SchemaCodeGenerator.requireValid` 双保险挡住（安全上无实害），但非法名会以 `IOException` → **500 而非 `SYS013`**，属于可用性与一致性问题；且校验职责散落，后续新增接口易漏。
**建议**：所有带 `{tableName}` 的接口统一先过 `invalidTableName`，并补列名/索引列校验说明。

---

### 4.9 前端 astral-front

> 无错误边界见 P0-3。

**[P1] `astral-front/src/app/dashboard/statistics/page.tsx:118` — 筛选竞态，旧响应覆盖新响应**
`fetchData` 无过期守卫。**已确认全仓 0 处 `AbortController` / `signal`**（grep 无结果）。
**影响**：快速切换平台/版本/日期时，慢响应后到会覆盖新结果，卡片与图表和当前筛选**不符**（`plugin/storage/page.tsx:201`、`plugin/feedback/page.tsx:58` 同病）。用户会看到「筛选没生效」，进而反复点击加剧竞态。
**建议**：`lib/client.ts` 支持 `signal`；effect 内用 `alive` 标志或 runId 丢弃过期结果。

**[P1] `astral-front/src/lib/statTracker.ts:76,89-150,220,315` — 埋点版本号恒为默认值，且客户端头契约被二次实现**
已复现确认：

```js
const meta = document.querySelector('meta[name="app-version"]'); // 该 meta 标签全仓不存在
```

同时 `detectOsBasic`/`detectOs`/`detectBrowser`(`:89-150`) 与 `client-info.ts:47-79` 是**两套独立的 UA 解析**，`ut` 还硬编码 `'web'`。
**影响**：(a) `stat_device`/`stat_metric` 的 `appVersion` 全为默认值 → 按版本维度的统计**全部失真**（这是统计体系的核心维度之一）；(b) 项目自述「客户端系统头契约需四处同步」，此处即第五处实现，改一处必漏一处。
**建议**：直接复用 `clientHeaders()` 与 `CLIENT_UT_WEB`；删除 meta 兜底。

**[P1] `astral-front/src/plugin/storage/page.tsx:64,130` — `validateFields` 在 try 之外，校验失败即未捕获 rejection**
`const values = await configForm.validateFields()` 位于 try 之前；`Overlay.tsx:77-88` 的 `handleOk` 只有 try/finally **无 catch**。
**影响**：必填校验失败抛出未处理的 Promise rejection，用户点「确定」后**无任何提示**，只会觉得按钮坏了。
**建议**：移入 try，或在 `Modal.handleOk` 统一加 catch。

**[P1] `statistics/page.tsx:130,240,389` — 「由 client.ts 统一提示」的注释不成立，错误全静默**
```js
catch { /* 接口异常由 client.ts 统一提示 */ }
```
但 `lib/client.ts:53-85` 只 `reject`、不弹 toast（antd message 已随 antd 移除）。
**影响**：接口失败时页面呈现空表且无任何信号，运维与前端的感知能力归零（问题会被长期隐藏，直接推高平均修复时间）。
**建议**：catch 内 `toast.error`，或在 client 拦截器统一接 sonner。

**[P1] `plugin/storage/page.tsx:245-285` — 上传直传逻辑两份逐行重复且无超时**
`uploadFileDirect` 与 `plugin/imgbed/page.tsx:227-272` 三分支几乎一致；原生 `fetch` 无 timeout/abort。
**影响**：新增 Provider 需改两处、易漏；大文件直传无法取消，弱网下请求悬空占资源。
**建议**：抽 `src/api/upload.ts` 单一实现，配 `AbortSignal.timeout`。

**[P1] `plugin/imgbed/page.tsx:88` — 每张卡片各发一次签名请求（N+1）**
`FileCard` 各自 `useEffect(loadUrl)` 调 `permanentUrl`/`downloadUrl`。
**影响**：一屏 20 张卡即 20 个并发请求；`onChanged()` 后全量重发，列表越大越糟。
**建议**：列表接口批量返回 url；或并发限流 + 结果缓存。

**[P2] `statistics/page.tsx:405`（`sequence/page.tsx:412` 内联同样）— 列定义未 memo，击穿表格缓存**
`const columns = [...]` 每次渲染新建，而 `ResizableTable.tsx:179-327` 有三级 `useMemo` 依赖该引用。
**影响**：每次渲染重算列宽、重建表头，**键入时整表重渲**（输入框卡顿的直接来源）。
**建议**：`useMemo` 固定 columns（`ApiTab:257` 已有正确示范，照抄即可）。

**[P2] `statistics/page.tsx:78` + `Overlay.tsx:345-353` — Tabs 全量挂载 + ECharts 未按需**
未传 `destroy` 即渲染全部 panel → 3 个 Tab 同时挂载、发 4 个请求、建 5 个图表；`import('echarts-for-react')` 拉全量 echarts。
**影响**：首屏请求翻倍；隐藏容器内 0 高度初始化的图表切回时需 resize；包体约 1MB。
**建议**：首次激活才渲染 + `destroyInactiveTabPane`；改 `echarts/core` 按需注册。

**[P2] `sequence/page.tsx:67,73,203`（`log/page.tsx:33,46` 同类）— 无 catch 的 then 与未清理的防抖定时器**
多处只有 `.then` / `.then + .finally`；`resolveTypeRef` 的 `setTimeout`(`:179`) 无 unmount 清理。
**影响**：接口失败产生未捕获 rejection；卸载后仍 `setState`（React 19 下为警告，逻辑上属内存泄漏）。
**建议**：统一 catch；effect 返回 `clearTimeout`。

**[P2] `lightlisten/page.tsx:167` — 落地页大图未优化**
`<img src="/lightlisten/banner.jpg">` 无 `width/height/loading`。
**影响**：CLS/LCP 指标损失（落地页的 LCP 直接影响转化）。
**建议**：改 `next/image`，至少补尺寸 + lazy。

> **该层结构评价**：分层清晰（`antd-compat` 适配层 + `api/` + `lib/` 契约集中），`DataTable`/`ResizableTable` 的「全站单一渲染层」设计讲究，注释质量高于平均水平。主要短板集中在**错误处理与并发**：无错误边界、0 处请求取消、大量「只 then 不 catch」，以及埋点与 UA 契约的重复实现。

---

## 五、横切问题（跨模块，权重高于单点）

### 5.1 零测试覆盖 —— 一切风险的放大器
**位置**：整个工程 `src/test` 下 **0 个 Java 测试文件**
**影响**：本报告列出的所有竞态、越界、丢数据问题，在修复时都没有回归保护；「修好 A 写坏 B」无法被自动发现。考虑到已有 25 处 `@Transactional` 与 27 个模块间 DI 关系，任何重构都是盲改。
**建议**：不必追求覆盖率，**优先为下列 5 条高风险路径补集成测试**（用 Testcontainers 起 PG + Redis）：
1. 发号器并发取号与跳号（SegmentGenerator）
2. 签到幂等（QtDakaService）
3. 上传回执幂等与并发（StorageFileService）
4. 指标 flush 的异常隔离与不丢计数（ApiMetricCollector + StatAggregationJob）
5. 鉴权链的 401/403 分支与白名单边界（AuthInterceptor）

### 5.2 129 处 `catch (Exception|Throwable)` 需要系统性收窄
**影响**：宽 catch 会把「必须失败的」编程错误（NPE、`IndexOutOfBounds`）一并吞掉，转为「静默降级」，问题从编译期/测试期推迟到线上且不可观测。已确认的具体危害见 `QtMediaService:294`、`QtAppNoticeService:129`、`StatAggregationJob`（无 catch 则是反例）。
**建议**：逐处改为具体异常类型；确实需要兜底的用 `catch (Exception e)` + 必须 `log.error` 携带原始异常栈（不可只记 `e.getMessage()`—— 现有大量代码正是如此）。

### 5.3 契约与逻辑的重复实现（4 处已知 + 2 处新发现）
| 重复内容 | 位置 |
|---|---|
| 客户端系统头（契约声明需同步的四处） | `ClientHeaders`（权威）/ qt-uniappx / qt-pc / `astral-front/src/lib/client-info.ts` |
| **UA 解析（新发现，第 5 处）** | `astral-front/src/lib/statTracker.ts:89-150` |
| **rootMessage 异常解包（新发现，5 份）** | storage 的 ConfigService / S3 / Cos / Oss / Upyun |
| 上传直传逻辑（2 份） | `plugin/storage/page.tsx` / `plugin/imgbed/page.tsx` |
| JetBrains 用户控制器（2 份近 200 行） | `QtUserController` / `QtAppUserController` |

**影响**：改一处漏一处，安全修复尤其危险（只修一边等于留后门）。**建议**：按上表逐项收敛到单一实现。

---

## 六、优化优先级路线图

### 第一梯队 · 本周（正确性 / 安全 / 可被直接利用的攻击面）
| # | 问题 | 位置 | 类型 |
|---|---|---|---|
| 1 | 邮件授权码明文回传 | `MailAccountController:31-41` | 凭据泄露 |
| 2 | 图片像素校验在解码后（解压炸弹） | `StorageFileService:293-300` | 内存 DoS |
| 3 | 指标桶维度无界 + drain 丢更新 | `ApiMetricCollector:37-53` | 内存 DoS + 数据错 |
| 4 | 匿名上报无限流、单请求放大 ~600 SQL | `AppStatController:41-47` | 连接池打穿 |
| 5 | 前端无错误边界（白屏） | `astral-front/src/app/` | 可用性 |
| 6 | 签到「查后插」无唯一约束 | `QtDakaService:66` | 数据污染 |
| 7 | 统计 flush 无异常隔离，丢整分钟数据 | `StatAggregationJob:56-99` | 静默丢数据 |
| 8 | 补 5 条高风险路径的集成测试 | — | 风险放大器 |

### 第二梯队 · 两周内（性能热点 / 韧性）
| # | 问题 | 位置 | 收益 |
|---|---|---|---|
| 9 | 鉴权链 4~5 次 Redis → 合并为 1 次 | `AuthInterceptor` | 每请求延迟，全局收益 |
| 10 | 在线 Token 全库扫描 → SCAN 游标 + 分页校验 | `TokenServiceImpl:52,139` | 去掉共享 Redis 的系统性风险 |
| 11 | 取号每次回源查 config → 加缓存 | `GeneratorFactory:108,236` | 发号 TPS 数量级提升 |
| 12 | 每插入一次统计写 → 批量/定时 | `SequenceMetaObjectHandler:113` | 全站写放大消除 |
| 13 | 平台筛选下推 SQL（修复翻页错乱） | `QtSourceService:176-181` | 功能性 bug |
| 14 | 校验 HTTP 客户端补超时 + 流式读取 | `StorageFileService:63,280` | 消除请求悬空 |
| 15 | 前端请求取消（AbortController） | `lib/client.ts` + 3 个页面 | 竞态 + 体验 |
| 16 | 邮件频控改 `setIfAbsent`、额度失败回补 | `QtUserService:222` / `MailServiceImpl:131` | 防刷 + 客诉 |
| 17 | 前端埋点版本号取真实值 | `statTracker.ts:76` | 恢复版本维度统计 |
| 18 | 关闭/保护 actuator 与 springdoc | `application.yml:169` | 信息泄露 |

### 第三梯队 · 一个月内（可维护性 / 结构）
19. 抽 `ProviderAdapter` 消除存储商 if-else（新增存储商成本从 5+ 处降到 1 处）
20. Controller 业务逻辑下沉（`QtAdminController`、`TableSchemaController`）
21. 合并重复实现：`QtUserController`、UA 解析、rootMessage、上传直传
22. 抽插件鉴权 SPI，解除宿主对插件类的硬编码依赖
23. 宽 catch 系统性收窄（129 处）+ 日志补异常栈
24. 统计聚合 SQL 合并（`COUNT(*) FILTER`、复合索引、`DISTINCT ON`）
25. 前端 `any` 收敛（276 处）、列定义 memo、ECharts 按需、Tabs 懒挂载
26. 时区显式化（`StatIngestService`）+ 上传条数上限（`QtUploadLikeListDto`）

---

## 七、值得保留的设计（避免重构时误伤）

以下设计经审阅确认是**刻意的正确决策**，后续重构请勿推翻：

1. **前端全站单一 `<table>` 渲染层** —— `ui/table.tsx` 仅被 `DataTable.tsx` 引用，`ResizableTable`/`antd-compat`/`TableCardList` 均为适配层。改样式只需动 2 个文件，是全项目最优雅的解耦点。
2. **`ut` 白名单 + 未知归空串** —— 有效阻止客户端伪造平台值撑爆统计分组（唯一缺失的是 `appVersion` 未享受同等待遇，见 P0-4）。
3. **`sys_notice.channel` 的 `NoticeChannel` 单一出口** —— 解析/匹配/归一集中一处，旧值兼容与「未知值回落到 Android+iOS 而非全部平台」的降级方向正确（防止定向公告泄露）。
4. **存储层的路径穿越防护与 SQL 标识符白名单** —— `SchemaRegistry.resolveSchemaFile` + `SchemaCodeGenerator.requireValid` 双保险，`StorageUserController` 的 XFF 问题不影响此结论。
5. **鉴权失败的宽松度把握** —— 该 401 就 401，未做「失败即放行」的伪降级。
6. **`stat/report` 排除指标拦截器** —— 避免自举放大，方向正确（仅缺限流）。
7. **`com.astral.dao.entity.*` 由 schema 生成** —— 单一数据源，避免实体与表结构漂移；本次审阅已按约定排除。

---

*报告完毕。所有结论均已定位到具体文件与行号，高严重度项已逐一在源码中复现确认。*

---

# 附录：修复记录（2026-09-30）

**执行范围**：保守批优化 + 安全修复（允许改库结构）。原则是**不改变正常路径的功能**：只做零行为变化的加固与并发/资源正确性修复，加上明确的安全与 bug 修复。

**验证结果**
```
后端：mvn -o -DskipTests compile → BUILD SUCCESS（12/12 模块）
前端：npx tsc --noEmit → exit 0；npm run build → 通过（30/30 静态页）
```
改动共 25 个文件修改 + 6 个新增。**未提交**（按协作约定由你主动提交）。

## 一、已修复

### 统计写入链路（P0-4）
| 文件 | 修复 |
|---|---|
| `astral-common/.../util/ClientHeaders.java` | 新增 `normalizeVersion()`：版本号字符形态校验（`[0-9A-Za-z]+([._+-][0-9A-Za-z]+)*`），非版本号一律归空串。原来只截断 32 字符，任意内容都能制造新分组 |
| `astral-server/.../ApiRequestMetricInterceptor.java` | 改用 `normalizeVersion` |
| `astral-monitor/.../ApiMetricCollector.java` | ① **`drain()` 改为「先退休、再加锁快照、后摘除」**：退休标记让此后的 `add` 被拒并由 `record` 改投新桶，快照保证已计入数值不漏 —— 既不丢计数也不重复计数（原实现存在「已脱链对象上累加」的静默丢数窗口）。② **新增桶基数上限 20000**，触顶后只丢弃版本维度（归并到空串桶），按接口/平台统计仍准确，内存与落库行数不再无界。③ `record` 入桶加重试，确保不落在退役桶上 |
| `astral-monitor/.../job/StatAggregationJob.java` | **逐桶 try/catch 隔离**：原来任一桶抛异常会中断整个方法，而 `drained` 已从内存摘除且不重试 → 丢整分钟全部指标。现在单桶失败只记 error（含完整桶维度便于补录）并继续，新增 `failed` 计数 |

### 存储校验链路（P0-2 / P1）
| 文件 | 修复 |
|---|---|
| `astral-plugin/.../storage/service/StorageFileService.java` | ① **图片像素校验改为 `ImageReader` 只读元信息**，不再 `ImageIO.read` 整幅解码 —— 消除「小文件 + 巨型画布」解压炸弹（原实现先解码再判上限，上限形同虚设）。② `full` 校验从 `readNBytes(Integer.MAX_VALUE)` 改为硬上限 8 MB；magic 读取常量化为 4 KB。③ **HTTP 客户端补 `connectTimeout=5s` + 单请求超时 15s + followRedirects**（原来无任何超时，Provider 卡死即请求永久挂起） |
| `astral-plugin/.../storage/controller/StorageUserController.java` | `clientIp()` 从「无条件取 XFF 首段」改为 `ClientIp.resolve(...)`（可信代理白名单 + 从右取首个不可信跳）；同文件已注入却从未使用的 `trustedProxies` 现在真正生效 |

### 凭据与邮件（P0-1 / P2）
| 文件 | 修复 |
|---|---|
| `astral-system/.../controller/MailAccountController.java` | 分页与详情**不再回传 `password`**（SMTP 授权码）。`update` 走 **lambdaUpdate 白名单 + 逐字段判 null**（MP 3.5.17 的 `updateById` 即便字段置 null 也会进 SET 子句，AGENTS §5；「留空=不改」由白名单按需更新实现）；`password` 传空串显式拒绝（MAIL007），防止全实体更新语义把已存授权码冲掉；`toggle` 同样收敛为单字段 lambdaUpdate |
| `astral-system/.../mail/MailServiceImpl.java` | ① 模板校验与渲染**前置到占额度之前**（原来先扣额度再查模板，模板不存在就白扣一次配额）。② 发送失败**回补额度**（`refundQuota`，Lua 原子：仅计数 >0 时 DECR、TTL 缺失补当日过期——裸 DECR 在「DB 降级占用 + Redis 恢复回补」交错下会造出 -1 无 TTL 计数器，当日多放一封）。发信会话本就带 10s SMTP 连接/读超时（多账户串行尝试时单次发送仍可达分钟级，是频控令牌化释放的动机，见并发正确性一节） |
| `astral-front/.../mail/account/page.tsx` | 配套：编辑不再回填密码、`required` 仅在新建时生效、留空时**整个摘掉该字段**（否则会以空串覆盖已保存的授权码） |

### 并发正确性
| 文件 | 修复 |
|---|---|
| `astral-plugin/.../qt/service/QtDakaService.java` | 捕获 `DuplicateKeyException` → 转成与预检一致的幂等提示。原来「先查后插」在双击/重放时两请求都能通过判重，导致积分与连续天数翻倍 |
| `astral-plugin/.../qt/service/QtUserService.java` | 邮件频控从 `hasKey` 判断后再 `set` 改为 **`setIfAbsent` 原子占位**（占位值为一次性 UUID 令牌）；发送失败按令牌比对释放窗口（Lua compare-and-delete）——多账户 SMTP 超时累计可超 60s 频控 TTL，届时 key 可能已被下一次发送重新占位，无条件 `delete` 会误删别人的窗口 |

### 参数边界（P2）
| 文件 | 修复 |
|---|---|
| `astral-common/.../util/PageQuery.java`（新增） | 统一分页/条数归一：页码钳制 `[1, 100000]`、单页上限 200。**对正常调用方是恒等映射**，只收敛越界值 |
| `astral-auth/.../TokenServiceImpl.java` | `pageFromSaToken` 钳制后再算偏移 —— 原来 `pageNum=0` 会得到负 `fromIndex` 并让 `subList` 抛异常（500）；且 `pageNum ≥ 10,737,420`（size=200）时 `(pageNum-1)*size` **int 溢出为负**同样 500，现已由 PageQuery 页码上界中心化消除 |
| `astral-monitor/.../StatReportController.java` | `limit` 钳制到 100；`error/page` 分页钳制 |
| `astral-log/.../api/LogController.java`、`controller/LoginLogController.java`、`controller/OperateLogController.java` | 四处分页参数钳制 |

### 数据 / DDL
| 文件 | 修复 |
|---|---|
| `db/migration/V20261001008__daka_unique_and_stat_error_composite_index.sql`（新增） | ① 清理 `qt_user_daka` 同日重复行 + `uk_qt_daka_uid_data` 唯一索引（幂等不变式落到数据库）。② `idx_stat_error_fp_time (fingerprint, occur_time)` 复合索引 —— 错误汇总的两个相关子查询原先只有单列索引，仍需回表按时间过滤；同时**退役**被左前缀完全覆盖的旧单列 `idx_stat_error_fingerprint`（纯写放大）。③ ①整段包 `to_regclass('qt_user_daka')` 守卫：全新空库下 Flyway 先于 `QtSchemaInitializer` 执行、表不存在时跳过，唯一索引由 `qt-schema.sql` 兜底（否则空库首启迁移失败、应用起不来） |
| `sql/qt-schema.sql`、`schema/qt_user_daka.json`、`schema/stat_error_log.json` | 同步索引定义（新库建表 / 表结构管理页口径一致）；`stat_error_log.json` 相应移除 `idx_stat_error_fingerprint` 条目 |

### 前端（错误处理 / 竞态 / 渲染性能）
| 文件 | 修复 |
|---|---|
| `app/error.tsx`、`app/global-error.tsx`、`app/not-found.tsx`、`app/dashboard/error.tsx`（新增） | 补全缺失的错误边界。原来一处渲染异常即整站白屏、无重试路径 |
| `dashboard/statistics/page.tsx`、`plugin/storage/page.tsx`、`feedback/page.tsx` | 筛选**竞态守卫**（`alive` 标志），修复慢响应覆盖新响应 |
| `dashboard/statistics/page.tsx` | 4 处静默 `catch {}` 改为 `toast.error`（注释声称「由 client.ts 统一提示」，实际 client.ts 只 reject 不提示，错误完全不可见） |
| `plugin/storage/page.tsx`、`antd-compat/Overlay.tsx` | `validateFields()` 移入 `try`（校验失败原本抛未处理 rejection，用户点「确定」毫无反应）；`handleOk` 补 catch 兜底 |
| `dashboard/statistics/page.tsx`、`sequence/page.tsx` | 列定义 `useMemo` 固定引用 —— 原来每次渲染新建数组，击穿 `ResizableTable` 三级 useMemo，键入时整表重渲 |
| `dashboard/sequence/page.tsx`、`log/page.tsx` | 补 catch；`resolveTypeRef` 的 `setTimeout` 补卸载清理 |

## 二、复核过程（关键结论均已回源确认）

- `AuthInterceptor` 的 `/api/v1/app/**` 宽放行、`ApiMetricCollector` 的维度无界与 drain 语义、`StorageFileService` 的解码顺序、`MailAccountController` 的实体直返 —— 均已在源码中逐行复现确认后才动手。
- 前端子代理对 `antd-compat/Overlay.tsx`（在「不要动」清单内）做了 3 行 catch 兜底，已人工复核并补上 `console.error` 保留调试信息；其余改动已逐文件 diff 复核。
- `sequence/page.tsx` 的 `useMemo(..., [])` 捕获 `canEditConfig`：已核实 `dashboard/layout.tsx:637` 在 `loading` 期间不渲染 children，页面挂载时权限已就绪且生命周期内不变，故依赖 `[]` 安全。

## 三、明确**未修复**（供你决策）

1. **`AuthInterceptor` 鉴权白名单收紧 + 每请求 4~5 次 Redis 合并** —— 属「契约与逻辑修正」：收紧 `/api/v1/app/**` 会改变鉴权行为、可能直接拒绝现有调用方；合并 Redis 读取需要重构会话读取路径，回归面大。**建议单独排期**。
2. **`StorageFileService` 回执 `DuplicateKeyException` 幂等** —— 该方法是 `@Transactional`，在事务内捕获后 PostgreSQL 已中止当前事务，无法同事务重查（会报 `current transaction is aborted`）。正确做法是在事务边界之外处理（控制器捕获后重查，或改用 `INSERT ... ON CONFLICT DO NOTHING`），涉及改动事务划分，**未做**。
3. **平台筛选下推（`QtSourceService`）、`getChanges` 的 `maxSeq` 契约、前端埋点版本号** —— 会改变可观察结果（翻页结果、同步契约、统计口径），不在本次选定范围内。
4. **`GeneratorFactory` 每次取号回源查 config、`SequenceMetaObjectHandler` 每次插入追加统计写** —— 加缓存/改批量会引入配置生效延迟与统计时序变化，**未做**。
5. **纯重构类 P2**（`ProviderAdapter` 抽取、Controller 逻辑下沉、`rootMessage` 去重、宽 catch 系统性收窄、前端 `any` 收敛等）—— 无功能收益，**未做**。

## 四、⚠️ 上线前必须注意

**迁移 `V20261001008` 会删除 `qt_user_daka` 中同 `(uid, data)` 的重复行**（每组保留 `id` 最小的一条，即最早的合法签到）。这**会同步降低**被重复计分用户的积分余额（`getAllIntegral` 按行求和）——这正是本次要修正的脏数据，且不会删除任何用户的首次签到，但属于**可观察的业务数据变化**，请确认后再执行。

迁移验证情况（2026-10-01 更新）：本地开发库（127.0.0.1:5433/astral）已应用 V01–V08，V08 清重与索引语句幂等重跑通过（重跑 0 行、`IF NOT EXISTS` 空操作）；生产库（15 条迁移全部待应用）已人工执行过同款清重 DO 块（删 927 行、备份留在 `astral.qt_user_daka_dup_bak_20261001`，核对无误后可删），Flyway 到位后正式执行等价于幂等重跑。另：生产库为角色级 `jit=off`，可避免 PL/pgSQL 清重块被 JIT 拖慢。

## 五、追加修复（2026-10-01 全量复核批次）

对全部改动（后端 32 文件 + 前端 + 迁移）二次评审后的增量修复：

| 文件 | 修复 |
|---|---|
| `MailAccountController.java` | `update`/`toggle` 从 `updateById` 全实体更新改为 lambdaUpdate 白名单（见上）；新增 MAIL007 拒空密码 |
| `MailServiceImpl.java` | `refundQuota` Lua 原子化（见上） |
| `PageQuery.java` | 页码补上界 100000，中心化消除 TokenServiceImpl int 溢出 500 与亿级 OFFSET 空转 |
| `ApiMetricCollector.java`（astral-monitor） | `drain()` 的 `remove` 失败分支：快照直接计入本轮产出。原 `putIfAbsent` 在并发 `record` 已重建新桶时是 no-op，快照会被**静默丢弃**（原注释「理论上不可达/不会丢计数」不成立——record 的失败重投递路径同样会移除旧桶） |
| `QtUserService.java` | 频控释放改令牌比对（见上） |
| `AppUserRoleService.java` | 注释澄清：启动收回范围刻意只限 `user:` 前缀 DATA；`admin:` 域 DATA 误授存量由 V20261001009 一次性清理 |
| `V20261001008` | 补 `to_regclass` 空库守卫（见上）；本地库已应用过初版内容，需删 history 中 20261001008 行幂等重跑 |
| `db/migration/README.md` | 迁移表补 V07 初版从未应用（id 9123 会撞 menu_type）、V08 守卫与本地重跑说明 |

