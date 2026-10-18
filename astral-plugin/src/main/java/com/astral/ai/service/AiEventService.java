package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.entity.AiRequestEventEntity;
import com.astral.ai.mapper.AiRequestEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 请求事件流服务。
 *
 * <p>事件是流式输出的唯一事实来源：模型增量、工具进度、引用、结束与失败都先落
 * {@code ai_request_event}，再由客户端按 {@code sequence} 游标拉取（设计文档 §8.2/§8.3）。
 * 这样做的好处是断线重连只需带上 {@code after}，不依赖连接状态。</p>
 *
 * <p>事件序号在单个请求内单调递增。写事件与读事件分属不同线程，
 * 这里用请求维度的单表 {@code max(sequence_no)} 计算下一个序号，并在唯一索引
 * {@code uk_ai_request_event_seq} 冲突时退避重试，避免并发写重号。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiEventService {

    /** 单次拉取默认条数 */
    public static final int DEFAULT_BATCH = 100;

    /** 单次拉取上限（与 DTO 常量一致） */
    public static final int MAX_BATCH = 500;

    /** 冲突重试次数 */
    private static final int MAX_RETRY = 5;

    private final AiRequestEventMapper eventMapper;

    /** 追加事件，返回写入后的序号 */
    public long append(Long requestId, String type, Object data) {
        String payload = AiJson.write(data);
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            long next = nextSequence(requestId);
            AiRequestEventEntity entity = new AiRequestEventEntity();
            entity.setRequestId(requestId);
            entity.setSequenceNo(next);
            entity.setEventType(type);
            entity.setDataJson(payload);
            try {
                eventMapper.insert(entity);
                return next;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发写同一序号：重算后重试
                log.debug("[AI] 事件序号冲突 requestId={} seq={}，重试", requestId, next);
            }
        }
        throw new AiException("AI011");
    }

    /** 按游标读取事件（after 之后，升序） */
    public List<AiRequestEventEntity> read(Long requestId, Long after, Integer limit) {
        int size = limit == null ? DEFAULT_BATCH : Math.max(1, Math.min(MAX_BATCH, limit));
        long cursor = after == null ? 0L : Math.max(0L, after);
        return eventMapper.selectList(new LambdaQueryWrapper<AiRequestEventEntity>()
                .eq(AiRequestEventEntity::getRequestId, requestId)
                .gt(AiRequestEventEntity::getSequenceNo, cursor)
                .orderByAsc(AiRequestEventEntity::getSequenceNo)
                .last("limit " + size));
    }

    /** 读取某请求的全部事件（管理端详情用，带上限） */
    public List<AiRequestEventEntity> readAll(Long requestId, int limit) {
        int size = Math.max(1, Math.min(MAX_BATCH, limit));
        return eventMapper.selectList(new LambdaQueryWrapper<AiRequestEventEntity>()
                .eq(AiRequestEventEntity::getRequestId, requestId)
                .orderByAsc(AiRequestEventEntity::getSequenceNo)
                .last("limit " + size));
    }

    /** 当前最大序号（没有事件返回 0） */
    public long lastSequence(Long requestId) {
        AiRequestEventEntity last = lastEvent(requestId);
        return last == null || last.getSequenceNo() == null ? 0L : last.getSequenceNo();
    }

    /** 最后一条事件（空返回 null） */
    public AiRequestEventEntity lastEvent(Long requestId) {
        List<AiRequestEventEntity> rows = eventMapper.selectList(new LambdaQueryWrapper<AiRequestEventEntity>()
                .eq(AiRequestEventEntity::getRequestId, requestId)
                .orderByDesc(AiRequestEventEntity::getSequenceNo)
                .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 拼接可读文本（用于管理端详情与调试导出） */
    public String concatText(Long requestId) {
        StringBuilder builder = new StringBuilder();
        for (AiRequestEventEntity event : readAll(requestId, MAX_BATCH)) {
            if (!AiConstants.EventType.MESSAGE_DELTA.equals(event.getEventType())) {
                continue;
            }
            var node = AiJson.readObject(event.getDataJson());
            builder.append(AiJson.text(node, "delta", ""));
        }
        return builder.toString();
    }

    /** 清理超期事件（保留策略见 AiProperties.eventRetentionDays） */
    public int prune(LocalDateTime before) {
        if (before == null) {
            return 0;
        }
        return eventMapper.delete(new LambdaQueryWrapper<AiRequestEventEntity>()
                .lt(AiRequestEventEntity::getCreateTime, before));
    }

    /** 事件 → 对外结构（保持 Map 形态，避免控制器重复转换） */
    public List<java.util.Map<String, Object>> toMaps(List<AiRequestEventEntity> events) {
        List<java.util.Map<String, Object>> result = new ArrayList<>();
        for (AiRequestEventEntity event : events) {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("sequence", event.getSequenceNo());
            item.put("type", event.getEventType());
            item.put("data", AiJson.read(event.getDataJson()));
            item.put("createdAt", event.getCreateTime());
            result.add(item);
        }
        return result;
    }

    private long nextSequence(Long requestId) {
        return lastSequence(requestId) + AiConstants.Runtime.EVENT_SEQUENCE_STEP;
    }
}
