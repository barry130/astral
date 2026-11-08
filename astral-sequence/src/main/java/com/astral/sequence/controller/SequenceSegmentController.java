package com.astral.sequence.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.common.util.PageQuery;
import com.astral.dao.entity.SequenceSegment;
import com.astral.sequence.service.SequenceSegmentService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 号段分配控制器
 * <p>
 * 号段表是发号器的运行时状态，只能由 {@code SegmentGenerator} 通过乐观锁维护。
 * 管理端只提供只读查询；原先生成的创建/更新/删除接口会把 max_value/version 直接暴露给
 * 任意持有 {@code admin:sequence:edit} 的账号，一旦误删号段行，下一次发号会重新从 1 开始，
 * 与已有主键冲突。为确保 ID 不重复，这里不提供写接口。
 * </p>
 */
@Tag(name = "号段分配表")
@RestController
@RequestMapping("/api/v1/admin/sequence/segment")
@RequiresPermission("admin:sequence:view")
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
    public Result<Page<SequenceSegment>> page(
            @RequestParam(defaultValue = "1") Integer pageNum, @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<SequenceSegment> page = new Page<>(PageQuery.pageNum(pageNum), PageQuery.pageSize(pageSize));
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
        SequenceSegment segment = sequenceSegmentService.getById(id);
        if (segment == null) {
            throw BusinessException.of("号段记录不存在");
        }
        return Result.success(segment);
    }
}
