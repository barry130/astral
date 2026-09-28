package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_mail_account")
public class SysMailAccount {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("account_name")
    @Size(max = 64)
    private String accountName;

    @TableField("smtp_host")
    @Size(max = 128)
    private String smtpHost;

    @TableField("smtp_port")
    private Integer smtpPort;

    @Size(max = 128)
    private String username;

    @Size(max = 256)
    private String password;

    @TableField("from_addr")
    @Size(max = 128)
    private String fromAddr;

    @TableField("from_name")
    @Size(max = 64)
    private String fromName;

    @TableField("ssl_enable")
    private Integer sslEnable;

    @TableField("starttls_enable")
    private Integer starttlsEnable;

    private Integer enabled;

    private Integer weight;

    @Size(max = 256)
    private String remark;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
