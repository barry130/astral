package com.astral.auth.registry;

import com.astral.auth.security.PermissionCache;
import com.astral.dao.entity.Permission;
import com.astral.dao.entity.Role;
import com.astral.dao.entity.RolePermission;
import com.astral.dao.entity.User;
import com.astral.dao.entity.UserRole;
import com.astral.dao.mapper.PermissionMapper;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.RolePermissionMapper;
import com.astral.dao.mapper.UserMapper;
import com.astral.dao.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * App 端默认角色（{@code APP_USER}）的归属维护。
 *
 * <p><b>为什么需要它</b>：权限码规范是 {@code 端:域:资源:操作[:范围]}，端 = {@code admin/user/all}。
 * App 客户端（qt-uniappx / qt-pc）调用的接口一律 {@code user:} 前缀，管理端一律 {@code admin:} 前缀。
 * 于是「App 用户默认拥有 App 端全部权限」可以被精确表达为
 * <b>「持有全部 {@code user:} 权限」</b>——用前缀做集合定义，而不是罗列具体码：
 * 以后注解上新增一个 {@code user:xxx:yyy}，{@link AppUserRoleInitializer} 启动时会自动补给该角色，
 * 不需要再写一条迁移，也不会误把 {@code admin:} 权限发出去。</p>
 *
 * <p><b>与 Flyway 分工</b>：{@code V20261001005} 负责存量数据一次性收敛（建角色/授权/回填），
 * 本类每次启动做同样的幂等收敛，负责增量与自愈（例如角色被误删、授权被清空）。
 * 两者都幂等，重复执行不会产生重复授权（唯一约束 {@code uk_sys_role_permission} /
 * {@code uk_sys_user_role} 兜底）。</p>
 *
 * <p><b>角色可移除</b>：这是普通角色（{@code is_super=0}），管理员在角色管理页把
 * APP_USER 从某用户身上摘掉（或直接停用/删除该角色）后，该用户的 App 端接口即 403——
 * 这是预期行为，属「权限收紧」，本类只在**启动时**回填，不会在运行期把用户刚摘掉的角色塞回去。
 * 唯一例外是用户明确要求的「新注册 App 用户自动分配」：见 {@link #assignToUser(Long)}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppUserRoleService {

    /** App 用户默认角色编码（sys_role.role_code） */
    public static final String ROLE_CODE = "APP_USER";
    /** App 端权限码前缀：规范第 1 段（端）为 user */
    public static final String APP_PERMISSION_PREFIX = "user:";
    /** App 用户类型（sys_user.user_type），与 QtUserService.USER_TYPE_APP 一致 */
    public static final String USER_TYPE_APP = "APP";

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final PermissionCache permissionCache;

    /**
     * 幂等：确保 APP_USER 角色存在，返回其 id。
     * 角色被管理员改名不影响本方法（按 role_code 定位，不按 id/名称）。
     */
    public Long ensureRole() {
        Role existing = roleMapper.selectOne(new QueryWrapper<Role>().eq("role_code", ROLE_CODE));
        if (existing != null) {
            return existing.getId();
        }
        Role role = new Role();
        role.setRoleCode(ROLE_CODE);
        role.setRoleName("App 用户");
        role.setDescription("App 端默认角色：持有全部 user: 前缀权限（App 客户端接口）。新注册 App 用户自动分配，可按需移除。");
        role.setStatus(1);
        role.setSort(100);
        role.setIsSuper(0);
        // id 由 SequenceMetaObjectHandler 按业务键 sys_role_id 取号（IdType.INPUT + fill 触发）
        roleMapper.insert(role);
        log.info("[AppUserRole] 已创建默认角色 {} (id={})", ROLE_CODE, role.getId());
        permissionCache.bumpVersion();
        return role.getId();
    }

    /**
     * 幂等：把库里所有 {@code user:} 前缀权限授予 APP_USER。
     *
     * @return 本次新增的授权条数（0 表示已是最新）
     */
    public int syncPermissions() {
        Long roleId = ensureRole();
        List<Permission> appPermissions = permissionMapper.selectList(
                new QueryWrapper<Permission>().likeRight("permission_code", APP_PERMISSION_PREFIX));
        int granted = 0;
        for (Permission p : appPermissions) {
            Long exists = rolePermissionMapper.selectCount(new QueryWrapper<RolePermission>()
                    .eq("role_id", roleId)
                    .eq("permission_id", p.getId()));
            if (exists != null && exists > 0) {
                continue;
            }
            RolePermission link = new RolePermission();
            link.setRoleId(roleId);
            link.setPermissionId(p.getId());
            rolePermissionMapper.insert(link);
            granted++;
        }
        if (granted > 0) {
            permissionCache.bumpVersion();
        }
        return granted;
    }

    /**
     * 幂等：把所有 {@code user_type='APP'} 的存量用户纳入 APP_USER（追加，不替换既有角色）。
     *
     * @return 本次新增的用户-角色关联条数
     */
    public int backfillAppUsers() {
        Long roleId = ensureRole();
        List<User> appUsers = userMapper.selectList(
                new QueryWrapper<User>().eq("user_type", USER_TYPE_APP));
        int linked = 0;
        for (User u : appUsers) {
            Long exists = userRoleMapper.selectCount(new QueryWrapper<UserRole>()
                    .eq("user_id", u.getId())
                    .eq("role_id", roleId));
            if (exists != null && exists > 0) {
                continue;
            }
            UserRole link = new UserRole();
            link.setUserId(u.getId());
            link.setRoleId(roleId);
            userRoleMapper.insert(link);
            linked++;
        }
        if (linked > 0) {
            permissionCache.bumpVersion();
        }
        return linked;
    }

    /**
     * 幂等：把 APP_USER 分配给指定用户。
     *
     * <p>运行期唯一会「自动发角色」的入口：App 端注册成功后调用，保证新用户开箱即用；
     * 其余场景（管理端手工调整）不会被覆盖。</p>
     */
    public void assignToUser(Long userId) {
        if (userId == null) {
            return;
        }
        Long roleId = ensureRole();
        Long exists = userRoleMapper.selectCount(new QueryWrapper<UserRole>()
                .eq("user_id", userId)
                .eq("role_id", roleId));
        if (exists != null && exists > 0) {
            return;
        }
        UserRole link = new UserRole();
        link.setUserId(userId);
        link.setRoleId(roleId);
        userRoleMapper.insert(link);
        permissionCache.bumpVersion();
        log.info("[AppUserRole] 已为用户 {} 分配默认角色 {}", userId, ROLE_CODE);
    }
}