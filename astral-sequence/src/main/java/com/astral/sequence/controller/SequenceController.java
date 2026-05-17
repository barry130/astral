package com.astral.sequence.controller;

import com.astral.common.constant.SequenceType;
import com.astral.common.result.Result;
import com.astral.dao.entity.SequenceHistory;
import com.astral.sequence.controller.dto.SequenceBatchRequest;
import com.astral.sequence.controller.dto.SequenceRequest;
import com.astral.sequence.controller.dto.SequenceResponse;
import com.astral.sequence.service.SequenceHistoryService;
import com.astral.sequence.service.SequenceService;
import com.astral.log.annotation.OperateLog;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 序列号生成控制器
 * <p>
 * 提供序列号生成的 REST API 接口，支持单个获取、批量获取以及查询支持的序列类型。
 * 所有接口都通过 {@link SequenceService} 委托给具体的生成器实现。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/sequence")
@RequiredArgsConstructor
public class SequenceController {
    /** 序列号服务，负责协调各种生成器 */
    private final SequenceService sequenceService;

    /**
     * 获取下一个序列号
     *
     * @param request 序列号请求，包含业务键 bizKey 和可选的生成类型 type
     * @return 包含生成的序列号、业务键、类型和时间戳的响应对象
     */
    @OperateLog("获取序列号")
    @PostMapping("/next")
    public Result<SequenceResponse> next(@Valid @RequestBody SequenceRequest request) {
        // 如果请求中未指定类型，则使用配置中的默认类型
        String type = request.getType() != null ? request.getType() : null;
        long seq = sequenceService.next(request.getBizKey(), type);
        SequenceResponse response = new SequenceResponse(
                request.getBizKey(),
                type,
                seq,
                System.currentTimeMillis()
        );
        return Result.success(response);
    }

    /**
     * 批量获取序列号
     * <p>
     * 一次性生成指定数量的序列号，返回以逗号分隔的字符串。
     * 响应中只包含最后一个序列号，调用方可以通过解析返回字符串获取所有序列号。
     * </p>
     *
     * @param request 批量请求，包含业务键 bizKey、数量 count 和可选的生成类型 type
     * @return 包含最后一个序列号的响应对象
     */
    @OperateLog("批量获取序列号")
    @PostMapping("/batch")
    public Result<SequenceResponse> batch(@Valid @RequestBody SequenceBatchRequest request) {
        String type = request.getType() != null ? request.getType() : null;
        // batch 方法返回逗号分隔的序列号字符串，例如 "1,2,3,4,5"
        String seqStr = sequenceService.batch(request.getBizKey(), request.getCount(), type);
        String[] parts = seqStr.split(",");
        // 取最后一个序列号作为响应值
        long lastSeq = Long.parseLong(parts[parts.length - 1]);
        SequenceResponse response = new SequenceResponse(
                request.getBizKey(),
                type,
                lastSeq,
                System.currentTimeMillis()
        );
        return Result.success(response);
    }

    /**
     * 获取所有支持的序列号生成类型
     * <p>
     * 返回所有 {@link SequenceType} 枚举值的详细信息，包括代码、值和描述。
     * 前端可以使用此接口动态渲染类型选择器。
     * </p>
     *
     * @return 包含所有序列类型信息的列表
     */
    @GetMapping("/types")
    public Result<List<Map<String, String>>> getTypes() {
        List<Map<String, String>> types = Arrays.stream(SequenceType.values())
                .map(t -> Map.of(
                        "code", t.name(),
                        "value", t.getValue(),
                        "description", getDescription(t)
                ))
                .collect(Collectors.toList());
        return Result.success(types);
    }

    /**
     * 获取序列类型的中文描述
     *
     * @param type 序列类型枚举值
     * @return 该类型的中文描述，用于前端展示
     */
    private String getDescription(SequenceType type) {
        return switch (type) {
            case SNOWFLAKE -> "Snowflake算法，高性能分布式ID";
            case SEGMENT -> "号段模式，数据库批量分配，连续ID";
            case REDIS -> "Redis原子递增，分布式安全";
            case DATABASE -> "数据库逐次分配";
            case SIMPLE -> "内存计数器，仅适用于测试";
        };
    }
}
