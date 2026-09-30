package com.astral.qt.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 批量收藏操作项（LIKE_SYNC_DESIGN.md §2.5，/like/batch 专用）。
 * <p>song/playlist 共用一套扁平字段，按 {@code type} 区分必填项（song 必填 sid+platform，
 * playlist 必填 pid+platform）——条件必填无法用 Bean Validation 按 type 标注，
 * 统一在 service 层校验并抛 320。其余字段约束与单条接口
 * {@link QtLikeSongActionDto}/{@link QtLikePlaylistActionDto} 一致。</p>
 */
@Data
public class QtLikeBatchOpDto {

    /** 操作对象类型：song-歌曲 / playlist-歌单（空值由 service 层校验拦截） */
    @Pattern(regexp = "song|playlist", message = "type仅支持song或playlist")
    private String type;

    /** add-收藏 / remove-取消收藏 */
    @Pattern(regexp = "add|remove", message = "action仅支持add或remove")
    private String action;

    /** song-歌曲ID */
    @Size(max = 64, message = "sid长度不能超过64")
    private String sid;

    /** song-歌曲所属歌单ID（可选，remove 为空表示从全部歌单移除）；playlist-歌单ID */
    @Size(max = 64, message = "pid长度不能超过64")
    private String pid;

    @Size(max = 16, message = "platform长度不能超过16")
    private String platform;

    @Size(max = 128, message = "name长度不能超过128")
    private String name;

    /** song-歌手 */
    @Size(max = 128, message = "singer长度不能超过128")
    private String singer;

    /** song-专辑 */
    @Size(max = 128, message = "album长度不能超过128")
    private String album;

    /** song-音源hash */
    @Size(max = 128, message = "hash长度不能超过128")
    private String hash;

    /** 封面地址（仅 add 生效，空值归一化为 NULL 不覆盖已有封面） */
    @Size(max = 2048, message = "picUrl长度不能超过2048")
    private String picUrl;

    /** 服务端内部分配的变更序号（按数组顺序递增），客户端无需上送、上送也会被覆盖 */
    private transient Long seq;
}
