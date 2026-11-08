package com.astral.ai.mapper;

import com.astral.ai.entity.AiKnowledgeDocumentEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 知识文档 Mapper
 */
@Mapper
public interface AiKnowledgeDocumentMapper extends BaseMapper<AiKnowledgeDocumentEntity> {
    @Select("""
            SELECT source_id,COALESCE(SUM(chunk_count),0) AS chunks FROM ai_knowledge_document GROUP BY source_id
            """)
    List<SourceChunks> countChunksBySource();

    record SourceChunks(Long sourceId, long chunks) {}

    @Select("SELECT pg_advisory_xact_lock(hashtextextended('ai:knowledge:' || CAST(#{sourceId} AS text),0))")
    @org.apache.ibatis.annotations.Options(
            flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE,
            useCache = false)
    String lockSource(@Param("sourceId") Long sourceId);
}
