package com.astral.sequence.controller;

import com.astral.common.annotation.RateLimit;
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

@RestController
@RequestMapping("/api/v1/all/sequence")
@RequiredArgsConstructor
public class SequenceController {
    private final SequenceService sequenceService;

    @RateLimit(key = "user", limit = 100, duration = 60, message = "序列生成请求过于频繁，请稍后再试")
    @OperateLog("获取序列号")
    @PostMapping("/next")
    public Result<SequenceResponse> next(@Valid @RequestBody SequenceRequest request) {
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

    @RateLimit(key = "user", limit = 50, duration = 60, message = "批量生成请求过于频繁，请稍后再试")
    @OperateLog("批量获取序列号")
    @PostMapping("/batch")
    public Result<SequenceResponse> batch(@Valid @RequestBody SequenceBatchRequest request) {
        String type = request.getType() != null ? request.getType() : null;
        String seqStr = sequenceService.batch(request.getBizKey(), request.getCount(), type);
        String[] parts = seqStr.split(",");
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
