package com.astral.qt.dto.vo;

import com.astral.qt.entity.QtLikePlaylist;
import com.astral.qt.entity.QtLikeSong;
import lombok.Data;

import java.util.List;

/** 用户收藏的歌单 + 歌曲 */
@Data
public class QtLikeListVo {

    private List<QtLikePlaylist> playlist;

    private List<QtLikeSong> song;
}