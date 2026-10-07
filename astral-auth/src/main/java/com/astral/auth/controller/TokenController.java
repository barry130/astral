package com.astral.auth.controller;

import com.astral.auth.dto.TokenInfo;
import com.astral.auth.service.TokenService;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.annotation.RequiresSuper;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Token管理控制器
 * <p>提供Token分页查询、吊销、踢出用户及清理过期Token等功能</p>
 * <p>权限：查看类接口要求 {@code admin:system:token:view}；吊销 / 踢人 / 清理属于会话失效类操作，
 * 要求 {@code admin:system:token:edit}（可委派给「会话运维」角色）。这些操作只让目标重新登录，
 * 不改变任何账号的权限集合，因此不属于提权类操作。</p>
 */
@Tag(name = "Token管理")
@RestController
@RequestMapping("/api/v1/admin/system/token")
@RequiredArgsConstructor
@RequiresPermission(value = "admin:system:token:view", name = "Token查看", domain = "system",
        description = "在线 Token / 会话列表查看")
public class TokenController {

    /** Token服务 */
    private final TokenService tokenService;

    /**
     * 分页查询Token列表
     * <p>从Sa-Token中获取Token信息并分页展示</p>
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @param userId 用户ID（可选，用于筛选特定用户的Token）
     * @return 分页Token信息
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<TokenInfo>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "10") Integer pageSize,
                                        @RequestParam(required = false) Long userId) {
        return Result.success(tokenService.pageFromSaToken(pageNum, pageSize, userId));
    }

    /**
     * 吊销指定Token
     *
     * @param id Token标识
     * @return 操作结果
     */
    @Operation(summary = "吊销Token")
    @PutMapping("/{id}/revoke")
    @RequiresPermission(value = "admin:system:token:edit", name = "Token吊销", domain = "system",
            description = "吊销单个 Token / 踢用户下线 / 清理过期 Token")
    public Result<Void> revoke(@PathVariable String id) {
        tokenService.revokeToken(id);
        return Result.success();
    }

    /**
     * 踢出指定用户（使其所有Token失效）
     *
     * @param userId 用户ID
     * @return 操作结果
     */
    @Operation(summary = "踢出用户")
    @PutMapping("/user/{userId}/kick")
    @RequiresPermission("admin:system:token:edit")
    public Result<Void> kickOut(@PathVariable Long userId) {
        tokenService.kickOutUser(userId);
        return Result.success();
    }

    /**
     * 清理所有过期的Token
     *
     * @return 操作结果
     */
    @Operation(summary = "清理过期Token")
    @DeleteMapping("/expired")
    @RequiresPermission("admin:system:token:edit")
    public Result<Void> cleanExpired() {
        tokenService.cleanExpiredTokens();
        return Result.success();
    }

    /**
     * 全端会话重置：吊销所有在线 Token，所有人（含操作者本人）重新登录。
     * <p>用于会话模型变更后的存量清理、安全事件应急。属全局高危操作，要求超管。</p>
     *
     * @return 操作结果
     */
    @Operation(summary = "全端会话重置（吊销所有在线Token，所有人重新登录）")
    @PutMapping("/revoke-all")
    @RequiresSuper
    public Result<Void> revokeAll() {
        tokenService.revokeAllTokens();
        return Result.success();
    }
}
