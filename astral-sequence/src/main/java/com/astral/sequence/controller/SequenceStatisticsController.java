package com.astral.sequence.controller;

import com.astral.dao.entity.SequenceStatistics;
import com.astral.sequence.service.SequenceStatisticsService;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 序列统计控制器
 * <p>提供序列使用统计数据的CRUD操作</p>
 */
@Tag(name = "序列统计表")
@RestController
@RequestMapping("/api/v1/sequence/statistics")
@RequiredArgsConstructor
public class SequenceStatisticsController {

    /** 序列统计服务 */
    private final SequenceStatisticsService sequenceStatisticsService;

    /**
     * 分页查询序列统计列表
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @return 分页序列统计数据
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<SequenceStatistics>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<SequenceStatistics> page = new Page<>(pageNum, pageSize);
        return Result.success(sequenceStatisticsService.page(page));
    }

    /**
     * 根据ID查询序列统计详情
     *
     * @param id 统计记录ID
     * @return 序列统计实体
     */
    @Operation(summary = "根据ID查询")
    @GetMapping("/{id}")
    public Result<SequenceStatistics> getById(@PathVariable Long id) {
        return Result.success(sequenceStatisticsService.getById(id));
    }

    /**
     * 创建序列统计记录
     *
     * @param entity 序列统计实体
     * @return 操作结果
     */
    @Operation(summary = "创建")
    @PostMapping
    public Result<Void> create(@RequestBody SequenceStatistics entity) {
        sequenceStatisticsService.save(entity);
        return Result.success();
    }

    /**
     * 更新序列统计记录
     *
     * @param id 统计记录ID
     * @param entity 序列统计实体（包含更新后的字段）
     * @return 操作结果
     */
    @Operation(summary = "更新")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SequenceStatistics entity) {
        entity.setId(id);
        sequenceStatisticsService.updateById(entity);
        return Result.success();
    }

    /**
     * 删除序列统计记录
     *
     * @param id 统计记录ID
     * @return 操作结果
     */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sequenceStatisticsService.removeById(id);
        return Result.success();
    }
}
