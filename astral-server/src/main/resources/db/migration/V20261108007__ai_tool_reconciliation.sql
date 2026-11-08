ALTER TABLE ai_tool_call ADD COLUMN IF NOT EXISTS reconciled_by BIGINT;
ALTER TABLE ai_tool_call ADD COLUMN IF NOT EXISTS reconciled_at TIMESTAMP;
ALTER TABLE ai_tool_call ADD COLUMN IF NOT EXISTS reconciliation_note VARCHAR(1000);
