-- Durable request ownership, tool-result identity and per-provider-stage accounting.
ALTER TABLE ai_request ADD COLUMN IF NOT EXISTS worker_token VARCHAR(64);
ALTER TABLE ai_request ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP;
ALTER TABLE ai_request ADD COLUMN IF NOT EXISTS quota_counted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_request ADD COLUMN IF NOT EXISTS quota_active BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_tool_call ADD COLUMN IF NOT EXISTS provider_tool_call_id VARCHAR(255);
ALTER TABLE ai_tool_call ADD COLUMN IF NOT EXISTS assistant_message_id BIGINT;
ALTER TABLE ai_tool_call ADD COLUMN IF NOT EXISTS tool_message_id BIGINT;
ALTER TABLE ai_tool_call ADD COLUMN IF NOT EXISTS execution_token VARCHAR(64);
ALTER TABLE ai_usage ADD COLUMN IF NOT EXISTS stage_key VARCHAR(96);
ALTER TABLE ai_usage ADD COLUMN IF NOT EXISTS day_row_id BIGINT;
ALTER TABLE ai_usage ADD COLUMN IF NOT EXISTS month_row_id BIGINT;
ALTER TABLE ai_usage ADD COLUMN IF NOT EXISTS reserved_input_tokens INTEGER;
ALTER TABLE ai_usage ADD COLUMN IF NOT EXISTS reserved_output_tokens INTEGER;
ALTER TABLE ai_usage ADD COLUMN IF NOT EXISTS input_price DECIMAL(18,6);
ALTER TABLE ai_usage ADD COLUMN IF NOT EXISTS output_price DECIMAL(18,6);
-- Preserve legacy usage rows verbatim; only new stages have an idempotency key.
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_usage_stage ON ai_usage(request_id, stage_key) WHERE stage_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ai_request_active_quota ON ai_request(user_id, agent_id, quota_active);
