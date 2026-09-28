package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
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

    @Size(max = 64)
    private String username;

    @TableField("login_type")
    @Size(max = 32)
    private String loginType;

    @Size(max = 64)
    private String ip;

    @Size(max = 128)
    private String location;

    @Size(max = 256)
    private String browser;

    @Size(max = 128)
    private String os;

    private Integer status;

    @Size(max = 256)
    private String msg;

    @TableField(value = "login_time", fill = FieldFill.INSERT)
    private LocalDateTime loginTime;

}
