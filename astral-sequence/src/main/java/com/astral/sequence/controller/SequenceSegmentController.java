package com.astral.sequence.controller;

import com.astral.dao.entity.SequenceSegment;
import com.astral.sequence.service.SequenceSegmentService;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 号段分配控制器
 * <p>提供序列号段分配记录的CRUD操作</p>
 */
@Tag(name = "号段分配表")
@RestController
@RequestMapping("/api/v1/sequence/segment")
@RequiredArgsConstructor
public class SequenceSegmentController {

    /** 号段分配服务 */
    private final SequenceSegmentService sequenceSegmentService;

    /**
     * 分页查询号段分配列表
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @return 分页号段分配数据
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<SequenceSegment>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<SequenceSegment> page = new Page<>(pageNum, pageSize);
        return Result.success(sequenceSegmentService.page(page));
    }

    /**
     * 根据ID查询号段分配详情
     *
     * @param id 号段记录ID
     * @return 号段分配实体
     */
    @Operation(summary = "根据ID查询")
    @GetMapping("/{id}")
    public Result<SequenceSegment> getById(@PathVariable Long id) {
        return Result.success(sequenceSegmentService.getById(id));
    }

    /**
     * 创建号段分配记录
     *
     * @param entity 号段分配实体
     * @return 操作结果
     */
    @Operation(summary = "创建")
    @PostMapping
    public Result<Void> create(@RequestBody SequenceSegment entity) {
        sequenceSegmentService.save(entity);
        return Result.success();
    }

    /**
     * 更新号段分配记录
     *
     * @param id 号段记录ID
     * @param entity 号段分配实体（包含更新后的字段）
     * @return 操作结果
     */
    @Operation(summary = "更新")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SequenceSegment entity) {
        entity.setId(id);
        sequenceSegmentService.updateById(entity);
        return Result.success();
    }

    /**
     * 删除号段分配记录
     *
     * @param id 号段记录ID
     * @return 操作结果
     */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sequenceSegmentService.removeById(id);
        return Result.success();
    }
}
