package com.astral.qt.service;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.User;
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
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration RATE_TTL = Duration.ofSeconds(60);
    private static final String CODE_KEY = "qt:email:code:";
    private static final String RATE_KEY = "qt:email:ratelimit:";

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
        StpUtil.getSession().set("nickname", user.getNickname());
        String token = StpUtil.getTokenValue();

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

        StpUtil.login(user.getId());
        StpUtil.getSession().set("username", user.getUsername());
        StpUtil.getSession().set("nickname", user.getNickname());
        String token = StpUtil.getTokenValue();

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

        // 发送频率限制：同一邮箱同一业务 60 秒内只能发一次（Redis 计数器）
        String rateK = RATE_KEY + dto.getEmail() + ":" + dto.getBody();
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(rateK))) {
            throw new QtException("发送过于频繁，请1分钟后再试");
        }

        String code = RandomUtil.randomNumbers(6);

        // 委托系统邮件服务发送（插件授权/每日限额/随机账户/模板渲染/记录落库 均在系统侧完成）
        try {
            mailService.send("qt", dto.getEmail(), dto.getBody(), Map.of("code", code));
            // 发送成功后才落库验证码与频控标记（TTL 各自控制有效期/频控窗口）
            stringRedisTemplate.opsForValue().set(CODE_KEY + dto.getEmail() + ":" + dto.getBody(), code, CODE_TTL);
            stringRedisTemplate.opsForValue().set(rateK, "1", RATE_TTL);
            log.info("[QtPlugin] 邮箱验证码已发送: email={}, body={}", dto.getEmail(), dto.getBody());
        } catch (BusinessException e) {
            throw new QtException(e.getMessage());
        } catch (Exception e) {
            log.error("[QtPlugin] 邮箱验证码发送失败: email={}", dto.getEmail(), e);
            throw new QtException("邮件发送失败，请稍后重试");
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
