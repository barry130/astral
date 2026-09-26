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
     * @param operation 操作类型（预留参数，当前未使用）
     * @param startTime 开始时间（预留参数，当前未使用）
     * @param endTime   结束时间（预留参数，当前未使用）
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
}