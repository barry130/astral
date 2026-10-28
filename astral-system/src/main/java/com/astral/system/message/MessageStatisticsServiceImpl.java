package com.astral.system.message;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysMailLog;
import com.astral.dao.entity.SysNotice;
import com.astral.dao.entity.SysSmsLog;
import com.astral.dao.mapper.SysMailLogMapper;
import com.astral.dao.mapper.SysNoticeMapper;
import com.astral.dao.mapper.SysSmsLogMapper;
import com.astral.system.dto.MessageDeliveryVo;
import com.astral.system.dto.MessageStatisticsVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 消息统计实现：三渠道合并聚合。
 *
 * <p><b>为什么在 Java 里聚合而不是写原生 SQL</b>：三张表的列名、状态语义、时间列都不同
 * （{@code sys_mail_log.send_time} / {@code sys_sms_log.send_time} / {@code sys_notice.create_time}，
 * 站内信没有 status 也没有 plugin_id），UNION + 分组要么靠方言函数、要么在 SQL 层做大量 CASE，
 * 维护成本远高于收益。这三张日志表体量很小（按发送记录增长，非业务主表），
 * 因此统一走「按条件取行 → 归一成 {@link Delivery} → 内存聚合」，好处是
 * overview / trend / list 三个接口**共用同一段过滤逻辑**，口径不可能分叉。</p>
 *
 * <p><b>筛选下推</b>：时间范围与场景能下推的直接下推到 SQL（走索引、少搬数据），
 * 状态 / 插件 / 关键字在内存里过滤 —— 因为这三项在三张表上的语义不一致
 * （站内信恒成功、关键字要跨「收件人/标题/主题/正文」四列匹配）。</p>
 *
 * <p><b>站内信的「发送方」口径</b>：{@code sys_notice} 没有来源列（无 sender / plugin_id），
 * 但站内信已经是通用的投递渠道（任意事件都可能发），不能一律算成 feedback 插件，
 * 因此按事件码前缀归组：{@code scene} 以 {@code feedback} 开头 → {@code feedback}，
 * 其余事件（userRolesChanged / changePasswordByEmail / systemAlert …）→ {@code system}。
 * 该口径同时用于明细的 pluginId 字段、plugins 分项统计和 {@code pluginId} 筛选参数，
 * 三者不会分叉；将来站内信补上来源列，只需改这里一个方法。</p>
 */
@Service
@RequiredArgsConstructor
public class MessageStatisticsServiceImpl implements MessageStatisticsService {

    private static final String CHANNEL_EMAIL = "EMAIL";
    private static final String CHANNEL_SMS = "SMS";
    private static final String CHANNEL_INAPP = "INAPP";

    /** 渠道枚举与展示顺序（无数据的渠道也在 channels 里出现，计数为 0） */
    private static final List<String> ALL_CHANNELS = List.of(CHANNEL_EMAIL, CHANNEL_SMS, CHANNEL_INAPP);

    /** 站内信按事件码前缀归组的两个桶：反馈事件 → feedback，其余事件 → system */
    private static final String INAPP_PLUGIN_FEEDBACK = "feedback";
    private static final String INAPP_PLUGIN_SYSTEM = "system";
    private static final String INAPP_FEEDBACK_SCENE_PREFIX = "feedback";

    /** scene / pluginId 为空时的归类值（前端按未知值展示原始串，不额外造文案） */
    private static final String UNKNOWN = "unknown";

    /** 明细标题截断长度（短信/站内信没有独立标题时取正文摘要） */
    private static final int TITLE_MAX_LENGTH = 50;

    private static final int TREND_DEFAULT_DAYS = 7;
    private static final int TREND_MAX_DAYS = 90;
    private static final int LIST_DEFAULT_PAGE_SIZE = 10;
    private static final int LIST_MAX_PAGE_SIZE = 200;

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SysMailLogMapper mailLogMapper;
    private final SysSmsLogMapper smsLogMapper;
    private final SysNoticeMapper noticeMapper;

    @Override
    public MessageStatisticsVo overview(MessageStatisticsQuery query) {
        Criteria criteria = Criteria.of(query);
        List<Delivery> rows = load(criteria);

        MessageStatisticsVo vo = new MessageStatisticsVo();
        long[] all = tally(rows);
        vo.setTotal(all[0]);
        vo.setSuccess(all[1]);
        vo.setFail(all[2]);
        vo.setSuccessRate(all[0] == 0 ? 0.0 : Math.round(all[1] * 1000.0 / all[0]) / 10.0);

        LocalDate today = LocalDate.now();
        List<Delivery> todayRows = rows.stream()
                .filter(row -> row.time() != null && today.equals(row.time().toLocalDate()))
                .toList();
        long[] todayTally = tally(todayRows);
        vo.setTodayTotal(todayTally[0]);
        vo.setTodaySuccess(todayTally[1]);
        vo.setTodayFail(todayTally[2]);

        for (String channel : criteria.channels()) {
            long[] t = tally(rows.stream().filter(row -> channel.equals(row.channel())).toList());
            MessageStatisticsVo.ChannelStat stat = new MessageStatisticsVo.ChannelStat();
            stat.setChannel(channel);
            stat.setTotal(t[0]);
            stat.setSuccess(t[1]);
            stat.setFail(t[2]);
            vo.getChannels().add(stat);
        }

        Map<String, long[]> sceneAgg = new LinkedHashMap<>();
        Map<String, long[]> pluginAgg = new LinkedHashMap<>();
        for (Delivery row : rows) {
            accumulate(sceneAgg, orUnknown(row.scene()), row);
            accumulate(pluginAgg, orUnknown(row.pluginId()), row);
        }
        sortedByTotalDesc(sceneAgg).forEach(entry -> {
            MessageStatisticsVo.SceneStat stat = new MessageStatisticsVo.SceneStat();
            stat.setScene(entry.getKey());
            stat.setTotal(entry.getValue()[0]);
            stat.setSuccess(entry.getValue()[1]);
            stat.setFail(entry.getValue()[2]);
            vo.getScenes().add(stat);
        });
        sortedByTotalDesc(pluginAgg).forEach(entry -> {
            MessageStatisticsVo.PluginStat stat = new MessageStatisticsVo.PluginStat();
            stat.setPluginId(entry.getKey());
            stat.setTotal(entry.getValue()[0]);
            stat.setSuccess(entry.getValue()[1]);
            stat.setFail(entry.getValue()[2]);
            vo.getPlugins().add(stat);
        });
        return vo;
    }

    @Override
    public List<TrendPointVo> trend(MessageStatisticsQuery query) {
        Criteria base = Criteria.of(query);
        int days = query.getDays() == null || query.getDays() < 1
                ? TREND_DEFAULT_DAYS
                : Math.min(query.getDays(), TREND_MAX_DAYS);

        LocalDate to = (query.getEnd() != null ? query.getEnd() : LocalDateTime.now()).toLocalDate();
        LocalDate from = query.getStart() != null ? query.getStart().toLocalDate() : to.minusDays(days - 1L);
        if (from.isAfter(to)) {
            throw BusinessException.of(320, "开始时间不能晚于结束时间");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > TREND_MAX_DAYS) {
            from = to.minusDays(TREND_MAX_DAYS - 1L);
        }

        Criteria criteria = base.range(from.atStartOfDay(), to.atTime(LocalTime.MAX));
        List<Delivery> rows = load(criteria);

        // key = 日期 + "|" + 渠道
        Map<String, long[]> agg = new LinkedHashMap<>();
        for (Delivery row : rows) {
            if (row.time() == null) {
                continue;
            }
            LocalDate day = row.time().toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) {
                continue;
            }
            accumulate(agg, day.format(DAY_FORMATTER) + "|" + row.channel(), row);
        }

        List<String> channels = criteria.channels();
        List<TrendPointVo> points = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            String date = day.format(DAY_FORMATTER);
            for (String channel : channels) {
                long[] t = agg.get(date + "|" + channel);
                TrendPointVo point = new TrendPointVo();
                point.setDate(date);
                point.setChannel(channel);
                point.setTotal(t == null ? 0 : t[0]);
                point.setSuccess(t == null ? 0 : t[1]);
                point.setFail(t == null ? 0 : t[2]);
                points.add(point);
            }
        }
        return points;
    }

    @Override
    public Page<MessageDeliveryVo> list(MessageStatisticsQuery query) {
        Criteria criteria = Criteria.of(query);
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1
                ? LIST_DEFAULT_PAGE_SIZE
                : Math.min(query.getPageSize(), LIST_MAX_PAGE_SIZE);

        List<Delivery> rows = load(criteria);
        // 三渠道合并排序：发送时间倒序，同一时刻按 id 倒序兜底（保证分页稳定、不重不漏）
        rows.sort(Comparator
                .comparing(Delivery::time, Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder()))
                .thenComparing(Delivery::id, Comparator.nullsLast(Comparator.<Long>reverseOrder())));

        long total = rows.size();
        long offset = (long) (pageNum - 1) * pageSize;
        List<MessageDeliveryVo> records = new ArrayList<>();
        if (offset < total) {
            int toIndex = (int) Math.min(total, offset + pageSize);
            for (Delivery row : rows.subList((int) offset, toIndex)) {
                records.add(toVo(row));
            }
        }

        Page<MessageDeliveryVo> page = new Page<>(pageNum, pageSize, total);
        page.setRecords(records);
        return page;
    }

    /**
     * 按筛选条件取回三渠道的归一化行。
     *
     * <p>时间与场景下推到 SQL；状态 / 插件 / 关键字在内存过滤，理由见类注释。</p>
     */
    private List<Delivery> load(Criteria criteria) {
        List<Delivery> rows = new ArrayList<>();
        List<String> channels = criteria.channels();

        if (channels.contains(CHANNEL_EMAIL)) {
            LambdaQueryWrapper<SysMailLog> wrapper = new LambdaQueryWrapper<SysMailLog>()
                    .ge(criteria.start() != null, SysMailLog::getSendTime, criteria.start())
                    .le(criteria.end() != null, SysMailLog::getSendTime, criteria.end())
                    .eq(criteria.scene() != null, SysMailLog::getScene, criteria.scene());
            for (SysMailLog log : mailLogMapper.selectList(wrapper)) {
                Delivery row = new Delivery(CHANNEL_EMAIL, log.getId(), log.getScene(), log.getToEmail(),
                        log.getSubject(), log.getContent(), log.getStatus(), log.getErrorMsg(),
                        log.getSendTime(), log.getPluginId());
                if (criteria.matches(row)) {
                    rows.add(row);
                }
            }
        }

        if (channels.contains(CHANNEL_SMS)) {
            LambdaQueryWrapper<SysSmsLog> wrapper = new LambdaQueryWrapper<SysSmsLog>()
                    .ge(criteria.start() != null, SysSmsLog::getSendTime, criteria.start())
                    .le(criteria.end() != null, SysSmsLog::getSendTime, criteria.end())
                    .eq(criteria.scene() != null, SysSmsLog::getScene, criteria.scene());
            for (SysSmsLog log : smsLogMapper.selectList(wrapper)) {
                Delivery row = new Delivery(CHANNEL_SMS, log.getId(), log.getScene(), log.getPhone(),
                        null, log.getContent(), log.getStatus(), log.getErrorMsg(),
                        log.getSendTime(), log.getPluginId());
                if (criteria.matches(row)) {
                    rows.add(row);
                }
            }
        }

        if (channels.contains(CHANNEL_INAPP)) {
            // sys_notice.channel 是「平台定向值」（all/app-windows…），不是投递渠道，这里一律按 INAPP 统计
            LambdaQueryWrapper<SysNotice> wrapper = new LambdaQueryWrapper<SysNotice>()
                    .ge(criteria.start() != null, SysNotice::getCreateTime, criteria.start())
                    .le(criteria.end() != null, SysNotice::getCreateTime, criteria.end())
                    .eq(criteria.scene() != null, SysNotice::getScene, criteria.scene());
            for (SysNotice notice : noticeMapper.selectList(wrapper)) {
                Delivery row = new Delivery(CHANNEL_INAPP, notice.getId(), notice.getScene(),
                        notice.getUserId() == null ? "all" : String.valueOf(notice.getUserId()),
                        notice.getTitle(), notice.getContent(), 1, null,
                        notice.getCreateTime(), inappPluginId(notice.getScene()));
                if (criteria.matches(row)) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static MessageDeliveryVo toVo(Delivery row) {
        MessageDeliveryVo vo = new MessageDeliveryVo();
        vo.setId(row.id());
        vo.setChannel(row.channel());
        vo.setScene(orUnknown(row.scene()));
        vo.setRecipient(row.recipient());
        vo.setTitle(displayTitle(row));
        vo.setStatus(row.status());
        vo.setStatusText(isSuccess(row) ? "成功" : "失败");
        vo.setErrorMsg(row.errorMsg());
        vo.setSendTime(row.time());
        vo.setPluginId(orUnknown(row.pluginId()));
        return vo;
    }

    /**
     * 明细标题：EMAIL 取 subject、SMS 没有标题列取正文、INAPP 取 title。
     * 三者都为空时回退正文摘要（邮件 subject 为空同样回退），避免列表出现空标题列。
     */
    private static String displayTitle(Delivery row) {
        String raw = row.title() != null && !row.title().isBlank() ? row.title() : row.content();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // 邮件/站内信正文是 HTML，摘成纯文本再截断，避免把标签甩到列表里
        String text = raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= TITLE_MAX_LENGTH ? text : text.substring(0, TITLE_MAX_LENGTH) + "…";
    }

    private static boolean isSuccess(Delivery row) {
        return Integer.valueOf(1).equals(row.status());
    }

    /**
     * 站内信的发送方（插件）归组：{@code sys_notice} 无来源列，按事件码前缀推导。
     *
     * <p>反馈事件（feedbackNewSubmission / feedbackPublished / feedbackStatusChanged /
     * feedbackAdminReplied / feedbackUserReplied）→ {@code feedback}；
     * 其余事件（userRolesChanged / changePasswordByEmail / systemAlert …）与场景为空 → {@code system}。</p>
     */
    private static String inappPluginId(String scene) {
        return scene != null && scene.toLowerCase(Locale.ROOT).startsWith(INAPP_FEEDBACK_SCENE_PREFIX)
                ? INAPP_PLUGIN_FEEDBACK
                : INAPP_PLUGIN_SYSTEM;
    }

    /** total / success / fail 三件套 */
    private static long[] tally(List<Delivery> rows) {
        long total = rows.size();
        long success = rows.stream().filter(MessageStatisticsServiceImpl::isSuccess).count();
        return new long[] {total, success, total - success};
    }

    private static void accumulate(Map<String, long[]> agg, String key, Delivery row) {
        long[] counter = agg.computeIfAbsent(key, k -> new long[3]);
        counter[0]++;
        if (isSuccess(row)) {
            counter[1]++;
        } else {
            counter[2]++;
        }
    }

    /** 按总量倒序、总量相同按 key 字典序，保证输出稳定可测 */
    private static List<Map.Entry<String, long[]>> sortedByTotalDesc(Map<String, long[]> agg) {
        return agg.entrySet().stream()
                .sorted(Comparator
                        .comparingLong((Map.Entry<String, long[]> entry) -> entry.getValue()[0])
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .toList();
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 投递渠道归一：空=不限，非法值按参数错误拒绝（320） */
    private static String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        String normalized = channel.trim().toUpperCase(Locale.ROOT);
        if (!ALL_CHANNELS.contains(normalized)) {
            throw BusinessException.of(320, "不支持的消息渠道：" + channel + "（可选 EMAIL / SMS / INAPP）");
        }
        return normalized;
    }

    /** 归一化后的一行投递记录（三渠道统一形状） */
    private record Delivery(
            String channel,
            Long id,
            String scene,
            String recipient,
            String title,
            String content,
            Integer status,
            String errorMsg,
            LocalDateTime time,
            String pluginId) {
    }

    /** 归一化后的筛选条件 */
    private record Criteria(
            LocalDateTime start,
            LocalDateTime end,
            String channel,
            String scene,
            Integer status,
            String pluginId,
            String keyword) {

        static Criteria of(MessageStatisticsQuery query) {
            return new Criteria(
                    query.getStart(),
                    query.getEnd(),
                    normalizeChannel(query.getChannel()),
                    blankToNull(query.getScene()),
                    query.getStatus(),
                    blankToNull(query.getPluginId()),
                    blankToNull(query.getKeyword()));
        }

        Criteria range(LocalDateTime newStart, LocalDateTime newEnd) {
            return new Criteria(newStart, newEnd, channel, scene, status, pluginId, keyword);
        }

        /** 需要参与统计的渠道（channel 为空=全部；指定 feedback/system 之外的插件时站内信不参与） */
        List<String> channels() {
            if (channel != null) {
                return List.of(channel);
            }
            if (pluginId != null
                    && !INAPP_PLUGIN_FEEDBACK.equalsIgnoreCase(pluginId)
                    && !INAPP_PLUGIN_SYSTEM.equalsIgnoreCase(pluginId)) {
                // 插件筛选值不可能是站内信的两个桶（如 ai / storage），站内信整体不参与
                return List.of(CHANNEL_EMAIL, CHANNEL_SMS);
            }
            return ALL_CHANNELS;
        }

        /**
         * 状态 / 插件 / 关键字的内存过滤。
         *
         * <p>站内信 status 恒为 1；其 pluginId 已在 {@link #inappPluginId(String)} 归一成
         * feedback / system，因此这里用同一套等值匹配即可复用给三渠道。</p>
         */
        boolean matches(Delivery row) {
            if (status != null && !status.equals(row.status())) {
                return false;
            }
            if (pluginId != null && (row.pluginId() == null || !pluginId.equalsIgnoreCase(row.pluginId()))) {
                return false;
            }
            return keyword == null
                    || containsIgnoreCase(row.recipient(), keyword)
                    || containsIgnoreCase(row.title(), keyword)
                    || containsIgnoreCase(row.content(), keyword);
        }

        private static boolean containsIgnoreCase(String source, String keyword) {
            return source != null && source.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
        }
    }
}
