package com.astral.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
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
        entity.setId(id);
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
        List<Long> roleIds = body.get("roleIds");
        // 先删除用户现有的所有角色关联
        userRoleService.remove(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, id));
        if (roleIds != null && !roleIds.isEmpty()) {
            // 构建新的用户角色关联列表并批量保存
            List<UserRole> userRoles = roleIds.stream()
                    .map(roleId -> {
                        UserRole ur = new UserRole();
                        ur.setUserId(id);
                        ur.setRoleId(roleId);
                        return ur;
                    })
                    .collect(Collectors.toList());
            userRoleService.saveBatch(userRoles);
        }
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
        User user = userService.getById(id);
        if (user == null) {
            return Result.error("用户不存在");
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
        String password = body.get("password");
        if (password == null || password.length() < 6) {
            return Result.error("密码至少6位");
        }
        User user = userService.getById(id);
        if (user == null) {
            return Result.error("用户不存在");
        }
        user.setPassword(BCrypt.hashpw(password));
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
        if (userService.getById(id) == null) {
            return Result.error("用户不存在");
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
        String userType = body.get("userType");
        if (userType == null || userType.isBlank()) {
            return Result.error("用户类型不能为空");
        }
        if (!"ADMIN".equals(userType) && !"APP".equals(userType)) {
            return Result.error("不支持的用户类型");
        }
        User user = userService.getById(id);
        if (user == null) {
            return Result.error("用户不存在");
        }
        user.setUserType(userType);
        user.setUpdateTime(LocalDateTime.now());
        userService.updateById(user);
        return Result.success();
    }
}
