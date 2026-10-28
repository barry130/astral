package com.astral.system.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 消息模板（合并视图）。
 *
 * <p>一条记录 = 一个「逻辑模板」：模板编码 + 绑定事件 + 该模板实际启用的投递渠道集合。
 * 底层仍按渠道分行存放（EMAIL/INAPP 在 {@code sys_mail_template}，SMS 在
 * {@code sys_sms_template}），本类只做呈现层聚合，避免管理端在两个页面里维护
 * 同一个业务模板的三份内容。</p>
 */
@Data
public class MessageTemplateVo {

    /** 模板编码（同一事件下唯一；同一逻辑模板的各渠道行共用） */
    private String templateCode;

    /** 模板名称 */
    private String templateName;

    /** 绑定事件码（sys_mail_template.scene / sys_sms_template.event_code） */
    private String scene;

    /** 事件展示名（代码注册的事件名，查不到时回退事件码） */
    private String sceneName;

    /** 已启用的投递渠道：EMAIL / INAPP / SMS */
    private List<String> channels;

    /** 邮件主题（EMAIL/INAPP 共用） */
    private String subject;

    /** 邮件/站内信正文（HTML，支持 ${变量}） */
    private String content;

    /** 模板变量声明（逗号分隔） */
    private String variables;

    /** 备注 */
    private String remark;

    /** 短信供应商侧模板编码 */
    private String smsProviderTemplateCode;

    /** 短信签名 */
    private String smsSignName;

    /** 短信模板内容示例（供应商报备用） */
    private String smsContentSample;

    /** 短信报备审核状态：0 待报备 / 1 已报备 / 2 审核失败 */
    private Integer smsAuditStatus;

    /** 短信审核备注 */
    private String smsAuditRemark;

    /** 最后更新时间（各渠道行取最大值） */
    private LocalDateTime updateTime;
}
