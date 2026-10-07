package com.astral.system.notify;

import java.util.Map;

import com.astral.dao.entity.SysNotice;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 站内信（INAPP 渠道）发送与收件箱。
 *
 * <p>投递落<b>统一通知表 sys_notice</b>（点对点 + 消息中心位 + 服务端 read_time），
 * 与 feedback 插件的通知/铃铛同表共用；模板复用 {@code sys_mail_template}
 * （channel=INAPP，scene=事件码，与邮件共用 (scene,channel) 唯一绑定），
 * 发送侧按模板行渲染标题与正文。事件→模板的解析归订阅规则（NotifyPublisher 按规则
 * 的 template_id 显式指定），本服务不负责事件解析。</p>
 */
public interface InappService {

    /** 原始直投（平台内部通知，类型标签 announce、平台 all） */
    void sendRaw(Long userId, String title, String content, String scene);

    /**
     * 按模板行投递（订阅规则用：规则的 template_id 显式指定模板，platform 为规则上的
     * 平台定向，空则 all）。模板不存在或 channel != INAPP 时抛 IllegalStateException；
     * 类型标签：variables 的 meta 字段 {@code noticeType} > 模板 scene 对应事件定义的
     * 回退标签 > {@code announce}。
     */
    void sendTemplateRow(Long templateId, Long userId, Map<String, String> variables, String platform);

    /** 某用户收件箱（时间倒序，仅消息中心位） */
    Page<SysNotice> myPage(Long userId, int pageNum, int pageSize);

    /** 未读数（点对点 + read_time 为空） */
    long unreadCount(Long userId);

    /** 标记已读（只能读自己的，否则抛 INAPP001） */
    void markRead(Long userId, Long id);

    /** 全部已读 */
    void markAllRead(Long userId);
}
