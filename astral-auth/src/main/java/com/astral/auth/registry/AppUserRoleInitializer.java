package com.astral.auth.registry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * App 端默认角色 APP_USER 的启动初始化器。
 *
 * <p>每次启动做三件幂等的事：</p>
 * <ol>
 *   <li>补登记注解声明的权限（{@link PermissionRegistry#registerMissing()}）；</li>
 *   <li>把全部 {@code user:} 前缀权限授予 APP_USER（{@link AppUserRoleService#syncPermissions()}）；</li>
 *   <li>把存量 {@code user_type='APP'} 用户纳入 APP_USER（{@link AppUserRoleService#backfillAppUsers()}）。</li>
 * </ol>
 *
 * <p><b>为什么自己再调一次 registerMissing()</b>：本类与 {@link PermissionAutoRegistrar}
 * 同为 {@link Ordered#LOWEST_PRECEDENCE}，Spring 对**同序** ApplicationRunner 不保证先后。
 * 若注册器后跑，本次授权循环就看不到本轮新登记的 {@code user:} 权限（最坏情况下慢一个启动周期）。
 * 注册方法本身幂等，重复调用只是多扫一遍 Bean，代价可忽略，换来顺序无关的确定性。</p>
 *
 * <p>与 {@code PermissionAutoRegistrar} 一致：任何异常只告警不阻断启动——
 * 角色/授权是「可用性增强」，不该把整个系统拖死；管理员仍可在角色管理页手工补齐。</p>
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class AppUserRoleInitializer implements ApplicationRunner {

    private final PermissionRegistry permissionRegistry;
    private final AppUserRoleService appUserRoleService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            permissionRegistry.registerMissing();
            int granted = appUserRoleService.syncPermissions();
            int linked = appUserRoleService.backfillAppUsers();
            if (granted > 0 || linked > 0) {
                log.info("[AppUserRole] 初始化完成：新增授权 {} 条，纳入 App 用户 {} 个", granted, linked);
            }
        } catch (Exception e) {
            log.warn("[AppUserRole] 初始化失败（不影响启动，可在角色管理页手工分配 {}）：{}",
                    AppUserRoleService.ROLE_CODE, e.getMessage(), e);
        }
    }
}