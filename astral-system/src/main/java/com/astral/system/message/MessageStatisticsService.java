package com.astral.system.message;

import java.util.List;

import com.astral.system.dto.MessageDeliveryVo;
import com.astral.system.dto.MessageStatisticsVo;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 消息统计：把邮件日志、短信日志、站内信三张表聚合成一个消息视图。
 *
 * <p>三个方法共用同一个筛选对象 {@link MessageStatisticsQuery}，因此「概览数字、趋势曲线、
 * 明细列表」永远描述的是同一批行。</p>
 */
public interface MessageStatisticsService {

    /** 三渠道统计概览（含 channels / scenes / plugins 三个 breakdown） */
    MessageStatisticsVo overview(MessageStatisticsQuery query);

    /**
     * 按天 × 渠道的趋势点（稠密网格）。
     *
     * <p>窗口由 {@code days}（默认 7、上限 90）推导；显式传了 start/end 时以显式值为准，
     * 跨度超过 90 天则截取末尾 90 天。</p>
     */
    List<TrendPointVo> trend(MessageStatisticsQuery query);

    /** 合并分页明细：三渠道各自过滤后合并、按发送时间倒序、再切页 */
    Page<MessageDeliveryVo> list(MessageStatisticsQuery query);
}
