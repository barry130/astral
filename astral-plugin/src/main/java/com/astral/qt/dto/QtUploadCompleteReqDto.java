package com.astral.qt.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 头像/歌单封面直传完成回执（UPDATE_DESIGN.md §5.2/§5.3）：凭 uploadId 完成登记核对与业务字段直写 */
@Data
public class QtUploadCompleteReqDto {

    @NotBlank(message = "uploadId不能为空")
    private String uploadId;
}
