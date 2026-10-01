package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_user")
public class User {

    @TableId(type = IdType.INPUT)
    private Long id;

    @Size(max = 64)
    @NotNull
    private String username;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Size(max = 128)
    @NotNull
    private String password;

    @Size(max = 64)
    private String nickname;

    @Size(max = 128)
    private String email;

    @Size(max = 20)
    private String phone;

    @Size(max = 256)
    private String avatar;

    private Integer status;

    @TableField("user_type")
    @Size(max = 20)
    private String userType;

    @TableField("device_id")
    @Size(max = 128)
    private String deviceId;

    @TableField("login_ip")
    @Size(max = 64)
    private String loginIp;

    @TableField("login_time")
    private LocalDateTime loginTime;

    @TableField("pwd_update_time")
    private LocalDateTime pwdUpdateTime;

    @TableField("totp_secret")
    @Size(max = 128)
    private String totpSecret;

    @TableField("totp_enabled")
    private Integer totpEnabled;

    @TableField("must_change_password")
    private Integer mustChangePassword;

    @TableField("status_reason")
    @Size(max = 255)
    private String statusReason;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

}
