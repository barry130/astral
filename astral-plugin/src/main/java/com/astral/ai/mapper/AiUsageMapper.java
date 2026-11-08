package com.astral.ai.mapper;

import com.astral.ai.dto.AiUsageAggregate;
import com.astral.ai.entity.AiUsageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 用量 Mapper
 */
@Mapper
public interface AiUsageMapper extends BaseMapper<AiUsageEntity> {
    String FILTER = """
            <where>
              <if test="userId != null">user_id = #{userId}</if>
              <if test="agentId != null">AND agent_id = #{agentId}</if>
              <if test="from != null">AND create_time &gt;= #{from}</if>
              <if test="to != null">AND create_time &lt; #{to}</if>
            </where>
            """;

    @Select("<script>SELECT COUNT(DISTINCT request_id) request_count, COALESCE(SUM(input_tokens),0) input_tokens, "
            + "COALESCE(SUM(output_tokens),0) output_tokens FROM ai_usage " + FILTER + "</script>")
    @Options(timeout = 5)
    AiUsageAggregate totals(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("agentId") Long agentId,
            @Param("userId") Long userId);

    @Select("<script>SELECT COALESCE(NULLIF(UPPER(TRIM(currency)),''),'CNY') currency, "
            + "COALESCE(SUM(settled_amount),0) settled_amount, COALESCE(SUM(reservation_amount),0) reservation_amount, "
            + "SUM(CASE WHEN status='UNKNOWN' THEN 1 ELSE 0 END) unknown_stages "
            + "FROM ai_usage " + FILTER
            + " GROUP BY COALESCE(NULLIF(UPPER(TRIM(currency)),''),'CNY') ORDER BY currency</script>")
    @Options(timeout = 5)
    List<AiUsageAggregate> costs(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("agentId") Long agentId,
            @Param("userId") Long userId);

    // A request belongs to the day of its first selected stage. All stage tokens keep their actual day.
    @Select(
            "<script>WITH selected AS (SELECT request_id,create_time,input_tokens,output_tokens FROM ai_usage "
                    + FILTER
                    + "), first_stage AS (SELECT request_id,MIN(create_time) first_time FROM selected GROUP BY request_id) "
                    + "SELECT CAST(u.create_time AS DATE) date, "
                    + "COUNT(DISTINCT CASE WHEN u.create_time=f.first_time THEN u.request_id END) request_count, "
                    + "COALESCE(SUM(u.input_tokens),0) input_tokens, COALESCE(SUM(u.output_tokens),0) output_tokens "
                    + "FROM selected u JOIN first_stage f ON f.request_id=u.request_id GROUP BY CAST(u.create_time AS DATE) ORDER BY date</script>")
    @Options(timeout = 5)
    List<AiUsageAggregate> trend(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("agentId") Long agentId,
            @Param("userId") Long userId);
}
