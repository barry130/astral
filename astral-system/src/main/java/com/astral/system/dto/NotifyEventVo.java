package com.astral.system.dto;

import java.util.List;

import lombok.Data;

/**
 * 通知事件视图（前端「绑定事件」下拉/说明的数据源，来自代码注册表 {@code NotifyEventRegistry}）。
 */
@Data
public class NotifyEventVo {

    private String code;

    private String name;

    private String description;

    /** 该事件可投递的渠道 */
    private List<String> channels;

    /** 该事件必须保留的渠道（保存规则时强制补全） */
    private List<String> requiredChannels;

    /** 事件载荷字段名列表（变量自动补全用，前端按 string[] 直接用） */
    private List<String> payloadKeys;

    /** 事件载荷字段（含说明，管理端展示用） */
    private List<FieldVo> payloadFields;

    @Data
    public static class FieldVo {

        private String name;

        private String description;
    }
}
