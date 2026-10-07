package com.astral.auth.service.impl;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.astral.auth.dto.LoginRequest;
import com.astral.auth.dto.LoginResponse;
import com.astral.auth.security.AuthCookieWriter;
import com.astral.auth.security.LoginDevice;
import com.astral.auth.security.LoginUserTypeResolver;
import com.astral.auth.security.RsaKeyManager;
import com.astral.auth.security.TotpUtil;
import com.astral.auth.service.AuthService;
import com.astral.auth.service.UserLoginMarker;
import com.astral.common.error.ErrorCodes;
import com.astral.common.exception.BusinessException;
import com.astral.common.util.ClientIp;
import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class AuthServiceImpl implements AuthService {
    @Autowired
    private UserMapper userMapper;

    /** 可信代理列表：决定是否采信 X-Forwarded-For（默认回环 + 私有网段） */
    @Value("${astral.web.trusted-proxies:" + ClientIp.DEFAULT_TRUSTED_PROXIES + "}")
    private String trustedProxies;
    
    @Autowired
    private RsaKeyManager rsaKeyManager;
    
    @Autowired
    private StpInterface stpInterface;

    /** 登录用户类型解析器：把 user_type 缓存进会话，供管理端身份门禁免查库读取 */
    @Autowired
    private LoginUserTypeResolver loginUserTypeResolver;

    /** 登录/活跃标记：把最后登录时间与 IP 落回 sys_user（App 端此前从未回写，login_time 全 NULL） */
    @Autowired
    private UserLoginMarker userLoginMarker;

    /** 管理端认证 Cookie 读写器：登录下发 HttpOnly Cookie + CSRF 令牌，登出清理 */
    @Autowired
    private AuthCookieWriter authCookieWriter;

    @Override
    public LoginResponse login(LoginRequest request) {
        if (rsaKeyManager.isAccountLocked(request.getUsername())) {
            throw new BusinessException("AUTH003");
        }
        
        User user = userMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>()
                .eq("username", request.getUsername())
                .eq("deleted", 0)
        );
        
        if (user == null) {
            rsaKeyManager.recordLoginFailure(request.getUsername());
            throw new BusinessException("AUTH002");
        }
        
        String password = request.getPassword();
        try {
            password = rsaKeyManager.decryptPasswordBase64(password);
        } catch (Exception e) {
            throw new BusinessException("AUTH004");
        }
        
        if (!BCrypt.checkpw(password, user.getPassword())) {
            rsaKeyManager.recordLoginFailure(request.getUsername());
            throw new BusinessException("AUTH002");
        }

        // 禁用账号不得登录（此前 status 只在管理页切换，登录链路从未校验，
        // 封禁后重新登录即可绕过 —— AUTH006 一直无人触发就是这个问题）
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException("AUTH006");
        }

        // TOTP 二次验证：启用的账号必须携带动态码；未携带返回 AUTH010 让前端补录，
        // 错误返回 AUTH011。TOTP 在密码校验之后，避免动态码先于密码泄露尝试
        if (user.getTotpEnabled() != null && user.getTotpEnabled() == 1) {
            String totpCode = request.getTotpCode();
            if (totpCode == null || totpCode.isBlank()) {
                throw new BusinessException("AUTH010");
            }
            if (!TotpUtil.verify(totpCode, user.getTotpSecret())) {
                throw new BusinessException("AUTH011");
            }
        }

rsaKeyManager.resetLoginFailures(request.getUsername());

        // device=ADMIN：is-share=false 下每次登录都是新 token，按「账号 × 端」隔离会话——
        // 管理端登出/被踢不影响同账号的 App 会话（反之亦然）
        StpUtil.login(user.getId(), LoginDevice.ADMIN);
        // 将用户名存入 Sa-Token 会话，供 AuthInterceptor 直接读取，避免每次请求查库
        StpUtil.getSession().set("username", user.getUsername());
        // nickname 必须兜底成空串：SaSession.dataMap 是 ConcurrentHashMap，
        // `set(key, null)` 会在 ConcurrentHashMap.put 里直接抛 NPE
        // （实测栈：SaSession.set:501 -> ConcurrentHashMap.putVal:1023）。
        // 用户的 nickname 允许为空（如 App 注册只填用户名），不兜底就会整个登录 500。
        StpUtil.getSession().set("nickname", user.getNickname() == null ? "" : user.getNickname());
        // 用户类型（ADMIN / APP）同样缓存进会话：管理端身份门禁依赖它区分
        // 「后台管理员」与「App 普通用户」，不能等到每次请求再查库
        loginUserTypeResolver.cacheCurrentUserType(user.getUserType());
        // 将登录IP和登录时间存入Token会话，供Token管理页面读取
        SaSession tokenSession = StpUtil.getTokenSession();
        tokenSession.set("loginIp", getClientIp());
        // loginTime 存 ISO-8601 字符串而不是裸 LocalDateTime：会话是持久化在 Redis 里的，
        // Sa-Token 1.42(Jackson2) 把 java.time 固定写成 yyyy-MM-dd HH:mm:ss，1.46(Jackson3)
        // 按默认 ISO-8601 读写，裸 LocalDateTime 一旦跨版本读写就会反序列化失败。
        // 字符串不参与 java.time 的格式协商，新旧版本都能安全读回。
        tokenSession.set("loginTime", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        // 回写最后登录时间与 IP 到 sys_user（会话里的副本只够 Token 管理页展示，库里的字段此前无人写）
        userLoginMarker.mark(user.getId(), getClientIp());
        String token = StpUtil.getTokenValue();
        
        List<String> roles = stpInterface.getRoleList(user.getId(), null);
        List<String> permissions = stpInterface.getPermissionList(user.getId(), null);
        
        LoginResponse response = new LoginResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setToken(token);
        response.setRoles(roles != null ? roles : Collections.emptyList());
        response.setPermissions(permissions != null ? permissions : Collections.emptyList());
        response.setUserType(user.getUserType());
        response.setMustChangePassword(user.getMustChangePassword());

        // 管理端令牌改由 HttpOnly Cookie 承载（此前存 localStorage，任一前端 XSS 即可窃取）。
        // 响应体里的 token 字段保留 —— 原生/第三方调用方（如脚本、Swagger 调试）仍可按头使用。
        jakarta.servlet.http.HttpServletResponse httpResponse = AuthCookieWriter.currentResponse();
        if (httpResponse != null) {
            response.setCsrfToken(authCookieWriter.writeLoginCookies(
                    httpResponse, token, StpUtil.getTokenTimeout()));
        }
        return response;
    }

    @Override
    public void logout() {
        StpUtil.logout();
        // 清掉 HttpOnly 认证 Cookie 与 CSRF Cookie：浏览器端必须真的「登出」，
        // 否则客户端仍会被自动带上 Cookie，表现为「登出了但刷新又进去了」
        jakarta.servlet.http.HttpServletResponse httpResponse = AuthCookieWriter.currentResponse();
        if (httpResponse != null) {
            authCookieWriter.clearCookies(httpResponse);
        }
    }

    /**
     * 获取客户端真实IP地址
     *
     * <p>走 {@link ClientIp} 的可信代理白名单逻辑：<b>只有直连方是可信代理时才采信
     * {@code X-Forwarded-For}</b>，且从右往左取第一个非可信地址。
     * 原实现无条件取 XFF 的第一个值，客户端可随意伪造，
     * 会让登录日志里的来源 IP 失去取证价值。</p>
     *
     * @return 客户端IP地址，获取失败返回null
     */
    private String getClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest req = attrs.getRequest();
                return ClientIp.resolve(
                        req.getHeader("X-Forwarded-For"),
                        req.getHeader("X-Real-IP"),
                        req.getRemoteAddr(),
                        trustedProxies);
            }
        } catch (Exception e) {
            log.debug("获取客户端IP失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public LoginResponse getLoginInfo() {
        if (!StpUtil.isLogin()) {
            throw new BusinessException("AUTH001");
        }

        long userId = StpUtil.getLoginIdAsLong();
        User user = userMapper.selectById(userId);

        if (user == null) {
            throw new BusinessException("SYS001");
        }

        // 刷新会话中的用户信息
        StpUtil.getSession().set("username", user.getUsername());
        // 同上：nickname 为 null 会让 SaSession.set 抛 NPE，必须兜底
        StpUtil.getSession().set("nickname", user.getNickname() == null ? "" : user.getNickname());
        loginUserTypeResolver.cacheCurrentUserType(user.getUserType());
        
        List<String> roles = stpInterface.getRoleList(userId, null);
        List<String> permissions = stpInterface.getPermissionList(userId, null);
        
        LoginResponse response = new LoginResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setToken(StpUtil.getTokenValue());
        response.setRoles(roles != null ? roles : Collections.emptyList());
        response.setPermissions(permissions != null ? permissions : Collections.emptyList());
        response.setUserType(user.getUserType());

        // 把 CSRF 令牌随响应体再交一次。
        //
        // 令牌本来只写在 astral_csrf Cookie 里，而「前端能否读到该 Cookie」取决于部署形态：
        // 前后端同源（Next.js rewrites 代理）时读得到；跨域直连（web.canace.cn → astral.canace.cn）
        // 时 Cookie 属于 API 域，页面所在域的 document.cookie 看不到它 —— 前端拿不到值就回填不了
        // X-CSRF-Token，所有写请求被双提交校验拦成 403（AUTH013），只读请求却正常，表现为
        // 「能看不能改」。前端在挂载时会调本接口恢复登录态，因此这里补上就实现了「刷新页面即自愈」。
        //
        // 判据是「凭据是否来自 Cookie」而不是「是不是管理员」：AuthInterceptor 只对
        // 非安全方法 + 未带 satoken 请求头的请求做双提交校验，与 user_type 无关。网页端
        // 里普通用户（userType=APP）同样能用 Cookie 登录（/imgbed 的删除、改可见性都是写操作，
        // 且不经过 /api/v1/admin/** 的管理员门禁），所以这里按管理员过滤会让这批用户刷新后
        // 必然撞 403。轻听 App 走 satoken 请求头，后端对其豁免校验，多拿一个值也无副作用。
        HttpServletResponse httpResponse = AuthCookieWriter.currentResponse();
        HttpServletRequest httpRequest = AuthCookieWriter.currentRequest();
        if (httpResponse != null && httpRequest != null) {
            response.setCsrfToken(authCookieWriter.ensureCsrfToken(
                    httpRequest, httpResponse, StpUtil.getTokenTimeout()));
        }
        return response;
    }
}