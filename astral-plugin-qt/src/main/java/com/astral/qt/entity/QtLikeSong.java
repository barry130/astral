package com.astral.qt.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户收藏的歌曲 */
@Data
@TableName("qt_like_song")
public class QtLikeSong {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long uid;

    /** 歌曲ID */
    private String sid;

    /** 所属歌单ID */
    private String pid;

    private String platform;

    private String name;

    private String singer;

    private String album;

    private String hash;

    /** 歌曲封面图片地址（第三方源 URL 快照，可空） */
    private String picUrl;

    /** 软删除时间，为空表示未删除 */
    private LocalDateTime deletedAt;

    /** 用户收藏变更序号（多端同步游标，用户维度递增） */
    private Long updatedSeq;

    /** 收藏状态变更时间 */
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}