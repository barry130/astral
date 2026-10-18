-- AI 模块数据字典：所有枚举取值由字典统一提供，前端下拉不得硬编码。
WITH seed(dict_code, dict_name, description) AS (
    VALUES ('ai_agent_type', 'AI 智能体类型', 'MUSIC 音乐 / ADMIN 管理 / QA 答疑 / CUSTOM 自定义'),
           ('ai_agent_status', 'AI 智能体状态', 'DRAFT 草稿 / PUBLISHED 已发布 / DISABLED 已停用'),
           ('ai_agent_version_status', 'AI 智能体版本状态', 'DRAFT 草稿 / PUBLISHED 已发布 / ARCHIVED 已归档'),
           ('ai_client_type', 'AI 客户端类型', 'QT_UNIAPPX / QT_PC / ASTRAL_FRONT'),
           ('ai_provider_type', 'AI 提供方协议', 'OPENAI_COMPATIBLE / OLLAMA / ANTHROPIC / GEMINI / MOCK'),
           ('ai_model_kind', 'AI 模型类别', 'CHAT / REASONING / VISION / EMBEDDING / RERANK'),
           ('ai_tool_risk_level', 'AI 工具风险等级', 'READ 只读 / DRAFT 草稿 / HIGH 高风险'),
           ('ai_tool_approval_policy', 'AI 工具确认策略', 'NONE 无需确认 / USER_CONFIRM 用户确认 / ADMIN_CONFIRM 管理员确认'),
           ('ai_knowledge_source_type', 'AI 知识源类型', '项目文档 / 接口契约 / 插件指南 / 部署指南 / 发布说明 / FAQ / 实时只读数据'),
           ('ai_knowledge_status', 'AI 知识源状态', 'PENDING / INDEXING / READY / FAILED / DISABLED'),
           ('ai_request_status', 'AI 请求状态', 'QUEUED / RUNNING / WAITING_TOOL / WAITING_APPROVAL / SUCCEEDED / FAILED / CANCELLED'),
           ('ai_message_status', 'AI 消息状态', 'PENDING / STREAMING / COMPLETED / FAILED / CANCELLED'),
           ('ai_index_job_status', 'AI 索引任务状态', 'PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED'),
           ('ai_feedback_type', 'AI 反馈类型', 'LIKE / DISLIKE / REGENERATE / REPORT'),
           ('ai_quota_scope_type', 'AI 额度作用域', 'GLOBAL 全局 / AGENT 智能体 / ROLE 角色 / USER 用户')
), missing AS (
    SELECT * FROM seed s WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_code = s.dict_code)
)
INSERT INTO sys_dict_type (id, dict_code, dict_name, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + ROW_NUMBER() OVER (ORDER BY dict_code),
       dict_code, dict_name, 1, description
FROM missing;

WITH seed(dict_code, dict_value, dict_label, dict_sort) AS (
    VALUES ('ai_agent_type', 'MUSIC', '音乐助手', 1),
           ('ai_agent_type', 'ADMIN', '管理助手', 2),
           ('ai_agent_type', 'QA', '答疑助手', 3),
           ('ai_agent_type', 'CUSTOM', '自定义', 4),

           ('ai_agent_status', 'DRAFT', '草稿', 1),
           ('ai_agent_status', 'PUBLISHED', '已发布', 2),
           ('ai_agent_status', 'DISABLED', '已停用', 3),

           ('ai_agent_version_status', 'DRAFT', '草稿', 1),
           ('ai_agent_version_status', 'PUBLISHED', '已发布', 2),
           ('ai_agent_version_status', 'ARCHIVED', '已归档', 3),

           ('ai_client_type', 'QT_UNIAPPX', '轻听 App', 1),
           ('ai_client_type', 'QT_PC', '轻听 PC', 2),
           ('ai_client_type', 'ASTRAL_FRONT', '管理后台', 3),

           ('ai_provider_type', 'OPENAI_COMPATIBLE', 'OpenAI 兼容', 1),
           ('ai_provider_type', 'OLLAMA', 'Ollama 本地', 2),
           ('ai_provider_type', 'ANTHROPIC', 'Anthropic', 3),
           ('ai_provider_type', 'GEMINI', 'Gemini', 4),
           ('ai_provider_type', 'MOCK', '本地模拟', 5),

           ('ai_model_kind', 'CHAT', '对话', 1),
           ('ai_model_kind', 'REASONING', '推理', 2),
           ('ai_model_kind', 'VISION', '多模态', 3),
           ('ai_model_kind', 'EMBEDDING', '向量', 4),
           ('ai_model_kind', 'RERANK', '重排', 5),

           ('ai_tool_risk_level', 'READ', '只读', 1),
           ('ai_tool_risk_level', 'DRAFT', '草稿', 2),
           ('ai_tool_risk_level', 'HIGH', '高风险', 3),

           ('ai_tool_approval_policy', 'NONE', '无需确认', 1),
           ('ai_tool_approval_policy', 'USER_CONFIRM', '用户确认', 2),
           ('ai_tool_approval_policy', 'ADMIN_CONFIRM', '管理员确认', 3),

           ('ai_knowledge_source_type', 'PROJECT_DOCUMENT', '项目文档', 1),
           ('ai_knowledge_source_type', 'API_DOCUMENT', '接口契约', 2),
           ('ai_knowledge_source_type', 'PLUGIN_DOCUMENT', '插件指南', 3),
           ('ai_knowledge_source_type', 'DEPLOYMENT_DOCUMENT', '部署指南', 4),
           ('ai_knowledge_source_type', 'RELEASE_NOTE', '发布说明', 5),
           ('ai_knowledge_source_type', 'FAQ', '常见问题', 6),
           ('ai_knowledge_source_type', 'LIVE_READONLY_DATA', '实时只读数据', 7),

           ('ai_knowledge_status', 'PENDING', '待索引', 1),
           ('ai_knowledge_status', 'INDEXING', '索引中', 2),
           ('ai_knowledge_status', 'READY', '就绪', 3),
           ('ai_knowledge_status', 'FAILED', '索引失败', 4),
           ('ai_knowledge_status', 'DISABLED', '已停用', 5),

           ('ai_request_status', 'QUEUED', '排队中', 1),
           ('ai_request_status', 'RUNNING', '生成中', 2),
           ('ai_request_status', 'WAITING_TOOL', '等待工具', 3),
           ('ai_request_status', 'WAITING_APPROVAL', '等待确认', 4),
           ('ai_request_status', 'SUCCEEDED', '已完成', 5),
           ('ai_request_status', 'FAILED', '失败', 6),
           ('ai_request_status', 'CANCELLED', '已取消', 7),

           ('ai_message_status', 'PENDING', '待生成', 1),
           ('ai_message_status', 'STREAMING', '生成中', 2),
           ('ai_message_status', 'COMPLETED', '已完成', 3),
           ('ai_message_status', 'FAILED', '失败', 4),
           ('ai_message_status', 'CANCELLED', '已取消', 5),

           ('ai_index_job_status', 'PENDING', '待执行', 1),
           ('ai_index_job_status', 'RUNNING', '执行中', 2),
           ('ai_index_job_status', 'SUCCEEDED', '成功', 3),
           ('ai_index_job_status', 'FAILED', '失败', 4),
           ('ai_index_job_status', 'CANCELLED', '已取消', 5),

           ('ai_feedback_type', 'LIKE', '赞', 1),
           ('ai_feedback_type', 'DISLIKE', '踩', 2),
           ('ai_feedback_type', 'REGENERATE', '重新生成', 3),
           ('ai_feedback_type', 'REPORT', '举报', 4),

           ('ai_quota_scope_type', 'GLOBAL', '全局', 1),
           ('ai_quota_scope_type', 'AGENT', '智能体', 2),
           ('ai_quota_scope_type', 'ROLE', '角色', 3),
           ('ai_quota_scope_type', 'USER', '用户', 4)
), missing AS (
    SELECT t.id AS type_id, s.* FROM seed s JOIN sys_dict_type t ON t.dict_code = s.dict_code
    WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = t.id AND d.dict_value = s.dict_value)
)
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY dict_code, dict_sort),
       type_id, dict_label, dict_value, dict_sort, 1 FROM missing;

UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_type_id';
UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_data_id';
