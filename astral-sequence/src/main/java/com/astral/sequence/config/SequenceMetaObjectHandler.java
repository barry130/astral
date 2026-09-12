package com.astral.sequence.config;

import com.astral.sequence.generator.SegmentGenerator;
import com.astral.sequence.service.GeneratorFactory;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * MyBatis-Plus 元对象处理器
 * <p>
 * 在实体插入和更新时自动填充公共字段：
 * <ul>
 *   <li><b>id</b>：使用号段生成器自动填充主键 ID（排除序列系统自身 4 张表），
 *       每张业务表独立序列（业务键 = 表名_id，如 sys_user_id）</li>
 *   <li><b>createTime</b>：插入时自动填充当前时间</li>
 *   <li><b>updateTime</b>：插入和更新时自动填充当前时间</li>
 * </ul>
 * </p>
 * <p>
 * 排除的表包括：sequence_config、sequence_statistics、sequence_history、sequence_segment。
 * 这 4 张表是序列系统自身的存储表，主键由数据库自增维护（避免递归取号）。
 * </p>
 */
@Slf4j
public class SequenceMetaObjectHandler implements MetaObjectHandler {

    /**
     * 排除自动填充的表名集合
     * <p>
     * 这些表是序列生成系统的核心表，使用自己的 ID 生成策略，
     * 不应该被自动填充覆盖。
     * </p>
     */
    private static final Set<String> EXCLUDED_TABLES = Set.of(
            "sequence_config",
            "sequence_statistics",
            "sequence_history",
            "sequence_segment"
    );

    /** 号段生成器，用于自动生成主键 ID */
    private final SegmentGenerator segmentGenerator;
    /** 实体 ID 序列提供者（业务键 = 表名_id） */
    private final EntityIdSequenceProvider entityIdSequenceProvider;
    /** 生成器工厂，用于在分配 ID 后异步刷新序列统计的当前值（供序列管理页展示） */
    private final GeneratorFactory generatorFactory;

    /**
     * 构造函数
     * <p>
     * 使用 @Lazy 注解延迟注入 SegmentGenerator 与 GeneratorFactory，避免循环依赖。
     * 二者都可能间接依赖于 MyBatis 的 SqlSessionFactory，而元对象处理器在
     * SqlSessionFactory 构建阶段就需要就绪，因此必须延迟到首次实际取号时再解析。
     * </p>
     *
     * @param segmentGenerator 号段生成器
     * @param entityIdSequenceProvider 实体 ID 序列提供者
     * @param generatorFactory 生成器工厂
     */
    public SequenceMetaObjectHandler(
            @Lazy SegmentGenerator segmentGenerator,
            EntityIdSequenceProvider entityIdSequenceProvider,
            @Lazy GeneratorFactory generatorFactory) {
        this.segmentGenerator = segmentGenerator;
        this.entityIdSequenceProvider = entityIdSequenceProvider;
        this.generatorFactory = generatorFactory;
    }

    /**
     * 插入时自动填充
     * <p>
     * 处理三个字段：
     * <ol>
     *   <li>id：如果为空，使用号段生成器自动生成（排除特定表）</li>
     *   <li>createTime：如果为空，填充当前时间</li>
     *   <li>updateTime：如果为空，填充当前时间</li>
     * </ol>
     * </p>
     *
     * @param metaObject MyBatis-Plus 元对象，包含实体信息和字段值
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        String tableName = resolveTableName(metaObject);
        if (tableName == null || EXCLUDED_TABLES.contains(tableName)) {
            return;
        }

        if (metaObject.hasGetter("id")) {
            Object idVal = metaObject.getValue("id");
            if (idVal == null) {
                String bizKey = entityIdSequenceProvider.getBizKeyForTable(tableName);
                long nextId = segmentGenerator.next(bizKey);
                setFieldValByName("id", nextId, metaObject);
                // 同步刷新序列统计的当前值，使序列管理页面的「当前值」能反映真实插入已占用的序列号
                generatorFactory.updateStatisticsAsync(bizKey, nextId);
                log.debug("Auto-filled id={} for table={} via bizKey={}", nextId, tableName, bizKey);
            }
        }

        // 自动填充创建时间
        if (metaObject.hasGetter("createTime")) {
            Object createTime = metaObject.getValue("createTime");
            if (createTime == null) {
                strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
            }
        }
        
        // 自动填充更新时间
        if (metaObject.hasGetter("updateTime")) {
            Object updateTime = metaObject.getValue("updateTime");
            if (updateTime == null) {
                strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }
        }
    }

    /**
     * 更新时自动填充
     * <p>
     * 只更新 updateTime 字段，保持 createTime 不变。
     * </p>
     *
     * @param metaObject MyBatis-Plus 元对象
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasGetter("updateTime")) {
            strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        }
    }

    /**
     * 解析实体对应的表名
     * <p>
     * 优先使用 MyBatis-Plus 的 TableInfo 获取表名（通过 @TableName 注解配置）。
     * 如果无法获取，则将类名转换为蛇形命名作为表名。
     * </p>
     *
     * @param metaObject 元对象
     * @return 表名，如果无法解析则返回 null
     */
    private String resolveTableName(MetaObject metaObject) {
        Object originalObject = metaObject.getOriginalObject();
        if (originalObject == null) return null;
        var tableInfo = TableInfoHelper.getTableInfo(originalObject.getClass());
        if (tableInfo != null && tableInfo.getTableName() != null) {
            return tableInfo.getTableName();
        }
        // 降级处理：将类名转换为蛇形命名
        return toSnakeCase(originalObject.getClass().getSimpleName());
    }

    /**
     * 将驼峰命名转换为蛇形命名
     * <p>
     * 例如：UserOrder -> user_order
     * 这是 MyBatis-Plus 默认的表名映射规则。
     * </p>
     *
     * @param camelCase 驼峰命名的字符串
     * @return 蛇形命名的字符串
     */
    private String toSnakeCase(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) return camelCase;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
