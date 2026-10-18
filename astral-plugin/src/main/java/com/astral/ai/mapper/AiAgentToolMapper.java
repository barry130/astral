package com.astral.ai.mapper;

import com.astral.ai.entity.AiAgentToolEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 智能体工具绑定 Mapper
 */
@Mapper
public interface AiAgentToolMapper extends BaseMapper<AiAgentToolEntity> {
}
