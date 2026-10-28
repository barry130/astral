package com.astral.system.dto;

import java.util.List;

import lombok.Data;

/**
 * 消息模板保存入参（新增/更新共用）。
 *
 * <p>{@code channels} 是多选：勾了哪个渠道就落哪几行——EMAIL/INAPP 写
 * {@code sys_mail_template}（两行，同编码不同 channel），SMS 写
 * {@code sys_sms_template}。取消勾选的渠道在保存时被删除，未列出的渠道内容保持不变。</p>
 */
@Data
public class MessageTemplateSaveDto {

    /** 模板编码（必填，同一渠道下唯一） */
    private String templateCode;

    /** 模板名称（必填） */
    private String templateName;

    /** 绑定事件码（必填，必须是代码注册过的事件） */
    private String scene;

    /** 原模板编码：更新时改编码用；新增或未改编码时留空 */
    private String originalCode;

    /** 原事件码：更新时换绑定事件用；未改事件时留空 */
    private String originalScene;

    /** 启用的投递渠道（EMAIL / INAPP / SMS，至少一个） */
    private List<String> channels;

    /** 邮件主题（EMAIL/INAPP） */
    private String subject;

    /** 邮件/站内信正文 HTML（EMAIL/INAPP） */
    private String content;

    /** 模板变量声明（逗号分隔） */
    private String variables;

    /** 备注 */
    private String remark;

    /** 短信供应商侧模板编码（SMS） */
    private String smsProviderTemplateCode;

    /** 短信签名（SMS） */
    private String smsSignName;

    /** 短信模板内容示例（SMS） */
    private String smsContentSample;

    /** 短信报备审核状态（SMS）：0 待报备 / 1 已报备 / 2 审核失败 */
    private Integer smsAuditStatus;

    /** 短信审核备注（SMS） */
    private String smsAuditRemark;
}
