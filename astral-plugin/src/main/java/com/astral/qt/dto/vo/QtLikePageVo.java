package com.astral.qt.dto.vo;

import com.astral.qt.entity.QtLikePlaylist;
import com.astral.qt.entity.QtLikeSong;
import lombok.Data;

import java.util.List;

/**
 * 全量分页拉取结果（LIKE_SYNC_DESIGN.md §2.4，首次/兜底同步）
 * <p>返回分页后的歌曲 + 歌单列表（仅未删除），并附带当前最大变更序号，客户端存储作为初始游标。</p>
 */
@Data
public class QtLikePageVo {

    /** 当前页歌曲（按 updated_seq 倒序） */
    private List<QtLikeSong> songs;

    /** 当前页歌单（按 updated_seq 倒序） */
    private List<QtLikePlaylist> playlists;

    /** 当前用户最大变更序号，作为同步游标 */
    private Long maxSeq;
}
