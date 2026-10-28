# Astral AI 接口契约

## 路径前缀

- 管理端配置：`/api/v1/admin/ai/**`
- 客户端使用：`/api/v1/app/ai/**`
- 兼容前缀：`/api/v1/all/ai/**`

插件 `AiPlugin#getApiPrefixes()` 只声明以上三个精确前缀。禁用 AI 插件时，只有这些前缀下的接口返回 `PLUGIN001`，不影响轻听音乐、文件存储、反馈等其它插件。

## 客户端接口（/api/v1/app/ai）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/agents` | 当前客户端可见、且当前用户有权限的智能体列表 |
| GET | `/capabilities?agentCode=` | 智能体能力开关（会话、反馈、文件上下文、引用、音乐动作） |
| GET | `/conversations?agentCode=` | 会话列表 |
| POST | `/conversations` | 新建会话，请求体 `{agentCode,title}` |
| GET | `/conversations/{id}` | 会话详情 |
| DELETE | `/conversations/{id}` | 删除会话（软删） |
| GET | `/conversations/{id}/messages?beforeId=&limit=` | 消息分页，返回 `nextCursor` 与 `hasMore` |
| POST | `/conversations/{id}/messages` | 发送消息，返回 `{requestId,userMessageId,assistantMessageId,status,stream}` |
| GET | `/requests/{id}/events?after=&limit=` | 拉取增量事件，返回 `nextSequence` 与 `terminal` |
| POST | `/requests/{id}/cancel` | 取消请求 |
| POST | `/messages/{id}/feedback` | 反馈：`LIKE`/`DISLIKE`/`REGENERATE`/`REPORT` |
| POST | `/actions/execute` | 上报客户端动作执行结果（播放发生在客户端） |

客户端类型通过请求头 `X-Ai-Client` 传给服务端，取值 `QT_UNIAPPX`、`QT_PC`、`ASTRAL_FRONT`；缺省时按 `QT_UNIAPPX` 处理。服务端会校验该智能体是否对应该客户端，不对应返回 `AI003`。

## 事件流

事件体结构：

```json
{
  "requestId": 123,
  "sequence": 5,
  "type": "message.delta",
  "data": { "delta": "..." },
  "createdAt": "2026-10-19T10:00:00"
}
```

事件类型：`request.created`、`message.started`、`message.delta`、`message.metadata`、`tool.planned`、`tool.started`、`tool.progress`、`tool.approval_required`、`tool.completed`、`recommendation.updated`、`message.citation`、`message.completed`、`request.cancelled`、`request.failed`。

`sequence` 单调递增且同一请求内唯一，断线后用 `after=<最后一条 sequence>` 续拉。请求进入终态（`SUCCEEDED`/`FAILED`/`CANCELLED`）时 `terminal=true`。

## 请求状态

`QUEUED` → `RUNNING` →（可选 `WAITING_TOOL` / `WAITING_APPROVAL`）→ `SUCCEEDED` / `FAILED` / `CANCELLED`。

## 错误码

| 码 | 含义 |
| --- | --- |
| AI001 | AI 插件未启用 |
| AI002 | 智能体不存在或未发布 |
| AI003 | 当前客户端不可用该智能体 |
| AI004 | 当前用户无智能体使用权限 |
| AI005 | AI 使用额度不足 |
| AI006 | AI 提供方或模型不可用 |
| AI007 | 智能体版本配置无效 |
| AI008 | 工具未授权或参数无效 |
| AI009 | 该操作需要确认后执行 |
| AI010 | 请求已取消 |
| AI011 | 事件游标已过期，请重新拉取 |
| AI012 | 会话绑定的智能体与请求不一致 |
| AI013 | 知识库文档不存在或未完成索引 |
| AI014 | AI 请求不存在或无权访问 |
| AI015 | 会话不存在或无权访问 |

统一响应体为 `{code,errorCode,msg,data,timestamp}`（字段名是 `msg`，不是 `message`）。
HTTP 200 表示服务端已处理，业务结论看 body 的 `code`：200 成功 / 300 业务失败 / 320 参数错误 /
401 未登录 / 403 无权限 / 429 限流 / 500 系统错误（业务失败同样返回 HTTP 200）。

## 管理端配置接口（/api/v1/admin/ai）

- 提供方：`GET/POST /providers`、`PUT/DELETE /providers/{id}`、`POST /providers/{id}/test`
- 模型：`GET /models?providerId=`、`POST /models`、`PUT/DELETE /models/{id}`
- 路由：`GET /routes`、`POST /routes`、`PUT/DELETE /routes/{id}`
- 智能体：`GET/POST /agents`、`GET/PUT/DELETE /agents/{id}`、`POST /agents/{id}/disable`、`POST /agents/{id}/enable`
- 版本：`GET/POST /agents/{id}/versions`、`GET/PUT /agent-versions/{id}`、`POST /agent-versions/{id}/validate`、`POST /agent-versions/{id}/publish`、`POST /agents/{id}/rollback`
- 绑定：`GET/PUT /agent-versions/{id}/tools`、`GET/PUT /agent-versions/{id}/knowledge`
- 提示词：`GET/POST /prompts`、`GET/PUT /prompts/{id}`、`GET/POST /prompts/{id}/versions`、`PUT /prompt-versions/{id}`、`POST /prompt-versions/{id}/publish`
- 知识库：`GET/POST /knowledge/sources`、`GET/PUT /knowledge/sources/{id}`、`GET/POST /knowledge/sources/{id}/documents`、`DELETE /knowledge/documents/{id}`、`POST /knowledge/sources/{id}/sync`、`GET /knowledge/index-jobs/{jobId}`
- 工具：`GET /tools`、`POST /tools/sync`、`PUT /tools/{id}`、`POST /tool-calls/{id}/approve`
- 额度：`GET/POST /quotas`、`PUT/DELETE /quotas/{id}`
- 用量与审计：`GET /usage`、`GET /usage/summary`、`GET /requests`、`GET /requests/{id}`、`GET /audit`、`GET /overview`

## 管理端使用接口

`admin_assistant`（管理助手）与 `admin_qa`（独立答疑）共用 `/api/v1/admin/ai` 下的会话接口，智能体通过 `agentCode` 指定；差异只在版本配置（模型、Prompt、工具、知识源、额度），不在业务代码里分支。
