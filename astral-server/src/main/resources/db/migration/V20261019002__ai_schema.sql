-- AI 模块表结构（Flyway 迁移版）。与 astral-plugin/src/main/resources/sql/ai-schema.sql 保持一致；
-- 主键由全局序列（业务键 = 表名_id）填充，故不设自增。

CREATE TABLE IF NOT EXISTS ai_provider (
    id BIGINT PRIMARY KEY,
    provider_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    secret_ref VARCHAR(128),
    encrypted_api_key VARCHAR(2048),
    connect_timeout_ms INTEGER DEFAULT 10000,
    read_timeout_ms INTEGER DEFAULT 120000,
    max_retries INTEGER DEFAULT 1,
    weight INTEGER DEFAULT 100,
    health_status VARCHAR(32) DEFAULT 'UNKNOWN',
    last_health_time TIMESTAMP,
    last_health_message VARCHAR(512),
    remark VARCHAR(512),
    created_by BIGINT,
    updated_by BIGINT,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_provider_code ON ai_provider (provider_code);
CREATE INDEX IF NOT EXISTS idx_ai_provider_enabled ON ai_provider (enabled);

-- AI 模型
CREATE TABLE IF NOT EXISTS ai_model (
    id BIGINT PRIMARY KEY,
    provider_id BIGINT NOT NULL,
    model_code VARCHAR(128) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    model_kind VARCHAR(32) NOT NULL DEFAULT 'CHAT',
    supports_stream BOOLEAN DEFAULT true,
    supports_tools BOOLEAN DEFAULT false,
    supports_json BOOLEAN DEFAULT false,
    supports_vision BOOLEAN DEFAULT false,
    max_context_tokens INTEGER DEFAULT 8192,
    max_output_tokens INTEGER DEFAULT 2048,
    input_price DECIMAL(18,6),
    output_price DECIMAL(18,6),
    currency VARCHAR(8) DEFAULT 'CNY',
    enabled BOOLEAN DEFAULT true,
    remark VARCHAR(512),
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_model_code ON ai_model (provider_id,model_code);
CREATE INDEX IF NOT EXISTS idx_ai_model_provider ON ai_model (provider_id);

-- AI 提供方路由
CREATE TABLE IF NOT EXISTS ai_provider_route (
    id BIGINT PRIMARY KEY,
    route_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    provider_id BIGINT NOT NULL,
    model_id BIGINT NOT NULL,
    fallback_model_id BIGINT,
    temperature DECIMAL(18,6) DEFAULT 0.7,
    top_p DECIMAL(18,6) DEFAULT 1,
    max_tokens INTEGER,
    extra_parameters_json TEXT,
    enabled BOOLEAN DEFAULT true,
    remark VARCHAR(512),
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_provider_route_code ON ai_provider_route (route_code);
CREATE INDEX IF NOT EXISTS idx_ai_provider_route_provider ON ai_provider_route (provider_id);

-- AI 智能体
CREATE TABLE IF NOT EXISTS ai_agent (
    id BIGINT PRIMARY KEY,
    agent_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    agent_type VARCHAR(32) NOT NULL DEFAULT 'CUSTOM',
    description VARCHAR(512),
    icon VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    client_types VARCHAR(128) NOT NULL,
    entry_config TEXT,
    permission_code VARCHAR(96),
    client_permission_codes TEXT,
    quota_policy_id BIGINT,
    published_version_id BIGINT,
    allow_user_context BOOLEAN DEFAULT true,
    allow_file_context BOOLEAN DEFAULT false,
    conversation_enabled BOOLEAN DEFAULT true,
    feedback_enabled BOOLEAN DEFAULT true,
    sort INTEGER DEFAULT 100,
    created_by BIGINT,
    updated_by BIGINT,
    deleted_at TIMESTAMP,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_agent_code ON ai_agent (agent_code);
CREATE INDEX IF NOT EXISTS idx_ai_agent_type ON ai_agent (agent_type,status);

-- AI 智能体版本
CREATE TABLE IF NOT EXISTS ai_agent_version (
    id BIGINT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    version_no INTEGER NOT NULL,
    version_name VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    provider_route_id BIGINT,
    system_prompt_version_id BIGINT,
    client_types_snapshot VARCHAR(128),
    entry_config_snapshot TEXT,
    context_policy_json TEXT,
    response_schema_json TEXT,
    tool_policy_json TEXT,
    knowledge_policy_json TEXT,
    model_parameters_json TEXT,
    safety_policy_json TEXT,
    quota_override_json TEXT,
    validation_summary_json TEXT,
    published_by BIGINT,
    published_at TIMESTAMP,
    rollback_from_version_id BIGINT,
    created_by BIGINT,
    updated_by BIGINT,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_agent_version_no ON ai_agent_version (agent_id,version_no);

-- AI 智能体访问策略
CREATE TABLE IF NOT EXISTS ai_agent_access_policy (
    id BIGINT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    subject_type VARCHAR(32) NOT NULL DEFAULT 'ALL',
    subject_id BIGINT DEFAULT 0,
    allow_access BOOLEAN DEFAULT true,
    daily_request_limit INTEGER,
    monthly_request_limit INTEGER,
    remark VARCHAR(512),
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ai_agent_access_agent ON ai_agent_access_policy (agent_id);

-- AI 提示词模板
CREATE TABLE IF NOT EXISTS ai_prompt_template (
    id BIGINT PRIMARY KEY,
    prompt_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    scene VARCHAR(64),
    description VARCHAR(512),
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT,
    updated_by BIGINT,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_prompt_template_code ON ai_prompt_template (prompt_code);

-- AI 提示词版本
CREATE TABLE IF NOT EXISTS ai_prompt_version (
    id BIGINT PRIMARY KEY,
    template_id BIGINT NOT NULL,
    version_no INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    system_prompt TEXT,
    user_prompt_template TEXT,
    variables_json TEXT,
    changelog VARCHAR(512),
    published_by BIGINT,
    published_at TIMESTAMP,
    created_by BIGINT,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_prompt_version_no ON ai_prompt_version (template_id,version_no);

-- AI 知识源
CREATE TABLE IF NOT EXISTS ai_knowledge_source (
    id BIGINT PRIMARY KEY,
    source_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    source_type VARCHAR(48) NOT NULL,
    description VARCHAR(512),
    path_pattern VARCHAR(512),
    enabled BOOLEAN DEFAULT true,
    sync_mode VARCHAR(16) DEFAULT 'MANUAL',
    status VARCHAR(32) DEFAULT 'READY',
    last_sync_time TIMESTAMP,
    document_count INTEGER DEFAULT 0,
    created_by BIGINT,
    updated_by BIGINT,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_knowledge_source_code ON ai_knowledge_source (source_code);

-- AI 知识文档
CREATE TABLE IF NOT EXISTS ai_knowledge_document (
    id BIGINT PRIMARY KEY,
    source_id BIGINT NOT NULL,
    doc_key VARCHAR(256) NOT NULL,
    title VARCHAR(256) NOT NULL,
    path VARCHAR(512),
    section VARCHAR(256),
    version VARCHAR(64),
    content TEXT,
    content_hash VARCHAR(64),
    status VARCHAR(32) DEFAULT 'READY',
    chunk_count INTEGER DEFAULT 0,
    indexed_at TIMESTAMP,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_knowledge_document_key ON ai_knowledge_document (source_id,doc_key);

-- AI 知识分片
CREATE TABLE IF NOT EXISTS ai_knowledge_chunk (
    id BIGINT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    chunk_no INTEGER NOT NULL,
    section VARCHAR(256),
    content TEXT,
    token_count INTEGER DEFAULT 0,
    embedding_json TEXT,
    create_time TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ai_knowledge_chunk_doc ON ai_knowledge_chunk (document_id);

-- AI 知识索引任务
CREATE TABLE IF NOT EXISTS ai_knowledge_index_job (
    id BIGINT PRIMARY KEY,
    source_id BIGINT NOT NULL,
    job_type VARCHAR(32) DEFAULT 'FULL',
    status VARCHAR(32) DEFAULT 'QUEUED',
    total_count INTEGER DEFAULT 0,
    processed_count INTEGER DEFAULT 0,
    failed_count INTEGER DEFAULT 0,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    error_summary VARCHAR(1024),
    created_by BIGINT,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ai_knowledge_index_job_source ON ai_knowledge_index_job (source_id,status);

-- AI 智能体知识绑定
CREATE TABLE IF NOT EXISTS ai_agent_knowledge (
    id BIGINT PRIMARY KEY,
    agent_version_id BIGINT NOT NULL,
    knowledge_source_id BIGINT NOT NULL,
    enabled BOOLEAN DEFAULT true,
    priority INTEGER DEFAULT 100,
    retrieval_top_k INTEGER DEFAULT 5,
    min_score DECIMAL(18,6) DEFAULT 0.2,
    citation_policy VARCHAR(32) DEFAULT 'REQUIRED',
    filter_policy_json TEXT,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_agent_knowledge ON ai_agent_knowledge (agent_version_id,knowledge_source_id);

-- AI 工具
CREATE TABLE IF NOT EXISTS ai_tool (
    id BIGINT PRIMARY KEY,
    tool_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    handler_key VARCHAR(64) NOT NULL,
    client_types VARCHAR(128),
    permission_codes TEXT,
    risk_level VARCHAR(16) NOT NULL DEFAULT 'READ',
    approval_policy VARCHAR(24) NOT NULL DEFAULT 'NONE',
    arguments_schema TEXT,
    result_schema TEXT,
    side_effects TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_by BIGINT,
    updated_by BIGINT,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_tool_code ON ai_tool (tool_code);

-- AI 智能体工具绑定
CREATE TABLE IF NOT EXISTS ai_agent_tool (
    id BIGINT PRIMARY KEY,
    agent_version_id BIGINT NOT NULL,
    tool_id BIGINT NOT NULL,
    enabled BOOLEAN DEFAULT true,
    sort INTEGER DEFAULT 100,
    risk_override VARCHAR(16),
    approval_policy VARCHAR(24),
    arguments_schema_override TEXT,
    result_schema_override TEXT,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_agent_tool ON ai_agent_tool (agent_version_id,tool_id);

-- AI 会话
CREATE TABLE IF NOT EXISTS ai_conversation (
    id BIGINT PRIMARY KEY,
    conversation_key VARCHAR(64) NOT NULL,
    agent_id BIGINT NOT NULL,
    agent_version_id BIGINT,
    user_id BIGINT NOT NULL,
    client_type VARCHAR(24) NOT NULL,
    title VARCHAR(256),
    status VARCHAR(16) DEFAULT 'ACTIVE',
    message_count INTEGER DEFAULT 0,
    last_message_time TIMESTAMP,
    context_json TEXT,
    pinned BOOLEAN DEFAULT false,
    deleted_at TIMESTAMP,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_conversation_key ON ai_conversation (conversation_key);
CREATE INDEX IF NOT EXISTS idx_ai_conversation_user ON ai_conversation (user_id,agent_id);

-- AI 消息
CREATE TABLE IF NOT EXISTS ai_message (
    id BIGINT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    request_id BIGINT,
    parent_message_id BIGINT,
    role VARCHAR(16) NOT NULL,
    content TEXT,
    content_type VARCHAR(24) DEFAULT 'TEXT',
    status VARCHAR(16) DEFAULT 'SUCCEEDED',
    blocks_json TEXT,
    citations_json TEXT,
    model_name VARCHAR(128),
    input_tokens INTEGER DEFAULT 0,
    output_tokens INTEGER DEFAULT 0,
    finish_reason VARCHAR(32),
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ai_message_conversation ON ai_message (conversation_id,id);

-- AI 请求
CREATE TABLE IF NOT EXISTS ai_request (
    id BIGINT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    agent_version_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    client_type VARCHAR(24),
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    idempotency_key VARCHAR(96),
    provider_id BIGINT,
    model_id BIGINT,
    prompt_version_id BIGINT,
    input_snapshot TEXT,
    context_snapshot TEXT,
    configuration_snapshot TEXT,
    knowledge_snapshot TEXT,
    tool_policy_snapshot TEXT,
    context_policy_snapshot TEXT,
    response_schema_snapshot TEXT,
    safety_policy_snapshot TEXT,
    quota_policy_snapshot TEXT,
    error_code VARCHAR(32),
    error_summary VARCHAR(1024),
    cancel_reason VARCHAR(256),
    token_in INTEGER DEFAULT 0,
    token_out INTEGER DEFAULT 0,
    started_at TIMESTAMP,
    first_token_at TIMESTAMP,
    finished_at TIMESTAMP,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ai_request_conversation ON ai_request (conversation_id,id);
CREATE INDEX IF NOT EXISTS idx_ai_request_user ON ai_request (user_id,create_time);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_request_idem ON ai_request (user_id,agent_id,idempotency_key);

-- AI 请求事件
CREATE TABLE IF NOT EXISTS ai_request_event (
    id BIGINT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    data_json TEXT,
    create_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_request_event_seq ON ai_request_event (request_id,sequence_no);

-- AI 工具调用
CREATE TABLE IF NOT EXISTS ai_tool_call (
    id BIGINT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    tool_code VARCHAR(64) NOT NULL,
    tool_name VARCHAR(128),
    risk_level VARCHAR(16),
    status VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
    arguments_json TEXT,
    result_json TEXT,
    error_message VARCHAR(1024),
    approval_status VARCHAR(24) DEFAULT 'NONE',
    approved_by BIGINT,
    approved_at TIMESTAMP,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ai_tool_call_request ON ai_tool_call (request_id);

-- AI 用量
CREATE TABLE IF NOT EXISTS ai_usage (
    id BIGINT PRIMARY KEY,
    request_id BIGINT,
    user_id BIGINT,
    agent_id BIGINT,
    agent_version_id BIGINT,
    provider_id BIGINT,
    model_id BIGINT,
    input_tokens INTEGER DEFAULT 0,
    output_tokens INTEGER DEFAULT 0,
    estimated_cost DECIMAL(18,6),
    currency VARCHAR(8) DEFAULT 'CNY',
    reservation_amount DECIMAL(18,6),
    settled_amount DECIMAL(18,6),
    status VARCHAR(16) NOT NULL DEFAULT 'RESERVED',
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ai_usage_user ON ai_usage (user_id,create_time);

-- AI 额度策略
CREATE TABLE IF NOT EXISTS ai_quota_policy (
    id BIGINT PRIMARY KEY,
    policy_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    scope_type VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',
    agent_id BIGINT DEFAULT 0,
    role_id BIGINT DEFAULT 0,
    user_id BIGINT DEFAULT 0,
    daily_requests INTEGER,
    monthly_requests INTEGER,
    daily_input_tokens BIGINT,
    monthly_input_tokens BIGINT,
    daily_output_tokens BIGINT,
    monthly_output_tokens BIGINT,
    max_input_chars INTEGER,
    max_context_tokens INTEGER,
    max_output_tokens INTEGER,
    max_concurrent INTEGER,
    monthly_cost_limit DECIMAL(18,6),
    enabled BOOLEAN DEFAULT true,
    remark VARCHAR(512),
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_quota_policy_code ON ai_quota_policy (policy_code);

-- AI 用户额度
CREATE TABLE IF NOT EXISTS ai_user_quota (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL DEFAULT 0,
    policy_id BIGINT,
    period_type VARCHAR(8) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE,
    request_count INTEGER DEFAULT 0,
    input_tokens BIGINT DEFAULT 0,
    output_tokens BIGINT DEFAULT 0,
    reserved_cost DECIMAL(18,6) DEFAULT 0,
    settled_cost DECIMAL(18,6) DEFAULT 0,
    version INTEGER DEFAULT 0,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_user_quota_period ON ai_user_quota (user_id,agent_id,period_type,period_start);

-- AI 反馈
CREATE TABLE IF NOT EXISTS ai_feedback (
    id BIGINT PRIMARY KEY,
    conversation_id BIGINT,
    message_id BIGINT,
    request_id BIGINT,
    user_id BIGINT,
    agent_id BIGINT,
    feedback_type VARCHAR(24) NOT NULL,
    reason_code VARCHAR(64),
    comment TEXT,
    metadata_json TEXT,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ai_feedback_agent ON ai_feedback (agent_id,create_time);


-- AI 表号段初始化（业务键 = 表名_id），与其它插件表一致：先占一段，运行期由高水位对齐抬升
INSERT INTO sequence_segment (biz_key, min_value, max_value, current_max_value, step)
VALUES
    ('ai_provider_id', 1, 1000, 1000, 1000),
    ('ai_model_id', 1, 1000, 1000, 1000),
    ('ai_provider_route_id', 1, 1000, 1000, 1000),
    ('ai_agent_id', 1, 1000, 1000, 1000),
    ('ai_agent_version_id', 1, 1000, 1000, 1000),
    ('ai_agent_access_policy_id', 1, 1000, 1000, 1000),
    ('ai_prompt_template_id', 1, 1000, 1000, 1000),
    ('ai_prompt_version_id', 1, 1000, 1000, 1000),
    ('ai_knowledge_source_id', 1, 1000, 1000, 1000),
    ('ai_knowledge_document_id', 1, 1000, 1000, 1000),
    ('ai_knowledge_chunk_id', 1, 1000, 1000, 1000),
    ('ai_knowledge_index_job_id', 1, 1000, 1000, 1000),
    ('ai_agent_knowledge_id', 1, 1000, 1000, 1000),
    ('ai_tool_id', 1, 1000, 1000, 1000),
    ('ai_agent_tool_id', 1, 1000, 1000, 1000),
    ('ai_conversation_id', 1, 1000, 1000, 1000),
    ('ai_message_id', 1, 1000, 1000, 1000),
    ('ai_request_id', 1, 1000, 1000, 1000),
    ('ai_request_event_id', 1, 1000, 1000, 1000),
    ('ai_tool_call_id', 1, 1000, 1000, 1000),
    ('ai_usage_id', 1, 1000, 1000, 1000),
    ('ai_quota_policy_id', 1, 1000, 1000, 1000),
    ('ai_user_quota_id', 1, 1000, 1000, 1000),
    ('ai_feedback_id', 1, 1000, 1000, 1000)
ON CONFLICT (biz_key) DO NOTHING;
