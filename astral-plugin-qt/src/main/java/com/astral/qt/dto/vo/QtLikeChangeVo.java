package com.astral.qt.dto.vo;

import lombok.Data;

/**
 * 单条收藏变更（LIKE_SYNC_DESIGN.md §2.3，封面同步见 LIKE_SONG_PIC_SYNC_DESIGN.md）
 * <p>type = song 时填充 sid/name/singer/album/hash/picUrl（歌曲封面）/pid（歌曲所属歌单）；
 * type = playlist 时填充 pid/name/picUrl（歌单封面）。
 * id 为 sid 或 pid 的别名，便于客户端统一按 id+platform 匹配。</p>
 */
@Data
public class QtLikeChangeVo {

    /** song-歌曲 / playlist-歌单 */
    private String type;

    /** 歌曲/歌单ID（type=song 时等于 sid，type=playlist 时等于 pid） */
    private String id;

    /** 歌曲ID（type=song） */
    private String sid;

    /** 歌单ID（type=playlist 时为歌单ID；type=song 时为歌曲所属歌单，可空） */
    private String pid;

    private String platform;

    /** 歌曲/歌单名称 */
    private String name;

    /** 歌手（type=song） */
    private String singer;

    /** 专辑（type=song） */
    private String album;

    /** 歌曲Hash（type=song） */
    private String hash;

    /** 封面图片地址：type=song 时为歌曲封面，type=playlist 时为歌单封面（均可空） */
    private String picUrl;

    /** 是否已删除（deleted_at 非空） */
    private Boolean deleted;

    /** 变更序号 */
    private Long updatedSeq;
}
