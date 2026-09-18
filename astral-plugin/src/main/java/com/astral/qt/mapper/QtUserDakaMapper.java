package com.astral.qt.mapper;

import com.astral.qt.entity.QtUserDaka;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface QtUserDakaMapper extends BaseMapper<QtUserDaka> {

    /** 某年-某月 的签到日期列表（升序） */
    @Select("SELECT data FROM qt_user_daka WHERE uid = #{uid} AND EXTRACT(YEAR FROM data) = #{year} AND EXTRACT(MONTH FROM data) = #{month} ORDER BY data")
    List<LocalDate> getDayByMonth(@Param("uid") Long uid, @Param("year") int year, @Param("month") int month);

    /** 该用户全部未删除的签到日期（用于 Java 端计算连续天数，兼容多数据库） */
    @Select("SELECT data FROM qt_user_daka WHERE uid = #{uid} ORDER BY data")
    List<LocalDate> getAllDakaDates(@Param("uid") Long uid);

    /** 有效总积分 = 总积分 - 使用积分签到的积分(每个扣10) */
    @Select("""
            SELECT COALESCE(SUM(integral),0) - (SELECT COALESCE(COUNT(*),0)*10 FROM qt_user_daka WHERE uid = #{uid} AND is_use_code = 1)
            FROM qt_user_daka WHERE uid = #{uid}
            """)
    Integer getAllIntegral(@Param("uid") Long uid);
}