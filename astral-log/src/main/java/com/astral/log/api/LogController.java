package com.astral.log.api;

import com.astral.common.result.Result;
import com.astral.dao.entity.LoginLog;
import com.astral.dao.entity.OperateLog;
import com.astral.dao.mapper.LoginLogMapper;
import com.astral.dao.mapper.OperateLogMapper;
import com.astral.log.service.LogService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

/**
 * 日志管理控制器
 * <p>
 * 提供操作日志和登录日志的分页查询接口。
 * 接口路径前缀：{@code /api/v1/log}
 * </p>
 */
@RestController
@RequestMapping("/api/v1/log")
@RequiredArgsConstructor
public class LogController {
    /** 日志服务 */
    private final LogService logService;
    /** 操作日志Mapper，用于直接查询操作日志 */
    private final OperateLogMapper operateLogMapper;
    /** 登录日志Mapper，用于直接查询登录日志 */
    private final LoginLogMapper loginLogMapper;

    /**
     * 分页查询操作日志
     * <p>
     * 支持按用户名模糊查询，结果按创建时间倒序排列。
     * </p>
     *
     * @param pageNum   页码，默认1
     * @param pageSize  每页大小，默认20
     * @param username  用户名（可选，支持模糊匹配）
     * @param operation 操作类型（可选，精确匹配 operate_type）
     * @param startTime 开始时间（可选，含当天零点，格式 yyyy-MM-dd）
     * @param endTime   结束时间（可选，含次日零点，格式 yyyy-MM-dd）
     * @return 分页结果，包含记录列表、总数、页码等信息
     */
    @GetMapping("/operate")
    public Result<Map<String, Object>> getOperateLogPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {

        Page<OperateLog> page = new Page<>(pageNum, pageSize);
        QueryWrapper<OperateLog> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("create_time");

        if (username != null && !username.isEmpty()) {
            wrapper.like("username", username);
        }
        // 此前 operation / startTime / endTime 三个参数声明了却完全没参与查询，
        // 前端"按操作类型筛选""按时间区间筛选"实际是静默失效的（看起来查了，其实返回全量）。
        if (operation != null && !operation.isEmpty()) {
            wrapper.eq("operate_type", operation);
        }
        LocalDateTime from = parseDateStart(startTime);
        LocalDateTime to = parseDateEnd(endTime);
        if (from != null) {
            wrapper.ge("create_time", from);
        }
        if (to != null) {
            wrapper.lt("create_time", to);
        }

        Page<OperateLog> result = operateLogMapper.selectPage(page, wrapper);
        
        Map<String, Object> response = new HashMap<>();
        response.put("records", result.getRecords());
        response.put("total", result.getTotal());
        response.put("size", result.getSize());
        response.put("current", result.getCurrent());
        response.put("pages", result.getPages());
        
        return Result.success(response);
    }

    /**
     * 分页查询登录日志
     * <p>
     * 支持按用户名模糊查询，结果按登录时间倒序排列。
     * </p>
     *
     * @param pageNum  页码，默认1
     * @param pageSize 每页大小，默认20
     * @param username 用户名（可选，支持模糊匹配）
     * @return 分页结果，包含记录列表、总数、页码等信息
     */
    @GetMapping("/login")
    public Result<Map<String, Object>> getLoginLogPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String username) {
        
        Page<LoginLog> page = new Page<>(pageNum, pageSize);
        QueryWrapper<LoginLog> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("login_time");
        
        if (username != null && !username.isEmpty()) {
            wrapper.like("username", username);
        }
        
        Page<LoginLog> result = loginLogMapper.selectPage(page, wrapper);
        
        Map<String, Object> response = new HashMap<>();
        response.put("records", result.getRecords());
        response.put("total", result.getTotal());
        response.put("size", result.getSize());
        response.put("current", result.getCurrent());
        response.put("pages", result.getPages());
        
        return Result.success(response);
    }

    /**
     * 解析起始时间（yyyy-MM-dd → 当天 00:00:00）
     *
     * @param date 日期字符串，可为 null / 空 / 非法
     * @return 解析成功返回当天零点；否则返回 null（表示不加该过滤条件）
     */
    private static LocalDateTime parseDateStart(String date) {
        LocalDate d = parseDate(date);
        return d != null ? d.atStartOfDay() : null;
    }

    /**
     * 解析结束时间（yyyy-MM-dd → 次日 00:00:00 的前一刻）
     * <p>用「小于次日零点」而不是「当天 23:59:59」，避免丢掉 23:59:59.5 这类带毫秒的记录。</p>
     *
     * @param date 日期字符串，可为 null / 空 / 非法
     * @return 解析成功返回次日零点（配合 le 使用）；否则返回 null
     */
    private static LocalDateTime parseDateEnd(String date) {
        LocalDate d = parseDate(date);
        return d != null ? d.plusDays(1).atStartOfDay() : null;
    }

    private static LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            // 非法日期不报错，退化为"不加该过滤条件"，避免前端传错格式直接 500
            return null;
        }
    }
}