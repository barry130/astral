package com.astral.storage.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 存储文件夹（sys_storage_folder）——组织与授权边界
 */
@Data
@TableName("sys_storage_folder")
public class StorageFolderEntity {

    public static final String OWNER_ADMIN = "ADMIN";
    public static final String OWNER_USER = "USER";
    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";
    /** 绑定调度策略：按优先级故障转移 */
    public static final String STRATEGY_FAILOVER = "FAILOVER";
    /** 绑定调度策略：同优先级内轮询 */
    public static final String STRATEGY_ROUND_ROBIN = "ROUND_ROBIN";

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long parentId;

    private String folderName;

    /** 展示路径（父路径 + 名称），仅展示用 */
    private String folderPath;

    /**
     * 绑定调度策略（FAILOVER | ROUND_ROBIN）：决定多配置绑定的选路方式。
     * <p>绑定集合存于 sys_storage_folder_config（多选 + priority 分层）。</p>
     */
    private String storageStrategy;

    /**
     * 目录上传目标/授权策略版本：绑定变更、授权全量替换、状态与可见性切换都会递增。
     * <p>发送意图准入时比对版本，防止「校验通过后配置被改」的窗口被利用。</p>
     */
    private Long targetVersion;

    /** ADMIN | USER | PLUGIN */
    private String ownerType;

    /** 所有者 ID（USER 时为用户 ID 字符串） */
    private String ownerId;

    /** 文件夹默认可见性：PRIVATE | PUBLIC */
    private String visibility;

    /** ENABLED | DISABLED */
    private String status;

    /** 是否需要 UPLOAD 授权行；关闭不等于公开 READ/UPDATE/DELETE，策略与配额仍生效 */
    private Boolean uploadAclRequired;

    /**
     * 目录授权状态。null 兼容尚未迁移的旧读取对象，默认仍要求授权。
     *
     * <p><b>方法名刻意不用 {@code is}/{@code get} 前缀</b>：字段是包装类型 {@code Boolean}，
     * Lombok 已生成 {@code Boolean getUploadAclRequired()}；若再手写 {@code boolean isUploadAclRequired()}，
     * MyBatis 的 {@code Reflector} 会把属性 {@code uploadAclRequired} 判成「返回类型不一致的重载 getter」，
     * 包装成 {@code AmbiguousMethodInvoker}——读库时走 resultMap 不受影响，
     * 但 {@code updateById} 绑定参数读该属性时才抛
     * {@code Illegal overloaded getter method with ambiguous type}，表现为保存目录 500。</p>
     */
    public boolean requiresUploadAcl() {
        return uploadAclRequired == null || uploadAclRequired;
    }

    /** 上传策略 JSON（requireLogin/maxSizeBytes/allowedMimes/dailyUploadLimit 等）；空 = 沿用插件全局默认 */
    private String uploadPolicy;

    private String createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    private String updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
