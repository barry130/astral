package com.astral.qt.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 用户签到记录 */
@Data
@TableName("qt_user_daka")
public class QtUserDaka {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long uid;

    private LocalDate data;

    /** 获得积分 */
    private Long integral;

    /** 是否使用积分签到 0否 1是 */
    private Long isUseCode;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}