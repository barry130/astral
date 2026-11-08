ALTER TABLE ai_usage ADD COLUMN IF NOT EXISTS reconciled_by BIGINT;
ALTER TABLE ai_usage ADD COLUMN IF NOT EXISTS reconciled_at TIMESTAMP;
ALTER TABLE ai_usage ADD COLUMN IF NOT EXISTS reconciliation_note VARCHAR(1000);
CREATE INDEX IF NOT EXISTS idx_ai_usage_created ON ai_usage(create_time);
CREATE INDEX IF NOT EXISTS idx_ai_usage_agent_created ON ai_usage(agent_id,create_time);
