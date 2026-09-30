package com.astral.dao.mapper;

import com.astral.dao.entity.StatDevice;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

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

    /**
     * 查询某平台下出现过的客户端版本列表（去重）
     * <p>
     * 设备登记表是所有 App 上报的注册表，因此它是「平台 × 版本」最权威的来源；
     * 统计页三个 Tab 的版本下拉统一取这里，保证与上报口径一致。
     * 排序按该版本最近活跃日期倒序 —— 比字符串排序更符合直觉
     * （字符串倒序会把 9.x 排在 10.x 之前）。
     * </p>
     *
     * @param ut 平台标识（调用方保证非 all/空）
     */
    @Select("SELECT app_version FROM stat_device " +
            "WHERE ut = #{ut} AND app_version IS NOT NULL AND app_version <> '' " +
            "GROUP BY app_version " +
            "ORDER BY MAX(last_date) DESC, app_version DESC")
    List<String> selectVersionsByUt(@Param("ut") String ut);
}
