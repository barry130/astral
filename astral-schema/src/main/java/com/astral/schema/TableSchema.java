package com.astral.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 表Schema定义
 * <p>
 * 描述数据库表的完整元数据信息，是代码生成的核心数据结构。
 * 从JSON Schema文件中反序列化得到，包含：
 * <ul>
 *   <li>表名、表注释、所属模块</li>
 *   <li>字段列表（数据库字段）</li>
 *   <li>非数据库字段列表（Entity中需要但不对应数据库列的字段）</li>
 *   <li>索引列表</li>
 * </ul>
 * </p>
 * <p>
 * 提供包名计算方法，用于确定生成的Entity、Mapper、Service、Controller所在的包。
 * </p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TableSchema {
    /** 数据库表名 */
    private String tableName;
    /** 表注释/说明 */
    private String tableComment;
    /** 所属模块名（用于生成Controller路径） */
    private String moduleName;
    /** Java类名（大驼峰命名） */
    private String className;
    /** 数据库字段列表 */
    private List<FieldSchema> fields;
    /** 非数据库字段列表（标注 @TableField(exist = false)） */
    private List<FieldSchema> nonDbFields;
    /** 索引列表 */
    private List<IndexSchema> indexes;

    /**
     * 获取Entity类所在的包名
     *
     * @return 固定返回 "com.astral.dao.entity"
     */
    public String getPackageName() {
        return "com.astral.dao.entity";
    }

    /**
     * 获取Mapper接口所在的包名
     *
     * @return 固定返回 "com.astral.dao.mapper"
     */
    public String getMapperPackageName() {
        return "com.astral.dao.mapper";
    }

    /**
     * 获取Service接口所在的包名
     *
     * @return 固定返回 "com.astral.server.service"
     */
    public String getServicePackageName() {
        return "com.astral.server.service";
    }

    /**
     * 获取Controller类所在的包名
     *
     * @return 固定返回 "com.astral.server.controller"
     */
    public String getControllerPackageName() {
        return "com.astral.server.controller";
    }
}
