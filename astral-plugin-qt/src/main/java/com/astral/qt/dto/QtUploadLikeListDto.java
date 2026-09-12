package com.astral.qt.dto;

import jakarta.validation.Valid;
import lombok.Data;

import java.util.List;

/**
 * 上传/同步收藏歌单与歌曲
 * <p>嵌套字段用 {@code @Valid} 逐级校验——否则元素内的
 * {@code @Size}（如 QtSongDto.picUrl/pid 的长度上限）不会生效，超长值会直接落库。</p>
 */
@Data
public class QtUploadLikeListDto {

    @Valid
    private List<QtPlaylistDto> playlistList;

    @Valid
    private List<QtSongDto> songList;
}