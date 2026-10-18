package com.astral.ai.mapper;

import com.astral.ai.entity.AiMessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 消息 Mapper
 */
@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessageEntity> {
}
