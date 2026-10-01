package com.astral.auth.registry;

import com.astral.auth.security.PermissionCache;
import com.astral.common.constant.PermissionType;
import com.astral.dao.entity.Permission;
import com.astral.dao.entity.Role;
import com.astral.dao.entity.RolePermission;
import com.astral.dao.entity.UserRole;
import com.astral.dao.mapper.PermissionMapper;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.RolePermissionMapper;
import com.astral.dao.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * App 端默认角色（{@code APP_USER}）的归属维护。
 *
 * <p><b>为什么需要它</b>：权限码规范是 {@code 端:域:资源:操作[:范围]}，端 = {@code admin/user/all}。
 * App 客户端（qt-uniappx / qt-pc）调用的接口一律 {@code user:} 前缀，管理端一律 {@code admin:} 前缀。
 * 于是「App 用户默认拥有 App 端全部<b>接口</b>权限」可以被精确表达为
 * <b>「持有全部 {@code user:} 前缀且 {@code type=API} 的权限」</b>——用前缀+类型做集合定义，
 * 而不是罗列具体码：以后注解上新增一个 {@code user:xxx:yyy}，{@link AppUserRoleInitializer}
 * 启动时会自动补给该角色，不需要再写一条迁移，也不会误把 {@code admin:} 权限发出去。
 * <b>范围权限（{@code type=DATA}，如 beta 渠道资格）不在此列</b>——那是投放资格，
 * 只能由管理员按人/按角色授予。</p>
 *
 * <p><b>与 Flyway 分工</b>：{@code V20261001005} 负责存量数据一次性收敛（建角色/授权/回填用户），
 * 已随 2026-10-01 上线执行完毕；本类每次启动只做轻量收敛（补角色、按前缀同步接口权限），
 * 负责「角色被误删、授权被清空、注解新增 user: 权限」这类增量自愈，
 * <b>不再逐个回填存量用户</b>——曾经的逐用户 selectCount 循环在百万级用户下拖慢启动数分钟
 * （healthcheck 误判的主要元凶），且存量已收敛、新注册走 {@link #assignToUser(Long)}，无此必要。
 * 两者都幂等，重复执行不会产生重复授权（唯一约束 {@code uk_sys_role_permission} /
 * {@code uk_sys_user_role} 兜底）。</p>
 *
 * <p><b>角色可移除</b>：这是普通角色（{@code is_super=0}），管理员在角色管理页把
 * APP_USER 从某用户身上摘掉（或直接停用/删除该角色）后，该用户的 App 端接口即 403——
 * 这是预期行为，属「权限收紧」，启动时不会把用户刚摘掉的角色塞回去。
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
        role.setDescription("App 端默认角色：持有全部 user: 前缀接口权限（App 客户端接口，不含范围/数据权限）。新注册 App 用户自动分配，可按需移除。");
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
     * 幂等：把库里所有 {@code user:} 前缀的<b>接口权限</b>（{@code type=API}）授予 APP_USER，
     * 并收回该角色名下任何<b>范围权限</b>（{@code type=DATA}）。
     *
     * <p><b>为什么排除 DATA</b>：范围权限（如 {@code user:qt:update:channel:beta}）表达的是
     * 「结果里能多看见哪一部分」——测试资格 / 灰度投放，语义上必须由管理员按人授予
     * （如 TESTER 角色）。若随默认角色批发，等于所有登录 App 用户都能看到测试版，
     * 违背结果级权限的投放意图（V20261001005 起按前缀全量授予时未区分类型，本方法为修复）。</p>
     *
     * @return 本次新增的授权条数（0 表示已是最新；收回数另记日志）
     */
    public int syncPermissions() {
        Long roleId = ensureRole();
        List<Permission> appPermissions = permissionMapper.selectList(
                new QueryWrapper<Permission>()
                        .likeRight("permission_code", APP_PERMISSION_PREFIX)
                        .eq("type", PermissionType.API));
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
        // 收回：APP_USER 名下的范围权限（历史误授存量，或管理员误加）。带 not-exists 语义的
        // 幂等清理——正常库删 0 行；与授权方向相反的同一不变量：APP_USER 永不持有 DATA 权限。
        // 注意收回范围刻意只有 user: 前缀（本初始化器只「发放」user: 权限，对应地也只收回它发得出去的）；
        // admin: 域 DATA 权限的误发存量一次性清理由 V20261001009 负责，这里不做全库清扫。
        int revoked = rolePermissionMapper.delete(new QueryWrapper<RolePermission>()
                .eq("role_id", roleId)
                .inSql("permission_id",
                        "SELECT id FROM sys_permission WHERE permission_code LIKE '"
                                + APP_PERMISSION_PREFIX + "%' AND type = " + PermissionType.DATA));
        if (revoked > 0) {
            log.info("[AppUserRole] 已从 {} 收回范围(DATA)权限 {} 条（范围权限不随默认角色发放）",
                    ROLE_CODE, revoked);
        }
        if (granted > 0 || revoked > 0) {
            permissionCache.bumpVersion();
        }
        return granted;
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
        try {
            userRoleMapper.insert(link);
        } catch (DuplicateKeyException e) {
            // 并发注册或存量数据已存在该关联：uk_sys_user_role 唯一约束兜底，视为幂等成功。
            // 2026-10-01 序列事故期间曾因取号撞主键把注册整个打断，这里不再让兜底场景升级为失败。
            log.info("[AppUserRole] 用户 {} 的 {} 关联已存在（唯一约束兜底），跳过", userId, ROLE_CODE);
            return;
        }
        permissionCache.bumpVersion();
        log.info("[AppUserRole] 已为用户 {} 分配默认角色 {}", userId, ROLE_CODE);
    }
}