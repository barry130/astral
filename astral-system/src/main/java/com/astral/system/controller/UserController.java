package com.astral.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.astral.auth.security.PermissionCache;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.annotation.RequiresSuper;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.common.util.CsvExportUtil;
import com.astral.common.util.PasswordPolicy;
import com.astral.dao.entity.Role;
import com.astral.dao.entity.User;
import com.astral.dao.entity.UserRole;
import com.astral.system.service.RoleService;
import com.astral.system.service.UserRoleService;
import com.astral.system.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理控制器
 * <p>提供用户CRUD操作、用户角色查询与分配功能</p>
 * <p>权限：查询类接口要求 {@code admin:system:user:view}，写接口与角色分配为提权面，要求超管（{@link RequiresSuper}）</p>
 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/api/v1/admin/system/user")
@RequiredArgsConstructor
public class UserController {

    /** 用户服务 */
    private final UserService userService;
    /** 用户角色关联服务 */
    private final UserRoleService userRoleService;
    /** 角色服务 */
    private final RoleService roleService;
    /** 权限缓存：用户角色关系变更后递增版本，立即失效所有用户的权限缓存 */
    private final PermissionCache permissionCache;

    /**
     * 分页查询用户列表
     * <p>统一管理后端系统用户（ADMIN）与插件 App 用户（APP）：
     * 不传 userType 时返回全部类型，传入时按类型过滤。</p>
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @param userType 用户类型（可选）：ADMIN 管理端 / APP 轻听App用户；为空查全部
     * @param username 用户名模糊搜索（可选）
     * @return 分页用户数据
     */
    @Operation(summary = "分页查询")
    @RequiresPermission("admin:system:user:view")
    @GetMapping("/page")
    public Result<Page<User>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String userType,
            @RequestParam(required = false) String username) {
        Page<User> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        // 统一管理所有类型用户（后台系统用户 + 插件 App 用户）
        if (userType != null && !userType.isBlank()) {
            wrapper.eq(User::getUserType, userType);
        }
        if (username != null && !username.isBlank()) {
            wrapper.and(w -> w.like(User::getUsername, username)
                    .or()
                    .like(User::getNickname, username)
                    .or()
                    .like(User::getEmail, username));
        }
        wrapper.orderByDesc(User::getCreateTime);
        return Result.success(userService.page(page, wrapper));
    }

    /**
     * 导出用户 CSV（与分页查询同一套过滤条件与权限）
     * <p>导出量按当前全量数据（不分页），字段不含密码散列。</p>
     */
    @Operation(summary = "导出CSV")
    @RequiresPermission("admin:system:user:view")
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String userType, @RequestParam(required = false) String username) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (userType != null && !userType.isBlank()) {
            wrapper.eq(User::getUserType, userType);
        }
        if (username != null && !username.isBlank()) {
            wrapper.and(w -> w.like(User::getUsername, username)
                    .or()
                    .like(User::getNickname, username)
                    .or()
                    .like(User::getEmail, username));
        }
        wrapper.orderByDesc(User::getCreateTime);
        List<User> users = userService.list(wrapper);
        byte[] csv = CsvExportUtil.build(
                new String[] {"ID", "用户名", "昵称", "邮箱", "状态", "用户类型", "封禁/注销理由", "最后登录IP", "最后登录时间", "密码更新时间", "创建时间"},
                users,
                u -> new Object[] {
                    u.getId(),
                    u.getUsername(),
                    u.getNickname(),
                    u.getEmail(),
                    u.getStatus() != null && u.getStatus() == 1 ? "正常" : "禁用",
                    u.getUserType(),
                    u.getStatusReason(),
                    u.getLoginIp(),
                    u.getLoginTime(),
                    u.getPwdUpdateTime(),
                    u.getCreateTime()
                });
        String filename = "users-" + java.time.LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + filename)
                .header("Content-Type", "text/csv;charset=UTF-8")
                .body(csv);
    }

    /**
     * 根据ID查询用户详情
     *
     * @param id 用户ID
     * @return 用户实体
     */
    @Operation(summary = "根据ID查询")
    @RequiresPermission("admin:system:user:view")
    @GetMapping("/{id}")
    public Result<User> getById(@PathVariable Long id) {
        User user = userService.getById(id);
        if (user != null) {
            // 与 exportCsv 口径一致：不回传 BCrypt 散列。
            // 「只有加密后的散列」不构成放行理由——离库即可离线爆破，
            // 且前端从不需要它（改密走 /{id}/password 单独接口）。
            user.setPassword(null);
        }
        return Result.success(user);
    }

    /**
     * 创建新用户
     *
     * @param entity 用户实体
     * @return 操作结果
     */
    @Operation(summary = "创建")
    @RequiresSuper
    @PostMapping
    public Result<Void> create(@Valid @RequestBody User entity) {
        userService.save(entity);
        // 新用户可能随后被写入角色关联（sys_user_role），递增权限版本避免缓存落后
        permissionCache.bumpVersion();
        return Result.success();
    }

    /**
     * 更新用户信息
     *
     * @param id 用户ID
     * @param entity 用户实体（包含更新后的字段）
     * @return 操作结果
     */
    @Operation(summary = "更新")
    // 仅改昵称/邮箱/手机号等资料字段：不含提权路径（userType/password/deleted 已在下方置 null），
    // 因此按普通写权限授予，可委派给「用户管理员」角色
    @RequiresPermission("admin:system:user:edit")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody User entity) {
        // 白名单更新：id 由路径决定，只写「资料类」业务列。
        // 不能用 updateById(客户端实体)——MP 3.5.17 默认 updateStrategy=NOT_NULL，
        // 会把实体上所有非 null 字段一并写进 SET（mass assignment），客户端可直接改写
        // password/pwd_update_time（口令轮换时间）、totp_secret/totp_enabled（二次因子）、
        // user_type/deleted（提权与逻辑删除）、status/status_reason（封禁状态）、
        // must_change_password/device_id/login_ip/login_time/create_time（内部记录列）。
        // 「置 null 剥离」也不成立：null 会被 NOT_NULL 策略跳过，挡不住也无法清空。
        var update = userService.lambdaUpdate().eq(User::getId, id);
        if (entity.getUsername() != null) {
            update.set(User::getUsername, entity.getUsername());
        }
        if (entity.getNickname() != null) {
            update.set(User::getNickname, entity.getNickname());
        }
        if (entity.getEmail() != null) {
            update.set(User::getEmail, entity.getEmail());
        }
        if (entity.getPhone() != null) {
            update.set(User::getPhone, entity.getPhone());
        }
        if (entity.getAvatar() != null) {
            update.set(User::getAvatar, entity.getAvatar());
        }
        update.update();
        // 更新用户可能伴随角色调整（sys_user_role），递增权限版本
        permissionCache.bumpVersion();
        return Result.success();
    }

    /**
     * 删除用户
     *
     * @param id 用户ID
     * @return 操作结果
     */
    @Operation(summary = "删除")
    @RequiresSuper
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userService.removeById(id);
        // 删除必须先踢掉存量会话：removeById 是逻辑删除，只改 sys_user.deleted，
        // Sa-Token 里该用户的 token 依然有效；而本系统 token 每请求滑动续期、
        // active-timeout=-1 关闭闲置过期 ⇒ 被删账号的会话不会自然过期，可继续调用管理端接口。
        // 拦截器里另有「账号状态」兜底门禁（≤30 秒），这里是即时生效路径。
        StpUtil.kickout(id);
        // 用户（及其角色关联）变更后，权限缓存立即失效
        permissionCache.bumpVersion();
        return Result.success();
    }

    /**
     * 获取指定用户的所有角色
     * <p>通过用户角色关联表查询用户关联的角色ID，再批量查询角色详情</p>
     *
     * @param id 用户ID
     * @return 用户角色列表
     */
    @Operation(summary = "获取用户角色")
    @RequiresPermission("admin:system:user:view")
    @GetMapping("/{id}/roles")
    public Result<List<Role>> getUserRoles(@PathVariable Long id) {
        List<UserRole> userRoles = userRoleService.list(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, id));
        if (userRoles.isEmpty()) {
            return Result.success(List.of());
        }
        // 提取角色ID列表，批量查询角色详情
        List<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).collect(Collectors.toList());
        List<Role> roles = roleService.listByIds(roleIds);
        return Result.success(roles);
    }

    /**
     * 为用户分配角色
     * <p>先删除用户现有的所有角色关联，再批量插入新的角色关联</p>
     *
     * @param id 用户ID
     * @param body 请求体，包含roleIds字段（角色ID列表）
     * @return 操作结果
     */
    @Operation(summary = "分配用户角色")
    // 提权类操作：给自己加角色 = 提权，只允许超级管理员执行
    @RequiresSuper
    @PutMapping("/{id}/roles")
    public Result<Void> assignRoles(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        if (userService.getById(id) == null) {
            throw BusinessException.of("用户不存在");
        }
        // 先删后插 + 角色存在性校验，整体在一个事务里（见 UserRoleServiceImpl.assignRoles）
        userRoleService.assignRoles(id, body.get("roleIds"));
        return Result.success();
    }

    /**
     * 封禁 / 解封用户
     *
     * @param id 用户ID
     * @param status 状态：1 启用 / 0 封禁
     * @return 操作结果
     */
    @Operation(summary = "封禁/解封用户")
    // 封禁不改变账号归属与权限集合（角色不变），可委派；真正的提权入口是 /{id}/type 与 /{id}/roles
    @RequiresPermission("admin:system:user:edit")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(
            @PathVariable Long id, @RequestParam Integer status, @RequestParam(required = false) String reason) {
        userService.changeStatus(id, status == null ? 1 : status, reason);
        // 封禁时踢下线
        if (status != null && status == 0) {
            StpUtil.kickout(id);
        }
        return Result.success();
    }

    /**
     * 重置用户密码
     *
     * @param id 用户ID
     * @param body 请求体，包含password字段
     * @return 操作结果
     */
    @Operation(summary = "重置用户密码")
    // 提权类操作：能改任意用户（含其他管理员）的密码 = 可直接接管账号
    @RequiresSuper
    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String password = body.get("password");
        User user = userService.getById(id);
        if (user == null) {
            throw BusinessException.of("用户不存在");
        }
        // Explicit credential-only update; do not write back unrelated account state loaded above.
        PasswordPolicy.validateOrThrow(password, user.getUsername());
        boolean changed = userService.update(
                null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<User>()
                        .eq(User::getId, id)
                        .eq(User::getPassword, user.getPassword())
                        .set(User::getPassword, BCrypt.hashpw(password))
                        .set(User::getPwdUpdateTime, LocalDateTime.now())
                        .set(User::getMustChangePassword, 1)
                        .set(User::getUpdateTime, LocalDateTime.now()));
        if (!changed) throw BusinessException.of("账号凭据已变更，请刷新后重试");
        // 重置密码后踢下线，强制重新登录
        StpUtil.kickout(id);
        return Result.success();
    }

    /**
     * 将用户踢下线（使其当前所有登录会话失效）
     *
     * @param id 用户ID
     * @return 操作结果
     */
    @Operation(summary = "踢下线")
    @RequiresPermission("admin:system:user:edit")
    @PutMapping("/{id}/kick")
    public Result<Void> kickOut(@PathVariable Long id) {
        if (userService.getById(id) == null) {
            throw BusinessException.of("用户不存在");
        }
        StpUtil.kickout(id);
        return Result.success();
    }

    /**
     * 修改用户类型（ADMIN / APP）
     *
     * @param id 用户ID
     * @param body 请求体，包含userType字段
     * @return 操作结果
     */
    @Operation(summary = "修改用户类型")
    // 改用户类型是提权入口（APP -> ADMIN），只允许超级管理员
    @RequiresSuper
    @PutMapping("/{id}/type")
    public Result<Void> changeType(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String userType = body.get("userType");
        if (userType == null || userType.isBlank()) {
            throw BusinessException.of("用户类型不能为空");
        }
        if (!"ADMIN".equals(userType) && !"APP".equals(userType)) {
            throw BusinessException.of("不支持的用户类型");
        }
        if (userService.getById(id) == null) {
            throw BusinessException.of("用户不存在");
        }
        // 同 changeStatus：只 patch userType，避免把 password 哈希带回 updateById 被二次哈希
        User patch = new User();
        patch.setId(id);
        patch.setUserType(userType);
        patch.setUpdateTime(LocalDateTime.now());
        userService.updateById(patch);
        // 类型变了，会话里缓存的 userType 立即失效，否则降级（ADMIN->APP）不会立刻生效
        StpUtil.kickout(id);
        return Result.success();
    }
}
