package com.astral.ai.mapper;

import com.astral.ai.dto.AiKnowledgeMatch;
import com.astral.ai.entity.AiKnowledgeChunkEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 知识分片 Mapper
 */
@Mapper
public interface AiKnowledgeChunkMapper extends BaseMapper<AiKnowledgeChunkEntity> {
    /** Indexed substring candidates, exact ranking and per-source thresholds all run before the bounded result. */
    @Options(timeout = 3)
    @Select("""
            <script>
            WITH terms(term,ord) AS (VALUES
              <foreach collection="terms" item="term" index="ordinal" separator=",">(#{term}::text,#{ordinal}::int)</foreach>
            ), candidates AS (
              SELECT c.id,c.document_id,c.source_id,s.source_type,d.title,d.path,COALESCE(c.section,d.section) AS section,
                d.version,c.chunk_no,c.content,LOWER(c.content) AS body,LOWER(COALESCE(d.title,'')) AS heading,
                CASE WHEN d.section IS NOT NULL AND c.section IS NOT NULL THEN 1 ELSE 0 END AS section_bonus,
                COALESCE(b.min_score,0.2) AS min_score,
                LEAST(50,GREATEST(1,COALESCE(b.retrieval_top_k,#{fallbackTopK}))) AS top_k
              FROM ai_knowledge_chunk c
              JOIN ai_knowledge_document d ON d.id=c.document_id AND d.status='READY'
              JOIN ai_knowledge_source s ON s.id=c.source_id AND s.enabled=TRUE
              JOIN ai_agent_knowledge b ON b.knowledge_source_id=c.source_id AND b.agent_version_id=#{versionId} AND b.enabled=TRUE
              WHERE astral_knowledge_grams(c.content) &amp;&amp; ARRAY(SELECT LEFT(term,3) FROM terms)
            ), scored AS (
              SELECT c.*,hits.first_term,
                LEAST(1.0,(hits.matched*100.0/(SELECT COUNT(*) FROM terms) + LEAST(hits.occurrences,20)*1.5
                  + CASE WHEN STRPOS(c.heading,hits.first_term)>0 THEN 12 ELSE 0 END + c.section_bonus)/143.0) AS score
              FROM candidates c CROSS JOIN LATERAL (
                SELECT COUNT(*) AS matched,
                  SUM(LEAST(51,(LENGTH(c.body)-LENGTH(REPLACE(c.body,term,'')))/LENGTH(term))) AS occurrences,
                  (ARRAY_AGG(term ORDER BY ord))[1] AS first_term
                FROM terms WHERE STRPOS(c.body,term)>0
              ) hits WHERE hits.matched>0
            ), ranked AS (
              SELECT *,ROW_NUMBER() OVER(PARTITION BY source_id ORDER BY score DESC,document_id,chunk_no,id) AS source_rank
              FROM scored WHERE score>=min_score
            )
            SELECT document_id,source_id,source_type,title,path,section,version,chunk_no,content,first_term,score
            FROM ranked WHERE source_rank &lt;= top_k ORDER BY score DESC,document_id,chunk_no,id LIMIT #{limit}
            </script>
            """)
    List<AiKnowledgeMatch> search(
            @Param("versionId") Long versionId,
            @Param("terms") List<String> terms,
            @Param("fallbackTopK") int fallbackTopK,
            @Param("limit") int limit);
}
