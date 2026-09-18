package com.astral.qt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 收藏/取消收藏单曲（LIKE_SYNC_DESIGN.md §2.1）
 * <p>action = add 收藏 / remove 取消收藏，替代旧的全量 uploadLikeList。</p>
 */
@Data
public class QtLikeSongActionDto {

    /** add-收藏 / remove-取消收藏 */
    @NotBlank(message = "action不能为空")
    @Pattern(regexp = "add|remove", message = "action仅支持add或remove")
    private String action;

    @NotBlank(message = "sid不能为空")
    @Size(max = 64, message = "sid长度不能超过64")
    private String sid;

    /** 歌曲所属歌单ID（可选；收藏到具体歌单时上送，落库到 qt_like_song.pid） */
    @Size(max = 64, message = "pid长度不能超过64")
    private String pid;

    @NotBlank(message = "platform不能为空")
    @Size(max = 16, message = "platform长度不能超过16")
    private String platform;

    @Size(max = 128, message = "name长度不能超过128")
    private String name;

    @Size(max = 128, message = "singer长度不能超过128")
    private String singer;

    @Size(max = 128, message = "album长度不能超过128")
    private String album;

    @Size(max = 128, message = "hash长度不能超过128")
    private String hash;

    /**
     * 歌曲封面图片地址（LIKE_SONG_PIC_SYNC_DESIGN.md §5.1）。
     * <p>可选；仅 action=add 生效。null/空白统一归一化为 NULL 落库，
     * 不会用空值覆盖数据库已有封面；最大 2048，超长由 Bean Validation 返回 400。</p>
     */
    @Size(max = 2048, message = "picUrl长度不能超过2048")
    private String picUrl;
}
