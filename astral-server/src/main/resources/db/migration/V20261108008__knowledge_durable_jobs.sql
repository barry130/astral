-- Durable document cursor; old incomplete jobs need an explicit retry after upgrade.
-- The plugin fallback may already have installed cursors and started durable jobs.
DO 'BEGIN
    LOCK TABLE ai_knowledge_index_job IN ACCESS EXCLUSIVE MODE;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema=current_schema() AND table_name=''ai_knowledge_index_job''
                     AND column_name=''end_document_id'') THEN
        UPDATE ai_knowledge_index_job
        SET status=''FAILED'', finished_at=CURRENT_TIMESTAMP,
            error_summary=''升级前的索引任务未完成，请重试''
        WHERE status IN (''QUEUED'',''PENDING'',''RUNNING'');
    END IF;
    ALTER TABLE ai_knowledge_index_job ADD COLUMN IF NOT EXISTS last_document_id BIGINT DEFAULT 0;
    ALTER TABLE ai_knowledge_index_job ADD COLUMN IF NOT EXISTS end_document_id BIGINT DEFAULT 0;
END';
ALTER TABLE ai_knowledge_index_job ALTER COLUMN status SET DEFAULT 'PENDING';
CREATE INDEX IF NOT EXISTS idx_ai_index_job_pending ON ai_knowledge_index_job(id)
    WHERE status IN ('PENDING','RUNNING');
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_index_job_active_source ON ai_knowledge_index_job(source_id)
    WHERE status IN ('PENDING','RUNNING');
ALTER TABLE ai_message ALTER COLUMN status SET DEFAULT 'COMPLETED';
UPDATE ai_message SET status='COMPLETED' WHERE status='SUCCEEDED';
