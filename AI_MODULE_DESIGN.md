# Astral AI 模块基础设计文档

> 文档版本：v1.3
>
> 设计状态：开发基线
>
> 适用范围：Astral 后端 AI 插件、Astral 管理端、轻听 `qt-uniappx` App、轻听 `qt-pc` 桌面端
>
> 目标：在 Astral 内建设统一 AI 平台，并提供三个可由后台分别配置、发布、授权和限额的智能体：轻听音乐助手、管理助手、管理端答疑助手。

## 1. 设计目标

AI 模块不是单独的聊天页面，而是一套由 Astral 统一管理的 AI 平台。平台负责模型接入、智能体配置、版本发布、会话、消息、完整请求流、工具调用、权限、额度、审计和用量统计；客户端负责场景上下文采集、流式展示和执行经过服务端授权的结构化动作。

本设计有一个必须遵守的产品原则：**任何智能体都不能把模型、Prompt、工具、知识源、权限、限制和入口写死在客户端或业务代码中**。代码只提供能力注册表、协议校验和安全边界；具体启用哪些智能体、使用哪个模型、采用什么 Prompt、允许哪些工具、面向哪些客户端和用户、每次能使用多少次，全部由管理端配置并通过版本发布生效。

本期确定三个产品形态：

1. **音乐助手**：面向 `qt-uniappx` 和 `qt-pc` 用户。两个客户端使用相同的智能体编码、会话协议、消息结构、工具语义和产品能力，分别通过各自平台适配器采集音乐上下文、执行搜索和执行播放动作。它理解自然语言音乐需求，跨已安装音源包搜索多个平台的歌曲、歌手、专辑和歌单，整理候选结果并给出推荐理由。
2. **管理助手**：面向 `astral-front` 管理端用户。帮助管理员查询系统状态、分析日志和反馈、解释权限及配置问题、生成处理方案和草稿；涉及修改、删除、发信、权限调整等高风险操作时，只能提出计划并等待管理员确认。
3. **管理端答疑助手**：面向 `astral-front` 的独立答疑入口。它与管理助手使用独立的智能体配置、Prompt、知识源、工具白名单、权限和额度，用于回答产品使用、系统功能、接口契约、部署文档和运营规则等问题。它默认以知识检索和文档引用为主，不拥有管理助手的运维写权限。

暂不把 AI 设计成可以任意执行代码、任意查询数据库或直接控制生产系统的万能 Agent。所有能力都必须通过后台登记的智能体版本、工具和权限暴露。

## 2. 当前系统约束与集成边界

### 2.1 Astral 后端

Astral 是 Java 25、Spring Boot 4.1 的多模块 Maven 项目。业务插件统一放在 `astral-plugin`，不新增独立 Maven 模块。插件由 `AstralPlugin` 自动发现并由 `PluginRegistry` 管理启停状态。

AI 插件应放在以下包路径：

```text
astral-plugin/src/main/java/com/astral/ai/
├── AiPlugin.java
├── controller/
├── service/
├── provider/
├── tool/
├── mapper/
├── entity/
├── dto/
├── config/
└── security/
```

插件 API 只能使用三层路径约定：

```text
/api/v1/app/ai/**      轻听 App 用户接口
/api/v1/admin/ai/**    管理端接口
/api/v1/all/ai/**      两端确实共用的只读接口
```

`AiPlugin#getApiPrefixes()` 必须只声明 AI 自己拥有的精确前缀，不能声明 `/api/v1/app` 或 `/api/v1/admin` 这类宽前缀。

### 2.2 管理端与三个智能体入口

管理端是 Next.js 16 App Router 应用，请求统一经过 `astral-front/src/api/client.ts`。新增 AI API 必须放在：

```text
astral-front/src/api/ai.ts
```

新增页面使用 shadcn/ui 和 Tailwind 语义令牌，不新增 Ant Design 页面。插件导航通过 `PluginFrontendExtension` 动态注入，但页面仍需实际创建。

管理端必须同时提供两个使用入口：

```text
/dashboard/plugin/ai             AI 平台配置与智能体管理
/dashboard/plugin/ai/assistant   管理助手
/dashboard/plugin/ai/qa          独立答疑助手
```

配置页建议拆分为：

```text
/dashboard/plugin/ai/providers
/dashboard/plugin/ai/models
/dashboard/plugin/ai/agents
/dashboard/plugin/ai/prompts
/dashboard/plugin/ai/knowledge
/dashboard/plugin/ai/quotas
/dashboard/plugin/ai/usage
/dashboard/plugin/ai/requests
/dashboard/plugin/ai/tools
/dashboard/plugin/ai/audit
```

`assistant` 与 `qa` 必须是不同的运行时智能体编码和不同的会话入口。页面可以复用同一套流式聊天组件，但请求时必须明确携带 `agentCode`，服务端再根据已发布版本解析模型、Prompt、工具、知识源和限制。前端不能自行决定这些配置。

### 2.3 轻听 App

`qt-uniappx` 使用 Vue 3 Composition API、`.uvue` 页面、UTS 服务和 Vapor 模式。页面必须使用 `qt-page-frame`，通用 UI 组件放在 `uni_modules/qt-ui`，网络请求必须经过 `services/http.ts`，统一携带 App 客户端系统头和 Bearer Token。

建议新增：

```text
qt-uniappx/pages/ai/index.uvue
qt-uniappx/pages/ai/conversations/index.uvue
qt-uniappx/pages/ai/conversation/index.uvue
qt-uniappx/services/ai.uts
qt-uniappx/stores/ai.uts
qt-uniappx/types/ai.uts
```

客户端只展示后端 `/api/v1/app/ai/agents` 返回的、面向当前客户端类型且已发布的智能体。首期至少展示 `music_assistant`；以后新增 App 智能体不应要求改死页面路由，只要智能体声明了入口位置、图标、能力和客户端约束，客户端就能渲染通用入口或会话页。

### 2.4 轻听 PC

`qt-pc` 的 Astral 请求通过 Tauri IPC 和 Rust `src-tauri/src/astral.rs`，不能直接照搬管理端的浏览器请求代码。`qt-pc` 与 `qt-uniappx` 必须使用相同的 `music_assistant` 智能体和相同的业务协议，功能范围保持一致：自然语言音乐问答、跨平台搜索、推荐结果、当前音乐上下文、播放/队列/收藏等白名单动作、会话历史、流式事件、取消、重试和反馈。

两个客户端的差异只存在于平台适配层：

| 能力 | qt-uniappx | qt-pc |
|---|---|---|
| 请求出口 | `services/http.ts` | Rust `astral.rs` + Tauri IPC |
| 流式传输 | 轮询事件或长轮询 | Rust 读取事件并转 Tauri event |
| 音源搜索 | UTS 音源引擎/音源包 | PC 音源脚本/引擎 |
| 播放动作 | Pinia 播放状态和页面路由 | Rust/React 播放队列与页面状态 |
| 上下文来源 | 当前播放、歌单、收藏 | 当前播放、歌单、队列、本地音乐摘要 |
| UI 实现 | `.uvue` + `qt-ui` | React 组件 |

两端必须共享以下契约，不得各自定义一套：

```text
agentCode = music_assistant
MusicSearchPlan
MusicCandidate
MusicRecommendationBlock
MusicClientAction
AI request/event/status
```

PC 端需要新增 Rust API、`src/services/ipc.ts` 包装函数、React AI 页面和事件监听。PC 与 App 的音乐功能不能因为传输方式不同而退化成“PC 只有普通聊天”。

PC 端的音乐上下文可以包括当前歌曲、歌手、专辑、歌单和播放队列。默认不上传本地音频或本地歌词，除非用户明确选择并确认。

### 2.5 三端智能体可用性矩阵

智能体是否能在某一端出现，由后台发布版本的 `clientTypes` 和 `entryConfig` 决定，不由客户端硬编码判断：

| 智能体 | qt-uniappx | qt-pc | astral-front | 默认用途 |
|---|---:|---:|---:|---|
| `music_assistant` | 是 | 是 | 可选预览 | 音乐搜索、推荐与播放协作 |
| `admin_assistant` | 否 | 否 | 是 | 系统管理、日志、反馈和配置分析 |
| `admin_qa` | 否 | 否 | 是 | 文档、功能和接口答疑 |

`clientTypes` 只是入口过滤条件，不代替权限判断。服务端仍需检查用户端类型、权限、发布状态、额度和智能体策略。

## 3. 总体架构

```text
┌──────────────────────────────────────────────────────────────┐
│                       Astral AI 平台                         │
│                                                              │
│  智能体目录 / 发布版本 / Provider / 模型 / Prompt             │
│  工具注册 / 知识源 / 权限 / 额度 / 会话 / 请求流               │
│  用量 / 审计 / 审批 / 安全策略                               │
└───────────────┬──────────────────────────────┬───────────────┘
                │                              │
     /api/v1/app/ai/**                /api/v1/admin/ai/**
                │                              │
     ┌──────────┴──────────┐       ┌───────────┴────────────┐
     │ qt-uniappx          │       │ astral-front            │
     │ qt-pc               │       │ admin_assistant         │
     │ music_assistant     │       │ admin_qa                │
     └──────────┬──────────┘       └───────────┬────────────┘
                │                              │
                └──────────────┬───────────────┘
                               │
                    Provider 适配与模型路由
                               │
              OpenAI Compatible / Ollama / 其他
```

三个智能体共享平台底座，但运行时配置完全隔离：

```text
music_assistant
  ├── 面向 qt-uniappx + qt-pc
  ├── 音乐上下文、音源搜索和播放器动作
  ├── user:ai:music:* 权限与独立额度
  └── 音乐 Prompt / 工具 / 知识源版本

admin_assistant
  ├── 面向 astral-front 管理端
  ├── 系统查询、日志分析、反馈分析和管理草稿
  ├── admin:ai:assistant:* 权限与独立额度
  └── 管理 Prompt / 工具 / 知识源版本

admin_qa
  ├── 面向 astral-front 管理端
  ├── 产品、接口、部署和使用答疑
  ├── admin:ai:qa:* 权限与独立额度
  └── 答疑 Prompt / 知识库 / 引用策略版本
```

代码只负责提供安全的能力边界和内置工具实现；后台决定智能体是否启用、面向哪个客户端、使用哪个模型、加载哪个 Prompt 版本、可用哪些工具和知识源、采用什么限制。客户端只能消费后端返回的已发布智能体配置，不能自行拼接 Prompt、选择未授权模型或启用工具。

## 4. 智能体模型与后台配置

### 4.1 智能体是运行时的一等配置对象

统一使用“智能体（Agent）”作为产品和数据库概念，以 `ai_agent` 表示一个可独立管理的助手。音乐助手、管理助手和答疑助手各有自己的身份、发布版本、权限策略、会话和用量，不能仅作为同一智能体的页面切换模式。

首批默认智能体编码如下：

```text
music_assistant   音乐助手
admin_assistant   管理助手
admin_qa          管理端答疑助手
```

这三个编码是初始种子配置，不是业务代码中的固定分支。后续管理员可以创建新的智能体，例如 `customer_support`、`file_assistant` 或 `release_assistant`。代码不应出现“如果 agentCode 等于某个值就使用某套 Prompt”的主流程判断；差异应来自已发布的智能体版本配置和工具策略。

### 4.2 `ai_agent` 基本配置

`ai_agent` 描述智能体的身份、可见范围和当前发布指针：

```text
id
agent_code                  全局唯一、稳定的外部编码
name                       管理端可修改的显示名称
agent_type                 MUSIC / ADMIN / QA / CUSTOM
description
icon
status                     DRAFT / PUBLISHED / DISABLED
client_types               QT_UNIAPPX / QT_PC / ASTRAL_FRONT，多值
entry_config               入口位置、标题、快捷问题、排序和展示条件
permission_code            使用该智能体的基础权限码
client_permission_codes    按客户端类型收窄的可选权限码
quota_policy_id            默认额度策略
published_version_id       当前生效版本
allow_user_context         是否允许用户上下文
allow_file_context         是否允许文件上下文
conversation_enabled
feedback_enabled
sort
created_by
updated_by
deleted_at                 仅允许删除未被请求引用的草稿
create_time
update_time
```

`agent_type`、`status`、`client_types` 等出现在管理端或客户端的枚举必须通过数据字典提供文案和选项。服务端内部仍可使用常量判断状态。

### 4.3 `ai_agent_version` 发布配置

智能体的可执行行为必须由不可变版本承载。发布新版本不直接覆盖正在运行的版本：

```text
id
agent_id
version_no
version_name
status                     DRAFT / PUBLISHED / ARCHIVED
provider_route_id          模型路由策略
system_prompt_version_id
client_types_snapshot      发布时锁定的客户端类型
entry_config_snapshot      发布时锁定的入口配置
context_policy_json
response_schema_json
tool_policy_json
knowledge_policy_json
model_parameters_json
safety_policy_json
quota_override_json
validation_summary_json
published_by
published_at
rollback_from_version_id
created_by
updated_by
create_time
update_time
```

版本发布后不可修改其模型、Prompt、工具、知识源、入口、权限和额度字段；需要变更时创建新的草稿版本并重新校验、发布。`PUT /agent-versions/{versionId}` 只允许更新 `DRAFT` 版本，已发布版本只能归档或通过智能体回滚切换。

一个请求开始时必须绑定 `agent_id + agent_version_id`。即使管理员随后发布新版本，已开始的请求仍使用原版本，保证流式请求、审计和问题复现的一致性。

版本发布必须执行校验：

- 默认模型存在且已启用。
- Provider 可用且密钥配置完整。
- Prompt 版本已发布。
- 响应 Schema 是合法 JSON Schema。
- 工具已登记且当前版本允许使用。
- 知识源存在且权限策略完整。
- 客户端类型和入口配置合法。
- 额度策略存在且不超过全局安全上限。
- 管理助手和答疑助手不能引用 App 端工具。
- 音乐助手不能引用管理端工具。

### 4.4 配置项与代码职责边界

| 配置内容 | 后台可配置 | 代码提供 |
|---|---:|---:|
| 智能体名称、图标、说明 | 是 | 默认种子 |
| 面向客户端和入口位置 | 是 | 客户端渲染能力 |
| 默认/备用模型 | 是 | Provider 适配器 |
| System Prompt | 是 | 模板变量和安全拼接器 |
| 模型参数 | 是 | 参数范围校验 |
| 上下文字段 | 是 | 客户端可提供字段白名单 |
| 工具选择和工具顺序 | 是 | 工具实现和安全边界 |
| 工具是否需确认 | 是 | 风险级别下限 |
| 知识库和引用策略 | 是 | 检索与权限校验 |
| 输入/输出/Token/并发限制 | 是 | 全局硬上限 |
| 用户/角色授权 | 是 | 声明式权限框架 |
| 流式协议和事件格式 | 否 | 统一平台协议 |
| 业务数据权限校验 | 否 | Service 和安全层 |
| 高风险工具最高风险级别 | 否 | 代码强制下限 |

管理员可以缩小能力，但不能通过配置绕过代码安全边界。例如，管理端可以把一个工具设置为“必须确认”，但不能把删除用户工具改成无需确认；可以限制最大 Token，但不能突破服务端硬上限。

### 4.5 后台配置工作流

```text
创建智能体
    ↓
配置客户端、入口和权限
    ↓
选择 Provider 路由和模型
    ↓
编辑 Prompt、上下文、工具和知识源
    ↓
设置配额、并发和保留策略
    ↓
保存草稿并运行配置校验
    ↓
使用测试会话验证回答和工具计划
    ↓
发布版本
    ↓
客户端重新获取能力清单并显示入口
```

发布、回滚、禁用和删除是不同操作：

- **发布**：让一个完整版本成为新请求的默认版本。
- **回滚**：把默认版本指针切回之前的已发布版本。
- **禁用**：阻止新请求，已运行请求按取消策略结束。
- **删除**：只允许删除从未被请求引用的草稿；已引用版本只能归档。

### 4.6 后台管理页面

管理端的 AI 平台页面应围绕智能体配置组织，而不是只提供一个“模型设置”表单：

```text
/dashboard/plugin/ai
├── 智能体总览
├── 智能体编辑 / 版本发布
├── Provider 管理
├── 模型与路由
├── Prompt 模板与版本
├── 工具注册与授权策略
├── 知识源与引用策略
├── 用户/角色权限
├── 额度与限制
├── 测试对话
├── 请求流与失败任务
└── 用量、费用与审计
```

智能体编辑页至少包含：

- 基本信息。
- 客户端和入口配置。
- Prompt 版本选择。
- 模型路由选择。
- 上下文字段白名单。
- 工具勾选、风险级别和确认策略。
- 知识库绑定和检索数量。
- 输入、输出、Token、并发和费用限制。
- 可使用的用户/角色范围。
- 发布前配置检查。
- 预览测试和版本对比。

### 4.7 运行时能力清单

客户端启动或进入 AI 功能时调用能力清单接口，返回已经过权限、客户端类型、发布状态和额度预检查的结果：

```json
{
  "agentCode": "music_assistant",
  "agentVersion": "2026.10.1",
  "name": "音乐助手",
  "enabled": true,
  "entry": {
    "locations": ["home", "search", "player", "playlist"],
    "quickPrompts": ["根据当前歌曲推荐相似音乐", "帮我生成学习歌单"]
  },
  "capabilities": {
    "stream": true,
    "musicSearch": true,
    "clientActions": ["PLAY_SONG", "ADD_TO_QUEUE"],
    "attachments": false
  },
  "limits": {
    "remainingRequests": 18,
    "maxInputChars": 4000
  }
}
```

返回中不能包含 API Key、完整 System Prompt、未授权工具、内部 Provider 地址或其他管理配置。

### 4.8 智能体实例与请求绑定

一次请求必须形成不可变运行快照：

```text
request_id
agent_id
agent_version_id
provider_id
model_id
prompt_version_id
knowledge_policy_snapshot
model_parameters_snapshot
tool_policy_snapshot
context_policy_snapshot
response_schema_snapshot
safety_policy_snapshot
quota_policy_snapshot
client_type
user_id
```

后续任何管理端配置变化只影响新请求。这样可以保证 `qt-uniappx`、`qt-pc`、管理助手和答疑助手在同一时刻使用的版本可追踪、可回放、可审计。

### 4.9 管理端答疑智能体

`admin_qa` 是管理端独立答疑智能体，与 `admin_assistant` 的职责、Prompt、工具和限制完全分开。它的主要目标是让管理员可以在管理后台直接询问“这个功能怎么用”“这个接口如何接入”“为什么这个请求被拒绝”“部署时需要配置什么”等问题。

答疑智能体的默认行为：

- 优先检索已发布的 Astral 文档、接口契约、插件指南、部署说明和运营规则。
- 回答必须尽量附带文档标题、章节、路径或接口来源。
- 文档没有覆盖的问题可以调用只读系统工具，但必须标记回答来自实时系统数据。
- 不使用 `admin_assistant` 的用户、角色、配置修改、文件删除、插件启停等管理工具。
- 默认不读取普通用户完整会话、密码、Token、API Key、Provider 密钥和敏感日志正文。
- 对文档冲突、版本不匹配或资料不足，应明确提示不确定性并给出需要确认的内容。

建议的答疑工具：

```text
search_project_document
get_api_contract
get_plugin_guide
get_deployment_guide
get_dictionary_definition
get_error_code_explanation
get_current_plugin_status_readonly
get_current_config_schema_readonly
```

知识源可以由后台配置多个来源，并按优先级和版本控制：

```text
PROJECT_DOCUMENT
API_DOCUMENT
PLUGIN_DOCUMENT
DEPLOYMENT_DOCUMENT
RELEASE_NOTE
FAQ
LIVE_READONLY_DATA
```

每条引用需要保留：

```text
sourceId
sourceType
title
path
section
version
matchedText
```

向模型提供的文档内容必须被包裹为不可信资料，不能覆盖系统 Prompt。回答中的引用来自检索结果，不能由模型自行编造路径、版本或章节。

答疑智能体允许后台单独设置：

- 使用的模型和备用模型。
- System Prompt 和答复语气。
- 可检索知识源。
- 每次最多引用数量和最大文档长度。
- 是否允许实时只读查询。
- 是否显示引用正文或只显示标题和定位。
- 每日/每月请求数、Token、并发和费用限制。
- 哪些角色可以使用以及是否允许导出回答。

`admin_qa` 不应因为拥有 `admin:ai:qa:use` 就自动拥有管理数据读取权限。实时查询工具继续使用各自的 `admin:` 权限和最小字段返回。

### 4.10 智能体配置示例

以下是后台保存的概念配置，不是写入 Java 或 TypeScript 的常量：

```json
{
  "agentCode": "music_assistant",
  "clientTypes": ["QT_UNIAPPX", "QT_PC"],
  "entryConfig": {
    "locations": ["home", "search", "player", "playlist"],
    "quickPrompts": ["根据当前歌曲推荐相似音乐", "帮我生成学习歌单"]
  },
  "modelRoute": {
    "primaryModel": "music-chat",
    "fallbackModels": ["general-chat"]
  },
  "promptVersion": "music-assistant-v3",
  "tools": ["search_music", "get_current_song", "get_current_playlist"],
  "toolPolicy": {
    "ADD_TO_FAVORITE": "USER_CONFIRM"
  },
  "contextPolicy": {
    "allowedFields": ["currentSong", "currentPlaylist", "installedSourceRegistry"]
  },
  "quotaPolicy": {
    "dailyRequests": 30,
    "maxInputChars": 4000,
    "maxConcurrent": 1
  }
}
```

不同智能体只改变后台配置，不改变统一请求协议。新增一个智能体的最低流程是：创建配置、选择客户端、绑定 Prompt/模型/工具/知识源、设置权限和额度、测试、发布；客户端通过能力清单发现它。

## 5. Provider 与模型设计

### 5.1 Provider 抽象

后端使用统一适配接口，Controller 不直接依赖具体供应商：

```java
public interface AiProvider {
    String type();

    AiProviderResponse chat(AiProviderRequest request);

    void stream(AiProviderRequest request, AiStreamListener listener);

    AiProviderHealth testConnection(AiProviderConfig config);
}
```

第一阶段优先支持 OpenAI Compatible，覆盖多数云服务和 Ollama。后续再增加 Anthropic、Gemini 等专用协议。

### 5.2 Provider 配置

`ai_provider` 保存服务商的非业务调用配置：

```text
id
provider_code
name
provider_type
base_url
enabled
secret_ref / encrypted_api_key
connect_timeout_ms
read_timeout_ms
max_retries
weight
health_status
last_health_time
create_time
update_time
```

API Key 必须加密存储或引用外部 Secret。任何列表、详情、日志和前端响应都不能返回原始密钥。编辑时空值表示不修改已有密钥。

Provider Base URL 必须限制为允许的 `https` 地址或显式允许的本地 Ollama 地址，禁止任意内网地址和未授权跳转，防止 SSRF。

### 5.3 模型配置

`ai_model` 保存模型能力和价格：

```text
id
provider_id
model_code
model_name
model_kind                 CHAT / REASONING / VISION / EMBEDDING / RERANK
supports_stream
supports_tools
supports_json
supports_vision
max_context_tokens
max_output_tokens
input_price
output_price
currency
enabled
create_time
update_time
```

模型能力由后端配置声明，客户端根据能力决定是否显示附件、图片、工具和结构化输出入口。

### 5.4 路由策略

模型路由需要支持：

- 默认模型。
- 备用模型。
- 按智能体选择模型。
- 按用户或角色限制模型范围。
- Provider 健康检查。
- 超时、重试和熔断。
- Provider 权重轮选。
- 429、5xx 和网络超时的有限重试。

不能在重试时重复执行有副作用的工具调用。工具调用必须使用请求幂等键。

## 6. 音乐助手设计

### 6.1 混合执行流程

`qt-uniappx` 和 `qt-pc` 使用同一条业务流程，只有客户端音源和播放器适配器不同：

```text
用户输入自然语言
        ↓
客户端携带 agentCode=music_assistant 创建会话消息和 AI 请求
        ↓
模型识别音乐意图
        ↓
生成结构化 MusicSearchPlan
        ↓
客户端使用已安装音源包/音源脚本搜索多个平台
        ↓
候选歌曲标准化、去重并回传
        ↓
模型排序并生成推荐理由
        ↓
保存最终消息和推荐结果
        ↓
客户端展示歌曲卡片并执行白名单动作
```

`qt-uniappx` 通过 UTS 音源引擎执行搜索，`qt-pc` 通过 PC 音源脚本/引擎执行搜索。两端都必须回传相同的 `MusicCandidate` 结构，并处理相同的 `MusicClientAction`。后端不能根据客户端类型删减音乐助手的核心业务能力。

由于当前音源逻辑位于客户端音源包，AI 后端不能假设任何平台接口可从服务端直接访问。两个客户端发送给后端的是标准化候选数据，不是平台 Cookie、签名、播放 URL 或音源包内部凭据。

### 6.2 搜索计划

模型生成的搜索计划必须经过 JSON Schema 校验和服务端限制：

```json
{
  "intent": "recommend_music",
  "keywords": ["粤语", "女声", "夜间驾驶"],
  "sources": ["wyy", "qq", "kw"],
  "limit": 30,
  "filters": {
    "language": "粤语",
    "popularity": "low"
  }
}
```

限制包括：

- 音源只能是客户端上报且当前可用的音源注册表 ID。
- `limit` 有最大值。
- 关键词和过滤条件有长度限制。
- 不能执行任意脚本、任意 URL 或任意客户端函数。
- 搜索超时后可以返回部分平台结果。

### 6.3 音乐助手工具

第一阶段工具：

```text
search_music
search_artist
search_album
search_playlist
get_song_detail
get_current_song
get_current_playlist
get_user_likes
get_play_history
```

第二阶段工具：

```text
create_queue
add_song_to_like
create_playlist_draft
open_song
open_artist
open_album
open_playlist
```

搜索和查询类工具可以自动执行。收藏、创建歌单、修改播放队列等写操作必须返回确认卡片，由用户确认后执行。

### 6.4 音乐助手返回结构

最终消息不能只有 Markdown 文本，应同时保存结构化内容：

```json
{
  "answer": "我为你整理了 12 首适合夜间驾驶的粤语女声歌曲。",
  "blocks": [
    {
      "type": "song_list",
      "items": [
        {
          "id": "song-id",
          "platform": "wyy",
          "title": "歌曲名",
          "artist": "歌手",
          "album": "专辑",
          "picUrl": "https://...",
          "reason": "节奏舒缓，适合夜间驾驶"
        }
      ]
    }
  ],
  "actions": [
    {
      "type": "PLAY_SONG",
      "songId": "song-id",
      "platform": "wyy"
    }
  ]
}
```

允许的客户端动作：

```text
PLAY_SONG
PLAY_LIST
ADD_TO_QUEUE
ADD_TO_FAVORITE
OPEN_SONG
OPEN_ARTIST
OPEN_ALBUM
OPEN_PLAYLIST
SEARCH_MUSIC
```

`qt-uniappx` 和 `qt-pc` 都必须再次校验动作类型、参数格式和当前用户状态。模型输出不能直接转换成 JavaScript 或任意函数调用。

### 6.5 音乐上下文

`qt-uniappx` 和 `qt-pc` 可按用户操作提供有限上下文：

```text
currentSong
currentArtist
currentAlbum
currentPlaylist
currentQueue
recentlyPlayedSummary
userPreferenceSummary
installedSourceRegistry
```

`qt-uniappx` 通过 Pinia/UTS 状态提供上下文，`qt-pc` 通过 React/播放器状态和 Rust IPC 提供上下文。上下文必须按场景最小化发送，并受到长度限制。播放历史和收藏信息属于用户数据，只有在用户已登录且智能体版本声明允许时才可使用。

## 7. 管理助手设计

### 7.1 工具分级

管理工具按风险分级：

**只读工具**：

```text
get_system_health
get_plugin_status
get_user_summary
get_permission_summary
get_storage_summary
search_operate_logs
search_login_logs
search_feedback
get_usage_statistics
check_config_completeness
```

**草稿工具**：

```text
draft_notice
draft_email
draft_feedback_reply
draft_config_change
draft_plugin_operation
```

**高风险工具**：

```text
disable_provider
update_config
change_user_status
change_role_permission
delete_file
publish_notice
send_email
```

高风险工具第一阶段只生成操作计划和确认卡片，不自动执行。

### 7.2 管理助手流程

```text
管理员提问
    ↓
权限检查与智能体检查
    ↓
模型生成只读查询计划
    ↓
服务端校验工具和参数
    ↓
执行只读工具
    ↓
模型总结结果并附数据来源
```

涉及变更时：

```text
管理员提出操作意图
    ↓
生成操作计划
    ↓
展示目标、字段、影响范围和风险
    ↓
管理员确认
    ↓
服务端重新校验当前权限和对象状态
    ↓
使用幂等工具执行
    ↓
写入审计记录
    ↓
返回执行结果
```

管理员不能通过 AI 绕过现有控制器权限。AI 工具内部必须调用业务 Service 的公开能力，并再次执行对象级权限校验。

### 7.3 管理助手回答要求

涉及系统状态、日志、统计和配置的问题，回答应带结构化来源：

```json
{
  "answer": "最近一小时登录失败主要集中在密码错误。",
  "sources": [
    {
      "type": "login_log",
      "label": "登录日志",
      "queryTimeRange": "最近一小时",
      "summary": "失败 32 次，其中密码错误 27 次"
    }
  ]
}
```

如果工具没有足够数据，助手必须明确说明未知，不能编造系统状态。

## 8. 会话、请求和完整 AI 流

### 8.1 核心状态模型

`ai_request` 表示一次完整的模型任务，不等同于一条消息。建议状态：

```text
QUEUED
RUNNING
WAITING_TOOL
WAITING_APPROVAL
SUCCEEDED
FAILED
CANCELLED
```

`ai_message` 保存用户消息、助手消息、工具消息和系统消息。为支持编辑后重试、重新生成和分支会话，消息应保留 `parent_message_id`。

### 8.2 统一事件

客户端消费统一事件模型：

```text
request.created
message.started
message.delta
message.metadata
tool.planned
tool.started
tool.progress
tool.approval_required
tool.completed
recommendation.updated
message.citation
message.completed
request.cancelled
request.failed
```

事件至少包含：

```json
{
  "requestId": "...",
  "eventId": "...",
  "sequence": 12,
  "type": "message.delta",
  "data": {},
  "createdAt": "..."
}
```

`sequence` 用于断线恢复，客户端保存最后消费位置。

### 8.3 传输方式

统一后端事件模型，按客户端采用不同传输方式：

| 客户端 | 第一阶段 | 后续优化 |
|---|---|---|
| Astral 管理端 | SSE | SSE + 断线恢复 |
| qt-uniappx | 创建请求后轮询事件 | 长轮询或 WebSocket |
| qt-pc | Rust 轮询/流式读取后转 Tauri event | Rust HTTP 流 + Tauri event |

App 不依赖浏览器原生 SSE。建议接口：

```text
POST /api/v1/app/ai/conversations/{id}/messages
GET  /api/v1/app/ai/requests/{requestId}/events?after=sequence
POST /api/v1/app/ai/requests/{requestId}/cancel
```

管理端可增加：

```text
POST /api/v1/admin/ai/conversations/{id}/messages
GET  /api/v1/admin/ai/requests/{requestId}/stream
POST /api/v1/admin/ai/requests/{requestId}/cancel
```

发送消息接口先返回：

```json
{
  "requestId": "request-id",
  "userMessageId": "user-message-id",
  "assistantMessageId": "assistant-message-id",
  "status": "QUEUED",
  "stream": true
}
```

### 8.4 取消、重试和幂等

- 客户端生成 `idempotencyKey`，服务端按用户、智能体和 Key 去重。
- 取消请求只允许会话所有者或拥有管理审计权限的管理员执行。
- Provider 已经产生的费用不能因为客户端取消而假设为零。
- 已产生的增量内容必须保留，失败消息标记为 `FAILED` 并保存错误摘要。
- 重试应创建新的 `ai_request`，不能覆盖旧请求的审计数据。
- 工具调用必须有独立幂等键，避免模型重试造成重复收藏、重复发信或重复配置修改。

## 9. 权限模型

### 9.1 插件权限

AI 插件建议实现 `PermissionProvider`，声明以下权限：

```text
user:ai:music:use
user:ai:conversation:view
user:ai:conversation:edit
user:ai:music:favorite
user:ai:music:playlist

admin:ai:config:view
admin:ai:provider:view
admin:ai:provider:edit
admin:ai:model:view
admin:ai:model:edit
admin:ai:route:view
admin:ai:route:edit
admin:ai:agent:view
admin:ai:agent:edit
admin:ai:agent:publish
admin:ai:prompt:view
admin:ai:prompt:edit
admin:ai:knowledge:view
admin:ai:knowledge:edit
admin:ai:knowledge:sync
admin:ai:quota:view
admin:ai:quota:edit
admin:ai:usage:view
admin:ai:request:view
admin:ai:request:cancel
admin:ai:conversation:view
admin:ai:conversation:content:view
admin:ai:audit:view
admin:ai:tool:view
admin:ai:tool:approve
admin:ai:tool:edit
admin:ai:assistant:use
admin:ai:qa:use
admin:ai:qa:export
```

权限编码必须遵守 `端:域:资源:操作[:范围]` 规范。App 用户默认拥有全部 `user:` 权限，但智能体、额度和工具仍需要业务层检查。

### 9.2 多层授权判断

每次 AI 请求至少检查：

```text
插件是否启用
智能体是否已发布且面向当前客户端
当前用户是否持有该智能体的基础权限
当前用户/角色是否被允许使用该智能体
请求客户端类型是否与 agentVersion.clientTypes 匹配
模型是否启用且在该版本允许范围内
Prompt、工具和知识源版本是否仍可用
额度是否足够
并发是否超限
工具是否允许且满足确认策略
会话是否属于当前用户且绑定同一智能体
文件是否有 READ 权限
```

管理端必须分别检查 `admin:ai:assistant:use` 和 `admin:ai:qa:use`。答疑权限不能推导出管理助手权限，管理助手权限也不能推导出 Provider、工具配置或知识源管理权限。App 用户的所有音乐工具调用只能使用 `user:` 端工具；`qt-pc` 与 `qt-uniappx` 共享同一组 App 权限和额度规则。

### 9.3 会话数据权限

- 普通用户只能查看自己的会话和消息。
- 管理员不能因为拥有 `admin:ai:request:view` 就默认查看所有完整对话内容。
- 查看用户完整会话需要单独权限，并写入审计日志。
- 删除会话只删除或标记当前用户有权删除的数据。
- 管理助手查询用户信息时只返回完成问题所需的最小字段。

## 10. 使用限制、额度和成本

### 10.1 限制维度

管理端可以按全局、角色、用户和智能体配置：

```text
是否可使用
每日请求次数
每月请求次数
每日输入 Token
每月输入 Token
每日输出 Token
每月输出 Token
单次最大输入字符
最大上下文 Token
最大输出 Token
并发请求数
允许的模型范围
允许的工具范围
允许的附件大小
月度费用上限
```

限制优先级：

```text
全局安全上限 > 智能体上限 > 角色策略 > 用户例外策略
```

不能通过用户策略突破全局安全上限。

### 10.2 Redis 与数据库分工

Redis 用于：

- 短周期请求限流。
- 并发锁。
- 当日计数。
- 请求取消标记。
- 幂等 Key。
- 流事件短期缓存。

PostgreSQL 用于：

- 长期额度策略。
- 用量明细。
- 费用统计。
- 审计数据。
- 月度汇总。

扣减额度应采用预占和结算两步：

```text
请求开始：预占预计 Token/费用
请求完成：按真实用量结算，多退少补
请求失败：释放未使用预占额度
```

并发和扣减操作必须保证原子性。

## 11. 数据模型

建议插件表：

```text
ai_provider
ai_model
ai_provider_route
ai_agent
ai_agent_version
ai_agent_access_policy
ai_prompt_template
ai_prompt_version
ai_knowledge_source
ai_knowledge_document
ai_knowledge_chunk
ai_knowledge_index_job
ai_agent_knowledge
ai_tool
ai_agent_tool
ai_conversation
ai_message
ai_request
ai_request_event
ai_tool_call
ai_usage
ai_quota_policy
ai_user_quota
ai_feedback
```

配置关系与请求关系：

```text
ai_provider 1 ── n ai_model
ai_provider_route n ── 1 ai_model（主模型），并通过路由配置关联多个备用模型
ai_agent 1 ── n ai_agent_version
ai_agent 1 ── n ai_agent_access_policy
ai_agent_version 1 ── n ai_prompt_version / ai_agent_tool / ai_agent_knowledge
ai_knowledge_source 1 ── n ai_knowledge_document
ai_knowledge_document 1 ── n ai_knowledge_chunk
ai_knowledge_source 1 ── n ai_knowledge_index_job
ai_agent_knowledge n ── 1 ai_knowledge_source
ai_conversation 1 ── n ai_message
ai_message 1 ── n ai_request
ai_request 1 ── n ai_request_event / ai_tool_call / ai_usage
```

`ai_agent_tool` 和 `ai_agent_knowledge` 是绑定表，不把工具或知识源直接写进智能体主表。这样同一个代码实现可以被多个智能体引用，但每个智能体版本仍可配置自己的参数、排序、过滤范围和确认策略。

### 11.1 `ai_provider_route`

```text
id
route_code
name
primary_model_id
fallback_model_ids
selection_strategy           FIXED / WEIGHTED / HEALTH_FIRST
retry_policy_json
condition_json
enabled
create_time
update_time
```

### 11.2 `ai_agent_access_policy`

```text
id
agent_id
subject_type                 ROLE / USER / CLIENT_TYPE
subject_id
allow_use
quota_policy_id
allowed_tool_codes
allowed_knowledge_source_ids
create_time
update_time
```

授权策略只能进一步收窄智能体版本权限，不能扩大全局工具风险级别。用户和角色授权仍通过 Astral RBAC；该表用于智能体级开关、客户端范围和特殊额度覆盖。

### 11.3 `ai_knowledge_source` 与 `ai_knowledge_document`

```text
ai_knowledge_source:
id
source_code
name
source_type                  PROJECT_DOCUMENT / API_DOCUMENT / PLUGIN_DOCUMENT / DEPLOYMENT_DOCUMENT / RELEASE_NOTE / FAQ / LIVE_READONLY_DATA
version
status
visibility_policy_json
retrieval_config_json
create_time
update_time

ai_knowledge_document:
id
source_id
title
path
section
document_version
content_hash
content
status
indexed_at
create_time
update_time
```

`content` 可以按部署策略存数据库或对象存储，但模型只接收经过权限过滤、长度限制和 Prompt Injection 清洗后的片段。文档更新产生新版本，历史请求仍保留引用的版本号和内容哈希。

### 11.4 `ai_knowledge_chunk` 与 `ai_knowledge_index_job`

```text
ai_knowledge_chunk:
id
document_id
source_version
chunk_no
section_path
content
content_hash
token_count
embedding_ref
status
create_time
update_time

ai_knowledge_index_job:
id
source_id
target_version
status
mode                       FULL / INCREMENTAL / DELETE
total_documents
processed_documents
failed_documents
error_summary
started_at
finished_at
create_time
update_time
```

`ai_knowledge_chunk` 是检索和引用的最小内容单元。`embedding_ref` 可以为空，首期允许使用 PostgreSQL 全文检索或代码实现的关键词检索；向量模型和向量存储必须通过 Provider/索引适配器接入，不能把向量库地址写死在答疑智能体中。索引任务必须可重试、可取消，并且只有完成且通过权限校验的文档版本才能被已发布智能体使用。

### 11.5 `ai_tool`

```text
id
tool_code                  全局唯一的代码注册名
name
description
handler_key                代码注册的安全处理器标识
client_types               允许的客户端类型
permission_codes           执行工具所需的声明式权限
risk_level                 READ / DRAFT / HIGH
approval_policy            NONE / USER_CONFIRM / ADMIN_CONFIRM
arguments_schema
result_schema
side_effects
status                     ENABLED / DISABLED
create_time
update_time
```

`handler_key` 只对应代码中登记的工具实现，后台不能创建任意 Java 类、Shell 命令、SQL 或脚本入口。工具注册表提供全局安全下限，智能体版本只能选择已登记工具并进一步收窄其参数、范围和确认策略。

### 11.6 `ai_agent_tool`

```text
id
agent_version_id
tool_id
enabled
sort
risk_override
approval_policy
arguments_schema_override
result_schema_override
create_time
update_time
```

`risk_override` 只能提高风险等级或增加确认要求，不能降低代码定义的最低风险等级。

### 11.7 `ai_agent_knowledge`

```text
id
agent_version_id
knowledge_source_id
enabled
priority
retrieval_top_k
min_score
citation_policy
filter_policy_json
create_time
update_time
```

知识源绑定同样属于智能体版本快照。发布后修改知识源绑定只影响新版本，不能改变已开始请求的检索范围。

### 11.8 `ai_conversation`

```text
id
user_id
client_type
agent_id
title
summary
status
last_message_time
create_time
update_time
```

### 11.9 `ai_message`

```text
id
conversation_id
parent_message_id
request_id
role                    SYSTEM / USER / ASSISTANT / TOOL
content
structured_content
status                  STREAMING / COMPLETED / FAILED
agent_version_id
model_id
prompt_version_id
knowledge_snapshot
input_tokens
output_tokens
create_time
update_time
```

### 11.10 `ai_request`

```text
id
conversation_id
user_id
agent_id
agent_version_id
model_id
provider_id
prompt_version_id
knowledge_snapshot
configuration_snapshot
idempotency_key
status
input_snapshot
context_snapshot
error_code
error_summary
started_at
first_token_at
finished_at
create_time
```

`input_snapshot`、`context_snapshot`、`knowledge_snapshot` 和 `configuration_snapshot` 必须经过脱敏和保留策略控制，不能无限制保存敏感内容。`configuration_snapshot` 至少包含模型参数、工具策略、客户端入口和额度快照。

### 11.11 `ai_request_event`

```text
id
request_id
sequence
event_type
payload
created_at
```

`request_id + sequence` 唯一。事件 payload 需要限制大小，长文本主要存消息表，事件只保存增量或引用。

### 11.12 `ai_tool_call`

```text
id
request_id
tool_code
arguments
arguments_hash
status
risk_level
approval_required
approved_by
approved_at
result_summary
error_summary
started_at
finished_at
```

原始工具结果可能包含敏感数据，应按工具类型设置脱敏和保留期限。

### 11.13 `ai_usage`

```text
id
request_id
user_id
agent_id
agent_version_id
provider_id
model_id
input_tokens
output_tokens
estimated_cost
currency
reservation_amount
settled_amount
status                     RESERVED / SETTLED / RELEASED
recorded_at
create_time
```

`ai_usage` 记录一次请求的额度预占和最终结算。预占、结算、释放必须具备幂等键，重试请求不能重复计费。

### 11.14 `ai_quota_policy` 与 `ai_user_quota`

```text
ai_quota_policy:
id
policy_code
name
scope_type                 GLOBAL / AGENT / ROLE / USER
agent_id
role_id
user_id
daily_requests
monthly_requests
daily_input_tokens
monthly_input_tokens
daily_output_tokens
monthly_output_tokens
max_input_chars
max_context_tokens
max_output_tokens
max_concurrent
monthly_cost_limit
enabled
create_time
update_time

ai_user_quota:
id
user_id
agent_id
period_start
period_end
request_count
input_tokens
output_tokens
reserved_cost
settled_cost
version
create_time
update_time
```

`ai_user_quota` 用于原子计数和结算快照，策略变更不回写历史用量。所有额度计算都必须同时带 `agent_id`，避免同一用户在不同智能体之间错误共享额度。

### 11.15 `ai_feedback`

```text
id
user_id
agent_id
agent_version_id
conversation_id
message_id
feedback_type               LIKE / DISLIKE / REGENERATE / REPORT
reason_code
comment
metadata_json
create_time
```

反馈只用于分析和评测，不自动修改 Prompt、权限、额度或工具配置。敏感反馈内容按会话保留策略处理。

### 11.16 Schema 与迁移

新增表必须同步对应的表结构 JSON，并可在 `/api/v1/admin/system/table-schema` 查询。建议至少创建以下插件 Schema 文件：

```text
astral-plugin/src/main/resources/schema/ai_provider.json
astral-plugin/src/main/resources/schema/ai_model.json
astral-plugin/src/main/resources/schema/ai_provider_route.json
astral-plugin/src/main/resources/schema/ai_agent.json
astral-plugin/src/main/resources/schema/ai_agent_version.json
astral-plugin/src/main/resources/schema/ai_agent_access_policy.json
astral-plugin/src/main/resources/schema/ai_prompt_template.json
astral-plugin/src/main/resources/schema/ai_prompt_version.json
astral-plugin/src/main/resources/schema/ai_knowledge_source.json
astral-plugin/src/main/resources/schema/ai_knowledge_document.json
astral-plugin/src/main/resources/schema/ai_knowledge_chunk.json
astral-plugin/src/main/resources/schema/ai_knowledge_index_job.json
astral-plugin/src/main/resources/schema/ai_agent_knowledge.json
astral-plugin/src/main/resources/schema/ai_tool.json
astral-plugin/src/main/resources/schema/ai_agent_tool.json
astral-plugin/src/main/resources/schema/ai_conversation.json
astral-plugin/src/main/resources/schema/ai_message.json
astral-plugin/src/main/resources/schema/ai_request.json
astral-plugin/src/main/resources/schema/ai_request_event.json
astral-plugin/src/main/resources/schema/ai_tool_call.json
astral-plugin/src/main/resources/schema/ai_usage.json
astral-plugin/src/main/resources/schema/ai_quota_policy.json
astral-plugin/src/main/resources/schema/ai_user_quota.json
astral-plugin/src/main/resources/schema/ai_feedback.json
```

数据库迁移放在：

```text
astral-server/src/main/resources/db/migration/V{yyyyMMddNNN}__ai_*.sql
```

建议先由一条初始化迁移创建基础表，再使用后续增量迁移写入 `music_assistant`、`admin_assistant`、`admin_qa` 三个默认智能体、权限和数据字典。默认智能体种子只能提供初始配置，后续行为必须走后台版本发布。所有实体 ID 遵守 Astral 的全局序列和 MyBatis-Plus 自动填充约定。

建议登记以下数据字典：

```text
ai_agent_type
ai_agent_status
ai_agent_version_status
ai_client_type
ai_provider_type
ai_model_kind
ai_tool_risk_level
ai_tool_approval_policy
ai_knowledge_source_type
ai_knowledge_status
ai_request_status
ai_message_status
ai_index_job_status
ai_feedback_type
```

管理端和客户端展示的状态、类型、平台、风险级别和审批策略必须通过 `fetchDictOptions` / `dictApi.byCode` 获取；服务端内部状态机仍使用常量。数据字典只提供文案和选项，不替代发布校验、权限校验和安全硬上限。

## 12. Prompt 与版本管理

Prompt 不能硬编码在 Controller 或 Service 中。建议：

```text
ai_prompt_template
ai_prompt_version
```

每个版本包含：

```text
template_id
version
system_prompt
variables_schema
model_parameters
tool_policy
status                  DRAFT / PUBLISHED / ARCHIVED
published_by
published_at
```

一次请求必须记录实际使用的智能体版本、Prompt 版本、模型版本、工具配置版本和知识源版本，确保可以追溯“当时为什么这样回答”。

Prompt 约束：

- 外部文件、音源搜索结果、检索文档和工具结果统一标记为不可信数据。
- 文档内容不能覆盖系统指令。
- 模型不能自行扩大工具权限。
- 管理助手必须声明数据不足时不能编造。
- 答疑助手必须区分文档依据和实时系统依据，不能编造引用。
- 音乐助手必须优先返回标准化歌曲结构，不生成播放 URL。

## 13. 前端产品设计

### 13.1 App 音乐助手页面

建议页面结构：

```text
AI 入口页
├── 快捷问题
│   ├── 推荐适合当前场景的音乐
│   ├── 根据当前歌曲推荐
│   ├── 解释当前歌词
│   └── 帮我生成歌单
├── 最近会话
└── 当前额度状态

会话页
├── 消息流
├── 工具进度
├── 歌曲/歌单结果卡片
├── 推荐理由
├── 播放/收藏/加队列动作
├── 停止生成
└── 重试/分支/删除
```

视觉上保持轻量和克制，避免在首页增加大面积装饰性 AI Banner。入口优先放在搜索页、当前歌曲操作菜单、歌单页和个人页的功能性位置。

### 13.2 管理助手页面

管理端在 `/dashboard/plugin/ai/assistant` 提供管理助手入口，在管理首页只保留一个低干扰的快捷入口。页面包括：

- 对话列表。
- 问题输入框。
- 流式 Markdown 回复。
- 工具调用进度。
- 数据来源和查询时间范围。
- 待确认操作卡片。
- 当前智能体版本、额度和权限提示。
- 复制、导出、反馈和重试。

### 13.3 管理端独立答疑页面

管理端在 `/dashboard/plugin/ai/qa` 提供独立答疑功能。它复用通用会话和流式消息组件，但必须以 `agentCode=admin_qa` 创建会话，不能复用管理助手的会话数据或运行时配置。

答疑页面包括：

- 文档/接口/部署/功能问题快捷提问。
- 流式 Markdown 回复。
- 引用列表，显示文档标题、路径、章节和版本。
- “来自文档”与“来自实时系统查询”的来源标识。
- 文档版本不匹配或资料不足时的提示。
- 复制回答、反馈、重新生成和清空会话。
- 当前答疑智能体版本、额度和可用范围提示。

答疑页面不显示管理助手的写操作按钮、审批卡片和高风险工具列表。若后台将某个自定义智能体发布到答疑入口，页面根据能力清单渲染其允许的只读块，不把任意配置直接暴露为前端代码。

### 13.4 qt-pc 音乐助手页面

`qt-pc` 提供与 `qt-uniappx` 等价的音乐助手功能，不单独实现一套普通聊天页面。PC 页面需要支持：

- `music_assistant` 能力清单和快捷问题。
- 当前播放歌曲、队列和歌单上下文。
- 多音源搜索进度和部分结果。
- 歌曲、专辑、歌手、歌单结构化卡片。
- 播放、队列、收藏和打开页面动作。
- 会话历史、流式输出、停止、取消、重试和反馈。

Rust 负责网络请求、Token 会话、事件读取和 Tauri event 转发，React 只消费统一事件和结构化消息。PC 端不能把 Provider、Prompt 或工具权限写进前端 Bundle。

客户端至少渲染：

```text
理解问题中
查询数据中
搜索多个音乐平台
整理推荐结果
等待确认
正在生成回答
已完成
请求失败
已取消
```

流式 Markdown、结构化卡片和工具事件必须分开渲染，不能把结构化 JSON 直接显示给用户。

## 14. 安全设计

必须满足以下要求：

1. API Key 加密存储，永不进入 Git、前端、普通日志和错误消息。
2. Provider URL 做 SSRF 校验，禁止访问未授权内网地址。
3. 模型请求设置连接、读取、总耗时和响应体上限。
4. 请求内容、工具结果和模型输出全部限制大小。
5. 会话、文件和工具结果按用户权限隔离。
6. 高风险工具必须二次确认，并重新校验权限和对象版本。
7. 工具参数使用白名单 DTO 和 Bean Validation，不接收任意 Map 直接下沉业务层。
8. 不允许 AI 执行任意 SQL、Shell、JavaScript 或文件系统路径。
9. 文件分析只接收 `fileId`，服务端校验用户 READ 权限后再受控读取。
10. 不将 Telegram 定位信息、私有签名 URL、对象存储密钥发送给客户端或模型。
11. Provider 请求按配置决定是否保存正文；默认审计只保存摘要、哈希、Token 和错误信息。
12. 支持会话删除和保留期限，敏感内容按策略清理。
13. 防止 Prompt Injection：外部内容只作为数据，不能成为系统指令。
14. 管理员查看用户完整会话必须拥有独立权限并记录审计。
15. 模型失败、工具失败和客户端断线不能导致额度重复扣减或工具重复执行。

## 15. 观测、审计和反馈

每次请求至少记录：

```text
requestId
userId
clientType
agentId
agentVersionId
providerId
modelId
promptVersion
knowledgeSnapshot
inputTokens
outputTokens
estimatedCost
queueLatencyMs
firstTokenLatencyMs
totalLatencyMs
finishReason
errorCode
```

答疑请求还应记录检索的知识源、文档版本、命中文档哈希和引用数量，便于复核回答依据。

管理端统计：

- 请求量和成功率。
- 首 Token 延迟。
- 完整响应耗时。
- Token 消耗和费用估算。
- 各 Provider/模型错误率。
- 429、超时、熔断次数。
- 用户和角色额度消耗。
- 工具调用成功率。
- 待审批和失败任务数量。

消息支持：

```text
LIKE
DISLIKE
REGENERATE
REPORT
```

反馈只用于评估和改进，不自动修改 Prompt 或权限。

## 16. API 草案

所有会话接口使用统一协议。客户端提交 `agentCode`，服务端根据当前客户端类型、用户权限和已发布版本解析实际配置；客户端不能提交 Provider、模型、Prompt 或工具列表覆盖后台配置。`agentCode` 不是业务分支开关，而是智能体配置的稳定外部标识。

### 16.1 App 接口（qt-uniappx 与 qt-pc 共用）

```text
GET    /api/v1/app/ai/agents
GET    /api/v1/app/ai/capabilities?agentCode={agentCode}
GET    /api/v1/app/ai/conversations?agentCode={agentCode}
POST   /api/v1/app/ai/conversations
GET    /api/v1/app/ai/conversations/{id}
DELETE /api/v1/app/ai/conversations/{id}
GET    /api/v1/app/ai/conversations/{id}/messages
POST   /api/v1/app/ai/conversations/{id}/messages
GET    /api/v1/app/ai/requests/{id}/events?after={sequence}
POST   /api/v1/app/ai/requests/{id}/cancel
POST   /api/v1/app/ai/messages/{id}/feedback
POST   /api/v1/app/ai/actions/execute
```

创建会话请求：

```json
{
  "agentCode": "music_assistant",
  "clientType": "QT_UNIAPPX",
  "title": "夜间驾驶歌单"
}
```

`qt-pc` 只将 `clientType` 换成 `QT_PC`，其余会话、消息、事件、动作和错误结构完全一致。音乐搜索候选、上下文和动作分别由两端适配器提供，但使用同一套 Schema。

发送消息请求：

消息接口从会话绑定的 `agent_id` 和 `agent_version_id` 解析配置；请求体不允许修改智能体。客户端可以重复提交 `agentCode` 作为幂等校验字段，但服务端必须要求它与会话绑定的智能体一致，不一致直接返回 `AI012`。

```json
{
  "content": "根据当前歌曲推荐一些节奏相近的音乐",
  "context": {
    "currentSong": {},
    "installedSourceRegistry": []
  },
  "idempotencyKey": "client-generated-key"
}
```

`context` 只能包含能力清单返回的允许字段；服务端会按 `context_policy_json` 再次过滤。`actions/execute` 只接受服务端签发或服务端可验证的结构化动作，不接受模型原始文本。PC 和 App 都必须在本地再次校验动作类型和当前资源状态。

### 16.2 管理端使用接口

管理助手和答疑助手共用以下通用会话接口，但必须显式指定不同的 `agentCode`：

```text
GET    /api/v1/admin/ai/agents
GET    /api/v1/admin/ai/capabilities?agentCode={agentCode}
GET    /api/v1/admin/ai/conversations?agentCode={agentCode}
POST   /api/v1/admin/ai/conversations
GET    /api/v1/admin/ai/conversations/{id}
DELETE /api/v1/admin/ai/conversations/{id}
GET    /api/v1/admin/ai/conversations/{id}/messages
POST   /api/v1/admin/ai/conversations/{id}/messages
GET    /api/v1/admin/ai/requests/{id}/stream
GET    /api/v1/admin/ai/requests/{id}/events?after={sequence}
POST   /api/v1/admin/ai/requests/{id}/cancel
POST   /api/v1/admin/ai/messages/{id}/feedback
POST   /api/v1/admin/ai/actions/execute
```

`/dashboard/plugin/ai/assistant` 只能创建 `admin_assistant` 或后台授权的管理类自定义智能体会话；`/dashboard/plugin/ai/qa` 只能创建 `admin_qa` 或后台授权的答疑类自定义智能体会话。这个限制由服务端的 `clientTypes`、入口配置和权限策略共同判断，不能只靠前端路由隐藏。

答疑会话需要额外返回引用信息：

```json
{
  "messageId": "message-id",
  "structuredContent": {
    "blocks": [],
    "citations": [
      {
        "sourceId": "doc-id",
        "sourceType": "PROJECT_DOCUMENT",
        "title": "插件开发指南",
        "path": "PLUGIN_GUIDE.md",
        "section": "权限声明",
        "version": "2026.10.1",
        "matchedText": "插件权限必须通过 PermissionProvider 声明。"
      }
    ]
  }
}
```

### 16.3 后台配置与发布接口

```text
GET    /api/v1/admin/ai/providers
POST   /api/v1/admin/ai/providers
PUT    /api/v1/admin/ai/providers/{id}
DELETE /api/v1/admin/ai/providers/{id}
POST   /api/v1/admin/ai/providers/{id}/test

GET    /api/v1/admin/ai/models
POST   /api/v1/admin/ai/models
PUT    /api/v1/admin/ai/models/{id}
DELETE /api/v1/admin/ai/models/{id}
GET    /api/v1/admin/ai/routes
POST   /api/v1/admin/ai/routes
PUT    /api/v1/admin/ai/routes/{id}

GET    /api/v1/admin/ai/agents
POST   /api/v1/admin/ai/agents
GET    /api/v1/admin/ai/agents/{id}
PUT    /api/v1/admin/ai/agents/{id}
DELETE /api/v1/admin/ai/agents/{id}
GET    /api/v1/admin/ai/agents/{id}/versions
POST   /api/v1/admin/ai/agents/{id}/versions
GET    /api/v1/admin/ai/agent-versions/{versionId}
PUT    /api/v1/admin/ai/agent-versions/{versionId}
POST   /api/v1/admin/ai/agent-versions/{versionId}/validate
POST   /api/v1/admin/ai/agent-versions/{versionId}/publish
POST   /api/v1/admin/ai/agents/{id}/rollback
POST   /api/v1/admin/ai/agents/{id}/disable

GET    /api/v1/admin/ai/prompts
POST   /api/v1/admin/ai/prompts
PUT    /api/v1/admin/ai/prompts/{id}
POST   /api/v1/admin/ai/prompts/{id}/publish
GET    /api/v1/admin/ai/knowledge/sources
POST   /api/v1/admin/ai/knowledge/sources
PUT    /api/v1/admin/ai/knowledge/sources/{id}
GET    /api/v1/admin/ai/knowledge/sources/{id}/documents
POST   /api/v1/admin/ai/knowledge/sources/{id}/documents
PUT    /api/v1/admin/ai/knowledge/documents/{id}
POST   /api/v1/admin/ai/knowledge/sources/{id}/sync
GET    /api/v1/admin/ai/knowledge/index-jobs/{jobId}
GET    /api/v1/admin/ai/agents/{id}/knowledge
PUT    /api/v1/admin/ai/agent-versions/{versionId}/knowledge
GET    /api/v1/admin/ai/tools
PUT    /api/v1/admin/ai/tools/{id}
GET    /api/v1/admin/ai/quotas
PUT    /api/v1/admin/ai/quotas
GET    /api/v1/admin/ai/usage
GET    /api/v1/admin/ai/requests
GET    /api/v1/admin/ai/requests/{id}
POST   /api/v1/admin/ai/tool-calls/{id}/approve
GET    /api/v1/admin/ai/audit
```

配置接口只保存草稿或版本，不直接修改运行中的请求快照。发布接口必须执行模型、Prompt、Schema、工具、知识源、权限、额度和客户端入口校验。所有写接口使用现有 CSRF、认证、声明式权限和操作日志机制。

### 16.4 请求体和错误约定

统一错误码至少区分：

```text
AI001  插件未启用
AI002  智能体不存在或未发布
AI003  当前客户端不可用
AI004  当前用户无智能体权限
AI005  额度不足
AI006  Provider/模型不可用
AI007  智能体版本配置无效
AI008  工具未授权或参数无效
AI009  需要用户/管理员确认
AI010  请求已取消
AI011  事件游标已过期
AI012  会话绑定的智能体不一致
AI013  知识库文档不存在或未完成索引
AI014  AI 请求不存在或无权访问
AI015  会话不存在或无权访问
```

错误响应仍使用 Astral 统一 `Result` 结构，不将 Provider 原始错误、API Key、内部 URL 或 Prompt 内容返回给客户端。

## 17. 分阶段开发计划

### 阶段一：平台底座与后台配置

交付：

- `AiPlugin` 注册、启停和精确 API 前缀。
- Provider、模型、路由、智能体和智能体版本 CRUD。
- API Key 加密和编辑留空不修改。
- Prompt 模板与版本、知识源与文档版本。
- 工具注册、智能体工具绑定和风险下限。
- 智能体客户端入口、RBAC 授权和独立额度策略。
- 发布前配置校验、版本发布、回滚和禁用。
- 管理端智能体配置页面和测试对话。
- 通用能力清单接口、配置测试会话和统一对话接口。

验收：

- 管理员可以创建一个不改代码的新自定义智能体，配置后发布即可被授权客户端发现。
- 管理员可以分别配置 `music_assistant`、`admin_assistant` 和 `admin_qa` 的模型、Prompt、工具、知识源、权限和额度。
- 插件可从后台启停，禁用后只拦截 AI 接口。
- 密钥不出现在响应和日志。
- Provider 测试成功/失败信息可识别。
- 未发布或版本校验失败的智能体不能被客户端调用。

### 阶段二：音乐助手 MVP（qt-uniappx 与 qt-pc 同步交付）

交付：

- App 和 PC 的 `music_assistant` 能力清单、入口和会话页面。
- 同一套自然语言搜索计划、候选歌曲 Schema 和推荐结果结构。
- qt-uniappx UTS 音源搜索与 qt-pc 音源脚本搜索适配器。
- 两端当前歌曲、队列、歌单上下文。
- 歌曲卡片、播放、加队列、收藏和打开页面动作。
- 会话历史、反馈、删除、停止和重试。

验收：

- qt-uniappx 和 qt-pc 都可以用自然语言跨多个已安装音源搜索。
- 两端展示相同的结构化推荐能力，传输方式不同不影响业务功能。
- 搜索结果不包含服务端生成的虚假播放链接。
- 播放动作只执行白名单参数。
- 音源不可用时可以显示部分结果和明确原因。
- 用户没有权限或额度不足时不能调用模型。

### 阶段三：完整 AI 流

交付：

- `ai_request` 和 `ai_request_event`。
- 管理端 SSE。
- qt-uniappx 事件轮询或长轮询。
- qt-pc Rust 事件转发为 Tauri event。
- 停止、取消、重试、断线恢复。
- 工具进度和失败事件。
- 请求幂等与额度预占结算。

验收：

- 三端刷新或断线后可以恢复未完成请求。
- 客户端断线不会重复扣费。
- 取消后 Provider 和工具不会继续产生新的副作用。
- 流式失败时已生成内容可恢复查看。

### 阶段四：管理助手与独立答疑 MVP

交付：

- `/dashboard/plugin/ai/assistant` 管理助手会话页。
- `/dashboard/plugin/ai/qa` 独立答疑会话页。
- 管理助手的系统、插件、日志、反馈和统计只读工具。
- 管理助手的配置检查、数据来源和公告/邮件/反馈草稿。
- 答疑助手的文档、接口、部署、字典和错误码检索工具。
- 答疑知识源、文档版本、引用结构和实时只读查询开关。
- 三类智能体独立的权限、额度、会话和审计。

验收：

- 管理助手和答疑助手分别拥有独立的 Prompt、模型、知识源、工具、权限和额度，并可独立发布；两者的会话和审计数据按智能体隔离。
- 答疑页面只能使用 `admin_qa` 或后台授权的答疑类智能体，不能调用管理助手的高风险工具。
- 答疑回答能够返回可核验的文档标题、路径、章节和版本。
- 管理助手只读问题能返回可核验的数据来源。
- 没有权限的数据不会出现在工具结果中。
- 草稿不会自动发布或发送。
- 工具失败、文档冲突和数据不足会明确反馈。
- 答疑检索只使用已发布且完成索引的知识版本，引用中的来源类型、路径、章节、版本和命中文本可被服务端复核。
- `admin_qa` 不继承 `admin_assistant` 的写工具、审批权限或会话数据；实时只读查询仍按工具声明的最小权限执行。
- 新建答疑类自定义智能体无需修改客户端业务代码，发布后即可通过能力清单接入答疑入口。

### 阶段五：审批与平台能力

交付：

- 高风险工具审批。
- 文件分析和知识库增强。
- Prompt A/B 评测。
- 模型路由和备用模型。
- 图片理解。
- 团队共享和预算控制。
- MCP 或外部工具适配。

## 18. 测试与验收范围

后端：

- Provider 路由、超时、重试和熔断。
- 会话数据隔离。
- 权限、额度和并发限制。
- 请求幂等、取消和断线恢复。
- 工具参数校验和审批。
- API Key 脱敏。
- Prompt Injection 和 SSRF 防护。
- 音乐搜索计划 Schema 校验。

管理端：

- Provider 和模型配置。
- 智能体发布和下线。
- 权限控制。
- 额度编辑。
- SSE 增量渲染。
- 工具审批卡片。
- 深色主题和窄屏布局。

App：

- 登录、登出和 Token 失效。
- 音乐上下文采集。
- 多音源部分成功。
- 事件轮询、取消和恢复。
- 歌曲卡片动作校验。
- Vapor 编译和紧凑/宽屏布局。

PC：

- Rust Token 续期。
- IPC 参数和错误转换。
- Tauri 事件流。
- 当前播放上下文。
- 本地数据默认不外传。

## 19. 暂不实现的范围

以下能力不进入首个可用版本：

- 任意 SQL 或 Shell 执行。
- 任意代码解释执行。
- 自动删除用户、文件或权限。
- 自动发布公告或发送邮件。
- 服务端直接抓取第三方音乐平台播放地址。
- 未经确认上传本地音频和歌词。
- 无限制的多 Agent 自主循环。
- 未经权限同步的共享知识库。
- 将模型原始输出直接当作客户端指令执行。

## 20. 完成定义

当以下条件全部满足时，才认为 AI 模块完成了基础融合：

1. 管理端可以配置 Provider、模型、智能体、Prompt、权限和额度。
2. App 用户可以使用音乐助手自然语言搜索多个音源并获得可播放的结构化推荐。
3. 管理员可以使用管理助手查询 Astral 系统和业务信息。
4. `music_assistant`、`admin_assistant` 和 `admin_qa` 都支持会话历史、流式输出、停止、失败恢复和反馈；其中 `music_assistant` 在 qt-uniappx 与 qt-pc 提供相同业务功能。
5. 所有请求都经过权限、额度、并发和智能体发布状态检查。
6. 所有工具调用都有白名单、参数校验和审计记录。
7. 高风险操作必须经过管理员确认。
8. API Key、私有文件信息、用户隐私和平台凭据不会泄露给客户端或日志。
9. App、管理端和 PC 使用同一套请求、事件、状态和审计模型。
10. 后端测试、管理端类型检查/构建、App 编译和 PC Rust 测试均通过。
11. 管理端答疑页面独立使用 `admin_qa` 或已授权的答疑类自定义智能体；其知识引用可核验，且不能调用管理助手的写工具。
12. 发布版本的模型、Prompt、工具、知识源、入口、权限和额度快照不可变，回滚、禁用、请求取消和用量结算均可审计。
13. 创建一个新的自定义智能体只需后台配置、校验和发布，不需要修改客户端或业务主流程代码。

后续实现应以本文为基线。若开发过程中需要改变 API 路径、事件字段、权限编码、数据库关系或客户端职责，应先更新本文对应章节，再实现代码和迁移。