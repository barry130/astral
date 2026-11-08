package com.astral.sequence.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.common.util.PageQuery;
import com.astral.dao.entity.SequenceStatistics;
import com.astral.sequence.service.SequenceStatisticsService;
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
 * 序列统计控制器
 * <p>
 * 序列统计由发号器异步维护，是只读的运行时观测数据；管理端不提供写入接口，
 * 避免手工改写 current_value 等统计字段造成观测值与真实号段不一致。
 * </p>
 */
@Tag(name = "序列统计表")
@RestController
@RequestMapping("/api/v1/admin/sequence/statistics")
@RequiresPermission("admin:sequence:view")
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
    public Result<Page<SequenceStatistics>> page(
            @RequestParam(defaultValue = "1") Integer pageNum, @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<SequenceStatistics> page = new Page<>(PageQuery.pageNum(pageNum), PageQuery.pageSize(pageSize));
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
        SequenceStatistics statistics = sequenceStatisticsService.getById(id);
        if (statistics == null) {
            throw BusinessException.of("序列统计记录不存在");
        }
        return Result.success(statistics);
    }
}
