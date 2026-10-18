package com.astral.ai.mapper;

import com.astral.ai.entity.AiConversationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 会话 Mapper
 */
@Mapper
public interface AiConversationMapper extends BaseMapper<AiConversationEntity> {
}
