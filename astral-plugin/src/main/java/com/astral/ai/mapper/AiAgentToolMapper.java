package com.astral.ai.mapper;

import com.astral.ai.entity.AiAgentToolEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 智能体工具绑定 Mapper
 */
@Mapper
public interface AiAgentToolMapper extends BaseMapper<AiAgentToolEntity> {
    @Select("SELECT status FROM ai_agent_version WHERE id=#{id} FOR UPDATE")
    String lockVersionStatus(@Param("id") Long id);
}
