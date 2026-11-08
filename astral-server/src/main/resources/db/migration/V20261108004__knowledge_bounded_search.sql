-- 1/2/3-grams preserve substring matching (including single CJK and bigrams) without pg_trgm privileges.
-- Query terms use a necessary gram as an indexed candidate, then verify the whole term before ranking.
CREATE OR REPLACE FUNCTION astral_knowledge_grams(body TEXT) RETURNS TEXT[]
LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT AS $$
    SELECT COALESCE(array_agg(DISTINCT SUBSTRING(LOWER(body) FROM pos FOR width)), ARRAY[]::text[])
    FROM generate_series(1,3) width
    CROSS JOIN LATERAL generate_series(1,LENGTH(body)-width+1) pos
$$;
CREATE INDEX IF NOT EXISTS idx_ai_chunk_grams ON ai_knowledge_chunk USING gin(astral_knowledge_grams(content));
CREATE INDEX IF NOT EXISTS idx_ai_document_source_id ON ai_knowledge_document(source_id,id);

-- Explicit score version: normalized V1 = old weighted score / 143. The shipped 0.2
-- database/UI default and other already-normalized 0..1 values retain their value.
-- Legacy weighted configurations (>1, including the former Java default 30) are converted once.
ALTER TABLE ai_agent_knowledge ADD COLUMN IF NOT EXISTS score_scale VARCHAR(24);
UPDATE ai_agent_knowledge SET min_score = CASE
    WHEN min_score IS NULL THEN 0.2
    WHEN min_score > 1 THEN LEAST(min_score / 143.0, 1)
    WHEN min_score < 0 THEN 0
    ELSE min_score END,
    score_scale = 'NORMALIZED_V1'
WHERE score_scale IS NULL;
ALTER TABLE ai_agent_knowledge ALTER COLUMN score_scale SET DEFAULT 'NORMALIZED_V1';
ALTER TABLE ai_agent_knowledge ALTER COLUMN score_scale SET NOT NULL;
ALTER TABLE ai_agent_knowledge ALTER COLUMN min_score SET DEFAULT 0.2;
