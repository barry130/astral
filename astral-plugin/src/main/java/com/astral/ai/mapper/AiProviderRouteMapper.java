package com.astral.ai.mapper;

import com.astral.ai.entity.AiProviderRouteEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 提供方路由 Mapper
 */
@Mapper
public interface AiProviderRouteMapper extends BaseMapper<AiProviderRouteEntity> {
    @Select("SELECT COUNT(*) FROM ai_agent_version WHERE provider_route_id=#{id}")
    long countVersionReferences(@Param("id") Long id);
}
