package com.astral.auth.registry;

import com.astral.auth.security.PermissionCache;
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
 * <p>每次启动做两件幂等的事：</p>
 * <ol>
 *   <li>补登记注解声明的权限（{@link PermissionRegistry#registerMissing()}）；</li>
 *   <li>把全部 {@code user:} 前缀<b>接口</b>权限授予 APP_USER，并收回其名下范围(DATA)权限
 *       （{@link AppUserRoleService#syncPermissions()}）。</li>
 * </ol>
 *
 * <p><b>不做存量用户回填</b>：存量已由 {@code V20261001005} 一次性收敛（2026-10-01 上线），
 * 新注册用户由 {@link AppUserRoleService#assignToUser(Long)} 在注册成功时分配。曾经的逐用户
 * 回填在百万级用户下拖慢启动数分钟（healthcheck 误判的主要元凶），已移除；若个别用户缺角色，
 * 在角色管理页手工补授即可。</p>
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
    private final PermissionCache permissionCache;

    @Override
    public void run(ApplicationArguments args) {
        try {
            permissionRegistry.registerMissing();
            int granted = appUserRoleService.syncPermissions();
            if (granted > 0) {
                log.info("[AppUserRole] 初始化完成：新增授权 {} 条", granted);
            }
            // 无条件失效权限缓存：Flyway 迁移先于本 Runner 执行且直改库（无法从 SQL 触达 Redis），
            // 此刻 granted/revoked 可能都是 0，但会话缓存的权限列表仍是迁移前的旧集合。
            // 每次 bump 一次版本的成本只是活跃会话各多查一次库，换取「迁移改动即时生效」。
            permissionCache.bumpVersion();
        } catch (Exception e) {
            log.warn("[AppUserRole] 初始化失败（不影响启动，可在角色管理页手工分配 {}）：{}",
                    AppUserRoleService.ROLE_CODE, e.getMessage(), e);
        }
    }
}