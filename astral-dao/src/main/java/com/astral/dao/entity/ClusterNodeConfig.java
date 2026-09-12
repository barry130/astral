package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("cluster_node_config")
public class ClusterNodeConfig {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("node_id")
    private String nodeId;

    @TableField("worker_id")
    private Integer workerId;

    @TableField("ip_address")
    private String ipAddress;

    private Integer port;

    @TableField("node_name")
    private String nodeName;

    private String status;

    @TableField("max_worker_id")
    private Integer maxWorkerId;

    private Boolean enabled;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

}
