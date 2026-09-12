package com.astral.qt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 收藏/取消收藏歌单（LIKE_SYNC_DESIGN.md §2.2）
 * <p>action = add 收藏 / remove 取消收藏，替代旧的全量 uploadLikeList。</p>
 */
@Data
public class QtLikePlaylistActionDto {

    /** add-收藏 / remove-取消收藏 */
    @NotBlank(message = "action不能为空")
    @Pattern(regexp = "add|remove", message = "action仅支持add或remove")
    private String action;

    @NotBlank(message = "pid不能为空")
    @Size(max = 64, message = "pid长度不能超过64")
    private String pid;

    @NotBlank(message = "platform不能为空")
    @Size(max = 16, message = "platform长度不能超过16")
    private String platform;

    @Size(max = 128, message = "name长度不能超过128")
    private String name;

    @Size(max = 256, message = "picUrl长度不能超过256")
    private String picUrl;
}
