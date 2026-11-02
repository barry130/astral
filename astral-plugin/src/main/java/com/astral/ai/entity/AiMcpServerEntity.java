package com.astral.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 服务器（Streamable HTTP）。
 *
 * <p>一个服务器承载若干远端工具；刷新时 discovery 会把 {@code tools/list} 的结果落成
 * {@code ai_tool}（executor_type=MCP），运行时由
 * {@link com.astral.ai.tool.declarative.McpToolExecutor} 转成 {@code tools/call}。</p>
 */
@Data
@TableName("ai_mcp_server")
public class AiMcpServerEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 服务器编码（工具编码前缀，小写 snake_case） */
    private String serverCode;

    /** 展示名称 */
    private String name;

    /** MCP 端点地址 */
    private String url;

    /** 附加请求头 JSON，值可写 ${secret:key} */
    private String headersJson;

    /** 是否为只读服务器（决定发现出来的工具默认风险） */
    private Boolean readOnly;

    /** 状态：ENABLED / DISABLED */
    private String status;

    /** 最近一次刷新发现的工具数量 */
    private Integer toolCount;

    /** 最近一次刷新时间 */
    private LocalDateTime lastRefreshTime;

    /** 最近一次失败原因 */
    private String lastError;

    /** 创建人 */
    private Long createdBy;

    /** 更新人 */
    private Long updatedBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
