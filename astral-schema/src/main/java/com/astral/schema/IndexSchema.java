package com.astral.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 索引Schema定义
 * <p>
 * 描述数据库表的索引信息，用于代码生成和建表SQL生成。
 * 从JSON Schema文件中反序列化得到。
 * </p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class IndexSchema {
    /** 索引名称 */
    private String indexName;
    /** 索引包含的列名列表 */
    private List<String> columns;
    /** 是否为唯一索引 */
    private boolean isUnique;
}
