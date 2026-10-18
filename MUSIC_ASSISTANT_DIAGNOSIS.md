# 音乐助手「推荐无法播放」完整诊断档案

> 生成时间：2026-10-18 17:20
> 证据来源：线上库 `astral`（203.195.155.193），requestId=8020 / conversationId=7009
> 追踪文件：`F:\qtMusic\.tmp\ai-trace.md`

---

## 一、现象

用户在 qt-pc 发「来点适合跑步的快歌」，音乐助手只回一句：

> 我会为你制定一个适合跑步的快歌搜索计划。

界面上没有歌曲卡片、没有播放按钮，无法播放。

---

## 二、证据链（逐层还原）

### 2.1 版本配置是对的（发版已生效）

`ai_request` id=8020：

| 字段 | 值 | 说明 |
|---|---|---|
| `agent_id` | 1001 | music_assistant |
| `agent_version_id` | **3006** | v3，新发布的版本 ✅ |
| `prompt_version_id` | **1004** | 提示词 v2，1329 字节 ✅ |
| `provider_id` / `model_id` | 2002 / 2003 | docode / deepseek-v4.1-flash |
| `response_schema_snapshot` | 393 字节 | 有 schema ✅ |
| `token_in` / `token_out` | 7737 / 689 | 正常 |

**结论：发版没问题，我上两轮的修复确实生效了。**

### 2.2 客户端也正常工作

`ai_tool_call` 显示 **4 次 `search_music` 全部 SUCCEEDED**，每次客户端都回传了 **30 首候选**（`result_json` 里 `candidates` 数组完整，含 songId/sourceId/coverUrl）。

`ai_request.input_snapshot` 里 `contextKeys` 包含 `musicCandidates`，说明 qt-pc 的 `prepareCandidates()` 正常上送了候选。

**结论：客户端搜索、上送、工具回填全链路正常。**

### 2.3 模型在死循环调用搜索工具

4 次 `search_music` 的关键词几乎一样：

| 轮次 | keywords | limit |
|---|---|---|
| 1 | 跑步/快节奏/燃脂/高能/运动 | 20 |
| 2 | 跑步/快节奏/运动/燃脂/高能 | 30 |
| 3 | 跑步/快歌/燃/节奏感/运动 | 30 |
| 4 | 跑步/燃脂/高能快歌/运动节奏/电子舞曲 | 30 |

每轮拿到的候选**完全相同**（都是 qq 源那 30 首），但模型仍继续搜。

### 2.4 最终回答只有过渡语

`ai_message` id=8062：

```
role = ASSISTANT
content(20) = 我会为你制定一个适合跑步的快歌搜索计划。
blocks(352) = {"toolCalls":[{...search_music...},{...get_user_likes...}]}
```

**注意：`blocks_json` 里存的是 toolCalls（工具调用记录），不是歌曲卡片。** 客户端渲染时读到 `blocks` 没有 `song_list` 类型，于是只显示纯文本。

---

## 三、五个根因（按影响排序）

### 根因 1（决定性）：响应 Schema 是不完整的草稿，模型无法据此产出歌曲

库里 393 字节的 schema：

```json
{"type":"object","required":["answer"],"properties":{
  "answer":{"type":"string"},
  "blocks":{"type":"array","items":{"type":"object","properties":{
    "type":{"type":"string"},"title":{"type":"string"},"items":{"type":"array"}}}},
  "actions":{"type":"array","items":{"type":"object","properties":{
    "type":{"type":"string"},"songId":{"type":"string"},"sourceId":{"type":"string"}}}}
}}
```

问题：
- `blocks[].items` 只写了 `{"type":"array"}`，**没有 `items` 子 schema** —— 模型不知道每首歌该放什么字段
- 没有 `required` 约束 `blocks`/`actions`（只有 `answer` 必填）
- 没有 `enum` 约束 `type` 只能是 `song_list` / `PLAY_SONG` 等
- 没有示例值，模型不知道 `songId` 从哪来

**后果：模型输出 `{"answer":"..."}` 就算满足 schema，所以它只写一句过渡语。** 这是最根本的原因。

### 根因 2：`response_format` 传错格式，提供方直接忽略

`OpenAiCompatibleProvider.java:282-287`：

```java
if (request.responseFormatJson() != null && !request.responseFormatJson().isBlank()) {
    JsonNode format = AiJson.read(request.responseFormatJson());
    if (format != null && format.isObject()) {
        body.set("response_format", format);
    }
}
```

而 `request.responseFormatJson()` 传的是 `version.getResponseSchemaJson()`，即裸 JSON Schema：

```json
{"type":"object","required":["answer"],"properties":{...}}
```

OpenAI 兼容协议要求的 `response_format` 必须是：

```json
{"type":"json_schema","json_schema":{"name":"xxx","schema":{...}}}
```
或至少
```json
{"type":"json_object"}
```

把裸 schema 塞进 `response_format`，`type` 字段是 `"object"`（非法值），提供方要么忽略、要么报 400。**deepseek 大概率是静默忽略。**

### 根因 3：`tool_policy.maxRounds` 配置完全没生效

版本里 `tool_policy_snapshot` 写的是：

```json
{"mode":"whitelist","maxRounds":3,"highRiskExecute":false}
```

但代码 `AiChatOrchestrator.java:69` 是硬编码：

```java
private static final int MAX_TOOL_ROUNDS = 4;
```

**`maxRounds` 从来没被读取过**（grep `getToolPolicyJson` 零命中）。后台配的 3 轮不生效，实际跑 4 轮。

### 根因 4：兜底作答的触发条件写错

`AiChatOrchestrator.java:267`：

```java
if (!cancelled(requestId) && answer.toString().isBlank()) {
```

但本次 `answer` = 「我会为你制定一个适合跑步的快歌搜索计划。」，**不为空**，所以兜底逻辑根本没触发。

正确条件应该是「answer 里没有结构化 blocks」或「最后一轮仍在调工具」，而不是「answer 为空」。

### 根因 5：历史里的 toolCalls 与 tool 结果配对修复只解决 400，不解决内容

上一轮加的 `dropOrphanToolMessages` 是对的（防 400），但它不改变「模型只回过渡语」这个事实。

---

## 四、为什么「重新发版」看起来没效果

用户以为发版就够了。实际上：

1. **发版确实生效了**（request.agent_version_id=3006 证明）
2. 但**发上去的内容本身是错的**——schema 是空壳草稿
3. 提示词里虽然写了「必须只输出 JSON」，但 schema 太弱，模型输出 `{"answer":"..."}` 就合规了
4. `response_format` 又传错，等于没有任何强制约束

**结论：不是发版没生效，是配置内容不足以让模型产出结构化结果。**

---

## 五、修复方案

### 修复 1：重写 response schema（必须）

给 `blocks[].items` 补完整子 schema，加 `required`、`enum`、示例：

```json
{
  "type": "object",
  "required": ["answer", "blocks"],
  "properties": {
    "answer": {
      "type": "string",
      "description": "给用户看的中文说明，1-2 句，不要罗列字段名"
    },
    "blocks": {
      "type": "array",
      "description": "必须至少包含一个 song_list 块；没有候选时才留空",
      "items": {
        "type": "object",
        "required": ["type", "title", "items"],
        "properties": {
          "type": { "type": "string", "enum": ["song_list"] },
          "title": { "type": "string" },
          "items": {
            "type": "array",
            "minItems": 1,
            "items": {
              "type": "object",
              "required": ["songId", "sourceId", "name", "artist", "reason"],
              "properties": {
                "songId": { "type": "string", "description": "必须逐字取自工具结果里的 songId" },
                "sourceId": { "type": "string", "description": "必须逐字取自工具结果里的 sourceId" },
                "name": { "type": "string" },
                "artist": { "type": "string" },
                "album": { "type": "string" },
                "duration": { "type": "integer" },
                "reason": { "type": "string", "description": "不超过 30 字的推荐理由" }
              }
            }
          }
        }
      }
    },
    "actions": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["type"],
        "properties": {
          "type": { "type": "string", "enum": ["PLAY_SONG","PLAY_LIST","ADD_TO_QUEUE","ADD_TO_FAVORITE","OPEN_SONG","OPEN_ARTIST","OPEN_ALBUM","OPEN_PLAYLIST","SEARCH_MUSIC"] },
          "songId": { "type": "string" },
          "sourceId": { "type": "string" }
        }
      }
    }
  }
}
```

### 修复 2：`response_format` 按协议包装

在 `OpenAiCompatibleProvider` 里判断：如果 schema 是裸 JSON Schema（有 `properties` 且 `type` 不是 `json_schema`/`json_object`），包装成：

```json
{"type":"json_schema","json_schema":{"name":"agent_response","strict":false,"schema":<裸schema>}}
```

并对不支持 `json_schema` 的提供方降级为 `{"type":"json_object"}`。

### 修复 3：读取 `tool_policy.maxRounds`

从 `version.getToolPolicyJson()` 解析 `maxRounds`，缺省用常量，并夹在 `[1, 8]` 区间。

### 修复 4：兜底条件改为「没有结构化 blocks」

```java
boolean hasBlocks = /* answer 解析出 blocks 非空 */;
if (!cancelled && !hasBlocks && roundUsedTools) { 强制再问一次 }
```

### 修复 5：提示词里直接嵌入 schema 与示例

当前提示词只有一句「必须只输出一个 JSON 对象」，示例里 `items: []` 是空的，模型就照抄空数组。改成给出**完整的 3 首歌示例**。

---

## 六、验证方法

修完后用这条 SQL 确认新版本生效：

```sql
SELECT a.published_version_id, v.version_no, v.system_prompt_version_id,
       length(v.response_schema_json)
FROM ai_agent a JOIN ai_agent_version v ON v.id = a.published_version_id
WHERE a.id = 1001;
```

再发一条消息，检查：

```sql
SELECT role, length(content), blocks_json FROM ai_message
WHERE conversation_id = <新会话> ORDER BY id;
```

**成功标志**：ASSISTANT 消息的 `blocks_json` 里出现 `"type":"song_list"` 且 `items` 非空。

---

# 第二轮诊断（2026-10-18 17:43 补充）

第一轮修完后用户仍然「还是不行」，而且报出新错误。继续追查又挖出**两个更深的问题**，其中一个直接导致请求 500。

## 七、根因 6：`specs` 空指针，整个请求 500

错误原文（截图红条 + `ai_request.error_summary`）：

```
Cannot invoke "java.util.List.isEmpty()" because "specs" is null
```

位置：`AiChatOrchestrator.callModel` 第 313 行（修复前）：

```java
specs.isEmpty() ? null : specs,
```

调用方是「工具轮用尽后强制作答」那段兜底逻辑，传的是 `callModel(..., null, assistant)` —— 明确把 `specs` 传成 `null`，而 `callModel` 里没做空判断，直接 NPE。

**讽刺之处**：这个兜底逻辑是第一轮为了「逼模型作答」新加的，结果它自己先崩了，导致整个请求变成 FAILED，用户连过渡语都保不住。

修复：

```java
specs == null || specs.isEmpty() ? null : specs,
```

## 八、根因 7：会话绑定归档版本，发版永远不生效

这是「发了版却没效果」的真正原因，也是**前两轮全部白费的关键**。

证据对比：

| 时间 | 事件 |
|---|---|
| 17:11 | 会话 7009 创建，绑定 v3（3006） |
| 17:23 | 我发布 v4（3007），v3 被归档 |
| 17:43 | 用户发「再来点」，请求 9021 的 `agent_version_id` **仍然是 3006** |

数据库状态明明是 `ai_agent.published_version_id = 3007`，请求却用了 3006。

原因在 `AiAccessService.resolveVersion`（修复前）：

```java
if (conversationVer != null) {
    AiAgentVersionEntity version = agentService.requireVersion(conversationVer);
    if (PUBLISHED.equals(version.getStatus()) || ARCHIVED.equals(version.getStatus())) {
        return version;   // ← ARCHIVED 也照样返回！
    }
}
```

会话绑定的版本只要状态是 `PUBLISHED` **或 `ARCHIVED`** 就沿用。于是：**会话一旦创建，后续发多少版都不会生效，必须手动新建会话。**

修复后：

```java
if (conversationVer != null && conversationVer.equals(publishedId)) {
    return agentService.requireVersion(publishedId);   // 就是当前发布版，沿用
}
if (conversationVer != null) {
    AiAgentVersionEntity bound = agentService.requireVersion(conversationVer);
    if (PUBLISHED.equals(bound.getStatus())) {
        return bound;                                   // 仍是发布版，沿用
    }
    // ARCHIVED / DRAFT → 落到下面跟随最新发布版
}
return agentService.requireVersion(publishedId);
```

## 九、根因 8：工具从不「用完即弃」，模型反复搜同一批歌

`ai_tool_call` 铁证：一次请求里 **4 次 `search_music`**，关键词只差一两个字：

| 轮次 | keywords |
|---|---|
| 1 | 跑步/快歌/动感/高节奏/运动 |
| 2 | 跑步/快歌/运动/节奏感强/燃 |
| 3 | 跑步/快节奏/燃/运动 |
| 4 | 跑步/燃脂/高能快歌/运动节奏/电子舞曲 |

每次客户端都回传**同样那 30 首候选**。模型看到 `search_music` 一直挂在可调用列表里，就一直调，把轮次耗尽。

原因：`toolSpecs(tools)` 每一轮都传全量工具。

修复：新增 `availableTools(tools, exhausted)`，成功执行过的 **READ 类**工具从后续轮次移除；`DRAFT`/`HIGH` 类不过滤（用户确认后本就允许重复执行）。

## 十、上游实测：api.haogpt.net 返回 503

用线上 provider 真实配置打了一次端到端：

```
POST https://api.haogpt.net/v1/chat/completions
HTTP 503
{"error":{"message":"The group daily usage authorization service is temporarily unavailable.",
          "code":"group_daily_usage_authorization_unavailable"}}
```

**这是上游授权服务故障，与配置无关。** 另外本地 KEK（`astral-local-dev-kek-2026`）与服务器不一致，`AiSecretCipher` 报 `Tag mismatch`，无法在本地解密 API Key 做完整验证。

## 十一、三轮修复总览

| 提交 | 内容 |
|---|---|
| `23152b0` | response_format 协议包装、maxRounds 读取、结构化兜底、完整 Schema（393B→2978B）、提示词三首歌示例 |
| `8c24e31` | 诊断档案 |
| `938eb64` | specs 空指针修复、归档版本不再沿用会话绑定 |
| `023328f` | 已用过的 READ 工具不再提供给后续轮次 |

**线上配置**：v4（id=3007）已发布，提示词 2349B，Schema 2978B，9 个工具绑定齐全。

**测试**：新增 `AiChatOrchestratorFormatTest`（10 用例）、`AiAccessServiceResolveVersionTest`（5 用例），全量 179 用例通过。

## 十二、若仍不行，下一步排查方向

1. **确认版本真的切过去了**：发一条消息后查 `ai_request.agent_version_id`，必须是 `3007`。若还是 3006，说明 `resolveVersion` 修复没生效（后端未重启或 jar 未 install）。
2. **确认 `response_format` 被接受**：看 `ai_request.configuration_snapshot` 里的 `responseSchemaJson` 是否为 2978B 版本。
3. **确认模型输出**：查 `ai_message` 里 ASSISTANT 消息的 `content` 是否为 JSON。若是纯文本，说明 deepseek 忽略了 `response_format`，此时需要走「把候选歌单直接拼进 system prompt」的兜底方案。
4. **上游 503**：等 api.haogpt.net 恢复，或换一个提供方测试。
