-- AI 工具审批 TOCTOU 防护（审计 2026-11-07 backend-ai-read-recovery R3）：
-- 会话计划期与审批期之间，admin:ai:tool:edit 可改 executor_config / risk_level / approval_policy，
-- 审批人确认的配置与实际执行配置可能不一致。计划建 WAITING_APPROVAL 行时写入
-- executor_config|risk|approval 的 SHA-256 快照哈希，审批时比对；空或不匹配一律拒绝（fail-closed）。
-- 存量行为空串，按“快照缺失”拒绝执行，要求重新发起对话确认。
ALTER TABLE ai_tool_call ADD COLUMN IF NOT EXISTS plan_hash VARCHAR(64) NOT NULL DEFAULT '';
