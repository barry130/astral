package com.astral.ai.mapper;

import com.astral.ai.entity.AiUserQuotaEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * AI 用户额度 Mapper
 */
@Mapper
public interface AiUserQuotaMapper extends BaseMapper<AiUserQuotaEntity> {

    /**
     * 用 PostgreSQL 事务级咨询锁串行化「同一用户 + 同一智能体 + 同一周期」的额度行创建，
     * 避免并发首请求时撞唯一索引。锁随事务结束自动释放。
     *
     * @param key 锁键（user:agent:period:start）
     * @return 占位结果
     */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    String lockQuotaKey(@Param("key") String key);

    /**
     * 原子累加额度计数（在多级阈值内才生效，返回受影响行数）。
     *
     * <p>上限参数必须显式声明 {@code jdbcType}：三个上限都允许为 null（表示该维度不限），
     * 而 MyBatis 对 null 参数默认按 {@code JdbcType.OTHER} 绑定。PostgreSQL 是强类型协议，
     * 遇到 {@code ? IS NULL} 这种没有比较对象可供推断的裸参数会直接报
     * {@code ERROR: could not determine data type of parameter $5}。
     * 指定 INTEGER/BIGINT 后驱动会带上明确类型，null 与具体数值都能正常执行。</p>
     *
     * @param id          额度行 ID
     * @param requests    请求数增量
     * @param inTokens    输入 Token 增量
     * @param outTokens   输出 Token 增量
     * @param reqLimit    请求数上限（null 表示不限）
     * @param inLimit     输入 Token 上限（null 表示不限）
     * @param outLimit    输出 Token 上限（null 表示不限）
     * @return 受影响行数（0 表示超限）
     */
    @Update("""
            UPDATE ai_user_quota
               SET request_count = request_count + #{requests},
                   input_tokens  = input_tokens + #{inTokens},
                   output_tokens = output_tokens + #{outTokens},
                   version       = version + 1,
                   update_time   = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND (#{reqLimit,jdbcType=INTEGER} IS NULL
                    OR request_count + #{requests} <= #{reqLimit,jdbcType=INTEGER})
               AND (#{inLimit,jdbcType=BIGINT} IS NULL
                    OR input_tokens + #{inTokens} <= #{inLimit,jdbcType=BIGINT})
               AND (#{outLimit,jdbcType=BIGINT} IS NULL
                    OR output_tokens + #{outTokens} <= #{outLimit,jdbcType=BIGINT})
            """)
    int addWithinLimits(@Param("id") Long id,
                        @Param("requests") int requests,
                        @Param("inTokens") long inTokens,
                        @Param("outTokens") long outTokens,
                        @Param("reqLimit") Integer reqLimit,
                        @Param("inLimit") Long inLimit,
                        @Param("outLimit") Long outLimit);
}
