package com.astral.sequence.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 实体 ID 序列提供者
 * <p>
 * 为每张业务表独立维护一个序列，业务键格式为 {@code 表名_id}（如 sys_user_id、qt_user_id）。
 * 序列系统自身的 4 张表（sequence_config/statistics/history/segment）使用数据库自增，不参与取号。
 * </p>
 * <p>
 * 预留段（reserve）：每张表首次初始化时预留 {@link #RESERVE} 个 ID 给初始化脚本中的
 * 种子数据与历史存量数据（自增 ID 时代），应用运行时生成的 ID 从 reserve + 1 开始，
 * 避免主键冲突。预留值对每张表恒定。
 * </p>
 */
@Slf4j
@Component
public class EntityIdSequenceProvider {

    /**
     * 预留 ID 数量：用于规避初始化脚本种子数据与历史存量数据的主键冲突
     */
    public static final long RESERVE = 1_000_000L;

    /** 全局序列业务键 = 数据库名 + "_id"（向后兼容，仅用于日志） */
    private final String entityIdBizKey;

    public EntityIdSequenceProvider(@Value("${spring.datasource.url:}") String jdbcUrl) {
        this.entityIdBizKey = deriveDatabaseName(jdbcUrl) + "_id";
        log.info("[EntityIdSequenceProvider] 实体ID序列业务键命名规则: {表名}_id, 数据库级序列: {}", entityIdBizKey);
    }

    /**
     * 根据表名生成实体 ID 序列的业务键
     *
     * @param tableName 数据库表名（如 sys_user）
     * @return 业务键（如 sys_user_id）
     */
    public String getBizKeyForTable(String tableName) {
        return tableName + "_id";
    }

    /**
     * 判断指定业务键是否为实体 ID 序列
     * <p>
     * 约定：所有以 {@code _id} 结尾的业务键视为实体 ID 序列，锁定为号段模式，
     * 不允许通过管理端修改类型或删除配置。
     * </p>
     *
     * @param bizKey 业务键
     * @return 如果是实体 ID 序列返回 true
     */
    public boolean isEntityIdBizKey(String bizKey) {
        return bizKey != null && bizKey.endsWith("_id");
    }

    /**
     * @return 全局序列业务键（数据库名_id），向后兼容
     */
    public String getEntityIdBizKey() {
        return entityIdBizKey;
    }

    public long getReserve() {
        return RESERVE;
    }

    /**
     * 从 JDBC URL 派生数据库名
     */
    private String deriveDatabaseName(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "astral";
        }
        String url = jdbcUrl;
        int q = url.indexOf('?');
        int sem = url.indexOf(';');
        if (q > 0) url = url.substring(0, q);
        if (sem > 0) url = url.substring(0, sem);
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);

        int lastSlash = url.lastIndexOf('/');
        int lastColon = url.lastIndexOf(':');
        int cut = Math.max(lastSlash, lastColon);
        String name = cut >= 0 ? url.substring(cut + 1) : url;

        name = name.trim();
        if (name.isEmpty()) name = "astral";
        name = name.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        return name;
    }
}
