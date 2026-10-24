package com.astral.cms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分享链接创建入参
 */
@Data
public class ShareCreateDto {

    /** 分享目标类型：post / page */
    @NotBlank(message = "分享目标类型不能为空")
    private String targetType;

    /** 分享目标ID */
    private Long targetId;

    /** 过期时间，NULL=永久有效 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireAt;

    /** 备注（便于区分多条分享链接的用途） */
    @Size(max = 256, message = "备注最多 256 个字符")
    private String remark;
}
