package com.astral.qt.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 音源包发布信息（对外视图）
 * <p>公开 manifest 的 release 节点与管理端列表/详情共用同一结构，
 * 避免「后台看到的」与「客户端拿到的」不一致。</p>
 */
@Data
public class QtSourceReleaseVo {

    /** 主键 ID（管理端用；公开 manifest 里为 null） */
    private Long id;

    /** 音源包版本号（yyyyMMddNN，后端生成，更新判定的唯一依据） */
    private Long sourceVersionCode;

    /** 音源包版本名（yyyy.MM.dd.N，后端派生，仅展示） */
    private String sourceVersionName;

    /** 适用平台（1101 安卓 / 1102 iOS / 1103 Windows） */
    private List<Long> platforms;

    /** 需要的宿主契约版本 */
    private Long hostApiVersion;

    /** 按平台准入的应用版本号：key 为平台字符串，value 为允许的应用版本号；平台缺省或空数组=不限制 */
    private Map<String, List<Long>> appVersionCodes;

    /** 发布渠道：stable / beta */
    private String channel;

    /** 更新说明 */
    private String notes;

    /** 产物清单（当前生效全集） */
    private List<QtSourceArtifactVo> artifacts;

    /** 指定回退到的版本号（可空） */
    private Long rollbackTo;

    /** 是否标记坏包 */
    private Boolean bad;

    /** 是否已发布（管理端用） */
    private Boolean published;

    /** 发布时间（未发布为空） */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime publishedAt;

    /** 创建时间（管理端用） */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime createTime;
}
