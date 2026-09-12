package com.astral.auth.service.impl;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import com.astral.auth.dto.TokenInfo;
import com.astral.auth.service.TokenService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Token管理服务实现类
 * <p>基于Sa-Token实现Token管理功能，包括从Sa-Token存储中分页获取Token信息、
 * 吊销Token、踢出用户以及清理过期Token</p>
 */
@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {

    /** 用户Mapper，用于查询用户名 */
    private final UserMapper userMapper;

    /**
     * 从Sa-Token中分页获取Token信息
     * <p>遍历Sa-Token存储中的所有Token键，解析Token信息并关联用户数据</p>
     *
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @param userId 用户ID（可选，用于筛选特定用户的Token）
     * @return 分页Token信息
     */
    @Override
    public Page<TokenInfo> pageFromSaToken(int pageNum, int pageSize, Long userId) {
        SaTokenDao dao = StpUtil.getStpLogic().getSaTokenDao();
        // 获取Sa-Token中所有存储的键
        List<String> allKeys = dao.searchData("", "", 0, -1, true);

        // 构建Token键的前缀，用于筛选登录Token
        String tokenPrefix = StpUtil.getStpLogic().getTokenName() + ":login:token:";
        List<TokenInfo> allTokens = new ArrayList<>();
        Set<Long> userIds = new HashSet<>();

        // 遍历所有键，筛选出登录Token
        for (String key : allKeys) {
            if (!key.startsWith(tokenPrefix)) {
                continue;
            }

            String tokenValue = key.substring(tokenPrefix.length());
            Object loginIdObj = StpUtil.getLoginIdByToken(tokenValue);
            if (loginIdObj == null) {
                continue;
            }

            // 解析用户ID
            Long sid;
            try {
                sid = Long.parseLong(loginIdObj.toString());
            } catch (NumberFormatException e) {
                continue;
            }

            // 如果指定了用户ID筛选，跳过不匹配的用户
            if (userId != null && !userId.equals(sid)) {
                continue;
            }

            userIds.add(sid);

            try {
                // 获取Token对应的会话信息
                SaSession session = StpUtil.getTokenSessionByToken(tokenValue);
                if (session == null) {
                    continue;
                }

                // 计算Token过期时间
                long timeout = StpUtil.getTokenTimeout(tokenValue);
                LocalDateTime expireTime = timeout > 0 
                    ? LocalDateTime.now().plusSeconds(timeout) : null;

                // 提取登录IP和创建时间
                String loginIp = (String) session.get("loginIp");
                LocalDateTime createTime = null;
                // 优先从会话中读取登录时显式存储的loginTime
                Object loginTimeObj = session.get("loginTime");
                if (loginTimeObj instanceof LocalDateTime) {
                    createTime = (LocalDateTime) loginTimeObj;
                } else {
                    // 兼容旧Token：回退到会话创建时间（懒加载场景下可能不准确）
                    Object createTimeObj = session.getCreateTime();
                    if (createTimeObj != null) {
                        // 兼容Long时间戳和LocalDateTime两种类型
                        if (createTimeObj instanceof Long) {
                            createTime = LocalDateTime.ofInstant(
                                Instant.ofEpochMilli((Long) createTimeObj), 
                                ZoneId.systemDefault());
                        } else if (createTimeObj instanceof LocalDateTime) {
                            createTime = (LocalDateTime) createTimeObj;
                        }
                    }
                }

                allTokens.add(TokenInfo.builder()
                        .id(tokenValue)
                        .userId(sid)
                        .token(tokenValue)
                        .expireTime(expireTime)
                        .loginIp(loginIp)
                        .status(1)
                        .createTime(createTime)
                        .build());
            } catch (Exception e) {
                // Token可能在遍历和获取会话之间过期，忽略异常
            }
        }

        // 批量查询用户名，避免N+1查询问题
        if (!userIds.isEmpty()) {
            Map<Long, String> usernameMap = userMapper.selectBatchIds(new ArrayList<>(userIds))
                    .stream()
                    .filter(u -> u != null)
                    .collect(Collectors.toMap(User::getId, User::getUsername));

            allTokens.forEach(t -> t.setUsername(usernameMap.getOrDefault(t.getUserId(), "用户" + t.getUserId())));
        }

        // 按创建时间倒序排序
        allTokens.sort(Comparator.comparing(TokenInfo::getCreateTime, 
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());

        // 手动分页
        int total = allTokens.size();
        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, total);

        List<TokenInfo> pageData = fromIndex < total 
                ? allTokens.subList(fromIndex, toIndex) 
                : new ArrayList<>();

        Page<TokenInfo> page = new Page<>(pageNum, pageSize, total);
        page.setRecords(pageData);
        return page;
    }

    /**
     * 吊销指定Token
     * <p>调用Sa-Token的logoutByToken方法使Token失效</p>
     *
     * @param tokenId Token标识
     */
    @Override
    public void revokeToken(String tokenId) {
        StpUtil.logoutByTokenValue(tokenId);
    }

    /**
     * 踢出指定用户
     * <p>调用Sa-Token的kickout方法使该用户的所有Token失效</p>
     *
     * @param userId 用户ID
     */
    @Override
    public void kickOutUser(Long userId) {
        StpUtil.kickout(userId);
    }

    /**
     * 清理所有过期的Token
     * <p>遍历Sa-Token中所有登录Token，检查超时时间，将已过期或无超时时间的Token登出</p>
     */
    @Override
    public void cleanExpiredTokens() {
        SaTokenDao dao = StpUtil.getStpLogic().getSaTokenDao();
        List<String> allKeys = dao.searchData("", "", 0, -1, true);

        String tokenPrefix = StpUtil.getStpLogic().getTokenName() + ":login:token:";
        for (String key : allKeys) {
            if (!key.startsWith(tokenPrefix)) {
                continue;
            }
            String tokenValue = key.substring(tokenPrefix.length());
            try {
                long timeout = StpUtil.getTokenTimeout(tokenValue);
                // timeout <= 0 表示Token已过期或没有设置超时
                if (timeout <= 0) {
                    StpUtil.logoutByTokenValue(tokenValue);
                }
            } catch (Exception ignored) {
                // Token可能已经被清理，忽略异常
            }
        }
    }
}
