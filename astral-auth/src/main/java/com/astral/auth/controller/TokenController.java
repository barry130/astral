package com.astral.auth.controller;

import com.astral.auth.dto.TokenInfo;
import com.astral.auth.service.TokenService;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Token管理控制器
 * <p>提供Token分页查询、吊销、踢出用户及清理过期Token等功能</p>
 */
@Tag(name = "Token管理")
@RestController
@RequestMapping("/api/v1/admin/system/token")
@RequiredArgsConstructor
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
    public Result<Void> cleanExpired() {
        tokenService.cleanExpiredTokens();
        return Result.success();
    }
}
