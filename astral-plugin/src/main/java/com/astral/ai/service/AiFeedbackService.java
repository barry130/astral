package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.dto.AiAppDtos;
import com.astral.ai.entity.AiFeedbackEntity;
import com.astral.ai.entity.AiMessageEntity;
import com.astral.ai.mapper.AiFeedbackMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息反馈服务。
 *
 * <p>反馈既用于改进 Prompt，也是「重生成」的触发入口（设计文档 §8.4）。
 * 同一用户对同一条消息只保留一条反馈，重复提交视为覆盖。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiFeedbackService {

    private final AiFeedbackMapper feedbackMapper;
    private final AiConversationService conversationService;

    /** 提交或覆盖反馈 */
    public AiFeedbackEntity submit(Long userId, AiMessageEntity message, String feedbackType,
                                   String reasonCode, String comment) {
        String type = feedbackType == null ? null : feedbackType.trim().toUpperCase();
        if (type == null || !AiAppDtos.FEEDBACK_TYPES.contains(type)) {
            throw new AiException("AI008", "不支持的反馈类型: " + feedbackType);
        }
        AiFeedbackEntity existing = find(userId, message.getId(), type);
        AiFeedbackEntity entity = existing == null ? new AiFeedbackEntity() : existing;
        entity.setConversationId(message.getConversationId());
        entity.setMessageId(message.getId());
        entity.setRequestId(message.getRequestId());
        entity.setUserId(userId);
        entity.setAgentId(null);
        entity.setFeedbackType(type);
        entity.setReasonCode(reasonCode == null ? null : com.astral.ai.common.AiJson.truncate(reasonCode, 64));
        entity.setComment(comment == null ? null : com.astral.ai.common.AiJson.truncate(comment, 2000));
        if (existing == null) {
            feedbackMapper.insert(entity);
        } else {
            feedbackMapper.updateById(entity);
        }
        return entity;
    }

    /** 查找同一用户对同一消息的同类反馈 */
    public AiFeedbackEntity find(Long userId, Long messageId, String feedbackType) {
        List<AiFeedbackEntity> rows = feedbackMapper.selectList(new LambdaQueryWrapper<AiFeedbackEntity>()
                .eq(AiFeedbackEntity::getUserId, userId)
                .eq(AiFeedbackEntity::getMessageId, messageId)
                .eq(AiFeedbackEntity::getFeedbackType, feedbackType)
                .orderByDesc(AiFeedbackEntity::getId)
                .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 取消反馈（再次点击已选中的点赞/点踩） */
    public void revoke(Long userId, Long messageId, String feedbackType) {
        AiFeedbackEntity existing = find(userId, messageId, feedbackType);
        if (existing != null) {
            feedbackMapper.deleteById(existing.getId());
        }
    }

    /** 管理端反馈列表 */
    public List<AiFeedbackEntity> search(String feedbackType, Long agentId, LocalDateTime from,
                                         LocalDateTime to, int pageNum, int pageSize) {
        int size = Math.max(1, Math.min(200, pageSize));
        int num = Math.max(1, pageNum);
        return feedbackMapper.selectList(new LambdaQueryWrapper<AiFeedbackEntity>()
                .eq(feedbackType != null && !feedbackType.isBlank(), AiFeedbackEntity::getFeedbackType, feedbackType)
                .eq(agentId != null, AiFeedbackEntity::getAgentId, agentId)
                .ge(from != null, AiFeedbackEntity::getCreateTime, from)
                .le(to != null, AiFeedbackEntity::getCreateTime, to)
                .orderByDesc(AiFeedbackEntity::getId)
                .last("limit " + size + " offset " + ((long) (num - 1) * size)));
    }

    /** 反馈统计：各类型数量 */
    public long countByType(String feedbackType, LocalDateTime from) {
        Long total = feedbackMapper.selectCount(new LambdaQueryWrapper<AiFeedbackEntity>()
                .eq(AiFeedbackEntity::getFeedbackType, feedbackType)
                .ge(from != null, AiFeedbackEntity::getCreateTime, from));
        return total == null ? 0L : total;
    }

    /** 供答疑/管理助手读取用户历史反馈（只读工具用） */
    public List<AiFeedbackEntity> recent(int limit) {
        int size = Math.max(1, Math.min(200, limit));
        return feedbackMapper.selectList(new LambdaQueryWrapper<AiFeedbackEntity>()
                .orderByDesc(AiFeedbackEntity::getId)
                .last("limit " + size));
    }

    /** 校验消息归属并取消息（供控制器复用） */
    public AiMessageEntity requireMessage(Long messageId, Long userId) {
        return conversationService.requireMessage(messageId, userId);
    }

    /** 类型常量（避免控制器硬编码字符串） */
    public static List<String> types() {
        return AiAppDtos.FEEDBACK_TYPES;
    }

    /** 反馈是否属于「重生成」 */
    public static boolean isRegenerate(String feedbackType) {
        return AiConstants.FeedbackType.REGENERATE.equalsIgnoreCase(feedbackType);
    }
}
