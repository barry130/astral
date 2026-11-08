package com.astral.auth.registry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时的权限自动登记入口。
 *
 * <p>放在 {@link ApplicationRunner} 而非 {@code @PostConstruct}：必须等所有控制器 Bean
 * 与插件 Bean 就绪、Flyway 迁移完成后再扫描，否则会漏声明或在表结构就绪前写库。</p>
 *
 * <p>任何异常只告警不阻断启动：权限登记失败最多让管理端权限树暂时缺项，
 * 不应把整个系统拖死。</p>
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class PermissionAutoRegistrar implements ApplicationRunner {

    private final PermissionRegistry permissionRegistry;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int inserted = permissionRegistry.registerMissing();
            if (inserted > 0) {
                log.info("[PermissionRegistry] 启动权限登记完成，新增 {} 条", inserted);
            }
        } catch (Exception e) {
            log.warn("[PermissionRegistry] 启动权限登记失败（不影响启动，可在管理端手工补齐）: {}", e.getMessage(), e);
        }
    }
}
