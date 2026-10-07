package com.astral.qt.service;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.User;
import com.astral.auth.registry.AppUserRoleService;
import com.astral.auth.security.LoginDevice;
import com.astral.auth.security.RsaKeyManager;
import com.astral.auth.service.UserLoginMarker;
import com.astral.dao.mapper.UserMapper;
import com.astral.qt.common.QtException;
import com.astral.qt.dto.QtChangePwByEmailDto;
import com.astral.qt.dto.QtLoginDto;
import com.astral.qt.dto.QtRegisterDto;
import com.astral.qt.dto.QtSendEmailDto;
import com.astral.qt.dto.QtUpdateUserDto;
import com.astral.qt.dto.vo.QtUserInfoVo;
import com.astral.system.notify.NotifyEventRegistry;
import com.astral.system.mail.MailService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 轻听用户服务
 * <p>登录 / 注册 / 邮箱验证码 / 更新资料 / 邮箱重置密码。</p>
 * <p>用户已并入宿主 {@code sys_user}（user_type='APP'），鉴权统一走 Sa-Token，
 * 不再自建 qt_user / qt_user_token 表。</p>
 */
@Slf4j
@Service
public class QtUserService extends ServiceImpl<UserMapper, User> {

    private static final String EMAIL_BODY_CHANGE_PW = NotifyEventRegistry.QT_PASSWORD_RESET_CODE;
    private static final String USER_TYPE_APP = "APP";

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 登录失败计数 / 账号锁定（Redis 为主、内存兜底）：App 登录此前无任何防爆破手段，
     * 拉齐到与管理端登录同一套 LoginFailureStore（5 次失败锁 15 分钟）。
     */
    @Resource
    private RsaKeyManager rsaKeyManager;

    @Resource
    private MailService mailService;

    @Resource
    private StpInterface stpInterface;

    /**
     * App 端默认角色（APP_USER）归属维护：新注册 App 用户自动分配该角色，
     * 使其开箱即拥有全部 {@code user:} 前缀权限（App 客户端接口）。
     */
    @Resource
    private AppUserRoleService appUserRoleService;

    /** 登录/活跃标记：登录与注册自动登录时回写 sys_user.login_time/login_ip */
    @Resource
    private UserLoginMarker userLoginMarker;

    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration RATE_TTL = Duration.ofSeconds(60);
    private static final String CODE_KEY = "qt:email:code:";
    private static final String RATE_KEY = "qt:email:ratelimit:";
    /** 验证码失败次数：达到上限即作废当前验证码，必须重新发送才能再试 */
    private static final String CODE_FAIL_KEY = "qt:email:codefail:";
    private static final int MAX_CODE_FAILURES = 5;

    /**
     * 比对占位令牌后删除频控 key：只有 key 里存的还是「本次占位写入的令牌」才删。
     * 发送若耗时超过频控 TTL（多账户 SMTP 超时累计可达分钟级），key 已过期并被
     * 另一次发送重新占位，无条件 delete 会误删别人的窗口；令牌比对后误删不可能发生。
     */
    private static final DefaultRedisScript<Long> RELEASE_RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    // ==================== 登录/注册（统一走宿主 sys_user + Sa-Token） ====================

    /**
     * 用户自助注销
     *
     * <p>注销 = 停用（status=0，理由「用户自助注销」）+ 匿名化（昵称改「已注销用户」、
     * 邮箱/手机/头像/设备标识清空）+ 全端下线。不可自助恢复，需管理员解封。</p>
     * <p>NOT_NULL 更新策略下 updateById 跳过 null 字段，清空列必须 lambdaUpdate 显式 set。</p>
     *
     * @param userId   当前登录用户
     * @param password 登录密码（注销属高危操作，必须凭密码确认）
     */
    public void deactivate(Long userId, String password) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new QtException("用户不存在");
        }
        if (password == null || password.isBlank() || !BCrypt.checkpw(password, user.getPassword())) {
            throw new QtException("密码不正确");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new QtException("账号已注销");
        }
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getStatus, 0)
                .set(User::getStatusReason, "用户自助注销")
                .set(User::getNickname, "已注销用户")
                .set(User::getEmail, null)
                .set(User::getPhone, null)
                .set(User::getAvatar, null)
                .set(User::getDeviceId, null)
                .set(User::getUpdateTime, java.time.LocalDateTime.now()));
        StpUtil.kickout(userId);
    }

    public QtUserInfoVo login(QtLoginDto dto) {
        if (rsaKeyManager.isAccountLocked(dto.getUsername())) {
            throw new QtException("登录失败次数过多，账号已被锁定，请15分钟后再试");
        }
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUserType, USER_TYPE_APP)
                        .and(w -> w.eq(User::getUsername, dto.getUsername())
                                .or().eq(User::getEmail, dto.getUsername()))
                        .last("LIMIT 1")
        );
        if (user == null) {
            rsaKeyManager.recordLoginFailure(dto.getUsername());
            throw new QtException("当前用户名或邮箱不存在");
        }
        if (!BCrypt.checkpw(dto.getPassword(), user.getPassword())) {
            rsaKeyManager.recordLoginFailure(dto.getUsername());
            throw new QtException("密码不正确");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new QtException("当前账号已被封锁，无法登录");
        }

        rsaKeyManager.resetLoginFailures(dto.getUsername());
        // device=APP：is-share=false 下每次登录都是新 token，按「账号 × 端」隔离会话——
        // App 登出/被踢不影响同账号的管理端会话（反之亦然），同端多台设备也互不牵连
        StpUtil.login(user.getId(), LoginDevice.APP);
        // 将用户名/昵称写入 Sa-Token 会话，与宿主一致
        StpUtil.getSession().set("username", user.getUsername());
        // nickname 必须兜底成空串：SaSession.dataMap 是 ConcurrentHashMap，
        // `set(key, null)` 会在 ConcurrentHashMap.put 里抛 NPE
        // （实测栈：SaSession.set:501 -> ConcurrentHashMap.putVal:1023 -> 登录接口 500「系统出现错误」）。
        // App 用户注册只填用户名/密码时可没有昵称，不兜底会导致这类账号完全无法登录。
        StpUtil.getSession().set("nickname", user.getNickname() == null ? "" : user.getNickname());
        String token = StpUtil.getTokenValue();

        // Token 管理页展示的登录IP/创建时间读自 token 会话：App 登录此前漏盖，页面上恒显示「—」
        String loginIp = userLoginMarker.currentClientIp();
        userLoginMarker.stampTokenSession(loginIp);
        // 回写最后登录时间与来源 IP（App 用户 login_time 此前恒为 NULL，无法做不活跃筛选）
        userLoginMarker.mark(user.getId(), loginIp);

        QtUserInfoVo vo = new QtUserInfoVo();
        vo.setToken(token);
        // 获取 token 剩余有效时长（秒），Sa-Token 默认返回秒数
        long expiresIn = StpUtil.getTokenTimeout();
        vo.setExpiresIn(expiresIn);
        vo.setUser(user);
        populateRolesAndPermissions(vo, user.getId());
        return vo;
    }

    public QtUserInfoVo register(QtRegisterDto dto) {
        if (!dto.getPassword().equals(dto.getPasswordConfirm())) {
            throw new QtException("两次密码不一致");
        }

        Long conflict = userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUserType, USER_TYPE_APP)
                        .and(w -> w.eq(User::getUsername, dto.getUsername())
                                .or().eq(User::getEmail, dto.getEmail())
                                .or().eq(User::getUsername, dto.getEmail())
                                .or().eq(User::getEmail, dto.getUsername()))
        );
        if (conflict != null && conflict > 0) {
            throw new QtException("当前用户名或邮箱已被注册");
        }

        User user = new User();
        BeanUtils.copyProperties(dto, user, "password", "passwordConfirm");
        user.setPassword(BCrypt.hashpw(dto.getPassword()));
        user.setUserType(USER_TYPE_APP);
        user.setStatus(1);
        user.setDeleted(0);
        // 昵称默认使用用户名
        if (user.getNickname() == null || user.getNickname().isBlank()) {
            user.setNickname(dto.getUsername());
        }
        if (user.getAvatar() == null || user.getAvatar().isBlank()) {
            user.setAvatar("");
        }
        // id / createTime / updateTime 由全局序列 + MetaObjectHandler 自动填充
        this.save(user);

        // 默认角色必须在 populateRolesAndPermissions 之前分配：
        // 否则注册响应里的 permissions 是空的，客户端首次启动就判定「无权限」。
        appUserRoleService.assignToUser(user.getId());

        StpUtil.login(user.getId(), LoginDevice.APP);
        StpUtil.getSession().set("username", user.getUsername());
        // 同上：注册时 nickname 常为空，直接 set(null) 会 NPE 导致注册接口 500
        StpUtil.getSession().set("nickname", user.getNickname() == null ? "" : user.getNickname());
        String token = StpUtil.getTokenValue();

        // 注册自动登录同样视为一次登录：盖 token 会话（登录IP/时间）+ 回写 login_time/login_ip
        String loginIp = userLoginMarker.currentClientIp();
        userLoginMarker.stampTokenSession(loginIp);
        userLoginMarker.mark(user.getId(), loginIp);

        QtUserInfoVo vo = new QtUserInfoVo();
        vo.setToken(token);
        // 获取 token 剩余有效时长（秒）
        long expiresIn = StpUtil.getTokenTimeout();
        vo.setExpiresIn(expiresIn);
        vo.setUser(user);
        populateRolesAndPermissions(vo, user.getId());
        return vo;
    }

    private void populateRolesAndPermissions(QtUserInfoVo vo, Long userId) {
        try {
            List<String> roles = stpInterface.getRoleList(userId, null);
            List<String> permissions = stpInterface.getPermissionList(userId, null);
            vo.setRoles(roles != null ? roles : Collections.emptyList());
            vo.setPermissions(permissions != null ? permissions : Collections.emptyList());
        } catch (Exception e) {
            log.warn("[QtPlugin] Failed to fetch roles/permissions for user {}: {}", userId, e.getMessage());
            vo.setRoles(Collections.emptyList());
            vo.setPermissions(Collections.emptyList());
        }
    }

    public QtUserInfoVo getUserInfoByToken(Long userId, String token) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new QtException(401, "用户不存在或登录已失效");
        }
        QtUserInfoVo vo = new QtUserInfoVo();
        vo.setUser(user);
        // 优先使用传入的 satoken（/me、/refresh 直接来自请求头）；
        // 未显式传入时回退到 Sa-Token 上下文中的当前 token。
        if (token == null || token.isBlank()) {
            token = StpUtil.getTokenValue();
        }
        vo.setToken(token);
        if (token != null && !token.isBlank()) {
            vo.setExpiresIn(StpUtil.getTokenTimeout(token));
        }
        populateRolesAndPermissions(vo, userId);
        return vo;
    }

    public void logout(Long userId) {
        // 注销当前 token 对应的会话（请求已携带 satoken，StpUtil 读取当前登录态）
        StpUtil.logout();
    }

    // ==================== 邮箱验证码 ====================

    public void sendEmail(QtSendEmailDto dto) {
        // 能发什么事件由系统侧发信授权 fail-closed 控制（allowed_scenes 留空=全拒，见 MailServiceImpl.send），
        // 插件侧不再自带白名单。注意 body 同时用作验证码 Redis 存储键，changePwByEmail 只认
        // EMAIL_BODY_CHANGE_PW 这个键——授权里多勾的事件只会白耗配额，不会产生可用验证码。
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUserType, USER_TYPE_APP)
                        .eq(User::getEmail, dto.getEmail())
        );
        if (user == null) {
            throw new QtException("当前邮箱不在系统中");
        }

        // 发送频率限制：同一邮箱同一业务 60 秒内只能发一次。
        // 必须用 setIfAbsent 原子占位：原实现是 hasKey 判断后再 set，两次操作之间存在窗口，
        // 并发/重放时两个请求都能通过判重，导致同一邮箱短时间内连发多封（可被刷爆邮件额度）。
        // 占位值用一次性令牌（而不是常量 "1"），失败释放时按令牌比对，见 RELEASE_RATE_LIMIT_SCRIPT。
        String rateK = RATE_KEY + dto.getEmail() + ":" + dto.getBody();
        String rateToken = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(rateK, rateToken, RATE_TTL))) {
            throw new QtException("发送过于频繁，请1分钟后再试");
        }

        String code = RandomUtil.randomNumbers(6);

        // 委托系统邮件服务发送（插件授权/每日限额/随机账户/模板渲染/记录落库 均在系统侧完成）
        try {
            mailService.send("qt", dto.getEmail(), dto.getBody(), Map.of("code", code));
            // 发送成功后才落库验证码（TTL 控制有效期）；频控窗口已在上面原子占位
            stringRedisTemplate.opsForValue().set(CODE_KEY + dto.getEmail() + ":" + dto.getBody(), code, CODE_TTL);
            log.info("[QtPlugin] 邮箱验证码已发送: email={}, body={}", dto.getEmail(), dto.getBody());
        } catch (BusinessException e) {
            // 未发出去就释放频控窗口，避免把用户无谓地锁 60 秒（保持「只有成功发送才占用窗口」的原语义）
            releaseRateLimit(rateK, rateToken);
            throw new QtException(e.getMessage());
        } catch (Exception e) {
            releaseRateLimit(rateK, rateToken);
            log.error("[QtPlugin] 邮箱验证码发送失败: email={}", dto.getEmail(), e);
            throw new QtException("邮件发送失败，请稍后重试");
        }
    }

    /** 释放邮箱发送频控窗口（发送失败时调用）；失败只记日志，不影响主流程异常抛出 */
    private void releaseRateLimit(String rateK, String rateToken) {
        try {
            stringRedisTemplate.execute(RELEASE_RATE_LIMIT_SCRIPT, List.of(rateK), rateToken);
        } catch (Exception e) {
            log.warn("[QtPlugin] 频控窗口释放失败: {}", e.getMessage());
        }
    }

    /**
     * 从 Redis 取验证码（已过期/不存在返回 null）。内部供 changePwByEmail 使用。
     */
    public String getValidCode(String email, String body) {
        return stringRedisTemplate.opsForValue().get(CODE_KEY + email + ":" + body);
    }

    public User changePwByEmail(QtChangePwByEmailDto dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUserType, USER_TYPE_APP)
                        .eq(User::getEmail, dto.getEmail())
        );
        if (user == null) {
            throw new QtException("当前邮箱还未注册");
        }

        String codeKey = CODE_KEY + dto.getEmail() + ":" + EMAIL_BODY_CHANGE_PW;
        String code = getValidCode(dto.getEmail(), EMAIL_BODY_CHANGE_PW);
        if (code == null) {
            throw new QtException("当前邮箱验证码不存在或已失效");
        }
        // 验证码只有 6 位数字（10^6），不计数就能在有效期内并发枚举并重置任意账号密码。
        // 失败次数与 IP 限流是两层防护：前者按邮箱维度封顶总尝试次数，后者挡住跨邮箱扫描。
        if (!code.equals(dto.getCode())) {
            if (recordCodeFailure(codeKey)) {
                throw new QtException("验证码错误次数过多，请重新获取验证码");
            }
            throw new QtException("当前验证码不正确");
        }

        user.setPassword(BCrypt.hashpw(dto.getPassword()));
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);

        // 验证码一次性失效（连同失败计数），注销该用户所有会话
        stringRedisTemplate.delete(List.of(codeKey, codeFailKey(codeKey)));
        StpUtil.kickout(user.getId());

        return user;
    }

    /**
     * 记一次验证码比对失败，返回是否已达上限。
     * <p>计数 key 与验证码同生命周期（首次失败时对齐验证码剩余 TTL），
     * 达到 {@value MAX_CODE_FAILURES} 次即删除验证码，强制重新发送才能继续尝试。</p>
     */
    private boolean recordCodeFailure(String codeKey) {
        String failKey = codeFailKey(codeKey);
        Long count = stringRedisTemplate.opsForValue().increment(failKey);
        if (count != null && count == 1L) {
            Long codeTtl = stringRedisTemplate.getExpire(codeKey, TimeUnit.SECONDS);
            stringRedisTemplate.expire(failKey,
                    codeTtl != null && codeTtl > 0 ? Duration.ofSeconds(codeTtl) : CODE_TTL);
        }
        if (count != null && count >= MAX_CODE_FAILURES) {
            stringRedisTemplate.delete(List.of(codeKey, failKey));
            log.warn("[QtPlugin] 邮箱验证码尝试次数超限，验证码已作废: key={}", codeKey);
            return true;
        }
        return false;
    }

    private static String codeFailKey(String codeKey) {
        return CODE_FAIL_KEY + codeKey.substring(CODE_KEY.length());
    }

    public User updateUser(Long userId, QtUpdateUserDto dto) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new QtException("用户不存在");
        }

        // 若邮箱变更，校验新邮箱唯一（仅限 APP 用户）
        if (!dto.getEmail().equals(user.getEmail())) {
            Long count = userMapper.selectCount(
                    new LambdaQueryWrapper<User>()
                            .eq(User::getUserType, USER_TYPE_APP)
                            .and(w -> w.eq(User::getEmail, dto.getEmail())
                                    .or().eq(User::getUsername, dto.getEmail()))
                            .ne(User::getId, userId)
            );
            if (count != null && count > 0) {
                throw new QtException("当前邮箱已被使用");
            }
            user.setEmail(dto.getEmail());
        }

        user.setNickname(dto.getNickname());
        user.setAvatar(dto.getAvatar());
        if (dto.getPassword() != null && !dto.getPassword().isEmpty()) {
            user.setPassword(BCrypt.hashpw(dto.getPassword()));
        }
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);

        // 更新资料后强制下线，前端重新登录（与参考实现一致）
        StpUtil.kickout(userId);
        return userMapper.selectById(userId);
    }

}
