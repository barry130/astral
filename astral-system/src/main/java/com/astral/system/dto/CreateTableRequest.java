package com.astral.system.dto;

import com.astral.schema.TableSchema;
import lombok.Data;

/**
 * 创建表结构请求DTO
 * <p>用于接收前端创建新表结构的请求参数</p>
 */
@Data
public class CreateTableRequest {
    /** 表名（下划线命名） */
    private String tableName;
    /** 表注释/描述 */
    private String tableComment;
    /** 所属模块名称，默认为system */
    private String moduleName;
    /** 是否包含通用字段（id、create_time、update_time、deleted），默认为true */
    private Boolean includeCommonFields = true;
}
