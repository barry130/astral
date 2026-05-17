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
     * @return 影响行数
     */
    @Insert("INSERT INTO sequence_segment (biz_key, min_value, max_value, current_max_value, step, version) " +
            "VALUES (#{bizKey}, #{minValue}, #{maxValue}, #{maxValue}, #{step}, 1)")
    int insertSegment(@Param("bizKey") String bizKey, @Param("minValue") int minValue,
                      @Param("maxValue") long maxValue, @Param("step") int step);
}
