package com.astral.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 字段Schema定义
 * <p>
 * 描述数据库表字段的完整元数据信息，包括：
 * <ul>
 *   <li>列名和Java字段名的映射关系</li>
 *   <li>JDBC类型和Java类型的对应</li>
 *   <li>主键、自增、必填、唯一等业务属性</li>
 *   <li>逻辑删除、乐观锁、自动填充等MyBatis-Plus特性</li>
 * </ul>
 * 从JSON Schema文件中反序列化得到，用于代码生成和建表SQL生成。
 * </p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FieldSchema {
    /** 数据库列名 */
    private String columnName;
    /** Java字段名（驼峰命名） */
    private String fieldName;
    /** Java字段类型 */
    private String fieldType;
    /** JDBC类型（如VARCHAR、BIGINT、TIMESTAMP等） */
    private String jdbcType;
    /** 字段注释/说明 */
    private String comment;
    /** 字段长度（字符串类型使用） */
    private Integer length;
    /** 默认值 */
    private String defaultValue;

    /** 是否为主键 */
    @JsonProperty("isPrimaryKey")
    private Boolean isPrimaryKey;
    /** 是否自增 */
    @JsonProperty("isAutoIncrement")
    private Boolean isAutoIncrement;
    /** 是否必填（NOT NULL） */
    @JsonProperty("isRequired")
    private Boolean isRequired;
    /** 是否唯一 */
    @JsonProperty("isUnique")
    private Boolean isUnique;
    /** 是否为逻辑删除字段（@TableLogic） */
    @JsonProperty("isLogicDelete")
    private Boolean isLogicDelete;
    /** 是否为乐观锁字段（@Version） */
    @JsonProperty("isVersion")
    private Boolean isVersion;
    /** 是否在JSON序列化时忽略（WRITE_ONLY） */
    @JsonProperty("isJsonIgnore")
    private Boolean isJsonIgnore;
    /** 自动填充策略（INSERT/UPDATE/INSERT_UPDATE） */
    private String isAutoFill;
    /** MyBatis-Plus主键策略类型（AUTO/INPUT/ASSIGN_ID等） */
    private String mybatisPlusIdType;
    /**
     * Jackson 日期格式（可选）
     * <p>非空时为该字段生成 {@code @JsonFormat(pattern = "值")}。仅用于「线上已存在客户端按固定格式收发日期」
     * 这类不能改用 ISO-8601 的兼容场景（例如管理端公告表单以 {@code yyyy-MM-dd HH:mm:ss} 提交时间）。
     * 默认留空，走 Jackson 默认的 ISO-8601。</p>
     */
    private String jsonFormat;
}
