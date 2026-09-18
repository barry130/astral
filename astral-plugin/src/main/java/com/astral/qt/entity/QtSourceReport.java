package com.astral.qt.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 音源包装载结果上报（装机分布统计与坏包发现）
 * <p>
 * 客户端每次装载远程音源包（含冒烟自检）后上报一条；用于观察某个版本在多少设备上装上了、
 * 以及是否出现冒烟失败，作为「是否标坏包 / 是否放量」的依据。
 * </p>
 */
@Data
@TableName("qt_source_report")
public class QtSourceReport {

    /** 结果：成功 */
    public static final String RESULT_OK = "ok";
    /** 结果：冒烟自检失败 */
    public static final String RESULT_SMOKE_FAILED = "smoke_failed";

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 客户端平台（1101/1102/1103） */
    private Long platform;

    /** 客户端应用版本号 */
    private Long appVersionCode;

    /** 装载的音源包版本号 */
    private Long sourceVersionCode;

    /** 结果：ok / smoke_failed */
    private String result;

    /** 失败详情（可空） */
    private String detail;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
