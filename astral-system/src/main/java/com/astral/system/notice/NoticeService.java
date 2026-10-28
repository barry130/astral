package com.astral.system.notice;

import com.astral.dao.entity.SysNotice;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * 站内通知（统一通知表 {@code sys_notice}）读写服务。
 *
 * <p>通知能力是宿主（astral-system）的通用能力，<b>不再属于任何业务插件</b>：
 * 反馈插件只在业务节点触发事件，发不发/发给谁/发什么由订阅规则与 INAPP 模板决定
 * （{@code NotifyPublisher} → {@code InappService} 落表），本服务只负责「读」与
 * 「管理端的增删改」这两件事。</p>
 *
 * <p>读路径的过滤链（App 端多个接口共用）：{@code is_show=1} → channel 命中 →
 * 生效时间窗 → 客户端版本区间 → audience → 广播或点对点 → 保留期
 * （{@code announce} 不受限，其余仅最近 N 天）。</p>
 */
public interface NoticeService {

    /**
     * 当前生效通知列表（App 端：Android + iOS，公开，三展示位共用）
     *
     * @param appVersion 客户端版本码（如 300），可为空
     * @param loggedIn   是否已登录
     * @param userId     已登录时的用户 ID（用于点对点过滤）
     */
    List<SysNotice> listForApp(String appVersion, boolean loggedIn, Long userId);

    /**
     * 当前生效通知列表（PC / 桌面端，公开）
     *
     * @param appVersion 客户端版本码（如 300），可为空
     * @param loggedIn   是否已登录
     * @param userId     已登录时的用户 ID（用于点对点过滤）
     */
    List<SysNotice> listForPc(String appVersion, boolean loggedIn, Long userId);

    /**
     * 当前生效通知列表（Web 端，公开）
     *
     * @param appVersion 客户端版本码（如 300），可为空
     * @param loggedIn   是否已登录
     * @param userId     已登录时的用户 ID（用于点对点过滤）
     */
    List<SysNotice> listForWeb(String appVersion, boolean loggedIn, Long userId);

    /**
     * 当前生效通知列表（指定平台集合，公开）
     *
     * @param targets    待匹配平台集合，来自 {@link NoticeTargets#resolveTargets}；
     *                   含 {@link NoticeTargets#ALL} 表示不限平台
     * @param appVersion 客户端版本码（如 300），可为空
     * @param loggedIn   是否已登录
     * @param userId     已登录时的用户 ID（用于点对点过滤）
     */
    List<SysNotice> listForChannel(List<String> targets, String appVersion, boolean loggedIn, Long userId);

    /**
     * 立即失效「生效通知」缓存。
     * <p>管理端 CRUD（{@link #create} / {@link #update} / {@link #delete}）内部已自动调用；
     * 外部仅在需要手动刷新时调用。事件触发的点对点通知由 {@code InappService} 写同一张表，
     * 靠缓存自身的 60s TTL 自然过期。</p>
     */
    void evictNoticeCache();

    /**
     * 消息中心列表（App 端，Android + iOS）
     * <p>在 {@link #listForApp} 基础上追加 display 含消息中心位，返回带 noticeType 供前端区分标签；
     * 已读状态由前端缓存判断，后端不返回 read 字段。</p>
     */
    List<SysNotice> listMessageCenter(Long userId);

    /** 消息中心列表（指定平台集合） */
    List<SysNotice> listMessageCenter(Long userId, List<String> targets);

    /** 未读数（候选总数，App 端）：已读判定在前端缓存，清缓存 = 全部未读 */
    long countUnread(Long userId);

    /** 未读数（候选总数，指定平台集合） */
    long countUnread(Long userId, List<String> targets);

    /** 已读回执（本期仅记日志，不落表） */
    void readAck(Long userId, List<Long> ids);

    // ==================== 管理端 ====================

    /**
     * 通知分页（channel / notice_type / 关键词 / 时间筛选）
     *
     * @param channel   渠道筛选，支持多选（逗号分隔平台值），兼容遗留单值 app/pc/web/all
     * @param noticeType 类型筛选：{@code announce} 精确匹配；{@code notice} 归并匹配历史
     *                   {@code feedback} / {@code request}（存量行不做数据迁移）；其余值精确匹配
     */
    Page<SysNotice> page(int pageNum, int pageSize, String channel, String noticeType,
                         String keyword, String startDate, String endDate);

    /** 新增通知（公告/定向）；渠道归一化与校验统一走 {@link NoticeTargets} */
    SysNotice create(SysNotice notice);

    /** 更新通知：编辑表单提交什么就写什么（清空即写 NULL） */
    void update(Long id, SysNotice notice);

    /** 删除通知（物理删） */
    void delete(Long id);

    /**
     * 管理端收件箱（顶栏铃铛数据源）：广播 + 发给当前管理员的点对点，不按渠道过滤
     */
    List<SysNotice> listAdminInbox(Long adminId);
}
