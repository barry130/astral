package com.astral.dao.mapper;

import com.astral.dao.entity.SequenceSegment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 序列号段表 Mapper 接口
 * 继承 MyBatis-Plus BaseMapper，提供基础 CRUD 操作
 * 包含号段分配、扩展等自定义方法
 */
@Mapper
public interface SequenceSegmentMapper extends BaseMapper<SequenceSegment> {

    /**
     * 根据业务键查询号段信息
     *
     * @param bizKey 业务键
     * @return 号段信息
     */
    @Select("SELECT * FROM sequence_segment WHERE biz_key = #{bizKey}")
    SequenceSegment selectByBizKey(@Param("bizKey") String bizKey);

    /**
     * 分配下一个号段（乐观锁更新）
     * 通过版本号实现并发控制，扩展号段最大值
     *
     * @param bizKey  业务键
     * @param version 当前版本号
     * @param step    步长
     * @return 影响行数（0表示并发冲突）
     */
    @Update("UPDATE sequence_segment SET max_value = max_value + #{step}, version = version + 1 " +
            "WHERE biz_key = #{bizKey} AND version = #{version}")
    int allocateNextSegment(@Param("bizKey") String bizKey, @Param("version") int version, @Param("step") int step);

    /**
     * 插入新号段记录
     *
     * @param bizKey   业务键
     * @param minValue 起始值
     * @param maxValue 最大值
     * @param step     步长
     * @return 影响行数（0 = 已存在，由其它并发线程插入；依赖 uk_segment_biz_key 唯一索引）
     */
    @Insert("INSERT INTO sequence_segment (biz_key, min_value, max_value, current_max_value, step, version, create_time, update_time) " +
            "VALUES (#{bizKey}, #{minValue}, #{maxValue}, #{maxValue}, #{step}, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) " +
            "ON CONFLICT (biz_key) DO NOTHING")
    int insertSegment(@Param("bizKey") String bizKey, @Param("minValue") long minValue,
                      @Param("maxValue") long maxValue, @Param("step") int step);

    /**
     * 查询业务表当前最大主键值（启动时高水位对齐用）。
     * 表名先经 {@code [A-Za-z0-9_]+} 白名单校验再以 ${} 拼接，来源为 SchemaRegistry 的
     * schema JSON（系统内部元数据，非请求输入）。
     *
     * @param tableName 表名（已通过标识符校验）
     * @return MAX(id)，空表返回 0
     */
    @Select("SELECT COALESCE(MAX(id), 0) FROM ${tableName}")
    Long selectMaxId(@Param("tableName") String tableName);

    /**
     * 判断表是否存在于当前 schema。高水位对齐前的探测：
     * 避免对「已登记 schema JSON 但物理表尚未建」的表每次启动报 missing relation 刷屏。
     */
    @Select("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = #{tableName}")
    int countTableInCurrentSchema(@Param("tableName") String tableName);

    /**
     * 抬高号段上限（只升不降）。启动高水位对齐专用：
     * 调用时机在全部生成器实例化之前，无并发持有旧内存缓冲，故不动 version。
     *
     * @return 影响行数（0 = 无需对齐，或已被并发抬高）
     */
    @Update("UPDATE sequence_segment SET max_value = #{newMaxValue}, update_time = CURRENT_TIMESTAMP " +
            "WHERE biz_key = #{bizKey} AND max_value < #{newMaxValue}")
    int raiseMaxValue(@Param("bizKey") String bizKey, @Param("newMaxValue") long newMaxValue);
}
