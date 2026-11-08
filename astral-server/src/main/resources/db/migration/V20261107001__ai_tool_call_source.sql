-- AI 工具调用审计补全：后台试跑（admin test-run）与普通会话调用一样必须留痕（ai_tool_call）。
-- 试跑不归属任何 ai_request，request_id 放开为可空；新增 source 列区分调用来源，
-- 存量行为 'chat'（会话编排调用），后台试跑写 'test-run'。
ALTER TABLE ai_tool_call ALTER COLUMN request_id DROP NOT NULL;
ALTER TABLE ai_tool_call ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'chat';
