package com.astral.storage.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 两步上传操作（sys_storage_upload_operation）——持久状态机 + 配额预留 + 幂等墓碑。
 *
 * <p>设计 v1.1 §5.4 / §8.2 的状态机：</p>
 * <pre>
 * CREATED ──PUT 准入──▶ RECEIVING ──正文校验通过──▶ SENDING ──上游 2xx 且响应合规──▶ REMOTE_SUCCEEDED
 *    │                     │                          │                                    │
 *    │                     │                          ├─ 可证明未发出请求 ─▶ FAILED（可换候选）  └─ 本地登记 ─▶ COMMITTED
 *    │                     │                          └─ 无法判定是否送达 ─▶ UNKNOWN_OUTCOME（禁止重传）
 *    └─ 超时未 PUT ─▶ EXPIRED                          └─ 明确拒绝 ─▶ FAILED
 * </pre>
 *
 * <p>SENDING 行必须在任何上游请求之前落库；UNKNOWN_OUTCOME 不允许自动重试、不允许自动释放配额。</p>
 */
@Data
@TableName("sys_storage_upload_operation")
public class StorageUploadOperationEntity {

    public static final String STATE_CREATED = "CREATED";
    public static final String STATE_RECEIVING = "RECEIVING";
    public static final String STATE_SENDING = "SENDING";
    public static final String STATE_REMOTE_SUCCEEDED = "REMOTE_SUCCEEDED";
    public static final String STATE_COMMITTED = "COMMITTED";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_EXPIRED = "EXPIRED";
    public static final String STATE_UNKNOWN_OUTCOME = "UNKNOWN_OUTCOME";

    /** 终态：不会再推进 */
    public static final java.util.Set<String> TERMINAL_STATES =
            java.util.Set.of(STATE_COMMITTED, STATE_FAILED, STATE_EXPIRED);

    public static final String CHARGE_NONE = "NONE";
    public static final String CHARGE_HELD = "HELD";
    public static final String CHARGE_CONSUMED = "CONSUMED";
    public static final String CHARGE_REFUNDED = "REFUNDED";

    public static final String PURPOSE_UPLOAD = "UPLOAD";
    public static final String PURPOSE_PROBE = "PROBE";

    public static final String ACTOR_USER = "USER";
    public static final String ACTOR_PLUGIN = "PLUGIN";

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 对外稳定上传标识（客户端 PUT 用） */
    private String uploadId;

    /** 对外稳定文件标识（文件表复用同一值） */
    private String publicId;

    /** USER | PLUGIN */
    private String actorType;

    private String actorId;

    /** 幂等键 SHA-256（客户端键从不落明文） */
    private String idempotencyKeyHash;

    /** 请求指纹 SHA-256：目录+目标+文件名+MIME+大小+摘要 */
    private String intentFingerprint;

    private String originalName;

    private String expectedMime;

    private Long expectedSize;

    private String expectedSha256;

    private Long folderId;

    private Long folderVersion;

    /** 路由快照：目标 ID/版本、能力、参数结构非秘密摘要；≤16KiB，不含 token/认证头/带 key 的 URL */
    private String routeSnapshotJson;

    private Long chosenConfigId;

    private Long chosenConfigVersion;

    private Integer chosenPresetVersion;

    /** 状态机当前状态 */
    private String state;

    /** 状态 CAS 版本 */
    private Long version;

    /** 推进租约令牌（每次拿到租约重新生成） */
    private String leaseToken;

    private LocalDateTime leaseUntil;

    /** 固定发送截止时间：首次 PUT 准入时确定，之后不再延长 */
    private LocalDateTime sendDeadline;

    /** 意图过期时间：CREATED 期间未 PUT 则 EXPIRED */
    private LocalDateTime intentExpiresAt;

    private LocalDate quotaDay;

    /** NONE | HELD | CONSUMED | REFUNDED */
    private String quotaCharge;

    /** 0/1：不计费目录（如系统探针） */
    private Integer quotaExempt;

    private Long actualSize;

    private String actualSha256;

    /** 有界尝试记录（最多 2 项，≤8KiB，不含正文/凭据） */
    private String attemptsJson;

    /** 有界安全回执（≤8KiB，已验证 URL/上游 ID/大小，不含完整响应） */
    private String safeResultJson;

    private String failureCode;

    private Long fileId;

    /** UPLOAD | PROBE */
    private String purpose;

    private Integer auditPending;

    private String auditEventKey;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private LocalDateTime completedAt;
}
