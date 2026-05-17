package com.astral.server.service;

import com.astral.server.dto.TokenInfo;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * Token管理服务接口
 * <p>提供基于Sa-Token的Token管理功能，包括分页查询、吊销、踢出用户和清理过期Token</p>
 */
public interface TokenService {

    /**
     * 从Sa-Token中分页获取Token信息
     *
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @param userId 用户ID（可选，用于筛选特定用户的Token）
     * @return 分页Token信息
     */
    Page<TokenInfo> pageFromSaToken(int pageNum, int pageSize, Long userId);

    /**
     * 吊销指定Token
     *
     * @param tokenId Token标识
     */
    void revokeToken(String tokenId);

    /**
     * 踢出指定用户（使其所有Token失效）
     *
     * @param userId 用户ID
     */
    void kickOutUser(Long userId);

    /**
     * 清理所有过期的Token
     */
    void cleanExpiredTokens();
}
