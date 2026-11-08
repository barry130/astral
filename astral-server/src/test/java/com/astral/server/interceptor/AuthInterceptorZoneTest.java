package com.astral.server.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.astral.server.interceptor.AuthInterceptor.RequestZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 认证区判定的路由级回归（直接测 {@code AuthInterceptor.classify}，不起 Spring 上下文）。
 *
 * <p>对应缺陷：拦截器用原始 {@code getRequestURI()} 做 {@code startsWith} 前缀判断。
 * Spring 在路由前会剥离矩阵参数，于是 {@code /api/v1/admin;x/system/user/page}
 * 命中 {@code /api/v1/admin/**} 控制器、却不满足 {@code startsWith("/api/v1/admin/")}，
 * 管理端身份门禁（{@code user_type=ADMIN}）被整段跳过 —— 任何自助注册的 APP 用户
 * 登录后即可直连管理端接口。另有两个同类漏判：{@code /api/v1/app/user}（无尾斜杠）
 * 落进 App 匿名区；{@code /api/v1/app/users/**} 之类兄弟前缀被当成用户区。</p>
 *
 * <p>本测试验证规范路径和匹配模式的分类合同；真实 Spring 7 路径缓存读取与门禁动作
 * 另由 {@link AuthInterceptorPreHandleTest} 验证。</p>
 */
class AuthInterceptorZoneTest {

    @Test
    @DisplayName("矩阵参数绕过形态归一后必须落进管理端区（不再跳过身份门禁）")
    void matrixParameterBypassIsAdminZone() {
        String lookupPath = RequestPathSupport.normalize("/api/v1/admin;x/system/user/page");
        assertEquals(RequestZone.ADMIN, AuthInterceptor.classify(lookupPath, "/api/v1/admin/system/user/page"));
    }

    @Test
    @DisplayName("重复斜杠/尾斜杠绕过形态同样落进管理端区")
    void slashVariantsAreAdminZone() {
        assertEquals(
                RequestZone.ADMIN,
                AuthInterceptor.classify(RequestPathSupport.normalize("//api/v1//admin/system/user/"), null));
    }

    @Test
    @DisplayName("兄弟前缀不算管理端：/api/v1/adminfoo 是普通登录区")
    void siblingAdminPrefixIsNotAdminZone() {
        assertEquals(RequestZone.AUTHENTICATED, AuthInterceptor.classify("/api/v1/adminfoo/list", null));
    }

    @Test
    @DisplayName("无尾斜杠的 /api/v1/app/user 是用户区，不掉进 App 匿名区")
    void appUserWithoutTrailingSlashIsUserZone() {
        assertEquals(RequestZone.APP_USER, AuthInterceptor.classify("/api/v1/app/user", null));
        assertEquals(RequestZone.APP_USER, AuthInterceptor.classify("/api/v1/app/user/profile", null));
        assertEquals(
                RequestZone.APP_USER,
                AuthInterceptor.classify(RequestPathSupport.normalize("/api/v1/app/user;a=b/profile"), null));
    }

    @Test
    @DisplayName("App 公共区免认证；user 的兄弟前缀不被误判为用户区")
    void appPublicZoneAndSiblingPrefix() {
        assertEquals(RequestZone.APP_PUBLIC, AuthInterceptor.classify("/api/v1/app/update/latest", null));
        // 匿名统计上报是已声明的精确白名单；不是仅靠 App 公共子树放行。
        assertEquals(RequestZone.PUBLIC_PATH, AuthInterceptor.classify("/api/v1/app/stat/report", null));
        assertEquals(RequestZone.APP_PUBLIC, AuthInterceptor.classify("/api/v1/app/users/list", null));
    }

    @Test
    @DisplayName("免认证白名单按精确端点匹配（登录/公钥）")
    void publicPathsAreExact() {
        assertEquals(RequestZone.PUBLIC_PATH, AuthInterceptor.classify("/api/v1/all/auth/login", null));
        // 白名单端点下的子路径不是白名单（精确匹配，不做前缀放行）
        assertEquals(RequestZone.AUTHENTICATED, AuthInterceptor.classify("/api/v1/all/auth/login/extra", null));
    }

    @Test
    @DisplayName("storage Worker/Origin 子树走 HMAC 服务身份；普通 storage 用户接口仍需登录")
    void storageServiceZoneIsNarrow() {
        assertEquals(RequestZone.STORAGE_SERVICE, AuthInterceptor.classify("/api/v1/all/storage/worker/upload", null));
        assertEquals(RequestZone.STORAGE_SERVICE, AuthInterceptor.classify("/api/v1/all/storage/origin/get/x", null));
        assertEquals(RequestZone.AUTHENTICATED, AuthInterceptor.classify("/api/v1/all/storage/user/list", null));
    }

    @Test
    @DisplayName("/api/v1/all 门户接口是「需登录」区（不是匿名区）")
    void allZoneRequiresLogin() {
        assertEquals(RequestZone.AUTHENTICATED, AuthInterceptor.classify("/api/v1/all/notify/inapp/my/page", null));
        assertEquals(RequestZone.AUTHENTICATED, AuthInterceptor.classify("/api/v1/all/sequence/next", null));
    }

    @Test
    @DisplayName("Spring 命中的映射模式可独立支撑区域判定（模式带 {id} 占位符也可）")
    void matchedPatternAloneIsEnough() {
        assertEquals(
                RequestZone.ADMIN,
                AuthInterceptor.classify("/api/v1/admin/system/user/1", "/api/v1/admin/system/user/{id}"));
        assertEquals(RequestZone.APP_USER, AuthInterceptor.classify("/api/v1/app/user/1", "/api/v1/app/user/{id}"));
    }
}
