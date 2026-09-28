package com.astral.sequence.config;

import com.astral.sequence.generator.SegmentGenerator;
import com.astral.sequence.service.GeneratorFactory;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
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
 *   <li><b>时间列</b>：按实体字段列表，把所有标注了
 *       {@code FieldFill.INSERT} / {@code UPDATE} / {@code INSERT_UPDATE}
 *       且类型为 {@code LocalDateTime} 的字段填成当前时间
 *       —— 不只是 {@code createTime} / {@code updateTime}，也包括
 *       {@code loginTime} 这类同样声明了填充的字段</li>
 * </ul>
 * </p>
 * <p>
 * <b>排除的表</b>：sequence_config、sequence_statistics、sequence_history、sequence_segment。
 * 这 4 张表是序列系统自身的存储表，主键由数据库自增 / MyBatis-Plus 维护
 * （避免递归取号）。<b>注意：排除仅针对主键 id 的取号，时间列照常填充</b>——
 * 早期实现是整段 early-return，导致这 4 张表的 createTime 永远为 null，
 * 而实体上又标了 {@code fill = FieldFill.INSERT}，MyBatis-Plus 会把该列
 * 强制写进 INSERT 并显式写入 NULL，反而顶掉了 DDL 的
 * {@code DEFAULT CURRENT_TIMESTAMP}，最终 NOT NULL 违约。
 * </p>
 */
@Slf4j
public class SequenceMetaObjectHandler implements MetaObjectHandler {

    /**
     * 不参与「主键取号」的表名集合
     * <p>
     * 这些表是序列生成系统的核心表，使用数据库自增 / MyBatis-Plus 自身的
     * 主键策略，不能走号段生成器（否则会递归取号）。
     * </p>
     * <p>
     * <b>该集合只影响 id 填充，不影响 createTime / updateTime 等时间列。</b>
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
     * 分两件事，互不影响：
     * <ol>
     *   <li><b>主键 id</b>：为空时用号段生成器取号；
     *       {@link #EXCLUDED_TABLES} 中的表跳过（否则递归取号）</li>
     *   <li><b>时间列</b>：所有表（含排除表）统一填充，见
     *       {@link #fillTimestamps(MetaObject, boolean)}</li>
     * </ol>
     * </p>
     *
     * @param metaObject MyBatis-Plus 元对象，包含实体信息和字段值
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        String tableName = resolveTableName(metaObject);
        boolean excluded = tableName != null && EXCLUDED_TABLES.contains(tableName);

        // 1) 主键取号：排除表不做
        if (!excluded && tableName != null && metaObject.hasGetter("id")) {
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

        // 2) 时间列：所有表都要填，排除表也一样
        fillTimestamps(metaObject, true);
    }

    /**
     * 更新时自动填充
     * <p>
     * 只填 updateTime 一类字段，保持 createTime 不变。
     * </p>
     *
     * @param metaObject MyBatis-Plus 元对象
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        fillTimestamps(metaObject, false);
    }

    /**
     * 按实体的真实字段列表填充时间列
     * <p>
     * <b>为什么不是按字段名写死？</b>
     * 原实现只认识 {@code createTime} / {@code updateTime} 两个字面字段名，
     * 于是 {@code LoginLog.loginTime}（同样标了 {@code fill = FieldFill.INSERT}）
     * 永远没人填 —— 而 MyBatis-Plus 只要看到 fill 注解，就会把该列强制写进
     * INSERT 并显式写入 NULL，反而顶掉了 DDL 的 {@code DEFAULT CURRENT_TIMESTAMP}，
     * 结果是 NOT NULL 违约、接口 500。
     * </p>
     * <p>
     * 这里改为遍历 {@link TableInfo} 的字段列表，凡是
     * 「标了 INSERT / UPDATE 填充 + 类型是 LocalDateTime + 当前值为 null」
     * 的字段一律补当前时间，从根上消除「注解声明了填充但处理器不认识」的错配。
     * </p>
     *
     * @param metaObject MyBatis-Plus 元对象
     * @param insert     true=插入填充（FieldFill.INSERT / INSERT_UPDATE），
     *                   false=更新填充（FieldFill.UPDATE / INSERT_UPDATE）
     */
    private void fillTimestamps(MetaObject metaObject, boolean insert) {
        Object entity = metaObject.getOriginalObject();
        if (entity == null) {
            return;
        }
        TableInfo tableInfo = TableInfoHelper.getTableInfo(entity.getClass());
        if (tableInfo == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (TableFieldInfo field : tableInfo.getFieldList()) {
            boolean fillable = insert ? field.isWithInsertFill() : field.isWithUpdateFill();
            if (!fillable || !LocalDateTime.class.equals(field.getPropertyType())) {
                continue;
            }
            String property = field.getProperty();
            if (metaObject.hasGetter(property) && metaObject.getValue(property) == null) {
                if (insert) {
                    strictInsertFill(metaObject, property, LocalDateTime.class, now);
                } else {
                    strictUpdateFill(metaObject, property, LocalDateTime.class, now);
                }
            }
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
