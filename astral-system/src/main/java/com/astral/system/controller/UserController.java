package com.astral.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.astral.auth.security.PermissionCache;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.annotation.RequiresSuper;
import com.astral.dao.entity.Role;
import com.astral.dao.entity.User;
import com.astral.dao.entity.UserRole;
import com.astral.system.service.RoleService;
import com.astral.system.service.UserRoleService;
import com.astral.system.service.UserService;
import com.astral.common.result.Result;
import com.astral.common.util.CsvExportUtil;
import com.astral.common.util.PasswordPolicy;
import org.springframework.http.ResponseEntity;
import java.util.List;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    public Result<Page<User>> page(@RequestParam(defaultValue = "1") Integer pageNum,
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
                    .or().like(User::getNickname, username)
                    .or().like(User::getEmail, username));
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
    public ResponseEntity<byte[]> exportCsv(@RequestParam(required = false) String userType,
                                            @RequestParam(required = false) String username) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (userType != null && !userType.isBlank()) {
            wrapper.eq(User::getUserType, userType);
        }
        if (username != null && !username.isBlank()) {
            wrapper.and(w -> w.like(User::getUsername, username)
                    .or().like(User::getNickname, username)
                    .or().like(User::getEmail, username));
        }
        wrapper.orderByDesc(User::getCreateTime);
        List<User> users = userService.list(wrapper);
        byte[] csv = CsvExportUtil.build(
                new String[]{"ID", "用户名", "昵称", "邮箱", "状态", "用户类型", "封禁/注销理由",
                        "最后登录IP", "最后登录时间", "密码更新时间", "创建时间"},
                users, u -> new Object[]{u.getId(), u.getUsername(), u.getNickname(), u.getEmail(),
                        u.getStatus() != null && u.getStatus() == 1 ? "正常" : "禁用",
                        u.getUserType(), u.getStatusReason(), u.getLoginIp(),
                        u.getLoginTime(), u.getPwdUpdateTime(), u.getCreateTime()});
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
        return Result.success(userService.getById(id));
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
        entity.setId(id);
        // 防止 mass assignment：请求体是实体，客户端可以直接塞入敏感字段。
        // 置 null 后 MyBatis-Plus 默认策略（NOT_NULL）会跳过这些列，不会被覆盖。
        entity.setUserType(null);      // 用户类型只能走 /{id}/type
        entity.setPassword(null);      // 密码只能走 /{id}/password（含 BCrypt 处理）
        entity.setDeleted(null);       // 逻辑删除标记不可由客户端改写
        entity.setPwdUpdateTime(null);
        entity.setLoginTime(null);
        entity.setLoginIp(null);
        entity.setCreateTime(null);
        userService.updateById(entity);
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
            return Result.fail("用户不存在");
        }
        // 先删后插 + 角色存在性校验，整体在一个事务里（见 UserRoleServiceImpl.assignRoles）
        userRoleService.assignRoles(id, body.get("roleIds"));
        // 用户→角色映射已变更，递增权限版本让所有用户的权限缓存下次读取即回源
        permissionCache.bumpVersion();
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
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status,
                                     @RequestParam(required = false) String reason) {
        if (userService.getById(id) == null) {
            return Result.fail("用户不存在");
        }
        // 封禁理由：封禁时必填落库（用户管理页展示），解封时清空
        if (status != null && status == 0) {
            if (reason == null || reason.isBlank()) {
                return Result.fail("封禁时必须填写理由");
            }
        }
        // 只 patch 状态相关列：绝不能把 getById 出来的实体（password 是哈希）整个塞回
        // updateById —— UserServiceImpl.updateById 会把非空 password 当新明文再哈希一次，
        // 落库成 BCrypt(BCrypt(明文))，用户从此无法登录（实测踩坑）。
        User patch = new User();
        patch.setId(id);
        patch.setStatus(status == null ? 1 : status);
        patch.setStatusReason(status != null && status == 0 ? reason.trim() : "");
        patch.setUpdateTime(LocalDateTime.now());
        userService.updateById(patch);
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
            return Result.fail("用户不存在");
        }
        // 密码强度策略（失败抛 IllegalArgumentException -> 400 文案）；
        // 传明文交给 UserServiceImpl.updateById 统一 BCrypt 一次：这里再 hashpw 会被
        // updateById 对非空 password 再哈希，落库成 BCrypt(BCrypt(明文))，登录 checkpw 必败
        PasswordPolicy.validateOrThrow(password, user.getUsername());
        user.setPassword(password);
        user.setPwdUpdateTime(LocalDateTime.now());
        // 管理员重置的密码视为临时口令：目标用户下次管理端登录必须自行修改
        user.setMustChangePassword(1);
        user.setUpdateTime(LocalDateTime.now());
        userService.updateById(user);
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
            return Result.fail("用户不存在");
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
            return Result.fail("用户类型不能为空");
        }
        if (!"ADMIN".equals(userType) && !"APP".equals(userType)) {
            return Result.fail("不支持的用户类型");
        }
        if (userService.getById(id) == null) {
            return Result.fail("用户不存在");
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
