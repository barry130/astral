package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_mail_account")
public class SysMailAccount {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("account_name")
    private String accountName;

    @TableField("smtp_host")
    private String smtpHost;

    @TableField("smtp_port")
    private Integer smtpPort;

    private String username;

    private String password;

    @TableField("from_addr")
    private String fromAddr;

    @TableField("from_name")
    private String fromName;

    @TableField("ssl_enable")
    private Integer sslEnable;

    @TableField("starttls_enable")
    private Integer starttlsEnable;

    private Integer enabled;

    private Integer weight;

    private String remark;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
