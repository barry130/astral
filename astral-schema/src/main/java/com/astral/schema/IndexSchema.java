package com.astral.schema;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;

/**
 * 索引Schema定义
 * <p>
 * 描述数据库表的索引信息，用于代码生成和建表SQL生成。
 * 从JSON Schema文件中反序列化得到。
 * </p>
 * <p>
 * 关于 {@code isUnique} 的绑定：JSON 契约字段名是 {@code isUnique}，但 Lombok 为
 * {@code boolean isUnique} 生成的是 {@code isUnique()}/{@code setUnique(boolean)}，
 * 按 JavaBean 规范推导出的属性名是 {@code unique}，Jackson 默认会把 JSON 里的
 * {@code "isUnique"} 当作未知字段静默忽略（{@code @JsonIgnoreProperties(ignoreUnknown=true)}），
 * 结果是「JSON 声明唯一索引、生成的 DDL 却丢了 UNIQUE」。
 * 这里用字段级 {@code @JsonProperty("isUnique")} 固定主名、{@code @JsonAlias("unique")}
 * 兼容旧写法，并忽略由 getter/setter 推导出的 {@code unique} 属性，避免同一字段被序列化两次。
 * </p>
 */
@Data
@JsonIgnoreProperties(
        ignoreUnknown = true,
        value = {"unique"})
public class IndexSchema {
    /** 索引名称 */
    private String indexName;
    /** 索引包含的列名列表 */
    private List<String> columns;

    /** 是否为唯一索引 */
    @JsonProperty("isUnique")
    @JsonAlias("unique")
    private boolean isUnique;
}
