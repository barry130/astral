package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_login_log")
public class LoginLog {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("user_id")
    private Long userId;

    private String username;

    @TableField("login_type")
    private String loginType;

    private String ip;

    private String location;

    private String browser;

    private String os;

    private Integer status;

    private String msg;

    @TableField(value = "login_time", fill = FieldFill.INSERT)
    private LocalDateTime loginTime;

}
