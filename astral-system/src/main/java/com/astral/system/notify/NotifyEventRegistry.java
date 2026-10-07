package com.astral.system.notify;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 通知事件注册表（代码注册，管理端只读）
 *
 * <p><b>为什么事件要写死在代码里</b>：事件的语义（何时触发、携带哪些数据、谁在调用）
 * 由发送方代码决定，不是运营可配的东西——把它做成自由文本就会出现「后台随便建事件、
 * 建完没人用、事件和模板对不上」的状态。代码注册事件 + 模板按 (事件 × 渠道) 绑定 +
 * 授权按事件码圈范围（fail-closed），三层各管各的：</p>
 *
 * <ul>
 *   <li>调用方（插件/告警引擎）：只写死事件码，如 {@link #QT_PASSWORD_RESET_CODE}；</li>
 *   <li>模板管理：后台新增/修改模板时 scene 必须选这里登记的事件、channel 必须是
 *       事件声明的渠道，且 variables 必须覆盖 payload 全部字段（发送侧按事件码解析模板，
 *       改文案不用动代码）；</li>
 *   <li>发信授权：allowed_scenes 只能从这里的登记事件里选，且<b>留空 = 一律拒绝</b>
 *       （fail-closed，缺省不猜）。</li>
 * </ul>
 *
 * <p>新增事件的步骤：这里加一条定义 → 调用方用 {@code mailService.send(..., 事件码, ...)}
 * 发送 → 管理端为该事件绑定模板 → （如需插件可发）在发信授权里勾上该事件。</p>
 *
 * <p>演进路线：本注册表即未来消息中心「事件订阅规则」的事件源声明；
 * 阶段②③接入 SMS/站内信时扩展 {@link NotifyChannel}，阶段④规则表按事件码订阅。</p>
 */
public final class NotifyEventRegistry {

    /** 轻听：邮箱验证码（改密/找回密码）。客户端 POST /api/v1/app/user/email 传 body=该事件码 */
    public static final String QT_PASSWORD_RESET_CODE = "changePasswordByEmail";

    /** 系统告警：告警引擎 EMAIL 渠道通知（title=告警标题，content=告警内容） */
    public static final String SYSTEM_ALERT = "systemAlert";

    /** 反馈插件：反馈/需求状态变更通知提交人（payload.userId=提交人） */
    public static final String FEEDBACK_STATUS_CHANGED = "feedbackStatusChanged";
    /** 反馈插件：反馈/需求公开发布通知提交人 */
    public static final String FEEDBACK_PUBLISHED = "feedbackPublished";
    /** 反馈插件：管理员回复通知提交人 */
    public static final String FEEDBACK_ADMIN_REPLIED = "feedbackAdminReplied";
    /** 反馈插件：新反馈/需求提交，群发管理员（规则收件人 ROLE 展开） */
    public static final String FEEDBACK_NEW_SUBMISSION = "feedbackNewSubmission";
    /** 反馈插件：用户在反馈中回复，群发管理员 */
    public static final String FEEDBACK_USER_REPLIED = "feedbackUserReplied";

    private static final List<NotifyEventDef> EVENTS = List.of(
            new NotifyEventDef(QT_PASSWORD_RESET_CODE, "找回密码验证码",
                    "轻听客户端邮箱验证码（修改密码/找回密码）。插件 qt 调用，验证码有效期 10 分钟",
                    List.of(new NotifyEventField("code", "6 位数字验证码，有效期 10 分钟")),
                    List.of(NotifyChannel.EMAIL), "announce"),
            new NotifyEventDef(SYSTEM_ALERT, "系统告警通知",
                    "告警引擎触发（/api/v1/admin/alert）。EMAIL 走模板渲染回退内置样式；SMS 走短信供应商池（systemAlert 短信模板必配，缺模板直接报 SMS002）；INAPP 按用户落库",
                    List.of(new NotifyEventField("title", "告警标题（规则名/指标摘要）"),
                            new NotifyEventField("content", "告警内容纯文本，多行")),
                    List.of(NotifyChannel.EMAIL, NotifyChannel.SMS, NotifyChannel.INAPP), "announce"),
            new NotifyEventDef(FEEDBACK_STATUS_CHANGED, "反馈状态变更通知",
                    "feedback 插件触发：反馈/需求状态变更时通知提交人。订阅规则收件人建议 PAYLOAD_FIELD=userId；payload 可带 meta 字段 noticeType（feedback/request）区分 App 端标签",
                    List.of(new NotifyEventField("feedbackId", "反馈ID"),
                            new NotifyEventField("userId", "提交人用户ID（INAPP 投递即收件人）"),
                            new NotifyEventField("feedbackTitle", "反馈标题"),
                            new NotifyEventField("statusName", "状态名（中文）")),
                    List.of(NotifyChannel.INAPP), null),
            new NotifyEventDef(FEEDBACK_PUBLISHED, "反馈公开发布通知",
                    "feedback 插件触发：反馈审核通过并公开发布时通知提交人",
                    List.of(new NotifyEventField("feedbackId", "反馈ID"),
                            new NotifyEventField("userId", "提交人用户ID"),
                            new NotifyEventField("feedbackTitle", "反馈标题")),
                    List.of(NotifyChannel.INAPP), null),
            new NotifyEventDef(FEEDBACK_ADMIN_REPLIED, "管理员回复通知",
                    "feedback 插件触发：管理员回复反馈时通知提交人",
                    List.of(new NotifyEventField("feedbackId", "反馈ID"),
                            new NotifyEventField("userId", "提交人用户ID"),
                            new NotifyEventField("feedbackTitle", "反馈标题")),
                    List.of(NotifyChannel.INAPP), null),
            new NotifyEventDef(FEEDBACK_NEW_SUBMISSION, "新反馈/需求提交通知",
                    "feedback 插件触发：新反馈/需求提交后群发管理员。订阅规则收件人建议 ROLE=ADMIN（按角色展开全部启用用户），平台建议 app-windows（仅管理端可见）",
                    List.of(new NotifyEventField("feedbackId", "反馈ID"),
                            new NotifyEventField("feedbackTitle", "反馈标题")),
                    List.of(NotifyChannel.INAPP), null),
            new NotifyEventDef(FEEDBACK_USER_REPLIED, "用户回复反馈通知",
                    "feedback 插件触发：用户在反馈中回复后群发管理员。收件人与平台建议同上",
                    List.of(new NotifyEventField("feedbackId", "反馈ID"),
                            new NotifyEventField("feedbackTitle", "反馈标题")),
                    List.of(NotifyChannel.INAPP), null));

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private NotifyEventRegistry() {
    }

    /** 全部已注册事件（管理端事件下拉/多选的数据源） */
    public static List<NotifyEventDef> list() {
        return EVENTS;
    }

    public static Optional<NotifyEventDef> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return EVENTS.stream().filter(e -> e.code().equals(code)).findFirst();
    }

    public static boolean exists(String code) {
        return find(code).isPresent();
    }

    /**
     * 解析事件码/变量列表字段：优先按 JSON 数组解析，失败回退按逗号拆分。
     * <p>{@code allowed_scenes} 历史存储是逗号分隔，{@code variables} 是 JSON 数组，
     * 管理端写路径校验统一用这个宽松解析，两种存量格式都接受。</p>
     */
    public static List<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = OBJECT_MAPPER.readValue(raw, new TypeReference<List<String>>() {});
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            return Arrays.stream(raw.replace("[", "").replace("]", "").replace("\"", "").split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
    }
}
