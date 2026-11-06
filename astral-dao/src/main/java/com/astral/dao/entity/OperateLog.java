package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_operate_log")
public class OperateLog {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @Size(max = 64)
    private String username;

    @Size(max = 64)
    private String module;

    @TableField("operate_type")
    @Size(max = 32)
    private String operateType;

    @TableField("request_method")
    @Size(max = 16)
    private String requestMethod;

    @TableField("request_url")
    @Size(max = 256)
    private String requestUrl;

    @TableField("request_params")
    private String requestParams;

    @TableField("response_result")
    private String responseResult;

    @Size(max = 64)
    private String ip;

    @Size(max = 128)
    private String location;

    private Integer status;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("execute_time")
    private Long executeTime;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

}
