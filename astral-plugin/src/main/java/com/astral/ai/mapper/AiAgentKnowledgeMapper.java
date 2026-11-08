package com.astral.ai.mapper;

import com.astral.ai.entity.AiAgentKnowledgeEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 智能体知识绑定 Mapper
 */
@Mapper
public interface AiAgentKnowledgeMapper extends BaseMapper<AiAgentKnowledgeEntity> {
    @Select("SELECT status FROM ai_agent_version WHERE id=#{id} FOR UPDATE")
    String lockVersionStatus(@Param("id") Long id);
}
