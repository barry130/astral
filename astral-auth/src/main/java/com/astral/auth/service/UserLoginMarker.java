package com.astral.auth.service;

import com.astral.common.util.ClientIp;
import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 用户登录/活跃标记：把最后活跃时间与来源 IP 落回 {@code sys_user.login_time / login_ip}。
 *
 * <p><b>背景</b>：App 端登录/注册走 Sa-Token（qt 插件流程），此前从未回写宿主字段，
 * 导致 7.2 万 App 用户的 {@code login_time} 恒为 NULL，无法按「最后登录」筛选不活跃用户，
 * 只能靠打卡/喜欢等业务行为间接拼合（2026-10-01 生产库巡检结论）。</p>
 *
 * <p><b>触发点</b>（谁接入见各调用方注释）：</p>
 * <ul>
 *   <li>管理端登录：{@code AuthServiceImpl.login}</li>
 *   <li>App 登录 / 注册自动登录：{@code QtUserService.login / register}</li>
 *   <li>App 显式刷新 token：{@code /api/v1/app/user/refresh}
 *       （客户端通常在启动时调用，是「用户回来了」的直接信号）</li>
 *   <li>Token 滑动续期：{@code AuthInterceptor.renewIfNeeded}（剩余有效期不足 1 天时触发，
 *       约 2 天最多一次，覆盖「未重新登录但持续使用」的用户）</li>
 * </ul>
 *
 * <p><b>实现约束</b>：</p>
 * <ul>
 *   <li>必须用 {@link LambdaUpdateWrapper} 白名单局部更新——MP 3.5.17 的 {@code updateById}
 *       会把显式 null 写进 SET 子句（项目实测，见 AGENTS §5），禁止实体整体更新；</li>
 *   <li>IP 解析走 {@link ClientIp} 可信代理白名单（X-Forwarded-For 可伪造，不能无条件采信）；
 *       IP 解析失败时只更新 {@code login_time}，不用 null 覆盖历史 {@code login_ip}；</li>
 *   <li>属旁路遥测：写库异常只记 warn 日志，绝不影响登录/续期主流程。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserLoginMarker {

    private final UserMapper userMapper;

    /** 可信代理列表：决定是否采信 X-Forwarded-For，与全站口径一致 */
    @Value("${astral.web.trusted-proxies:" + ClientIp.DEFAULT_TRUSTED_PROXIES + "}")
    private String trustedProxies;

    /** 标记当前请求线程上的用户活跃（自动从请求上下文解析 IP，无请求时只更新时间） */
    public void mark(Long userId) {
        mark(userId, currentRequest());
    }

    /** 标记用户活跃，IP 从给定请求解析（request 为 null 时只更新时间） */
    public void mark(Long userId, HttpServletRequest request) {
        mark(userId, resolveIp(request));
    }

    /**
     * 解析当前请求的客户端真实 IP（可信代理白名单口径；无请求上下文时返回 null）。
     * <p>供 token 会话 {@link #stampTokenSession} 等调用方与 sys_user 回写共用同一次解析。</p>
     */
    public String currentClientIp() {
        return resolveIp(currentRequest());
    }

    /**
     * 把本次登录的 IP/时间盖到当前 token 会话上：Token 管理页的「登录IP / 创建时间」
     * 两列读取的是 <b>token 会话</b>（{@code StpUtil.getTokenSessionByToken}）而非用户会话，
     * 登录方必须显式盖章，否则该 token 在页面上恒显示「—」（App 登录此前即漏在此）。
     * <p>与宿主 {@code AuthServiceImpl.login} 的口径一致：IP 无法解析（null/空白）时跳过
     * loginIp（会话 set(null) 会在 ConcurrentHashMap 上抛 NPE），loginTime 恒写。</p>
     */
    public void stampTokenSession(String ip) {
        try {
            SaSession tokenSession = StpUtil.getTokenSession();
            if (hasRealIp(ip)) {
                tokenSession.set("loginIp", ip);
            }
            // loginTime 存 ISO-8601 字符串而不是裸 LocalDateTime：会话持久化在 Redis 里，
            // java.time 的序列化形态随 Jackson 版本漂移，字符串不参与格式协商（同 AuthServiceImpl）
            tokenSession.set("loginTime", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } catch (Exception e) {
            // 旁路遥测：失败不影响登录主流程
            log.warn("写入 token 会话 loginIp/loginTime 失败: {}", e.getMessage());
        }
    }

    /** 核心：白名单局部更新 login_time（必写）与 login_ip（解析成功才写） */
    public void mark(Long userId, String ip) {
        if (userId == null) {
            return;
        }
        try {
            LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<User>()
                    .eq(User::getId, userId)
                    .set(User::getLoginTime, LocalDateTime.now());
            // IP 兜底：null / 空白 / ClientIp 的 "unknown" 哨兵值一律不写，
            // 避免（a）用 null 覆盖历史 IP（b）把哨兵值当真实 IP 落库成脏数据
            if (hasRealIp(ip)) {
                wrapper.set(User::getLoginIp, ip);
            }
            userMapper.update(null, wrapper);
        } catch (Exception e) {
            // 旁路遥测：失败不影响登录/续期主流程
            log.warn("回写 sys_user.login_time/login_ip 失败 userId={}: {}", userId, e.getMessage());
        }
    }

    /** 判定是否为可落库的真实 IP：null / 空白 / "unknown" 哨兵值都视为无 IP（ClientIp 完全无法判定时返回 "unknown"） */
    private static boolean hasRealIp(String ip) {
        return ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip);
    }

    /** 按全站口径解析客户端真实 IP（可信代理白名单，防 X-Forwarded-For 伪造） */
    private String resolveIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        return ClientIp.resolve(
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getRemoteAddr(),
                trustedProxies);
    }

    /** 从 RequestContextHolder 取当前请求（非 Web 线程返回 null） */
    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}
