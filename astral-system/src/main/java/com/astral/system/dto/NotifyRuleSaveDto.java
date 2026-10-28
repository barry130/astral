package com.astral.system.dto;

import java.util.List;

import lombok.Data;

/**
 * 通知规则保存入参（新增/更新共用，整体覆盖式保存）。
 *
 * <p>一次提交 = 一条规则 + 其全部渠道 + 每个渠道的收件人。服务端按规则 id 做
 * 「差量重建」：删除已移除的渠道/收件人行，保留与新增的重新落库；
 * 事件声明里的必达渠道（{@link com.astral.system.notify.NotifyEventDef#requiredChannels()}）
 * 即使没提交也会被强制补一条启用渠道。</p>
 */
@Data
public class NotifyRuleSaveDto {

    /** 规则名称（必填） */
    private String ruleName;

    /** 绑定事件码（必填，必须是代码注册过的事件） */
    private String eventCode;

    /** 备注 */
    private String remark;

    /** 规则总开关：0 停用 / 1 启用 */
    private Integer enabled;

    /** 渠道配置（至少一个） */
    private List<ChannelDto> channels;

    @Data
    public static class ChannelDto {

        /** 渠道码：EMAIL / SMS / INAPP */
        private String channel;

        /** 模板 id；空 = 按「事件 × 渠道」自动匹配 */
        private Long templateId;

        /** 渠道开关：0 停用 / 1 启用（缺省启用） */
        private Integer enabled;

        /** 每日限额（0 或空 = 不限） */
        private Integer dailyLimit;

        /** 限额口径：RECIPIENT 按收件人 / GLOBAL 全局（缺省 RECIPIENT） */
        private String limitScope;

        /** 平台定向（逗号分隔） */
        private String platform;

        /** 收件人（至少一个） */
        private List<RecipientDto> recipients;
    }

    @Data
    public static class RecipientDto {

        /** FIXED / PAYLOAD_FIELD / ROLE */
        private String type;

        /** 固定值 / payload 字段名 / 角色编码 */
        private String value;
    }
}
