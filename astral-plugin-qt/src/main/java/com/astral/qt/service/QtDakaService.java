package com.astral.qt.service;

import com.astral.qt.common.QtException;
import com.astral.qt.dto.QtUserDakaDto;
import com.astral.qt.dto.vo.QtDakaDaysAndCodeVo;
import com.astral.qt.entity.QtUserDaka;
import com.astral.qt.mapper.QtUserDakaMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 轻听签到服务
 * <ul>
 *   <li>签到积分规则：连续 0-6 天 +1，7-29 天 +3，30-364 天 +5，365+ 天 +10</li>
 *   <li>补签开关 qt.user.is-re-daka-enable（默认 false：仅可签当天）</li>
 *   <li>使用积分签到 type=1：需 ≥10 有效积分，当次积分照常发放，但从有效积分中扣除 10</li>
 * </ul>
 */
@Slf4j
@Service
public class QtDakaService extends ServiceImpl<QtUserDakaMapper, QtUserDaka> {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Value("${qt.user.is-re-daka-enable:false}")
    private String isReDakaEnable;

    @Resource
    private QtUserDakaMapper dakaMapper;

    /** 连续签到天数 */
    public int getRunningDayCount(Long uid, LocalDate anchor) {
        List<LocalDate> dates = dakaMapper.getAllDakaDates(uid);
        if (dates.isEmpty()) return 0;
        Set<LocalDate> set = new HashSet<>(dates);
        int count = 0;
        LocalDate cur = anchor;
        while (set.contains(cur)) {
            count++;
            cur = cur.minusDays(1);
        }
        return count;
    }

    public void daka(Long uid, QtUserDakaDto dto) {
        LocalDate date = LocalDate.parse(dto.getTime(), DAY_FMT);
        // 日期不能是未来
        if (LocalDate.now().isBefore(date)) {
            throw new QtException("还没到该日期哦，无法签到~");
        }
        // 不支持补签时，仅允许当天
        if (!"true".equalsIgnoreCase(isReDakaEnable) && !LocalDate.now().isEqual(date)) {
            throw new QtException("不支持签到非今日的日期哦~");
        }

        // 是否已签
        List<LocalDate> monthDays = dakaMapper.getDayByMonth(uid, date.getYear(), date.getMonthValue());
        if (monthDays.contains(date)) {
            throw new QtException("该日期已经签到过了哦，无法继续签到~");
        }

        int code = 0;
        if (LocalDate.now().isEqual(date)) {
            int runningDays = getRunningDayCount(uid, date);
            if (runningDays >= 0 && runningDays < 7) code = 1;
            else if (runningDays < 30) code = 3;
            else if (runningDays < 365) code = 5;
            else code = 10;
        }

        // 使用积分签到需要 ≥10 有效积分
        if ("1".equals(dto.getType())) {
            int integral = getAllIntegral(uid);
            if (integral < 10) {
                throw new QtException("当前积分不够使用积分签到哦，请使用正常签到~");
            }
        }

        QtUserDaka daka = new QtUserDaka();
        daka.setUid(uid);
        daka.setData(date);
        daka.setIntegral((long) code);
        daka.setIsUseCode(Long.valueOf(dto.getType()));
        daka.setCreateTime(java.time.LocalDateTime.now());
        daka.setUpdateTime(java.time.LocalDateTime.now());
        this.save(daka);
    }

    public QtDakaDaysAndCodeVo getDakaDaysAndCode(Long uid) {
        LocalDate today = LocalDate.now();
        // 连续签到以「今天」为锚点；如果今天还没签，则以昨天为锚点统计历史连续天数
        int streak;
        List<LocalDate> month = dakaMapper.getDayByMonth(uid, today.getYear(), today.getMonthValue());
        boolean todayDone = month.contains(today);
        if (todayDone) {
            streak = getRunningDayCount(uid, today);
        } else {
            LocalDate yesterday = today.minusDays(1);
            streak = month.contains(yesterday) ? getRunningDayCount(uid, yesterday) : 0;
        }
        QtDakaDaysAndCodeVo vo = new QtDakaDaysAndCodeVo();
        vo.setDays(streak);
        vo.setCode(getAllIntegral(uid));
        vo.setTodayDone(todayDone);
        return vo;
    }

    public List<String> getDakaInfoByMonth(Long uid, String time) {
        // 前端传入 yyyy-MM，这里补全为 yyyy-MM-01
        LocalDate date = LocalDate.parse(time + "-01", DAY_FMT);
        List<LocalDate> days = dakaMapper.getDayByMonth(uid, date.getYear(), date.getMonthValue());
        return days.stream().map(LocalDate::toString).toList();
    }

    /** 有效总积分 */
    public int getAllIntegral(Long uid) {
        Integer value = dakaMapper.getAllIntegral(uid);
        return value == null ? 0 : value;
    }
}