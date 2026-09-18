package com.astral.qt.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 音源包 manifest 响应体（GET /api/v1/app/source/manifest）
 * <p>
 * manifest <b>不是发布产物</b>：它由后端按数据库记录动态生成，客户端只调这个接口、
 * 不读任何静态 manifest 文件；撤回/标坏/灰度都只需改数据库。
 * </p>
 * <p>
 * 服务端已完成平台匹配、应用版本准入、渠道与撤回预筛，因此这里最多只返回一条 release。
 * </p>
 */
@Data
public class QtSourceManifestVo {

    /** manifest 结构版本 */
    private Integer schema = 3;

    /** 生成时间 */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime generatedAt;

    /** 命中的 release；为 null 表示客户端保持当前版本 */
    private QtSourceReleaseVo release;

    public QtSourceManifestVo() {
        this.generatedAt = LocalDateTime.now();
    }
}
