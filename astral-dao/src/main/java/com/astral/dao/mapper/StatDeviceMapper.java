package com.astral.dao.mapper;

import com.astral.dao.entity.StatDevice;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 统计设备登记表 Mapper 接口
 * 继承 MyBatis-Plus BaseMapper，提供基础 CRUD 操作
 */
@Mapper
public interface StatDeviceMapper extends BaseMapper<StatDevice> {

    /**
     * 更新设备最近活跃信息（upsert 第二步：唯一键冲突时执行）
     * <p>
     * last_date 直接置为本次上报日期（日期单调递增），last_active_time 同理。
     * app_version/model/os/last_ip 取最新上报值覆盖。
     * </p>
     */
    @Update("UPDATE stat_device SET ut = #{ut}, app_version = #{appVersion}, model = #{model}, " +
            "os = #{os}, last_ip = #{lastIp}, last_date = #{lastDate}, last_active_time = #{lastActiveTime} " +
            "WHERE device_id = #{deviceId}")
    int updateLastActive(@Param("deviceId") String deviceId,
                         @Param("ut") String ut,
                         @Param("appVersion") String appVersion,
                         @Param("model") String model,
                         @Param("os") String os,
                         @Param("lastIp") String lastIp,
                         @Param("lastDate") java.time.LocalDate lastDate,
                         @Param("lastActiveTime") java.time.LocalDateTime lastActiveTime);
}
