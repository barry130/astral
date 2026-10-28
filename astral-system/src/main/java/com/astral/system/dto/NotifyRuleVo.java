package com.astral.system.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 通知规则视图（规则 / 渠道 / 收件人 三表聚合后的出参）。
 *
 * <p>一条规则 = 一个事件 + 若干渠道；每个渠道有自己的模板、限额与平台定向，
 * 以及若干收件人（FIXED 固定值 / PAYLOAD_FIELD 取事件载荷字段 / ROLE 按角色展开）。</p>
 */
@Data
public class NotifyRuleVo {

    private Long id;

    /** 规则名称（同一事件下建议语义化，如「反馈状态变更-站内信+邮件」） */
    private String ruleName;

    /** 绑定事件码 */
    private String eventCode;

    /** 事件展示名（由注册表补全，前端不用再查一次） */
    private String eventName;

    /** 备注 */
    private String remark;

    /** 规则总开关：0 停用 / 1 启用 */
    private Integer enabled;

    /** 平台定向合集（各渠道平台的并集，便于列表展示） */
    private List<String> platforms;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 渠道配置 */
    private List<ChannelVo> channels;

    @Data
    public static class ChannelVo {

        private Long id;

        /** 渠道码：EMAIL / SMS / INAPP */
        private String channel;

        /** 该渠道使用的模板 id；空 = 按「事件 × 渠道」自动匹配 */
        private Long templateId;

        /** 模板展示名（补全用，前端不必再查） */
        private String templateName;

        /** 渠道开关：0 停用 / 1 启用 */
        private Integer enabled;

        /** 每日限额（0 或空 = 不限） */
        private Integer dailyLimit;

        /** 限额口径：RECIPIENT 按收件人 / GLOBAL 全局 */
        private String limitScope;

        /** 平台定向（逗号分隔，all 表示全部） */
        private String platform;

        /** 收件人 */
        private List<RecipientVo> recipients;
    }

    @Data
    public static class RecipientVo {

        /** FIXED / PAYLOAD_FIELD / ROLE */
        private String type;

        /** 固定值 / payload 字段名 / 角色编码 */
        private String value;
    }
}
