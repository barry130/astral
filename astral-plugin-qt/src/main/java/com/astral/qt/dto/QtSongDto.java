package com.astral.qt.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 歌曲 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class QtSongDto {

    private String id;

    /** 歌曲所属歌单ID（新客户端用；≤64，落库到 qt_like_song.pid） */
    @Size(max = 64, message = "pid长度不能超过64")
    private String pid;

    /** 所属歌单ID（旧字段，兼容保留；pid 为空时回退用它） */
    @Deprecated
    private String likePlaylist;

    private String platform;

    private String singer;

    private String name;

    private String hash;

    private String album;

    /**
     * 歌曲封面图片地址（LIKE_SONG_PIC_SYNC_DESIGN.md §5.1，旧全量接口兼容）。
     * <p>可选；null/空白统一归一化为 NULL 落库，不会用空值覆盖云端已有封面。</p>
     */
    @Size(max = 2048, message = "picUrl长度不能超过2048")
    private String picUrl;
}