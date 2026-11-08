package com.astral.qt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 头像直传取凭证请求（UPDATE_DESIGN.md §5.2）
 * <p>文件不经过 Astral 服务器：本接口只声明元数据，服务端签发一次性直传凭证。</p>
 */
@Data
public class QtAvatarTicketReqDto {

    @NotBlank(message = "fileName不能为空")
    @Size(max = 255, message = "fileName长度不能超过255")
    private String fileName;

    @NotBlank(message = "contentType不能为空")
    @Size(max = 128, message = "contentType长度不能超过128")
    private String contentType;

    /** 文件大小（字节），服务端按文件夹策略收紧校验 */
    private long sizeBytes;
}
