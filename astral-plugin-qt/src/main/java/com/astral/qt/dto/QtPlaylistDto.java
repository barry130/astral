package com.astral.qt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 歌单 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class QtPlaylistDto {

    private String id;

    private String platform;

    private String picUrl;

    private String name;

    private Byte isImport;
}