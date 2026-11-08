package com.astral.ai.mapper;

import com.astral.ai.entity.AiAgentVersionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 智能体版本 Mapper
 */
@Mapper
public interface AiAgentVersionMapper extends BaseMapper<AiAgentVersionEntity> {
    @Select("SELECT * FROM ai_agent_version WHERE id=#{id} FOR UPDATE")
    AiAgentVersionEntity lock(@Param("id") Long id);

    @Select("SELECT id FROM ai_provider_route WHERE id=#{id} FOR UPDATE")
    Long lockRoute(@Param("id") Long id);
}
