package com.astral.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.astral.auth.security.PermissionChecker;
import com.astral.dao.entity.Role;
import com.astral.dao.entity.User;
import com.astral.dao.entity.UserRole;
import com.astral.system.service.RoleService;
import com.astral.system.service.UserRoleService;
import com.astral.system.service.UserService;
import com.astral.common.result.Result;
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
    /** 权限校验器：管理端接口按 RBAC 权限编码校验，避免「仅登录即可调用」 */
    private final PermissionChecker permissionChecker;

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
    @GetMapping("/page")
    public Result<Page<User>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                    @RequestParam(defaultValue = "10") Integer pageSize,
                                    @RequestParam(required = false) String userType,
                                    @RequestParam(required = false) String username) {
        permissionChecker.require("system:user:view");
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
     * 根据ID查询用户详情
     *
     * @param id 用户ID
     * @return 用户实体
     */
    @Operation(summary = "根据ID查询")
    @GetMapping("/{id}")
    public Result<User> getById(@PathVariable Long id) {
        permissionChecker.require("system:user:view");
        return Result.success(userService.getById(id));
    }

    /**
     * 创建新用户
     *
     * @param entity 用户实体
     * @return 操作结果
     */
    @Operation(summary = "创建")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody User entity) {
        permissionChecker.requireSuper();
        userService.save(entity);
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
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody User entity) {
        permissionChecker.requireSuper();
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
        return Result.success();
    }

    /**
     * 删除用户
     *
     * @param id 用户ID
     * @return 操作结果
     */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        permissionChecker.requireSuper();
        userService.removeById(id);
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
    @GetMapping("/{id}/roles")
    public Result<List<Role>> getUserRoles(@PathVariable Long id) {
        permissionChecker.require("system:user:view");
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
    @PutMapping("/{id}/roles")
    public Result<Void> assignRoles(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        // 提权类操作：给自己加角色 = 提权，只允许超级管理员执行
        permissionChecker.requireSuper();
        if (userService.getById(id) == null) {
            return Result.fail("用户不存在");
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
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        permissionChecker.requireSuper();
        User user = userService.getById(id);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        user.setStatus(status == null ? 1 : status);
        user.setUpdateTime(LocalDateTime.now());
        userService.updateById(user);
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
    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        // 提权类操作：能改任意用户（含其他管理员）的密码 = 可直接接管账号
        permissionChecker.requireSuper();
        String password = body.get("password");
        if (password == null || password.length() < 6) {
            return Result.fail("密码至少6位");
        }
        User user = userService.getById(id);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        // 传明文交给 UserServiceImpl.updateById 统一 BCrypt 一次：这里再 hashpw 会被
        // updateById 对非空 password 再哈希，落库成 BCrypt(BCrypt(明文))，登录 checkpw 必败
        user.setPassword(password);
        user.setPwdUpdateTime(LocalDateTime.now());
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
    @PutMapping("/{id}/kick")
    public Result<Void> kickOut(@PathVariable Long id) {
        permissionChecker.requireSuper();
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
    @PutMapping("/{id}/type")
    public Result<Void> changeType(@PathVariable Long id, @RequestBody Map<String, String> body) {
        // 改用户类型是提权入口（APP -> ADMIN），只允许超级管理员
        permissionChecker.requireSuper();
        String userType = body.get("userType");
        if (userType == null || userType.isBlank()) {
            return Result.fail("用户类型不能为空");
        }
        if (!"ADMIN".equals(userType) && !"APP".equals(userType)) {
            return Result.fail("不支持的用户类型");
        }
        User user = userService.getById(id);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        user.setUserType(userType);
        user.setUpdateTime(LocalDateTime.now());
        userService.updateById(user);
        // 类型变了，会话里缓存的 userType 立即失效，否则降级（ADMIN->APP）不会立刻生效
        StpUtil.kickout(id);
        return Result.success();
    }
}
