package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiConversationEntity;
import com.astral.ai.entity.AiMessageEntity;
import com.astral.ai.mapper.AiConversationMapper;
import com.astral.ai.mapper.AiMessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 会话与消息持久化服务。
 *
 * <p>会话是「用户 + 智能体 + 客户端」的对话容器，<b>不承载任何行为配置</b>——
 * 行为全部来自请求绑定时的智能体版本快照（设计文档 §4.8）。
 * 因此会话只记录 {@code agentId} 与最近一次使用的版本，允许后续版本升级后继续对话。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConversationService {

    /** 单页消息默认条数 */
    public static final int DEFAULT_PAGE_SIZE = 30;

    /** 单页消息上限 */
    public static final int MAX_PAGE_SIZE = 100;

    /** 标题最大长度 */
    private static final int MAX_TITLE = 200;

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;

    // ==================== 会话 ====================

    /** 用户在某智能体下的会话列表（按最近消息时间倒序） */
    public List<AiConversationEntity> list(Long userId, Long agentId, String clientType) {
        LambdaQueryWrapper<AiConversationEntity> wrapper = new LambdaQueryWrapper<AiConversationEntity>()
                .eq(AiConversationEntity::getUserId, userId)
                .eq(agentId != null, AiConversationEntity::getAgentId, agentId)
                .eq(clientType != null && !clientType.isBlank(),
                        AiConversationEntity::getClientType, clientType)
                .isNull(AiConversationEntity::getDeletedAt)
                .orderByDesc(AiConversationEntity::getPinned)
                .orderByDesc(AiConversationEntity::getLastMessageTime)
                .orderByDesc(AiConversationEntity::getId);
        return conversationMapper.selectList(wrapper);
    }

    /** 按主键取（不过滤归属，内部调用） */
    public AiConversationEntity find(Long id) {
        if (id == null) {
            return null;
        }
        return conversationMapper.selectById(id);
    }

    /** 取会话并校验归属；不存在或不属于该用户抛 AI015 */
    public AiConversationEntity requireOwned(Long id, Long userId) {
        AiConversationEntity entity = find(id);
        if (entity == null || entity.getDeletedAt() != null
                || (userId != null && !userId.equals(entity.getUserId()))) {
            throw new AiException("AI015");
        }
        return entity;
    }

    /** 取会话并校验归属 + 智能体一致（避免客户端拿 A 智能体会话调 B 智能体） */
    public AiConversationEntity requireOwned(Long id, Long userId, Long agentId) {
        AiConversationEntity entity = requireOwned(id, userId);
        if (agentId != null && !agentId.equals(entity.getAgentId())) {
            throw new AiException("AI012");
        }
        return entity;
    }

    /** 新建会话 */
    @Transactional(rollbackFor = Exception.class)
    public AiConversationEntity create(Long userId, AiAgentEntity agent, String clientType, String title) {
        AiConversationEntity entity = new AiConversationEntity();
        entity.setConversationKey("cv_" + UUID.randomUUID().toString().replace("-", ""));
        entity.setAgentId(agent.getId());
        entity.setAgentVersionId(agent.getPublishedVersionId());
        entity.setUserId(userId);
        entity.setClientType(clientType == null ? AiConstants.ClientType.ASTRAL_FRONT : clientType);
        entity.setTitle(normalizeTitle(title, agent.getName()));
        entity.setStatus(AiConstants.ConversationStatus.ACTIVE);
        entity.setMessageCount(0);
        entity.setPinned(Boolean.FALSE);
        conversationMapper.insert(entity);
        return entity;
    }

    /** 重命名 */
    public AiConversationEntity rename(Long id, Long userId, String title) {
        AiConversationEntity entity = requireOwned(id, userId);
        entity.setTitle(normalizeTitle(title, entity.getTitle()));
        conversationMapper.updateById(entity);
        return entity;
    }

    /** 置顶/取消置顶 */
    public AiConversationEntity pin(Long id, Long userId, Boolean pinned) {
        AiConversationEntity entity = requireOwned(id, userId);
        entity.setPinned(Boolean.TRUE.equals(pinned));
        conversationMapper.updateById(entity);
        return entity;
    }

    /** 软删除会话（消息保留，便于审计） */
    public void delete(Long id, Long userId) {
        AiConversationEntity entity = requireOwned(id, userId);
        entity.setDeletedAt(LocalDateTime.now());
        entity.setStatus(AiConstants.ConversationStatus.DELETED);
        conversationMapper.updateById(entity);
    }

    /** 记录一次消息活动：更新标题（首条消息自动命名）、计数、最近时间、当前版本 */
    public void touch(AiConversationEntity conversation, Long agentVersionId, String firstUserContent) {
        if (conversation == null) {
            return;
        }
        boolean defaultTitle = conversation.getTitle() == null || conversation.getTitle().isBlank()
                || "新会话".equals(conversation.getTitle());
        if (defaultTitle && firstUserContent != null && !firstUserContent.isBlank()) {
            conversation.setTitle(truncateTitle(firstUserContent));
        }
        if (agentVersionId != null) {
            conversation.setAgentVersionId(agentVersionId);
        }
        conversation.setMessageCount((conversation.getMessageCount() == null ? 0 : conversation.getMessageCount()) + 1);
        conversation.setLastMessageTime(LocalDateTime.now());
        conversationMapper.updateById(conversation);
    }

    // ==================== 消息 ====================

    /** 分页读取消息（beforeId 游标向更早翻页） */
    public List<AiMessageEntity> messages(Long conversationId, Long beforeId, Integer limit) {
        int size = limit == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(MAX_PAGE_SIZE, limit));
        LambdaQueryWrapper<AiMessageEntity> wrapper = new LambdaQueryWrapper<AiMessageEntity>()
                .eq(AiMessageEntity::getConversationId, conversationId)
                .lt(beforeId != null && beforeId > 0, AiMessageEntity::getId, beforeId)
                .orderByDesc(AiMessageEntity::getId)
                .last("limit " + size);
        List<AiMessageEntity> rows = messageMapper.selectList(wrapper);
        rows.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        return rows;
    }

    /** 取消息并校验归属（通过会话） */
    public AiMessageEntity requireMessage(Long messageId, Long userId) {
        AiMessageEntity message = messageMapper.selectById(messageId);
        if (message == null) {
            throw new AiException("AI014");
        }
        requireOwned(message.getConversationId(), userId);
        return message;
    }

    /** 取单条消息（不存在返回 null） */
    public AiMessageEntity message(Long messageId) {
        return messageId == null ? null : messageMapper.selectById(messageId);
    }

    /** 某请求下的全部消息（用户消息 + 助手消息 + 工具消息） */
    public List<AiMessageEntity> byRequest(Long requestId) {
        if (requestId == null) {
            return List.of();
        }
        return messageMapper.selectList(new LambdaQueryWrapper<AiMessageEntity>()
                .eq(AiMessageEntity::getRequestId, requestId)
                .orderByAsc(AiMessageEntity::getId));
    }

    /** 新增消息 */
    public AiMessageEntity append(AiMessageEntity message) {
        if (message.getStatus() == null) {
            message.setStatus(AiConstants.MessageStatus.COMPLETED);
        }
        if (message.getContentType() == null) {
            message.setContentType("TEXT");
        }
        messageMapper.insert(message);
        return message;
    }

    /** 更新消息（流式增量落库） */
    public void update(AiMessageEntity message) {
        messageMapper.updateById(message);
    }

    private String normalizeTitle(String title, String fallback) {
        String text = title == null ? null : title.trim();
        if (text == null || text.isEmpty()) {
            text = fallback == null ? "新会话" : fallback;
        }
        return truncateTitle(text);
    }

    private String truncateTitle(String text) {
        String trimmed = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (trimmed.length() > MAX_TITLE) {
            return trimmed.substring(0, MAX_TITLE);
        }
        return trimmed;
    }
}
