package com.astral.qt.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 轻听插件表结构初始化器
 * <p>
 * 应用启动时自动执行 qt-schema.sql（CREATE TABLE IF NOT EXISTS，幂等）。
 * 受 astral.plugins.qt.enabled 控制：插件关闭时跳过建表。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "astral.plugins.qt.enabled", havingValue = "true", matchIfMissing = true)
public class QtSchemaInitializer {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.setContinueOnError(true);
            populator.setIgnoreFailedDrops(true);
            populator.addScript(new ClassPathResource("sql/qt-schema.sql"));
            populator.execute(jdbcTemplate.getDataSource());
            log.info("[QtPlugin] 轻听表结构初始化完成（qt_app_notice/update/daka/like_*/email_code）");
        } catch (Exception e) {
            log.error("[QtPlugin] 轻听表结构初始化失败", e);
            throw e;
        }
        // 用户体系统一：将历史 qt_user 并入宿主 sys_user（user_type='APP'）；幂等，无历史数据则 no-op
        migrateLegacyQtUsers();
    }

    /**
     * 历史轻听 App 用户迁移到宿主 sys_user（user_type='APP'）。
     * <p>仅当 qt_user 表仍存在时执行；按用户名去重，已存在的用户不重复迁入。
     * 轻听用户的会话 token（qt_user_token）不迁移，需重新登录。</p>
     */
    private void migrateLegacyQtUsers() {
        try {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.tables " +
                            "WHERE table_schema = current_schema() AND table_name = 'qt_user'",
                    Integer.class);
            if (exists == null || exists == 0) {
                return;
            }
            int migrated = jdbcTemplate.update(
                    "INSERT INTO sys_user (id, username, password, nickname, avatar, email, device_id, " +
                            "status, user_type, create_time, update_time) " +
                            "SELECT id, username, password, COALESCE(nickname, username), COALESCE(avatar, ''), " +
                            "email, device_id, CASE WHEN state = 1 THEN 1 ELSE 0 END, 'APP', create_time, update_time " +
                            "FROM qt_user q " +
                            "WHERE NOT EXISTS (SELECT 1 FROM sys_user s WHERE s.username = q.username)");
            if (migrated > 0) {
                log.info("[QtPlugin] 历史轻听用户迁移至 sys_user 完成，共 {} 条", migrated);
            }
        } catch (Exception e) {
            log.warn("[QtPlugin] 历史轻听用户迁移跳过: {}", e.getMessage());
        }
    }
}