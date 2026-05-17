package com.astral.system.controller;

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
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户管理控制器
 * <p>提供用户CRUD操作、用户角色查询与分配功能</p>
 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/api/v1/system/user")
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
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @return 分页用户数据
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<User>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                   @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<User> page = new Page<>(pageNum, pageSize);
        return Result.success(userService.page(page));
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
    public Result<Void> create(@RequestBody User entity) {
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
}
