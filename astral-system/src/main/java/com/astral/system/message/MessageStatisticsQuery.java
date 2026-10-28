package com.astral.system.message;

import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;

import lombok.Data;

/**
 * 消息统计/明细的公共筛选条件，直接由管理端 GET 查询参数绑定（参数名 = 字段名）。
 *
 * <p>三个接口（overview / trend / list）共用同一份筛选对象，保证「概览、趋势、明细」
 * 在同一组条件下口径一致：时间范围对 EMAIL/SMS 用 {@code send_time}、
 * 对 INAPP 用 {@code create_time}。</p>
 */
@Data
public class MessageStatisticsQuery {

    /** 起始时间（含），格式 yyyy-MM-dd HH:mm:ss；空=不限 */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime start;

    /** 结束时间（含），格式 yyyy-MM-dd HH:mm:ss；空=不限 */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime end;

    /** 投递渠道：EMAIL / SMS / INAPP（忽略大小写）；空=全部 */
    private String channel;

    /** 场景（事件码，精确匹配） */
    private String scene;

    /** 1 成功 / 0 失败；INAPP 恒为成功，传 0 时站内信不参与统计 */
    private Integer status;

    /** 发送方插件；站内信固定归为 feedback，传其它插件时站内信不参与统计 */
    private String pluginId;

    /** 关键字：模糊匹配收件人 / 标题 / 主题 / 正文（忽略大小写） */
    private String keyword;

    /** 趋势窗口天数：默认 7，上限 90（仅 /trend 使用） */
    private Integer days;

    /** 页码，从 1 开始（仅 /list 使用） */
    private Integer pageNum;

    /** 每页条数（仅 /list 使用，上限 200） */
    private Integer pageSize;
}
