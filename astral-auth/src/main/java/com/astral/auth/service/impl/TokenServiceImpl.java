package com.astral.auth.service.impl;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.auth.dto.TokenInfo;
import com.astral.auth.security.TokenFingerprint;
import com.astral.auth.service.TokenService;
import com.astral.common.exception.BusinessException;
import com.astral.common.util.PageQuery;
import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Token管理服务实现类
 * <p>基于Sa-Token实现Token管理功能，包括从Sa-Token存储中分页获取Token信息、
 * 吊销Token、踢出用户以及清理过期Token</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {

    /** 单次会话列表最多扫描的 token 键数（本页只需一页数据，扫全库没有意义） */
    private static final int MAX_SCANNED_TOKENS = 20_000;

    /** 用户Mapper，用于查询用户名 */
    private final UserMapper userMapper;

    /**
     * 扫描 Redis 中的登录 Token 键（有界）。
     *
     * <p><b>为什么不再用 {@code dao.searchData("", "", 0, -1, true)}</b>：</p>
     * <ol>
     *   <li>那是「取回全库所有键再在内存里筛」，而本项目的 Redis 与线上业务共用同一个实例，
     *       管理端翻一次页就是一次全库扫描 + 每 token 2~3 次往返；</li>
     *   <li>更要紧的是<b>顺序不可依赖</b>：Sa-Token 的 {@code SaFoxUtil.searchList} 对
     *       {@code sortType} 只做 {@code Collections.reverse}（SCAN/KEYS 本身无序），
     *       于是「最新会话」分页会整页错乱 —— 安全审计看到的在线会话是错的。</li>
     * </ol>
     * <p>改为：前缀检索 + 条数上界（{@link #MAX_SCANNED_TOKENS}）；「最新在前」不再依赖存储顺序，
     * 由下方按会话创建时间倒序保证。</p>
     */
    private List<String> scanTokenKeys(SaTokenDao dao, String tokenPrefix) {
        try {
            List<String> keys = dao.searchData(tokenPrefix, "", 0, MAX_SCANNED_TOKENS + 1, true);
            if (keys == null) {
                throw new IllegalStateException("Missing session lookup result");
            }
            return keys;
        } catch (Exception e) {
            log.warn("[token] 会话键检索失败: {}", e.getClass().getSimpleName());
            throw new BusinessException("COMMON002", "会话存储暂时不可用，无法确认操作结果，请稍后重试");
        }
    }

    private void requireCompleteScan(List<String> keys) {
        if (keys.size() > MAX_SCANNED_TOKENS) {
            throw new BusinessException("COMMON002", "会话数量超过本次扫描上限，无法确认全部状态；请按用户执行会话管理");
        }
    }

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

        // 构建Token键的前缀，用于筛选登录Token
        String tokenPrefix = StpUtil.getStpLogic().getTokenName() + ":login:token:";
        List<TokenInfo> allTokens = new ArrayList<>();
        Set<Long> userIds = new HashSet<>();

        // 遍历所有键，筛选出登录Token
        List<String> keys = scanTokenKeys(dao, tokenPrefix);
        requireCompleteScan(keys);
        for (String key : keys) {
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
                LocalDateTime expireTime = timeout > 0 ? LocalDateTime.now().plusSeconds(timeout) : null;

                // 提取登录IP和创建时间
                String loginIp = (String) session.get("loginIp");
                // 优先从会话中读取登录时显式存储的loginTime。
                // 该字段历史上出现过 LocalDateTime / ISO-8601 字符串 / yyyy-MM-dd HH:mm:ss 字符串 /
                // 毫秒时间戳等多种形态（Sa-Token 与 Jackson 版本不同导致），统一走宽容解析。
                LocalDateTime createTime = parseLoginTime(session.get("loginTime"));
                if (createTime == null) {
                    // 兼容旧Token：回退到会话创建时间（懒加载场景下可能不准确）
                    createTime = parseLoginTime(session.getCreateTime());
                }

                allTokens.add(toSessionInfo(
                        tokenValue,
                        sid,
                        expireTime,
                        loginIp,
                        // is-share=false 后同账号每端/每设备一个 token，device 由登录时写入；
                        // 改前发行的旧 token 无 device 记录，返回 null 由前端兜底展示
                        StpUtil.getLoginDeviceByToken(tokenValue),
                        createTime));
            } catch (Exception e) {
                // Token可能在遍历和获取会话之间过期，忽略异常
            }
        }

        // 批量查询用户名，避免N+1查询问题
        if (!userIds.isEmpty()) {
            Map<Long, String> usernameMap = userMapper.selectBatchIds(new ArrayList<>(userIds)).stream()
                    .filter(u -> u != null)
                    .collect(Collectors.toMap(User::getId, User::getUsername));

            allTokens.forEach(t -> t.setUsername(usernameMap.getOrDefault(t.getUserId(), "用户" + t.getUserId())));
        }

        // 按创建时间倒序排序
        allTokens.sort(Comparator.comparing(TokenInfo::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed());

        // 手动分页。pageNum/pageSize 来自 query，必须先钳制再算偏移：
        // pageNum < 1 会得到负 fromIndex 并让 subList 抛 IndexOutOfBoundsException（500），
        // pageSize 过大则让下面的手工切片与上游序列都失去意义。
        int safePageNum = PageQuery.pageNum(pageNum);
        int safePageSize = PageQuery.pageSize(pageSize);
        int total = allTokens.size();
        int fromIndex = (safePageNum - 1) * safePageSize;
        int toIndex = Math.min(fromIndex + safePageSize, total);

        List<TokenInfo> pageData = fromIndex < total ? allTokens.subList(fromIndex, toIndex) : new ArrayList<>();

        Page<TokenInfo> page = new Page<>(safePageNum, safePageSize, total);
        page.setRecords(pageData);
        return page;
    }

    /**
     * 组装会话出参 DTO。
     *
     * <p><b>出参里永远没有明文 token</b>：{@code id} 与 {@code token} 都只放指纹
     * （见 {@code TokenFingerprint}）。这是「只读查看权限不得退化成账号接管」的落点，
     * 单独抽成方法是为了让单测能直接断言「任何入参形态下出参都不含凭据」。</p>
     */
    static TokenInfo toSessionInfo(
            String tokenValue,
            Long userId,
            LocalDateTime expireTime,
            String loginIp,
            String device,
            LocalDateTime createTime) {
        String fingerprint = TokenFingerprint.of(tokenValue);
        return TokenInfo.builder()
                .id(fingerprint)
                .userId(userId)
                .token(fingerprint)
                .expireTime(expireTime)
                .loginIp(loginIp)
                .device(device)
                .status(1)
                .createTime(createTime)
                .build();
    }

    /**
     * 吊销指定会话。
     *
     * <p>入参是 token 指纹而不是明文 token（见 {@code TokenFingerprint}）。指纹反查不到
     * 在线会话时按「已失效」处理并直接返回成功——吊销语义是幂等的，前端在列表过期后
     * 重复点吊销不该看到报错。</p>
     *
     * @param sessionId 会话标识（token 指纹）
     */
    @Override
    public void revokeToken(String sessionId) {
        String tokenValue = resolveTokenBySessionId(sessionId);
        if (tokenValue == null) {
            log.info("[token] 吊销未命中在线会话（可能已自然失效）: sessionId={}", sessionId);
            return;
        }
        logoutSafely(tokenValue);
    }

    /**
     * 把会话标识（token 指纹）反查为明文 token。
     *
     * <p>入参必须是 32 位十六进制指纹：明文 token 形态直接判为不命中，避免
     * 「吊销接口顺便接受凭据」这种把 token 写进 URL/日志的用法重新长回来。</p>
     */
    @Override
    public String resolveTokenBySessionId(String sessionId) {
        if (!TokenFingerprint.looksLikeFingerprint(sessionId)) {
            return null;
        }
        SaTokenDao dao = StpUtil.getStpLogic().getSaTokenDao();
        String tokenPrefix = StpUtil.getStpLogic().getTokenName() + ":login:token:";
        List<String> keys = scanTokenKeys(dao, tokenPrefix);
        for (String key : keys) {
            if (!key.startsWith(tokenPrefix)) {
                continue;
            }
            String tokenValue = key.substring(tokenPrefix.length());
            if (sessionId.equals(TokenFingerprint.of(tokenValue))) {
                return tokenValue;
            }
        }
        requireCompleteScan(keys);
        return null;
    }

    private void logoutSafely(String tokenValue) {
        try {
            StpUtil.logoutByTokenValue(tokenValue);
        } catch (RuntimeException e) {
            log.warn("[token] 会话撤销失败: {}", e.getClass().getSimpleName());
            throw new BusinessException("COMMON002", "会话撤销未完成，请稍后重试");
        }
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
     * <p>有界检索登录Token，保留永久会话（NEVER_EXPIRE），只清理实际过期状态。</p>
     */
    @Override
    public void cleanExpiredTokens() {
        SaTokenDao dao = StpUtil.getStpLogic().getSaTokenDao();
        String tokenPrefix = StpUtil.getStpLogic().getTokenName() + ":login:token:";
        List<String> allKeys = scanTokenKeys(dao, tokenPrefix);
        requireCompleteScan(allKeys);
        for (String key : allKeys) {
            if (!key.startsWith(tokenPrefix)) {
                continue;
            }
            String tokenValue = key.substring(tokenPrefix.length());
            try {
                long timeout = StpUtil.getTokenTimeout(tokenValue);
                if (timeout == SaTokenDao.NOT_VALUE_EXPIRE || timeout == 0) {
                    logoutSafely(tokenValue);
                }
            } catch (RuntimeException e) {
                throw new BusinessException("COMMON002", "会话清理未完成，请稍后重试");
            }
        }
    }

    /**
     * 全端会话重置：吊销所有在线 Token
     * <p>遍历Sa-Token中所有登录Token逐个吊销（语义等同逐 token logout），
     * 用于会话模型变更后的存量清理与安全事件应急。操作者本人的会话也会被吊销。</p>
     */
    @Override
    public void revokeAllTokens() {
        SaTokenDao dao = StpUtil.getStpLogic().getSaTokenDao();
        String tokenPrefix = StpUtil.getStpLogic().getTokenName() + ":login:token:";
        List<String> allKeys = scanTokenKeys(dao, tokenPrefix);
        requireCompleteScan(allKeys);
        for (String key : allKeys) {
            if (!key.startsWith(tokenPrefix)) {
                continue;
            }
            logoutSafely(key.substring(tokenPrefix.length()));
        }
    }

    /**
     * 会话时间字段的历史格式集合，按「新 → 旧」顺序依次尝试
     */
    private static final DateTimeFormatter[] SESSION_TIME_FORMATS = {
        // 2026-09-27T17:42:07（当前写入格式 / Sa-Token 1.46 + Jackson 3 默认）
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        // 2026-09-27 17:42:07（Sa-Token 1.42 + Jackson 2 的 DATE_TIME_PATTERN）
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        // 2026-09-27 17:42:07.123
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
    };

    /**
     * 宽容解析会话中的时间字段
     * <p>同一个字段在历史上出现过多种形态，全部在这里兼容：</p>
     * <ul>
     *   <li>{@link LocalDateTime}——Sa-Token 1.42(Jackson2) 反序列化出来的 {@code yyyy-MM-dd HH:mm:ss}，
     *       或 1.46(Jackson3) 反序列化出来的 ISO-8601；</li>
     *   <li>字符串——当前登录时写入的 ISO-8601，以及历史遗留的 {@code yyyy-MM-dd HH:mm:ss}；</li>
     *   <li>{@link Number}——毫秒时间戳，如 Sa-Token 自己维护的 {@code createTime}。</li>
     * </ul>
     *
     * @param value 会话中取出的原始值，可为 null
     * @return 解析结果，无法识别时返回 null，由调用方决定回退策略
     */
    private static LocalDateTime parseLoginTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Number number) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(number.longValue()), ZoneId.systemDefault());
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter formatter : SESSION_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // 换下一种格式继续试
            }
        }
        // 兜底：字符串形式的毫秒时间戳
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(text)), ZoneId.systemDefault());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
