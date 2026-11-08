package com.astral.sequence.controller;

import com.astral.common.annotation.RateLimit;
import com.astral.common.constant.SequenceType;
import com.astral.common.result.Result;
import com.astral.sequence.controller.dto.SequenceBatchRequest;
import com.astral.sequence.controller.dto.SequenceRequest;
import com.astral.sequence.controller.dto.SequenceResponse;
import com.astral.sequence.service.SequenceResult;
import com.astral.sequence.service.SequenceService;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/all/sequence")
@RequiredArgsConstructor
public class SequenceController {
    private final SequenceService sequenceService;

    @RateLimit(key = "user", limit = 100, duration = 60, message = "序列生成请求过于频繁，请稍后再试")
    @PostMapping("/next")
    public Result<SequenceResponse> next(@Valid @RequestBody SequenceRequest request) {
        SequenceResult result = sequenceService.next(request.getBizKey(), request.getType());
        SequenceResponse response = new SequenceResponse(
                request.getBizKey(), result.type(), Long.toString(result.value()), null, System.currentTimeMillis());
        return Result.success(response);
    }

    @RateLimit(key = "user", limit = 50, duration = 60, message = "批量生成请求过于频繁，请稍后再试")
    @PostMapping("/batch")
    public Result<SequenceResponse> batch(@Valid @RequestBody SequenceBatchRequest request) {
        SequenceResult result = sequenceService.batch(request.getBizKey(), request.getCount(), request.getType());
        SequenceResponse response = new SequenceResponse(
                request.getBizKey(),
                result.type(),
                Long.toString(result.value()),
                result.values(),
                System.currentTimeMillis());
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
                        "description", getDescription(t)))
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
