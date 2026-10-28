package com.astral.system.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 消息统计概览（三渠道聚合视图）。
 *
 * <p>把三张物理表合成一个「消息」概念：{@code sys_mail_log} → EMAIL、
 * {@code sys_sms_log} → SMS、{@code sys_notice} → INAPP。所有数字都产自**同一组筛选条件**
 * （时间 / 渠道 / 场景 / 状态 / 插件 / 关键字）下的同一个行集，因此
 * {@code total/success/fail} 与 {@code channels}、{@code scenes}、{@code plugins}
 * 三个 breakdown 必然自洽（各 breakdown 之和 = total）。</p>
 *
 * <p><b>站内信没有失败态</b>：{@code fail} 恒为 0，{@code success} 等于该渠道总量。
 * 它的发送方在库里没有独立列（{@code sys_notice} 只由反馈事件写入），
 * 统一归为插件 {@code feedback}；邮件的 {@code plugin_id} 为空时归为 {@code unknown}。</p>
 */
@Data
public class MessageStatisticsVo {

    /** 命中筛选的投递总数（EMAIL + SMS + INAPP） */
    private long total;

    /** 成功数（status=1；INAPP 全部计入） */
    private long success;

    /** 失败数（status=0；INAPP 恒为 0） */
    private long fail;

    /** 成功率（0-100，保留 1 位小数；total=0 时为 0.0） */
    private double successRate;

    /** 今日投递数（按各渠道的本地发送时间落在今天统计） */
    private long todayTotal;

    /** 今日成功数 */
    private long todaySuccess;

    /** 今日失败数 */
    private long todayFail;

    /** 按投递渠道聚合，顺序固定 EMAIL / SMS / INAPP（无数据的渠道也会出现，计数为 0） */
    private List<ChannelStat> channels = new ArrayList<>();

    /** 按场景（事件码）聚合，按总量倒序 */
    private List<SceneStat> scenes = new ArrayList<>();

    /** 按发送方插件聚合，按总量倒序 */
    private List<PluginStat> plugins = new ArrayList<>();

    /** 单渠道计数 */
    @Data
    public static class ChannelStat {

        /** 投递渠道：EMAIL / SMS / INAPP */
        private String channel;

        private long total;

        private long success;

        private long fail;
    }

    /** 单场景计数（scene 为事件码；库里为空时归为 unknown） */
    @Data
    public static class SceneStat {

        private String scene;

        private long total;

        private long success;

        private long fail;
    }

    /** 单插件计数（pluginId；库里为空时归为 unknown） */
    @Data
    public static class PluginStat {

        private String pluginId;

        private long total;

        private long success;

        private long fail;
    }
}
