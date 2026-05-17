package com.astral.sequence.controller;

import com.astral.dao.entity.SequenceHistory;
import com.astral.sequence.service.SequenceHistoryService;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 序列生成历史控制器
 * <p>
 * 提供序列号生成历史的查询接口，支持分页查询和获取最近记录。
 * 历史记录用于审计和追踪序列号的生成情况。
 * </p>
 */
@Tag(name = "序列生成历史表")
@RestController
@RequestMapping("/api/v1/sequence/history")
@RequiredArgsConstructor
public class SequenceHistoryController {

    /** 序列历史服务，负责历史记录的查询操作 */
    private final SequenceHistoryService sequenceHistoryService;

    /**
     * 分页查询序列生成历史
     *
     * @param pageNum  页码，从 1 开始，默认为 1
     * @param pageSize 每页大小，默认为 10
     * @param bizKey   可选的业务键过滤条件
     * @return 分页后的历史记录列表，按创建时间倒序排列
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<IPage<SequenceHistory>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "10") Integer pageSize,
                                               @RequestParam(required = false) String bizKey) {
        return Result.success(sequenceHistoryService.getPage(pageNum, pageSize, bizKey));
    }

    /**
     * 获取最近的序列生成记录
     * <p>
     * 返回最近生成的序列号记录，限制数量最大为 1000，防止一次性加载过多数据。
     * </p>
     *
     * @param bizKey 可选的业务键过滤条件
     * @param limit  返回记录数量，默认为 100
     * @return 最近的历史记录列表
     */
    @Operation(summary = "最近记录")
    @GetMapping("/recent")
    public Result<List<SequenceHistory>> recent(@RequestParam(required = false) String bizKey,
                                                @RequestParam(defaultValue = "100") Integer limit) {
        return Result.success(sequenceHistoryService.getRecent(bizKey, limit));
    }
}
