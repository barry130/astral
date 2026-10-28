# 通知能力收归宿主 + 反馈插件解耦（V2 分析）

> **状态：已实施完成（2026-10-27）。** 本文件原为「只分析、未改代码」的方案稿，现保留全文作为设计依据；
> 实际落地见下面的「实施结果」一节与文末 §9 验收清单。
> V1 的三处前提已被你的反馈推翻，本版重写：①通知类型收敛为「公告 / 通知」②反馈只触发事件、不含任何通知逻辑 ③表与字典改动全部走**新增** Flyway，历史迁移一个字不改。
> 定位也变了：这不是「修一个耦合 bug」，而是**一个新需求**——把通知做成宿主的通用能力，反馈只是它的第一个订阅方。

## 实施结果（2026-10-27）

- **代码**：通知服务/Controller/拦截器/异常映射迁入 `astral-system` 的 `com.astral.system.notice`
  （`NoticeService(Impl)`、`NoticeTargets`、`NoticeDisplay`、`AppNoticeAuthInterceptor`、
  `AdminNoticeController`/`AppNoticeController`、`NoticeWebConfig`、`NoticeAppExceptionHandler`）；
  `astral-common` 新增 `AppRestResp`（该类后来在「统一响应体」重构中删除，App 端改用宿主 `Result`），
  `NoticeConstants` 增 `TYPE_NOTICE`/`TYPE_ANNOUNCE`；
  插件侧删除 `AdminMessageController`/`AppMessageController`/`NoticeChannel`/`FeedbackNoticeService`，
  `FeedbackService` 的 7 个业务触发点直接 `notifyPublisher.publishAsync`（payload 为
  `{feedbackId, userId, feedbackTitle, statusName}`，不带 noticeType）。
- **迁移**：实号为 `V20261027001__sys_notice_to_host.sql`（`CREATE TABLE IF NOT EXISTS` + 6 索引 + 列 COMMENT
  放 `DO` 块）与 `V20261027002__notice_type_announce_notice.sql`（`notice` 新增、`feedback`/`request` 置 `status=0`），
  历史脚本一字未改。
- **前端**：新增 `src/api/notice.ts`，`src/api/feedback.ts` 只留反馈域；通知管理与铃铛切到 new 模块；
  删除孤儿路由 `src/app/dashboard/message/page.tsx`（`/dashboard/message` → `/dashboard/system/mail?tab=notice`）。
- **验证**：`mvn -B clean install -DskipTests` 全 12 模块 BUILD SUCCESS；`mvn -pl astral-system,astral-plugin install`
  全绿（astral-system 31、astral-plugin 193，`NoticeDisclaimerFieldsTest`/`NoticePlatformTest` 为新增）；
  `npx tsc --noEmit` + `npm run build` 通过，`/dashboard/message` 已不在路由表。
- **与方案稿的差异**：① 迁移日期 20261027（方案里写的是 20261025）；② `InappServiceImpl` **没有**注入
  `NoticeService`——它按 `@RequiredArgsConstructor` 注入 2 个依赖，构造签名被
  `FeedbackReplyPlatformTest` 直接 `new`，故通知展示位判断退回「60s TTL 缓存 + `NoticeDisplay.hasDisplay`」读库；
  ③ 反馈触发点按代码走查是 7 处（方案写 6 处）。
- **已知遗留**：`V20261001007`/`V20261002006` 仍是对 `sys_notice` 的裸 `ALTER`，在全新空库上先于
  `V20261027001` 执行会因表不存在而失败（与既有 `V20261001008` 同类，按「只增不改」不在本次处理）；

---

## 1. 你这轮三条修正，对新方案的影响

| 你的原话 | 我的理解 | 对方案的改动 |
|---|---|---|
| 「反馈回复如果需要发送通知应该是触发了通知规则，这个应该不绑定到反馈服务里面，应该是反馈服务触发了通知」 | 插件里不该存在「通知服务」这一层。反馈只在业务节点**触发一个事件**，发不发、发给谁、发什么、走哪个渠道，全部由**订阅规则**决定 | V1 里我打算新建 `FeedbackNotifyTrigger` 承载 5 个 `notify*` 方法 —— **这个类删掉，不要了**。改为 `FeedbackService` 在 6 个业务点直接 `notifyPublisher.publishAsync(...)`，插件内零通知代码 |
| 「通知管理的类型应该为公告/通知两个了，而反馈和需求应该是归属于反馈插件了」 | `notice_type` 的真实语义是「广播 / 点对点」，不是业务分类。反馈/需求是**点对点通知的业务来源**，归反馈插件自己的 `feedback_type` | 字典 `notice_type` 三项 → 两项（公告 / 通知）；`NoticeConstants.TYPE_FEEDBACK/TYPE_REQUEST` 删除；反馈/需求的区分回到 `sys_feedback.type` + `feedback_type` 字典（**已存在**，无需新建） |
| 「如果有表拆分 flyway 管理就行。之前的 flyway 已经执行完成了，不需要继续更新任何内容和补充」 | ①`sys_notice` 的建表权从插件挪到 Flyway ②**不许改历史迁移**（V1 里的 A/B/C 三选一全部作废）③不 UPDATE 存量业务数据 | 只新增两条迁移：一条「收权建表」（幂等 no-op），一条「字典收敛」；**存量 308 行通知一条都不动** |

---

## 2. 一条新证据，直接支持你的判断

我连线上库（`203.195.155.193:5432/astral`，只读 SELECT）核了一下，`notice_type` 的真实分布：

```
notice_type   count        行数         user_id IS NULL
feedback        202         308 条总计     11 条
request          95      ← 96% 是点对点    ↑ 恰好等于 announce 的 11 条
announce         11
```

**`user_id IS NULL` 的 11 条，与 `notice_type='announce'` 的 11 条完全重合。** 也就是说：所谓「公告 / 反馈 / 需求」三种类型，真实结构其实是：

- **公告**（广播，`user_id IS NULL`）= 原来的 `announce`，11 条
- **通知**（点对点，`user_id` 非空）= 原来的 `feedback` + `request`，297 条

「反馈 / 需求」根本不是通知的类型，而是这 297 条点对点通知**是谁触发的**——那当然是反馈插件的事。你的判断和数据完全对得上。

另外两个已存在的事实（意味着这条需求不用新建机制）：

- **规则驱动已经建好了**：`V20261002007__feedback_rule_notify.sql` 已经种下 5 个 INAPP 模板 + 5 条启用规则（线上 `sys_notify_rule` 里 `event_code like 'feedback%'` 实测 5 条）。该迁移文件开头的注释原文就是：「feedback 插件 5 个硬编码触发点改走 `NotifyPublisher.publishAsync`，文案/收件人/平台全部由订阅规则 + INAPP 模板配置」。
- **`feedback_type` 字典已经在**：`V20260914001__init.sql:1449-1455` 种了 `issue`（问题）/ `request`（需求）；`Feedback.TYPE_ISSUE/TYPE_REQUEST` 与之对应。

所以这条需求真正的剩余工作只有三件：**① 把通知的 HTTP 面从插件搬到宿主；② 把插件里那层「通知服务」拆掉，只留事件触发；③ 把类型口径从 3 项收敛到 2 项。**

---

## 3. 职责矩阵（改造后的终态）

| 关注点 | 归属 | 说明 |
|---|---|---|
| `sys_notice` 表结构 | **宿主（Flyway）** | 新增迁移接管 `CREATE TABLE`，插件 SQL 不再碰 |
| 通知的查询/过滤/保留期/版本区间/受众 | **宿主** | `astral-system` 的 `NoticeService` |
| App 消息中心 4 个端点、管理端 5 个端点 | **宿主** | 路径原样保留（`/api/v1/app/message/**`、`/api/v1/admin/message/**`） |
| 通知类型：公告 / 通知 | **宿主** | 字典两项 + `NoticeConstants` |
| 事件订阅规则、模板、渠道（EMAIL/SMS/INAPP） | **宿主** | 已有 `NotifyEventRegistry` / `NotifyPublisher` / `sys_notify_rule` |
| 「反馈状态变更了」这个**事实**以及触发时机 | **反馈插件** | `FeedbackService` 里 6 个业务节点 `publishAsync` |
| 反馈/需求 的业务分类（问题 / 需求） | **反馈插件** | `sys_feedback.type` + `feedback_type` 字典 |
| 通知的文案、收件人、平台、开关 | **管理端配置** | 由 `sys_notify_rule` + `sys_mail_template` 决定，代码不写死 |

**依赖方向**：`astral-plugin → astral-system`（单向，已存在）。宿主**永远不知道**反馈的存在；它只知道「有一类事件叫 `feedbackStatusChanged`，谁订阅了谁收」。5 个事件码是这条契约线，属于宿主注册表（`NotifyEventRegistry:42-50`），保持不动。

---

## 4. 改造一：通知类型收敛为「公告 / 通知」

### 4.1 目标口径

| 类型 | 值 | 语义 | 判定 | 客户端标签 |
|---|---|---|---|---|
| 公告 | `announce`（**沿用存值与现标签，零改动**） | 广播：开屏 / 通告栏 / 消息中心 | `user_id IS NULL` | 不显示标签（现状即如此） |
| 通知 | `notice`（**新值**） | 点对点站内信 | `user_id` 非空 | 显示「通知」/业务标签 |

历史值 `feedback` / `request` 保留为**历史别名**，代码读侧归并到「通知」组，**不做数据迁移**。

### 4.2 为什么「公告」沿用 `announce` 这个旧值（关键约束）

已发布客户端把三个值**硬编码在二进制里**，而 uni-app x 的包一旦发出去就改不动：

- `F:\qtMusic\qt-uniappx\services\notice.ts:252-256`
  ```ts
  export function noticeTypeLabel(noticeType?: string): string {
    if (noticeType == "feedback") return "反馈";
    if (noticeType == "request") return "需求";
    return "公告";          // ← 未知值一律当「公告」
  }
  ```
- `F:\qtMusic\qt-uniappx\pages\notice\list\index.uvue:226-230`：`if (t == "announce") return "";` —— 只有 `announce` 隐藏标签，其余都渲染灰标签。
- `F:\qtMusic\qt-uniappx\pages\notice\list\index.uvue:173-179`：`ntypeColor()` 也是按这三个值给蓝/橙/灰。

如果按「干净做法」把存量 297 条点对点行统一改成 `notice`、把 11 条广播也换成一个新值，老 App 里会变成：

- 297 条个人通知：从「反馈」/「需求」（蓝 / 橙）退化成 **灰「公告」** —— 明显倒退
- 11 条广播：从「不显示标签」变成 **灰「公告」** —— 轻微

**所以「公告」既沿用 `announce` 存值、也沿用「公告」这个现成 label，字典这一项一行都不用改**；「通知」用新值 `notice`（只影响新产生的行）。存量 297 条历史别名行**一条不动** —— 老 App 照旧渲染，正是你说的「不需要继续更新任何内容」。

> 顺带说明：你上一条说「公告不是公共」，其实是**方案里最省事的一种结果**——`announce` 的现成 label 就是「公告」，所以连改 label 这种展示层改动都不需要了，通知类型的收敛到最后只剩「加一项、停用两项」。

> 代价：老 App 里新产生的个人通知会显示「公告」灰标签（因为 `notice` 未知 → fallback）。这个只有在下一个 App 版本发布后才会修好，且只影响标签颜色，不影响功能与跳转（客户端不用 `noticeType` 做任何路由）。

### 4.3 代码侧收敛

- `astral-common/src/main/java/com/astral/common/constant/NoticeConstants.java`
  - `TYPE_ANNOUNCE = "announce"` **保持原名原值不动**（字面意思就是「公告」，与收敛后的口径完全一致，改名纯属噪声）
  - 新增 `TYPE_NOTICE = "notice"`
  - 删除 `TYPE_FEEDBACK` / `TYPE_REQUEST`（已核：全仓零引用，是死常量），改为 `@Deprecated TYPE_LEGACY_FEEDBACK = "feedback"` / `TYPE_LEGACY_REQUEST = "request"`（仅读侧归并用）
- 保留期规则天然对齐，`listActiveNotices` 逻辑不用改：现在就是「`notice_type='announce'` 不受保留期裁剪，其余只留最近 7 天」。改成「公告不受限、通知受限」后语义更顺，**代码里一行都不用改**。
- 管理端筛选兼容：`page(noticeType='notice')` → `in('notice','feedback','request')`；`= 'announce'` → `eq`。
- 新建时：选了「公告」写 `announce`，选了「通知」写 `notice`（`create()` 里目前 `noticeType==null` 时强制 `TYPE_ANNOUNCE`，这条逻辑保持不动）。

### 4.4 反馈通知的类型由「事件定义」决定，不再由插件决定

现在插件把 `toNoticeType(f.getType())`（issue→`feedback`、request→`request`）塞进 payload 的 `noticeType` 字段，由 `InappServiceImpl:50` 优先取 payload。改造后：

- `FeedbackService` 的 payload **只带业务字段**（`feedbackId` / `userId` / `feedbackTitle` / `statusName`），不再带 `noticeType` —— 插件不再管类型标签
- `NotifyEventRegistry` 里 5 个反馈事件的 `NotifyEventDef.noticeType` 从 `null` 改为 `"notice"`（`NotifyEventDef.java:18-21` 的 javadoc 同步把 `announce/feedback/request` 改成 `announce(公告)/notice(通知)`）
- `InappServiceImpl.sendTemplateRow` 的三级回退（payload → 事件定义 → `announce`）保持原样，只是事件定义这次会给 `notice`
- `FeedbackService.toNoticeType()` 整个方法删除

### 4.5 字典收敛（新增 Flyway）

`sys_dict_type(id=85, dict_code='notice_type')`，`sys_dict_data` 现状：130 `announce`/公告、131 `feedback`/反馈、132 `request`/需求。

因为「公告」这一项的值和 label **本来就对**，这次收敛只剩两步：加一项、停用两项。**没有任何 UPDATE 触及公告行，更没有 `UPDATE sys_notice`。**

```sql
-- 1) 新增 通知（sort 2），主键取 MAX+1，幂等
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT (SELECT COALESCE(MAX(id),0)+1 FROM sys_dict_data), t.id, '通知', 'notice', 2, 1,
       '点对点站内信（含反馈回复、状态变更等），受保留期限制'
FROM sys_dict_type t WHERE t.dict_code='notice_type'
  AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id=t.id AND d.dict_value='notice');

-- 2) 131/132 停用（不删除，可逆）；历史行仍按原 label 显示，业务分类回归反馈插件
UPDATE sys_dict_data SET status = 0, update_time = CURRENT_TIMESTAMP
WHERE dict_type_id = (SELECT id FROM sys_dict_type WHERE dict_code='notice_type')
  AND dict_value IN ('feedback','request');
```

`announce`（id=130）这一行**完全不动**：`dict_value='announce'`、`dict_label='公告'`、`status=1`。

> 为什么「停用」而不是删除：删掉后管理端若打开一条历史行，下拉会显示裸 `feedback`；停用只是不出现在选项里，`sys_dict_data` 仍有据可查、可回滚。同理**不做** `UPDATE sys_notice SET notice_type=...`。

### 4.6 「反馈 / 需求」的细分只留在反馈侧（已定，选 A）

**结论（你的口径）：**

| 位置 | 分类口径 | 数据来源 |
|---|---|---|
| 消息中心 / 通知管理（App + 管理端） | **只有 公告 / 通知**，不出现「反馈」「需求」 | `sys_notice.notice_type`（`announce` / `notice`，历史 `feedback`/`request` 一律归并显示为「通知」） |
| 反馈需求页（管理端「反馈管理」+ App 反馈模块） | **正常区分 反馈 / 需求** | `sys_feedback.type`（`issue` / `request`）+ `feedback_type` 字典（**早已存在，零改动**） |

所以：

- **客户端不再需要任何「反馈 vs 需求」标签逻辑**，B 方案（宿主开 `NoticeTagProvider` SPI）**否决**，不引入跨模块 SPI。
- 管理端通知列表里，历史 297 行的类型列统一渲染为「通知」（值仍是 `feedback`/`request`，只是展示层归并）。老行被编辑保存时 `notice_type` 会被写成 `notice` —— 这是一次自然的、用户主动触发的归一，不做批量 UPDATE。
- 已发布的 App 包里 `noticeTypeLabel()` 仍会把历史行显示成「反馈」/「需求」，这是客户端二进制里写死的，改不了；下个 App 版本把该方法改成「`announce` → 公告，其余 → 通知」即可收敛，属于**发版任务**而非本次后端改造。
- 反过来，反馈侧完全不受影响：`sys_feedback.type` 与 `feedback_type` 字典是给「反馈管理」页的类型筛选、列表标签、以及 App 反馈提交表单用的，和通知没有关系。

> 一句话：**通知是通道，反馈是业务**。通道只关心「广播还是点对点」，业务分类留在业务自己的表里。

---

## 5. 改造二：反馈只触发事件，插件里不留通知逻辑

### 5.1 现状：已经有 80%，差最后一步

`FeedbackService` 现在这样调（6 处）：

```java
noticeService.notifyStatusChange(f.getId(), f.getUserId(), f.getTitle(), toNoticeType(f.getType()), statusName);
```

而被调用的 `FeedbackNoticeService.notifyStatusChange` 体只有三行：

```java
notifyPublisher.publishAsync(NotifyEventRegistry.FEEDBACK_STATUS_CHANGED,
        Map.of("feedbackId", ..., "userId", ..., "feedbackTitle", ..., "statusName", ..., "noticeType", ...));
```

也就是说：真正的投递早就是规则驱动的，**中间那层「通知服务」是个纯转发壳**，却让整个通知能力挂在了反馈插件上。拆掉它即可。

### 5.2 改造后

```java
// FeedbackService 内，业务节点直接触发（示例：状态变更）
notifyPublisher.publishAsync(NotifyEventRegistry.FEEDBACK_STATUS_CHANGED,
        Map.of("feedbackId", String.valueOf(f.getId()),
               "userId", String.valueOf(f.getUserId()),
               "feedbackTitle", nullToEmpty(f.getTitle()),
               "statusName", statusName));
```

6 个触发点（行号按现状）：`:100` 新提交、`:158` 用户回复、`:246`/`:252` 状态变更、`:249`/`:266` 公开发布、`:287` 管理员回复。

要点：

- 注入 `NotifyPublisher`（宿主 `astral-system` 的 Bean，`publishAsync` 自带 try/catch，注释原文「反馈插件等『通知不能挡主流程』的调用方用这个入口」）→ **通知发失败绝不影响反馈主流程**
- `FeedbackService` 只加一个私有 `nullToEmpty()`，**不新建任何通知类**
- 删除 `FeedbackNoticeService` 的 5 个 `notify*` + `toNoticeType` + `nullToEmpty`
- 插件不再有任何类名里带 `Notice`/`Notify` 的东西（除事件码常量引用）

### 5.3 「规则没配就不发」是预期行为

现在规则是种子进去的（5 条 enabled）。如果管理员把某条规则停用，反馈照样提交成功，只是不发通知 —— 这正是「通知由规则决定」的语义，也是这次改造想要的效果。

---

## 6. 改造三：表与字典全部走新增 Flyway

按你的要求：**历史迁移一个字不改**（V1 里的 out-of-order / 加守卫 / flyway repair 三选一全部作废），只新增两条。

### 6.1 `V20261025001__sys_notice_to_host.sql` —— 收权建表

把 `astral-plugin/src/main/resources/sql/feedback-schema.sql:44-79` 的第 3.3 段整体搬到宿主迁移（DDL 逐字照抄）：

```sql
-- sys_notice 由 feedback 插件的 @PostConstruct 初始化器建表，导致「禁用反馈插件 = 通知能力消失」。
-- 现收归宿主 Flyway 管理。存量库上本条全部为 no-op（表与索引已存在）。
CREATE TABLE IF NOT EXISTS sys_notice ( ... 25 列，见附录 A ... );
CREATE INDEX IF NOT EXISTS idx_notice_channel       ON sys_notice(channel);
CREATE INDEX IF NOT EXISTS idx_notice_type          ON sys_notice(notice_type);
CREATE INDEX IF NOT EXISTS idx_notice_user          ON sys_notice(user_id);
CREATE INDEX IF NOT EXISTS idx_notice_show          ON sys_notice(is_show, is_top);
CREATE INDEX IF NOT EXISTS idx_notice_create_time   ON sys_notice(create_time);
CREATE INDEX IF NOT EXISTS idx_sys_notice_user_read ON sys_notice(user_id, read_time);
-- COMMENT ON COLUMN 段同样搬过来（用 DO $$ IF to_regclass('sys_notice') IS NOT NULL 守卫）
```

同时从 `feedback-schema.sql` 删掉 3.3 段与其 `COMMENT` 行，`FeedbackSchemaInitializer` 的日志文案改为「（sys_feedback/sys_feedback_reply）」。

> **已知遗留（本需求不处理）**：`V20261001007` / `V20261002006` 里的裸 `ALTER TABLE sys_notice` 没有 `to_regclass` 守卫，所以「全新空库从零跑迁移链」仍会失败。按你的要求不动历史迁移，这里只登记为遗留项；真要支持全新库一键起，将来单独提一条迁移或调整 baseline。

### 6.2 `V20261025002__notice_type_announce_notice.sql` —— 类型收敛

见 4.5 的两段 SQL（加 `notice`、停用 `feedback`/`request`）。**纯字典，不碰 `announce` 那一行，不动 `sys_notice` 任何业务数据。**

---

## 7. 代码搬迁清单

### 7.1 后端新建（`astral-system`，包 `com.astral.system.notice`）

| 新文件 | 来源 | 要点 |
|---|---|---|
| `notice/NoticeService.java` | `FeedbackNoticeService` 通知面 | interface，风格对齐既有 `InappService` |
| `notice/NoticeServiceImpl.java` | 同上 | 保留 Caffeine（`maximumSize 4` / TTL 60s / key `activeNotices`）；`evictNoticeCache` 在 create/update/delete 后调用（现 `:246`/`:372`/`:378`） |
| `notice/NoticeTargets.java` | `feedback/common/NoticeChannel.java`（283 行） | **必须改名**：`astral-system/.../notify/NotifyChannel.java` 已占用 `NotifyChannel`（EMAIL/SMS/INAPP），同包同名编译冲突。只有 `ClientHeaders` 一个依赖，引用方仅 2 个 |
| `notice/NoticeDisplay.java` | `FeedbackNoticeService:39-46` 的 `DISPLAY_*` + `hasDisplay` | `InappServiceImpl:33` 的私有同名常量改为引用本类，消掉「同值两份」 |
| `notice/AppNoticeAuthInterceptor.java` | `feedback/config/FeedbackAuthInterceptor.java` | `StpUtil.getLoginIdByToken(request.getHeader("satoken"))` → `request.setAttribute("userId")`；失败写 `AppRestResp.error(401,"登录状态已失效")` |
| `notice/dto/ReadAckDto.java` | `feedback/dto/ReadAckDto.java` | 3 行，直接搬 |
| `notice/controller/AdminNoticeController.java` | `feedback/controller/AdminMessageController.java` | `@RequestMapping("/api/v1/admin/message")` **不变**；`@Tag("通知管理-管理端")` |
| `notice/controller/AppNoticeController.java` | `feedback/controller/AppMessageController.java` | `@RequestMapping("/api/v1/app/message")` **不变**；返回 `AppRestResp` |
| `config/NoticeWebConfig.java` | 新建 | `@Order(HIGHEST_PRECEDENCE+40)`（`PluginWebConfig` 是 HIGHEST、`FeedbackWebConfig` 是 +30、宿主 `WebMvcConfig` 无 order）；挂 `/api/v1/app/message/**` + `excludePathPatterns("/api/v1/app/message/active")`。**system 模块第一个 `WebMvcConfigurer`** |
| `config/NoticeAppExceptionHandler.java` | `FeedbackGlobalExceptionHandler` 的 App 部分 | `@RestControllerAdvice(assignableTypes={AppNoticeController.class})`、`@Order(HIGHEST_PRECEDENCE+20)`、返回 `AppRestResp`。**system 第一个 `@RestControllerAdvice`** |

### 7.2 `astral-common` 新建

`result/AppRestResp.java` —— 与 `FeedbackRestResp` / `QtRestResp` 逐字同构（`code`/`msg`/`data` + `success()` / `success(T)` / `error(String)`=300 / `error(Integer,String)`）。
⚠️ `astral-common/pom.xml` **没有 lombok**，getter 要手写。

### 7.3 插件侧（`astral-plugin/src/main/java/com/astral/feedback/`）

**删除 4 个**
- `controller/AdminMessageController.java`
- `controller/AppMessageController.java`
- `common/NoticeChannel.java`
- `service/FeedbackNoticeService.java`

**修改 5 个**
| 文件 | 改动 |
|---|---|
| `FeedbackPlugin.java` | `getApiPrefixes()` 删 `"/api/v1/app/message"`、`"/api/v1/admin/message"` 两条（**这是拆分生效的唯一开关**）；`getDescription()` 去掉「与统一通知」；`onDisable()` 日志同步 |
| `service/FeedbackService.java` | 6 处改为直接 `publishAsync`；删 `toNoticeType`；注入 `NotifyPublisher` |
| `config/FeedbackWebConfig.java` | 只挂 `/api/v1/app/feedback/**`，删 `excludePathPatterns` |
| `config/FeedbackAuthInterceptor.java` | `PUBLIC_PATHS` 清空（反馈 App 端无公开接口） |
| `config/FeedbackAdminExceptionHandler.java` / `FeedbackGlobalExceptionHandler.java` | `assignableTypes` 移除已删的 2 个 Controller |

**新建 0 个**（V1 的 `FeedbackNotifyTrigger` 取消）

### 7.4 其他后端改动

| 文件 | 改动 |
|---|---|
| `astral-common/.../constant/NoticeConstants.java` | 新增 `TYPE_NOTICE="notice"`；`TYPE_ANNOUNCE` **原名原值保留**；删死常量 `TYPE_FEEDBACK`/`TYPE_REQUEST`，留 `TYPE_LEGACY_*`（仅读侧归并） |
| `astral-system/.../notify/NotifyEventRegistry.java` | 5 个反馈事件的 `NotifyEventDef.noticeType`：`null` → `"notice"`；`:65-67` 描述里「payload 可带 meta 字段 noticeType」删除 |
| `astral-system/.../notify/NotifyEventDef.java` | javadoc 的 `announce/feedback/request` → `announce(公告)/notice(通知)` |
| `astral-system/.../notify/InappServiceImpl.java` | `DISPLAY_MESSAGE_CENTER` 引用 `NoticeDisplay`；`TYPE_ANNOUNCE` 用法不变 |
| `astral-plugin/.../qt/QtPlugin.java:15` | 注释「由 feedback 插件 sys_notice 提供」→「由宿主通知模块提供」 |
| `astral-sequence/.../config/AsyncConfig.java` | javadoc 里 `FeedbackNoticeService` 的 5 个方法 → 改为 `FeedbackService` 的触发点 |

### 7.5 测试

| 现 | 迁到 |
|---|---|
| `astral-plugin/src/test/java/com/astral/feedback/service/FeedbackNoticeDisclaimerFieldsTest.java`（`:43`/`:138` `new FeedbackNoticeService()`） | `astral-system/src/test/java/com/astral/system/notice/NoticeDisclaimerFieldsTest.java` |
| `astral-plugin/src/test/java/com/astral/feedback/service/FeedbackNoticePlatformTest.java`（`:24`/`:46`） | 同上目录 `NoticePlatformTest.java` |

新增 1 条关键测试：**反馈插件不注册时，通知的 Service / Controller 仍然可用**（用 `@ConditionalOnProperty` 关掉 feedback 上下文跑一遍）。

### 7.6 前端（`astral-front`）

| 文件 | 改动 |
|---|---|
| 新建 `src/api/notice.ts` | 从 `src/api/feedback.ts` 切出 `SysNotice`(:50-86)、`noticeAdminApi`(= `messageAdminApi`:138-154)、`loadNoticeDicts`（只拉 `notice_channel`/`notice_type`）、`noticeTypeLabel`(:232)、`noticeChannelLabel`(:239)、`noticeTypeOptions`(:264)、`noticeChannelOptions`(:273)、`NOTICE_CHANNEL_ALL`(:270)、`NOTICE_TYPE_TEXT`/`NOTICE_CHANNEL_TEXT`(:335-336)。独立缓存 `noticeDictCache` |
| `src/api/feedback.ts` | `FEEDBACK_DICT_CODES`(:157-165) 与 `loadFeedbackDicts`(:178) **原样保留**（`dashboard/feedback/page.tsx:17,89`、`notify-rule/page.tsx:9,45`、`NoticeManagement.tsx:10,169` 三处在用）；通知相关导出改为从 `@/api/notice` 再导出并标 `@deprecated` |
| `src/components/admin/NoticeManagement.tsx:10-13` | 7 个符号改从 `@/api/notice`；`loadFeedbackDicts` → `loadNoticeDicts`（`:169`） |
| `src/components/NoticeBell.tsx:7-8` | `SysNotice` / `noticeTypeLabel` 改从 `@/api/notice`；顺手补「全部通知」跳转（现在 Popover 里没有任何入口） |
| `src/app/dashboard/system/mail/notify-rule/page.tsx:9,45` | 改 `@/api/notice` + `loadNoticeDicts()`（**最容易漏的一处**，它硬依赖 `noticeChannelOptions`/`NOTICE_CHANNEL_ALL`） |
| `src/app/dashboard/feedback/page.tsx:17` | **不动** |
| `NOTICE_TYPE_TEXT_FALLBACK`(:294) 与 `noticeTypeLabel`(:232) | 改成**只有两档**：`announce → 公告`，**其余一切（`notice`/`feedback`/`request`/未知）→ 通知**。不再出现「反馈」「需求」字样（§4.6 已定） |
| 通知列表的「类型」列与筛选下拉 | 下拉只有 公告 / 通知 两项；表格类型列走上面同一套 label，历史行显示「通知」 |

### 7.7 孤儿路由（顺手修，可选）

`src/app/dashboard/message/page.tsx`（11 行，只渲染 `<NoticeManagement />`）既不在 `sys_menu` 种子、也不在 `getNavItems()`，只能手敲 URL；而「消息中心 → 通知管理」页签（`system/mail/page.tsx:45,57`）已经渲染同一个面板。建议删掉它，入口收敛到页签 + 铃铛补链（需你点头）。

---

## 8. 实施顺序（每步可独立编译）

| 步 | 内容 | 判据 |
|---|---|---|
| 1 | `astral-common`：`AppRestResp` + `NoticeConstants` 改名 | `mvn -pl astral-common install` |
| 2 | `astral-system`：`notice` 包 + 2 个 config；同时**删插件的 2 个 Controller**（必须在同一步，否则路径冲突 `Ambiguous mapping`） | `mvn clean install -DskipTests` |
| 3 | 插件侧清理：删 `NoticeChannel`/`FeedbackNoticeService`、`FeedbackService` 改直连 `publishAsync`、`FeedbackPlugin` 删 2 条前缀、3 个 config 收窄 | `mvn clean install -DskipTests` 全绿 |
| 4 | `NotifyEventRegistry` 5 个事件 `noticeType="notice"` | 启动后提交反馈 → `sys_notice` 新行 `notice_type='notice'` |
| 5 | 两条 Flyway 迁移 | 启动日志无迁移错误；字典 `notice_type` 只剩 公告/通知 两项生效 |
| 6 | 测试迁移 + 新增「插件禁用仍可用」测试 | `mvn test -pl astral-system,astral-plugin` |
| 7 | 前端 `api/notice.ts` + 4 处 import | `npx tsc --noEmit` && `npm run build` |

---

## 9. 验收清单

### 9.1 接口契约（一字不能变）

1. `GET /api/v1/app/message/active?versionCode=300` 匿名 → 200 + `{code:200,data:[...]}`
2. `GET /api/v1/app/message/center` 带 `satoken` → 200；不带 → HTTP 200 + body `{code:401,msg:"登录状态已失效"}`
3. `GET /api/v1/app/message/unread-count`、`POST /api/v1/app/message/read-ack` 同上
4. `GET /api/v1/admin/message/inbox` 带管理员 token → 200 + 宿主 `Result`
5. `GET/POST/PUT/DELETE /api/v1/admin/message/**` 全部通

### 9.2 职责分离（本次改造的硬判据）

6. **禁用反馈插件**（`astral.plugins.feedback.enabled=false` 重启）→ 第 1/2/4/5 条**仍然全部正常**；`/api/v1/app/feedback/**` 应报 `PLUGIN001`
7. 禁用状态下 `admin:message:view` / `admin:message:edit` / `user:message:view` / `user:message:read` 四个权限码仍在（`PermissionRegistry` 扫 `@RestController`，与插件开关无关）
8. 反向：启用插件 → 提交反馈 → 线上 `sys_notice` 新增行 `notice_type='notice'`、`scene='feedbackNewSubmission'`、`user_id` 为空（管理员角色展开）
9. 把 `sys_notify_rule` 里「反馈状态变更-通知提交人」停用 → 改反馈状态时**不发通知但业务成功**（证明规则驱动）

### 9.3 类型口径

10. 管理端「通知管理」类型下拉只有 **公告 / 通知** 两项
11. 选「公告」→ 落库 `announce`；选「通知」→ 落库 `notice`
12. 筛选「通知」能带出历史的 `feedback`/`request` 行（共 297 条），筛选「公告」带出 11 条
13. 历史行在列表**类型列显示「通知」**，不出现「反馈」「需求」字样（§4.6）
14. `sys_notice` 总行数改造前后都是 308，**没有任何 UPDATE**
15. `/dashboard/feedback` 的类型筛选与列表标签仍然正常显示「问题（反馈）」/「需求」（来自 `feedback_type` 字典，与通知无关）

### 9.4 数据与迁移

16. `flyway_schema_history` 新增 2 条，无 checksum 报错，无 out-of-order
17. `feedback-schema.sql` 不再含 `sys_notice`；插件禁用后新建库仍由 Flyway 建出该表
18. `sys_dict_data` 里 `notice_type`：`announce`(label=公告,status=1)、`notice`(通知,1)、`feedback`/`request` 已 status=0

### 9.5 前端

19. `npx tsc --noEmit` 0 错；`npm run build` 通过
20. `/dashboard/system/mail?tab=notice` 增删改查全通
21. `/dashboard/system/mail/notify-rule` 事件列表 + 平台多选正常（字典拆分最易踩坑处）
22. `/dashboard/feedback` 正常（字典未动）
23. 顶栏铃铛能拉 inbox、能标已读

---

## 10. 风险

| 风险 | 等级 | 对策 |
|---|---|---|
| 第 2 步中间态两个 Controller 抢同一路径 → `Ambiguous mapping` 启动失败 | 高 | 第 2 步必须「建新 + 删旧」同一次提交，不要停在中间态 |
| `FeedbackPlugin.getApiPrefixes()` 残留 message 前缀 → 拆分完全失效（症状与改造前一样） | 高 | 验收第 6 条是硬判据；grep 兜底 `"/api/v1/app/message"` 不应再出现在插件里 |
| 老 App 里新通知显示灰「公告」标签 | 中 | 已用「公告沿用 announce」把影响限制在「新产生的个人通知」，且只影响颜色；下个 App 版本发版时一并修 |
| `NoticeChannel` → `NoticeTargets` 改名漏改 | 低 | 只有 2 个引用方、14 处引用 |
| 前端 `@/api/feedback` 再导出兼容层长期留双份 | 中 | 全部标 `@deprecated`，写进 `CODING_GUIDE.md` |
| `NotifyEventDef.noticeType` 改 `"notice"` 后，历史规则行是否受影响 | 低 | 规则按 `eventCode` 匹配，`noticeType` 只影响新行标签；不影响投递 |
| 字典停用 131/132 后管理端打开历史行，下拉显示裸值 | 低 | 列表用 label 展示、编辑时按原值回填；若不接受可改为「保留但不出现在新建下拉」（需给 dict 接口加过滤参数，成本更高） |

---

## 11. 明确不改的东西

- **不改任何历史 Flyway 迁移文件**（含 `V20261001007` / `V20261002006` 的裸 ALTER）
- **不 UPDATE 任何存量 `sys_notice` 行**（308 条原样）
- **不动 `sys_dict_data` 里 `announce`（id=130）那一行**：值 `announce`、label「公告」、status=1 全部原样，字典改动只有「加 `notice` + 停用 `feedback`/`request`」
- **不动 `NotifyEventRegistry` 的 5 个事件码字符串与 `notifyType` 之外的字段**（`sys_notify_rule` 按 `eventCode` 关联，改了会静默失联）
- **不动 `sys_notice` 表结构**（不加列不改列，本次只搬「谁建表/谁读写」）
- **不合并两条站内信链路**：`/api/v1/all/notify/inapp/**`（门户/管理端收件箱，服务端 `read_time` 已读）与 `/api/v1/app/message/**`（App 多端投递，客户端缓存判已读）语义不同，并存
- **不动 `AdminToolProvider`**（`astral-plugin/src/main/java/com/astral/ai/tool/AdminToolProvider.java:86` 直接用 `SysNoticeMapper`，`:469`/`:852` 两个 AI 工具），搬迁后天然正常
- **不动客户端代码**（qt-uniappx / qt-pc）

---

## 12. 已定 / 待定

**已定（本轮确认）**

- 通知类型口径：**公告 / 通知**（不是「公共」）；`announce` 存值与 label 零改动
- 消息中心侧**不出现「反馈 / 需求」**，历史 `feedback`/`request` 值一律归并显示为「通知」；`NoticeTagProvider` SPI 方案否决
- 反馈 / 需求的细分只留在反馈侧：`sys_feedback.type` + 已存在的 `feedback_type` 字典
- `feedback_type` 字典**保持原样**：`issue` = 「问题」、`request` = 「需求」（已确认不改，6.2 那条迁移只动 `notice_type`，不碰 `feedback_type`）
- 表与字典改动只走**新增** Flyway，历史迁移一个字不改，存量 308 行通知一条不 UPDATE

**还需你点头（2 个）**

1. **`/dashboard/message` 孤儿路由**：删掉收敛到「消息中心 → 通知管理」页签（推荐）/ 补菜单种子
2. **包名 `com.astral.system.notice`** 是否合意（不建议为此新建 Maven 模块）

**本次不做、留作后续**

- 客户端 `noticeTypeLabel()` 改成「公告 / 其余→通知」（要发 App 版本；老包继续显示历史别名）

---

## 附录 A：`sys_notice` 建表 DDL（逐字取自 `feedback-schema.sql:44-79`，迁入 Flyway 时原样保留）

| 列 | 类型 / 默认 | 说明 |
|---|---|---|
| id | BIGINT PK | 序列 `sys_notice_id`（`SequenceMetaObjectHandler` 自动取号，无需种子） |
| channel | VARCHAR(96) NOT NULL DEFAULT 'app-android,app-ios' | 逗号分隔平台集合，全选拼接 67 字符，故 96 |
| notice_type | VARCHAR(16) NOT NULL DEFAULT 'announce' | 公告 `announce` / 通知 `notice` |
| scene | VARCHAR(64) | 事件码（`feedbackStatusChanged` 等） |
| user_id | BIGINT | NULL = 广播 |
| feedback_id | BIGINT | 反馈类通知的来源 |
| display | BIGINT NOT NULL DEFAULT 4 | 位掩码 1 开屏 / 2 通告栏 / 4 消息中心 |
| title | VARCHAR(128) NOT NULL | |
| content | VARCHAR(65535) | |
| url | VARCHAR(512) | |
| is_show / is_top / dialog_closable / first_login_only / marquee | BIGINT NOT NULL DEFAULT 1 / 0 / 1 / 0 / 0 | |
| read_seconds | BIGINT | 开屏读秒，服务端 clamp 上限 60 |
| terms_version | VARCHAR(32) | 非空时强制全平台 + 强制可关闭 |
| effective_start / effective_end | TIMESTAMP | |
| version_min / version_max | BIGINT | 客户端版本区间 |
| audience | VARCHAR(16) NOT NULL DEFAULT 'ALL' | ALL / LOGGED_IN / NOT_LOGGED_IN |
| read_time | TIMESTAMP | |
| create_time / update_time | TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP | |

索引 6 个：`idx_notice_channel`、`idx_notice_type`、`idx_notice_user`、`idx_notice_show(is_show,is_top)`、`idx_notice_create_time`、`idx_sys_notice_user_read(user_id,read_time)`。
