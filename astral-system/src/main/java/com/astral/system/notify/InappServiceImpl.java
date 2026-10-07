package com.astral.system.notify;

import java.time.LocalDateTime;
import java.util.Map;

import com.astral.common.constant.NoticeConstants;
import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.entity.SysNotice;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.dao.mapper.SysNoticeMapper;
import com.astral.system.notify.NotifyEventDef;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 站内信（INAPP 渠道）实现：投递落<b>统一通知表 sys_notice</b>（点对点行：
 * user_id=收件人、display 含消息中心位(4)、channel=all），与 feedback 插件的
 * 通知/公告/管理端铃铛共用一张表一份展示逻辑；本渠道新增的增量是
 * 服务端已读（read_time）——广播行不在此列（已读仍由客户端缓存判定）。
 * 模板渲染收口在 {@link #render}（HTML 转义，收件人是普通用户）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InappServiceImpl implements InappService {

    /** 展示位：消息中心（与 feedback 插件 FeedbackNoticeService.DISPLAY_MESSAGE_CENTER 同值） */
    private static final long DISPLAY_MESSAGE_CENTER = 4L;

    private final SysNoticeMapper noticeMapper;
    private final SysMailTemplateMapper mailTemplateMapper;

    @Override
    public void sendRaw(Long userId, String title, String content, String scene) {
        insert(userId, title == null ? "" : title, content == null ? "" : content, scene, NoticeConstants.TYPE_ANNOUNCE, null);
    }

    @Override
    public void sendTemplateRow(Long templateId, Long userId, Map<String, String> variables, String platform) {
        SysMailTemplate tpl = mailTemplateMapper.selectById(templateId);
        if (tpl == null || !NotifyChannel.INAPP.equals(tpl.getChannel())) {
            throw new IllegalStateException("站内信模板不存在或渠道不是 INAPP（templateId=" + templateId + "）");
        }
        // 类型标签：payload meta 字段 > 事件定义回退值 > announce（App 端消息中心按此打标签）
        String noticeType = variables == null ? null : variables.get("noticeType");
        if (noticeType == null || noticeType.isBlank()) {
            noticeType = NotifyEventRegistry.find(tpl.getScene()).map(NotifyEventDef::noticeType)
                    .filter(v -> v != null && !v.isBlank()).orElse(NoticeConstants.TYPE_ANNOUNCE);
        }
        insert(userId, render(tpl.getSubject(), variables), render(tpl.getContent(), variables),
                tpl.getScene(), noticeType, platform);
    }

    private void insert(Long userId, String title, String content, String scene, String noticeType, String platform) {
        SysNotice row = new SysNotice();
        row.setUserId(userId);
        row.setTitle(title);
        row.setContent(content);
        row.setScene(scene);
        row.setNoticeType(noticeType);
        row.setChannel(platform == null || platform.isBlank() ? NoticeConstants.CHANNEL_ALL : platform);
        row.setDisplay(DISPLAY_MESSAGE_CENTER);
        row.setIsShow(1L);
        row.setIsTop(0L);
        row.setAudience("ALL");
        try {
            noticeMapper.insert(row);
        } catch (Exception e) {
            // 投递失败不阻断主流程（站内信是通知性质），记错误日志由运维兜底
            log.error("[Inapp] 站内信写入失败 user={}: {}", userId, e.getMessage());
        }
    }

    @Override
    public Page<SysNotice> myPage(Long userId, int pageNum, int pageSize) {
        Page<SysNotice> page = noticeMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<SysNotice>()
                        .eq(SysNotice::getUserId, userId)
                        .eq(SysNotice::getIsShow, 1L)
                        .orderByDesc(SysNotice::getCreateTime));
        // display 是多值位掩码列，按展示位过滤放 Java 侧（与 feedback 插件读路径同款选择）
        page.getRecords().removeIf(n -> n.getDisplay() == null || (n.getDisplay() & DISPLAY_MESSAGE_CENTER) != DISPLAY_MESSAGE_CENTER);
        return page;
    }

    @Override
    public long unreadCount(Long userId) {
        Long n = noticeMapper.selectCount(new LambdaQueryWrapper<SysNotice>()
                .eq(SysNotice::getUserId, userId)
                .eq(SysNotice::getIsShow, 1L)
                .isNull(SysNotice::getReadTime));
        return n == null ? 0 : n;
    }

    @Override
    public void markRead(Long userId, Long id) {
        SysNotice row = noticeMapper.selectById(id);
        if (row == null || !userId.equals(row.getUserId())) {
            // 不区分「不存在」与「不是你的」，避免探测他人消息 ID
            throw new BusinessException("INAPP001", id);
        }
        if (row.getReadTime() == null) {
            SysNotice update = new SysNotice();
            update.setId(id);
            update.setReadTime(LocalDateTime.now());
            noticeMapper.updateById(update);
        }
    }

    @Override
    public void markAllRead(Long userId) {
        noticeMapper.update(null, new LambdaUpdateWrapper<SysNotice>()
                .eq(SysNotice::getUserId, userId)
                .eq(SysNotice::getIsShow, 1L)
                .isNull(SysNotice::getReadTime)
                .set(SysNotice::getReadTime, LocalDateTime.now()));
    }

    /** ${var} 占位替换，值 HTML 转义（收件人是普通用户，模板内容是管理员可写的） */
    private String render(String template, Map<String, String> vars) {
        if (template == null) {
            return "";
        }
        String r = template;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                r = r.replace("${" + e.getKey() + "}", escape(e.getValue() == null ? "" : e.getValue()));
            }
        }
        return r;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
