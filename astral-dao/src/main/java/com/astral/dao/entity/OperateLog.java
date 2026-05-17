package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("sys_operate_log")
public class OperateLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    private String username;

    private String module;

    @TableField("operate_type")
    private String operateType;

    @TableField("request_method")
    private String requestMethod;

    @TableField("request_url")
    private String requestUrl;

    @TableField("request_params")
    private String requestParams;

    @TableField("response_result")
    private String responseResult;

    private String ip;

    private String location;

    private Integer status;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("execute_time")
    private Long executeTime;

    @TableField("create_time")
    private LocalDateTime createTime;

}
