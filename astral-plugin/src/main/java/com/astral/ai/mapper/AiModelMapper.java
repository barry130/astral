package com.astral.ai.mapper;

import com.astral.ai.entity.AiModelEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 模型 Mapper
 */
@Mapper
public interface AiModelMapper extends BaseMapper<AiModelEntity> {
    @Select("SELECT COUNT(*) FROM ai_provider_route WHERE model_id=#{id} OR fallback_model_id=#{id}")
    long countRouteReferences(@Param("id") Long id);
}
