package com.astral.qt.service;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.User;
import com.astral.auth.registry.AppUserRoleService;
import com.astral.auth.service.UserLoginMarker;
import com.astral.dao.mapper.UserMapper;
import com.astral.qt.common.QtException;
import com.astral.qt.dto.QtChangePwByEmailDto;
import com.astral.qt.dto.QtLoginDto;
import com.astral.qt.dto.QtRegisterDto;
import com.astral.qt.dto.QtSendEmailDto;
import com.astral.qt.dto.QtUpdateUserDto;
import com.astral.qt.dto.vo.QtDataVo;
import com.astral.qt.dto.vo.QtUserInfoVo;
import com.astral.system.mail.MailService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 轻听用户服务
 * <p>登录 / 注册 / 邮箱验证码 / 更新资料 / 邮箱重置密码 / 头像上传。</p>
 * <p>用户已并入宿主 {@code sys_user}（user_type='APP'），鉴权统一走 Sa-Token，
 * 不再自建 qt_user / qt_user_token 表。</p>
 */
@Slf4j
@Service
public class QtUserService extends ServiceImpl<UserMapper, User> {

    private static final String EMAIL_BODY_CHANGE_PW = "changePasswordByEmail";
    private static final String USER_TYPE_APP = "APP";

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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

    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration RATE_TTL = Duration.ofSeconds(60);
    private static final String CODE_KEY = "qt:email:code:";
    private static final String RATE_KEY = "qt:email:ratelimit:";

    /**
     * 比对占位令牌后删除频控 key：只有 key 里存的还是「本次占位写入的令牌」才删。
     * 发送若耗时超过频控 TTL（多账户 SMTP 超时累计可达分钟级），key 已过期并被
     * 另一次发送重新占位，无条件 delete 会误删别人的窗口；令牌比对后误删不可能发生。
     */
    private static final DefaultRedisScript<Long> RELEASE_RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    // ==================== 登录/注册（统一走宿主 sys_user + Sa-Token） ====================

    public QtUserInfoVo login(QtLoginDto dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUserType, USER_TYPE_APP)
                        .and(w -> w.eq(User::getUsername, dto.getUsername())
                                .or().eq(User::getEmail, dto.getUsername()))
                        .last("LIMIT 1")
        );
        if (user == null) {
            throw new QtException("当前用户名或邮箱不存在");
        }
        if (!BCrypt.checkpw(dto.getPassword(), user.getPassword())) {
            throw new QtException("密码不正确");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new QtException("当前账号已被封锁，无法登录");
        }

        StpUtil.login(user.getId());
        // 将用户名/昵称写入 Sa-Token 会话，与宿主一致
        StpUtil.getSession().set("username", user.getUsername());
        // nickname 必须兜底成空串：SaSession.dataMap 是 ConcurrentHashMap，
        // `set(key, null)` 会在 ConcurrentHashMap.put 里抛 NPE
        // （实测栈：SaSession.set:501 -> ConcurrentHashMap.putVal:1023 -> 登录接口 500「系统出现错误」）。
        // App 用户注册只填用户名/密码时可没有昵称，不兜底会导致这类账号完全无法登录。
        StpUtil.getSession().set("nickname", user.getNickname() == null ? "" : user.getNickname());
        String token = StpUtil.getTokenValue();

        // 回写最后登录时间与来源 IP（App 用户 login_time 此前恒为 NULL，无法做不活跃筛选）
        userLoginMarker.mark(user.getId());

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

        StpUtil.login(user.getId());
        StpUtil.getSession().set("username", user.getUsername());
        // 同上：注册时 nickname 常为空，直接 set(null) 会 NPE 导致注册接口 500
        StpUtil.getSession().set("nickname", user.getNickname() == null ? "" : user.getNickname());
        String token = StpUtil.getTokenValue();

        // 注册自动登录同样视为一次登录：回写 login_time/login_ip
        userLoginMarker.mark(user.getId());

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

        String code = getValidCode(dto.getEmail(), EMAIL_BODY_CHANGE_PW);
        if (code == null) {
            throw new QtException("当前邮箱验证码不存在或已失效");
        }
        if (!code.equals(dto.getCode())) {
            throw new QtException("当前验证码不正确");
        }

        user.setPassword(BCrypt.hashpw(dto.getPassword()));
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);

        // 验证码一次性失效，注销该用户所有会话
        stringRedisTemplate.delete(CODE_KEY + dto.getEmail() + ":" + EMAIL_BODY_CHANGE_PW);
        StpUtil.kickout(user.getId());

        return user;
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

    // ==================== 文件上传 ====================

    /**
     * 头像上传：默认存本地 ./data/qt-upload/avatar/，返回可访问 CDN/baseUrl 前缀。
     * 若部署环境挂了 Nginx/CDN，通过配置重写 qt.file.base-url 覆盖返回前缀。
     */
    public QtDataVo<String> upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new QtException("上传文件为空");
        }
        String original = file.getOriginalFilename() == null ? "avatar.bin" : file.getOriginalFilename();
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot >= 0) {
            ext = original.substring(dot).toLowerCase();
        }
        String fileName = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + ext;

        try {
            Path dir = Paths.get("data", "qt-upload", "avatar").toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName);
            file.transferTo(target.toFile());
            log.info("[QtPlugin] 头像上传成功: {}", target);
            String url = "/files/qt-upload/avatar/" + fileName;
            QtDataVo<String> vo = new QtDataVo<>(url);
            return vo;
        } catch (IOException e) {
            log.error("[QtPlugin] 头像上传失败", e);
            throw new QtException("上传图片解析出错");
        }
    }
}
