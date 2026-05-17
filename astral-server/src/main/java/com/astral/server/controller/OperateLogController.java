package com.astral.server.controller;

import com.astral.dao.entity.OperateLog;
import com.astral.server.service.OperateLogService;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 操作日志控制器
 * <p>提供操作日志的CRUD操作</p>
 */
@Tag(name = "操作日志表")
@RestController
@RequestMapping("/api/v1/log/operate_log")
@RequiredArgsConstructor
public class OperateLogController {

    /** 操作日志服务 */
    private final OperateLogService operateLogService;

    /**
     * 分页查询操作日志列表
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @return 分页操作日志数据
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<OperateLog>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<OperateLog> page = new Page<>(pageNum, pageSize);
        return Result.success(operateLogService.page(page));
    }

    /**
     * 根据ID查询操作日志详情
     *
     * @param id 日志ID
     * @return 操作日志实体
     */
    @Operation(summary = "根据ID查询")
    @GetMapping("/{id}")
    public Result<OperateLog> getById(@PathVariable Long id) {
        return Result.success(operateLogService.getById(id));
    }

    /**
     * 创建操作日志
     *
     * @param entity 操作日志实体
     * @return 操作结果
     */
    @Operation(summary = "创建")
    @PostMapping
    public Result<Void> create(@RequestBody OperateLog entity) {
        operateLogService.save(entity);
        return Result.success();
    }

    /**
     * 更新操作日志
     *
     * @param id 日志ID
     * @param entity 操作日志实体（包含更新后的字段）
     * @return 操作结果
     */
    @Operation(summary = "更新")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody OperateLog entity) {
        entity.setId(id);
        operateLogService.updateById(entity);
        return Result.success();
    }

    /**
     * 删除操作日志
     *
     * @param id 日志ID
     * @return 操作结果
     */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        operateLogService.removeById(id);
        return Result.success();
    }
}
