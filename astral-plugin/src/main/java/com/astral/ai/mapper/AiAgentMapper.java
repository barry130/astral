package com.astral.ai.mapper;

import com.astral.ai.entity.AiAgentEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 智能体 Mapper
 */
@Mapper
public interface AiAgentMapper extends BaseMapper<AiAgentEntity> {
    @Select("SELECT * FROM ai_agent WHERE id=#{id} AND deleted_at IS NULL FOR UPDATE")
    AiAgentEntity lock(@Param("id") Long id);
}
