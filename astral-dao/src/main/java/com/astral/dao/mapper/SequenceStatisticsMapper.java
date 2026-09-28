package com.astral.dao.mapper;

import com.astral.dao.entity.SequenceStatistics;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 序列统计表 Mapper 接口
 * 继承 MyBatis-Plus BaseMapper，提供基础 CRUD 操作
 */
@Mapper
public interface SequenceStatisticsMapper extends BaseMapper<SequenceStatistics> {

    /**
     * 根据业务键查询统计信息
     *
     * @param bizKey 业务键
     * @return 序列统计信息
     */
    @Select("SELECT * FROM sequence_statistics WHERE biz_key = #{bizKey}")
    SequenceStatistics selectByBizKey(@Param("bizKey") String bizKey);

    /**
     * 原子自增并取回新值。
     *
     * <p><b>为什么必须让数据库做自增</b>：DATABASE 生成器的语义是「每次取号都落库」。
     * 原实现是「应用层加锁 → 读旧值 → 写新值」，有两个致命问题：</p>
     * <ol>
     *   <li>锁在 {@code finally} 里释放，而事务在代理返回后才提交 → 锁与事务边界不重合，
     *       并发下另一个线程能读到未提交的旧值，生成<b>重复号</b>；</li>
     *   <li>JVM 内的 {@code ReentrantLock} 跨实例完全无效，多实例部署必然重号。</li>
     * </ol>
     *
     * <p>一条 {@code UPDATE ... RETURNING} 同时完成「自增 + 读取新值」，
     * 由数据库的行锁保证原子性：无锁竞争、无事务边界问题、天然跨实例安全。
     * （PostgreSQL 原生支持 {@code RETURNING}。）</p>
     *
     * @param bizKey 业务键
     * @param step   步长
     * @return 自增后的新值；业务键不存在（0 行受影响）时返回 null
     */
    @Update("UPDATE sequence_statistics SET current_value = current_value + #{step}, "
            + "update_time = CURRENT_TIMESTAMP "
            + "WHERE biz_key = #{bizKey} AND deleted = 0 "
            + "RETURNING current_value")
    Long incrementAndGet(@Param("bizKey") String bizKey, @Param("step") long step);

    /**
     * 初始化统计行（不存在才插入）。
     *
     * <p>依赖 {@code uk_statistics_biz_key} 唯一索引做并发去重；
     * 时间列显式写入而不依赖 DDL 默认值，避免不同环境的默认值行为差异。</p>
     *
     * @param bizKey 业务键
     * @return 实际插入行数（0 表示已存在）
     */
    @Insert("INSERT INTO sequence_statistics (biz_key, current_value, total_generate, create_time, update_time) "
            + "VALUES (#{bizKey}, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (biz_key) DO NOTHING")
    int insertIfAbsent(@Param("bizKey") String bizKey);

    /**
     * 按业务键更新当前值。
     *
     * <p>配合 {@link #insertIfAbsent(String)} 使用，替代原来的
     * {@code selectByBizKey → 判空 → insert/updateById} 三步：
     * 原写法在并发首次调用时会因 select 与 insert 之间的窗口插入重复行，
     * 且 {@code updateById} 会整行覆盖，把别的字段一并改掉。这里只更新必要的两列。</p>
     *
     * @param bizKey       业务键
     * @param currentValue 当前序列号值
     * @param updateTime   更新时间（由调用方显式传入，避免依赖数据库时区）
     * @return 更新行数
     */
    @Update("UPDATE sequence_statistics SET current_value = #{currentValue}, "
            + "last_generate_time = #{updateTime}, update_time = #{updateTime} "
            + "WHERE biz_key = #{bizKey} AND deleted = 0")
    int updateCurrentValueByBizKey(@Param("bizKey") String bizKey,
                                   @Param("currentValue") long currentValue,
                                   @Param("updateTime") LocalDateTime updateTime);
}
