package com.astral.server.interceptor;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.auth.security.AuthCookieWriter;
import com.astral.auth.security.LoginUserStateResolver;
import com.astral.auth.security.LoginUserTypeResolver;
import com.astral.auth.service.UserLoginMarker;
import com.astral.common.result.Result;
import com.astral.common.web.CsrfTokenSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * 全局统一认证拦截器（宿主管理端 + 轻听插件用户端合并）
 * <p>基于 Sa-Token 实现请求认证拦截：</p>
 * <p><b>传输层契约</b>：认证/授权失败的响应一律为 HTTP 200（服务端已处理），
 * 失败语义只写在 {@code body.code}（401 未登录 / 403 无权限），HTTP 状态码不再复述业务码。</p>
 * <ul>
 *   <li><b>宿主管理区</b>（/api/v1/** 除 Qt 用户区外）：未登录返回 HTTP 200 +
 *       {@code Result} 业务码 401（errorCode=AUTH001），
 *       登录后把 userId/username 写入 request 属性（供限流拦截器与控制器使用）</li>
 *   <li><b>Qt 用户区</b>（/api/v1/app/user/**）：未携带/无效 satoken 返回 HTTP 200 +
 *       {@code Result} 业务码 401（“登录状态已失效”，与历史 App 客户端约定一致），
 *       登录后把 userId/username 写入 request 属性</li>
 *   <li><b>Token 自动续期</b>：任何 /api/** 请求只要携带有效 satoken（含匿名接口如 /api/v1/app/update），
 *       剩余有效期低于阈值（astral.auth.token-renew-threshold，默认 1 天）时自动续满
 *       sa-token.timeout（默认 3 天），实现活跃用户滑动续期；
 *       每请求仅一次 Redis 读，临近过期才触发一次写</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** JSON序列化器，用于将错误响应写入响应体 */
    private final ObjectMapper objectMapper;

    /** 登录用户类型解析器：区分「管理端用户」与「App 用户」 */
    private final LoginUserTypeResolver loginUserTypeResolver;

    /** 登录/活跃标记：滑动续期发生时回写 sys_user.login_time/login_ip */
    private final UserLoginMarker userLoginMarker;

    /** 管理端认证 Cookie 读写器：滑动续期时同步延长 Cookie，避免会话还在、Cookie 先过期 */
    private final AuthCookieWriter authCookieWriter;

    /** 账号状态兜底门禁：用户被删除/禁用后，其存量会话必须失效 */
    private final LoginUserStateResolver loginUserStateResolver;

    /**
     * 管理端接口要求的 user_type，默认 {@code ADMIN}。
     * 配成空串可关闭管理端身份门禁（仅应急排障用）。
     */
    @Value("${astral.auth.admin-required-user-type:ADMIN}")
    private String adminRequiredUserType;

    /** 续期阈值（秒）：剩余有效期低于该值时自动续满（0=关闭自动续期） */
    @Value("${astral.auth.token-renew-threshold:86400}")
    private long renewThreshold;

    /** 续期目标时长（秒）：默认取 sa-token.timeout（259200 = 3 天） */
    @Value("${sa-token.timeout:259200}")
    private long tokenTimeout;

    /** satoken 请求头名（与 sa-token.token-name 一致） */
    private static final String TOKEN_HEADER = "satoken";

    /**
     * Qt 用户区前缀（<b>不带尾斜杠</b>，判定统一走 {@link RequestPathSupport#under} 的段边界比较）。
     */
    private static final String QT_USER_PREFIX = "/api/v1/app/user";

    /**
     * App 匿名公共区前缀。
     *
     * <p>注意这里<b>不是</b>改成显式白名单：已发布客户端（qt-uniappx / qt-pc）在
     * {@code /api/v1/app/**} 下有版本更新、统计上报等公开路由，且 qt 插件自身还有一层
     * Bearer 拦截器；在未逐条核对线上路由前把区域改成名单会直接断客户端。
     * 本次只做两件事：① 判定改用 Spring 匹配后的路径（矩阵参数/重复斜杠不会漏判）；
     * ② 用户区排除按段边界比较，{@code /api/v1/app/users/**} 之类兄弟前缀不再被误判为公开。</p>
     */
    private static final String APP_PUBLIC_PREFIX = "/api/v1/app";

    /**
     * 宿主管理端前缀（<b>不带尾斜杠</b>，同 {@link #QT_USER_PREFIX}）。
     *
     * <p>该前缀下的接口需要「已登录 + 管理端身份（{@code sys_user.user_type = ADMIN}）」双重校验。
     * 原因：管理端与轻听 App 端共用同一套 Sa-Token 命名空间，App 注册接口又是免认证的，
     * 只校验 {@code StpUtil.isLogin()} 会让任何注册用户都能调用管理端接口。</p>
     */
    private static final String ADMIN_PREFIX = "/api/v1/admin";

    /**
     * storage 插件 Worker / Origin 专属子树（仅接受 HMAC 服务身份，不接受浏览器 satoken）。
     * 必须精确限定到这两个子树，不能放行整个 /api/v1/all/storage/**（用户接口仍需登录）。
     */
    private static final String STORAGE_WORKER_PREFIX = "/api/v1/all/storage/worker";

    private static final String STORAGE_ORIGIN_PREFIX = "/api/v1/all/storage/origin";

    /** 免认证路径白名单（合并原宿主 WebMvcConfig excludes 与 qt 插件 PUBLIC_PATHS） */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            // 宿主认证（/api/v1/all/auth）
            "/api/v1/all/auth/public-key",
            "/api/v1/all/auth/login",
            "/api/v1/all/auth/logout",
            // 轻听用户端免认证（/api/v1/app/user）
            // 注意：upload 已从白名单移除 —— 匿名上传 + 同源静态目录 = 存储型 XSS，
            // 头像上传属于「登录后才该做的事」，客户端请先登录再上传。
            "/api/v1/app/user/login",
            "/api/v1/app/user/register",
            "/api/v1/app/user/email",
            "/api/v1/app/user/changePass",
            "/api/v1/app/user/refresh",
            // 全端统计：匿名上报入口放行
            "/api/v1/app/stat/report");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String rawUri = request.getRequestURI();
        String token = request.getHeader(TOKEN_HEADER);

        // 0. 安全边界判定必须与「Spring 实际路由到的路径」同源。
        //    不能拿 getRequestURI() 原串做前缀比较：Spring 在路由前会把路径解析成
        //    「段 + 矩阵参数」，矩阵参数被剥离，于是 "/api/v1/admin;x/system/user/page"
        //    仍会命中 /api/v1/admin/** 的控制器，而原串 startsWith("/api/v1/admin/") 为 false
        //    —— 管理端身份门禁被整段跳过（登录态仍要求，即任何 APP 注册用户可直连管理端接口）。
        //    这里优先取 Spring 自己算出的匹配结果（HandlerMapping 属性，已处理 contextPath、
        //    矩阵参数与重复斜杠），取不到时才退回本地归一化。
        String lookupPath = resolveLookupPath(request, rawUri);
        String matchedPattern = resolveBestMatchingPattern(request);
        RequestZone zone = classify(lookupPath, matchedPattern);

        // 1. Token 自动续期（所有区域通用；匿名接口带了有效 token 也续）
        renewIfNeeded(request, token);

        // 2. 免认证区放行：精确白名单端点、App 公共区（/api/v1/app 下非 user 区）、
        //    storage Worker/Origin（HMAC 服务身份）。三者的区域判定见 classify()。
        //    注意：白名单只做「精确路径」匹配，不再有 startsWith 前缀放行。
        //    宽前缀放行（曾经的 /api/v1/system/table-schema、/api/v1/admin/system/table-schema）
        //    会让该前缀下「未来新增的接口」默认匿名，保护责任被下推给后续开发者，已移除。
        if (zone == RequestZone.PUBLIC_PATH || zone == RequestZone.APP_PUBLIC || zone == RequestZone.STORAGE_SERVICE) {
            return true;
        }

        // 3. 免认证区不参与 CSRF；其后所有区都要过 CSRF 双提交校验（仅「凭据来自 Cookie」时）。

        // 3.8 CSRF 双提交校验：仅针对「管理端区 + 凭据实际来自 Cookie」的请求。
        //     管理台令牌已改由 HttpOnly Cookie 承载，浏览器会自动带上它，
        //     跨站页面因此能借用户身份发出写请求（CSRF）。校验 Cookie 与请求头是否一致即可拦住：
        //     跨站读不到 Cookie 值，伪造不出匹配的头。
        //     仍然走 satoken 请求头的调用方（轻听 App、脚本、Swagger）不受影响 —— 显式携带凭据
        //     天然不存在 CSRF，强制它们参与双提交会打断已发布的客户端。
        if (requiresCsrfCheck(request) && !passCsrfCheck(request, response)) {
            return false;
        }

        // 4. Qt 用户区：校验 satoken 有效性，失败写 HTTP 200 + 业务码 401（App 客户端历史约定只读 body code）
        if (zone == RequestZone.APP_USER) {
            Long userId = resolveUserId(token);
            if (userId == null) {
                writeSessionInvalid(response);
                return false;
            }
            // 账号已被删除/禁用时，旧 token 不能继续代表它（见 LoginUserStateResolver 类注释）
            if (!accountActiveOrFail(response, userId, token)) {
                return false;
            }
            request.setAttribute("userId", userId);
            setUsernameAttribute(request);
            return true;
        }

        // 4. 宿主管理区：校验登录态，失败写 HTTP 200 + 业务码 401（errorCode=AUTH001）
        if (!StpUtil.isLogin()) {
            writeUnauthorized(response);
            return false;
        }

        // 4.5 账号状态门禁（删除/禁用兜底）：Sa-Token 只保证 token 有效，不保证账号仍可用。
        //     删除用户走逻辑删除、库里直接改 status=0，都没有代码路径会去踢人；而本拦截器
        //     每请求滑动续期、sa-token.active-timeout=-1 关闭闲置过期 ⇒ 被删账号的会话
        //     不会自然过期。这里每请求校验一次（30 秒短 TTL 缓存，只查 id/status 两列），
        //     命中不可用即踢掉当前会话并 401。
        //     生效延迟：显式 kickout 的路径（封禁）即时；仅改库/删除后的兜底 ≤30 秒。
        if (rejectIfAccountInactive(response, rawUri)) {
            return false;
        }

        // 5. 管理端身份门禁：/api/v1/admin/** 必须是管理端用户（user_type=ADMIN）
        //    管理端与 App 端共用 Sa-Token 命名空间，App 注册免认证 —— 只判 isLogin()
        //    等于把管理端对所有注册用户开放。此处按 sys_user.user_type 区分身份。
        if (zone == RequestZone.ADMIN && !passAdminIdentityGate(response)) {
            return false;
        }

        // 从 Sa-Token 会话中读取用户信息（登录时已存入），避免每次请求查库
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId != null) {
            request.setAttribute("userId", loginId.toString());
            setUsernameAttribute(request);
            log.debug("请求用户: userId={}", loginId);
        }
        return true;
    }

    /**
     * 管理端身份门禁。
     *
     * <p>要求当前登录用户的 {@code sys_user.user_type} 等于 {@link #adminRequiredUserType}
     * （默认 {@code ADMIN}）。该列在 V20260914001 中定义为
     * {@code NOT NULL DEFAULT 'ADMIN'}，历史管理员天然满足，不会因引入校验被锁在门外。</p>
     *
     * <p>把 {@code astral.auth.admin-required-user-type} 配成空串可关闭本校验
     * （仅用于排障应急，不要在生产长期关闭）。</p>
     *
     * @return 通过返回 true；否则已写入 HTTP 200 + 业务码 403 的响应并返回 false
     */
    private boolean passAdminIdentityGate(HttpServletResponse response) throws Exception {
        if (adminRequiredUserType == null || adminRequiredUserType.isBlank()) {
            return true;
        }
        Object loginId = StpUtil.getLoginIdDefaultNull();
        String userType = loginUserTypeResolver.resolve(loginId);
        if (adminRequiredUserType.equals(userType)) {
            return true;
        }

        log.warn("管理端身份门禁拒绝: userId={}, userType={}, 要求={}", loginId, userType, adminRequiredUserType);

        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(200);
        Result<?> result = Result.error("COMMON005");
        result.setCode(403);
        response.getWriter().write(objectMapper.writeValueAsString(result));
        return false;
    }

    /**
     * 是否需要做 CSRF 双提交校验。
     *
     * <p>两个条件同时成立才校验：</p>
     * <ol>
     *   <li>请求方法非只读（GET/HEAD/OPTIONS/TRACE 不产生副作用，放行）；</li>
     *   <li>凭据来自 Cookie —— 只要带了 {@code satoken} 请求头就说明是显式调用方，直接豁免。</li>
     * </ol>
     *
     * <p>第二步的豁免不能省：轻听 App 与脚本同域也可能被浏览器顺带带上 Cookie，
     * 按「有没有头」而不是「有没有 Cookie」判断，才不会误伤既有调用方。</p>
     */
    private boolean requiresCsrfCheck(HttpServletRequest request) {
        if (CsrfTokenSupport.isSafeMethod(request.getMethod())) {
            return false;
        }
        String headerToken = request.getHeader(TOKEN_HEADER);
        if (headerToken != null && !headerToken.isBlank()) {
            return false;
        }
        // 没有头也没 Cookie：未认证请求，交给后面的登录校验去 401，这里不重复拦
        return CsrfTokenSupport.readCookie(request, TOKEN_HEADER) != null;
    }

    /** 双提交校验：Cookie 与请求头必须同时存在且相等（常量时间比较） */
    private boolean passCsrfCheck(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String cookieCsrf = CsrfTokenSupport.readCookie(request, CsrfTokenSupport.CSRF_COOKIE);
        String headerCsrf = request.getHeader(CsrfTokenSupport.CSRF_HEADER);
        if (cookieCsrf != null
                && !cookieCsrf.isBlank()
                && CsrfTokenSupport.constantTimeEquals(cookieCsrf, headerCsrf)) {
            return true;
        }

        log.warn(
                "CSRF 校验失败: uri={}, method={}, cookie存在={}, header存在={}",
                request.getRequestURI(),
                request.getMethod(),
                cookieCsrf != null,
                headerCsrf != null);

        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(200);
        Result<?> result = Result.error("AUTH013");
        result.setCode(403);
        response.getWriter().write(objectMapper.writeValueAsString(result));
        return false;
    }

    /**
     * Token 滑动续期：剩余有效期不足阈值时续满（任何异常不影响请求）。
     *
     * <p>续期发生 = 用户仍活跃，同步回写 {@code sys_user.login_time/login_ip}，
     * 使「最后活跃」口径覆盖未重新登录但持续使用的用户（回写失败不影响请求）。</p>
     */
    private void renewIfNeeded(HttpServletRequest request, String token) {
        if (renewThreshold <= 0 || token == null || token.isBlank()) {
            return;
        }
        try {
            // token 无效/已过期时 getLoginIdByToken 返回 null，直接跳过
            Object loginId = StpUtil.getLoginIdByToken(token);
            if (loginId == null) {
                return;
            }
            long remain = StpUtil.getTokenTimeout(token);
            if (remain > 0 && remain < renewThreshold) {
                StpUtil.renewTimeout(token, tokenTimeout);
                userLoginMarker.mark(Long.parseLong(loginId.toString()), request);
                // Cookie 承载方：会话续到 3 天，Cookie 也得跟着延，否则 Cookie 先到期，
                // 浏览器不再带凭据 —— 表现为「一直活跃却突然要重新登录」
                HttpServletResponse httpResponse = AuthCookieWriter.currentResponse();
                if (httpResponse != null && CsrfTokenSupport.readCookie(request, TOKEN_HEADER) != null) {
                    authCookieWriter.refreshCookies(httpResponse, token, tokenTimeout);
                }
                log.debug("token 已自动续期: remain={}s -> {}s", remain, tokenTimeout);
            }
        } catch (Exception e) {
            log.debug("token 续期跳过: {}", e.getMessage());
        }
    }

    /** 从 satoken 请求头解析登录用户ID（无效/未携带返回 null） */
    private Long resolveUserId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Object loginId = StpUtil.getLoginIdByToken(token);
            if (loginId != null) {
                // loginId 可能是 String 或 Number，统一按字符串解析为 Long
                return Long.parseLong(loginId.toString());
            }
        } catch (Exception e) {
            log.debug("satoken 解析失败: {}", e.getMessage());
        }
        return null;
    }

    /** 认证区分类：安全判定的唯一入口，与拦截动作解耦（便于单测覆盖「绕过形态落到哪个区」） */
    enum RequestZone {
        /** 免认证：精确白名单端点（登录、公钥等） */
        PUBLIC_PATH,
        /** 免认证：App 公共区（/api/v1/app 下非 user 区） */
        APP_PUBLIC,
        /** 免认证：storage Worker/Origin 子树（HMAC 服务身份） */
        STORAGE_SERVICE,
        /** App 用户区：需 satoken */
        APP_USER,
        /** 管理端区：需登录 + user_type=ADMIN */
        ADMIN,
        /** 其余需登录区（/api/v1/all/** 等） */
        AUTHENTICATED
    }

    /**
     * 判定请求所属认证区。
     *
     * <p>入参必须是 <b>Spring 匹配后</b>的路径（{@link #resolveLookupPath}）与映射模式
     * （{@link #resolveBestMatchingPattern}），不是原始 {@code getRequestURI()}
     * —— 否则矩阵参数形态（{@code /api/v1/admin;x/...}）会绕过管理端身份门禁。</p>
     *
     * <p>顺序有意为之：先判免认证区（白名单/App 公共/storage 服务身份），再判 App 用户区，
     * 最后才是管理端；区域之间用<b>段边界</b>比较，兄弟前缀
     * （{@code /api/v1/adminfoo}、{@code /api/v1/app/users}）不会被吞进受控区或匿名区。</p>
     *
     * @param lookupPath     Spring 匹配后的规范路径
     * @param matchedPattern Spring 命中的映射模式（可为 null）
     * @return 所属认证区
     */
    static RequestZone classify(String lookupPath, String matchedPattern) {
        if (PUBLIC_PATHS.contains(lookupPath) || (matchedPattern != null && PUBLIC_PATHS.contains(matchedPattern))) {
            return RequestZone.PUBLIC_PATH;
        }
        boolean appUserZone = inZone(lookupPath, matchedPattern, QT_USER_PREFIX);
        if (RequestPathSupport.under(lookupPath, APP_PUBLIC_PREFIX) && !appUserZone) {
            return RequestZone.APP_PUBLIC;
        }
        if (inZone(lookupPath, matchedPattern, STORAGE_WORKER_PREFIX)
                || inZone(lookupPath, matchedPattern, STORAGE_ORIGIN_PREFIX)) {
            return RequestZone.STORAGE_SERVICE;
        }
        if (appUserZone) {
            return RequestZone.APP_USER;
        }
        if (inZone(lookupPath, matchedPattern, ADMIN_PREFIX)) {
            return RequestZone.ADMIN;
        }
        return RequestZone.AUTHENTICATED;
    }

    /**
     * 从 Spring 的公开路径缓存 API 读取应用内路由路径。
     *
     * <p>同时兼容 PathPattern 的 RequestPath 和 UrlPathHelper 的字符串缓存；
     * Spring 7 已移除 HandlerMapping.LOOKUP_PATH_ATTRIBUTE，不能依赖历史常量。
     * Spring 负责 contextPath 剥离，本地归一化仍处理矩阵参数和重复斜杠。
     * 未缓存时退回本地归一化；匹配模式仍独立参与受控区域判定。</p>
     */
    private String resolveLookupPath(HttpServletRequest request, String rawUri) {
        if (ServletRequestPathUtils.hasCachedPath(request)) {
            String lookupPath = ServletRequestPathUtils.getCachedPathValue(request);
            if (!lookupPath.isBlank()) {
                return RequestPathSupport.normalize(lookupPath);
            }
        }
        return RequestPathSupport.normalize(rawUri);
    }

    /**
     * 取 Spring 命中的映射模式（如 {@code /api/v1/admin/system/user/{id}}）。
     *
     * <p>与 lookupPath 互为印证：模式表达「这条请求落到了哪个控制器的哪个映射」，
     * 路径表达「实际请求的规范形态」。两者任一落在受控前缀下即按受控区处理，
     * 避免只依赖单一来源（模式里可能带 {@code {id}} 占位符，路径里可能带具体值）。</p>
     *
     * @return 模式字符串；无匹配信息返回 {@code null}
     */
    private String resolveBestMatchingPattern(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (attribute == null) {
            return null;
        }
        String pattern = attribute.toString();
        if (pattern.isEmpty()) {
            return null;
        }
        return pattern.charAt(0) == '/' ? pattern : "/" + pattern;
    }

    /** 路径或命中的映射模式任一落在该前缀下，即视为属于该区（按路径段边界比较） */
    private static boolean inZone(String lookupPath, String matchedPattern, String prefix) {
        return RequestPathSupport.under(lookupPath, prefix) || RequestPathSupport.under(matchedPattern, prefix);
    }

    /**
     * 账号状态查询：不可用 → 踢会话 + 401；查询失败 → 503 拒绝本次请求（<b>不踢会话</b>）。
     *
     * @return 账号仍可用返回 {@code true}；已写响应并需中断请求返回 {@code false}
     */
    private boolean accountActiveOrFail(HttpServletResponse response, Long userId, String tokenToKick)
            throws Exception {
        boolean active;
        try {
            active = loginUserStateResolver.isActive(userId);
        } catch (IllegalStateException e) {
            // fail-closed：状态查不到就拒绝本次请求（503），绝不放行；
            // 但这是瞬时基础设施故障，不能据此吊销会话 —— 恢复后用户原会话应当仍有效。
            // 日志只留安全摘要（用户 id + 异常类型），不打 SQL/数据源细节、不记录凭据。
            log.error(
                    "账号状态校验不可用，拒绝本次请求（503，不踢会话）: userId={}, err={}",
                    userId,
                    e.getClass().getSimpleName());
            writeServiceUnavailable(response);
            return false;
        }
        if (active) {
            return true;
        }
        // 已知具体 token 时按 token 踢（App 端凭据不在默认 token-name 上也能踢准）
        if (tokenToKick != null) {
            StpUtil.logoutByTokenValue(tokenToKick);
        } else {
            StpUtil.logout();
        }
        log.warn("拒绝已失效账号的会话: userId={}", userId);
        writeUnauthorized(response);
        return false;
    }

    /** 账号状态校验不可用：HTTP 503 + 业务码 503（COMMON001），不销毁会话 */
    private void writeServiceUnavailable(HttpServletResponse response) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(503);
        Result<?> result = Result.error("COMMON001");
        result.setCode(503);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }

    /**
     * 账号已被删除/禁用时踢掉当前会话并写 401。
     *
     * <p>与 {@code UserController.delete/changeStatus} 里的显式 {@code kickout} 互补：
     * 显式踢人覆盖代码路径，这里兜住「逻辑删除」「直接改库」等所有其它来源。
     * 状态查询走 {@code LoginUserStateResolver} 的 30 秒短 TTL 缓存，且只读 id/status 两列。</p>
     *
     * @return 命中「账号不可用」返回 {@code true}（响应已写出）
     */
    private boolean rejectIfAccountInactive(HttpServletResponse response, String rawUri) throws Exception {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            return false;
        }
        Long userId;
        try {
            userId = Long.parseLong(loginId.toString());
        } catch (NumberFormatException e) {
            // loginId 不是数字（理论不可能）：不阻断，交给后续权限判定
            return false;
        }
        if (accountActiveOrFail(response, userId, null)) {
            return false;
        }
        if (response.getStatus() != 503) {
            log.warn("拒绝已失效账号的会话: userId={}, uri={}", userId, rawUri);
        }
        return true;
    }

    /** 未登录/会话失效：HTTP 200 + 业务码 401（errorCode=AUTH001） */
    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(200);
        Result<?> result = Result.error("AUTH001");
        result.setCode(401);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }

    /** Qt 用户区会话失效：HTTP 200 + 业务码 401（App 客户端历史约定只读 body.code） */
    private void writeSessionInvalid(HttpServletResponse response) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(200);
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(401, "登录状态已失效")));
    }

    /** 从 Sa-Token 会话读取用户名写入 request 属性（无会话时回退 userId） */
    private void setUsernameAttribute(HttpServletRequest request) {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        String username = null;
        SaSession session = StpUtil.getSession(false);
        if (session != null) {
            username = (String) session.get("username");
        }
        if (username == null) {
            username = loginId != null ? loginId.toString() : null;
        }
        if (username != null) {
            request.setAttribute("username", username);
        }
    }
}
