package com.astral.server.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 认证区边界判定的回归。
 *
 * <p>对应缺陷：{@code AuthInterceptor} 用 {@code request.getRequestURI()} 原串做
 * {@code startsWith} 前缀比较来决定「是否进入管理端身份门禁」。而 Spring MVC 在路由前会把路径
 * 解析成「段 + 矩阵参数」，矩阵参数被剥离，于是
 * {@code /api/v1/admin;x/system/user/page} 会命中 {@code /api/v1/admin/**} 的控制器，
 * 却因为原串不满足 {@code startsWith("/api/v1/admin/")} 而<b>跳过</b>管理端身份门禁
 * （登录态仍要求，所以任何自助注册的 APP 用户都能直连管理端接口）。</p>
 *
 * <p>修复后一律按 Spring 匹配后的路径判定，本测试锁住归一化形态与前缀的<b>段边界</b>语义。</p>
 */
class RequestPathSupportTest {

    @Test
    @DisplayName("普通路径原样保留")
    void keepsPlainPath() {
        assertEquals("/api/v1/admin/system/user", RequestPathSupport.normalize("/api/v1/admin/system/user"));
    }

    @Test
    @DisplayName("剥掉矩阵参数：绕过门禁的形态被折叠回受控前缀")
    void stripsMatrixParameters() {
        assertEquals(
                "/api/v1/admin/system/user/page", RequestPathSupport.normalize("/api/v1/admin;x/system/user/page"));
        assertEquals("/api/v1/admin/system/user", RequestPathSupport.normalize("/api/v1/admin/system/user;a=b"));
        assertEquals("/api/v1/admin/system/user", RequestPathSupport.normalize("/api/v1/admin;/system/user"));
    }

    @Test
    @DisplayName("折叠重复斜杠与去尾斜杠")
    void collapsesSlashVariants() {
        assertEquals("/api/v1/admin/system/user", RequestPathSupport.normalize("//api//v1///admin/system/user"));
        assertEquals("/api/v1/admin/system/user", RequestPathSupport.normalize("/api/v1/admin/system/user/"));
        assertEquals("/api/v1/admin/system/user", RequestPathSupport.normalize("/api/v1/admin/system/user//"));
    }

    @Test
    @DisplayName("去查询串/片段；空值归一为根路径")
    void stripsQueryAndFragment() {
        assertEquals("/api/v1/admin/x", RequestPathSupport.normalize("/api/v1/admin/x?a=1&b=2"));
        assertEquals("/api/v1/admin/x", RequestPathSupport.normalize("/api/v1/admin/x#frag"));
        assertEquals("/", RequestPathSupport.normalize(null));
        assertEquals("/", RequestPathSupport.normalize(""));
        assertEquals("/", RequestPathSupport.normalize("///"));
    }

    @Test
    @DisplayName("前缀判定按路径段边界：相等与子路径命中")
    void underMatchesSegmentBoundary() {
        assertTrue(RequestPathSupport.under("/api/v1/admin", "/api/v1/admin"));
        assertTrue(RequestPathSupport.under("/api/v1/admin/system/user", "/api/v1/admin"));
        // 前缀自带尾斜杠也接受（内部归一）
        assertTrue(RequestPathSupport.under("/api/v1/admin/system/user", "/api/v1/admin/"));
        assertTrue(RequestPathSupport.under("/api/v1/app/user/profile", "/api/v1/app/user"));
    }

    @Test
    @DisplayName("兄弟前缀不算命中：/api/v1/adminfoo 不属于 /api/v1/admin")
    void underRejectsSiblingPrefixes() {
        assertFalse(RequestPathSupport.under("/api/v1/adminfoo", "/api/v1/admin"));
        assertFalse(RequestPathSupport.under("/api/v1/app/users/list", "/api/v1/app/user"));
        assertFalse(RequestPathSupport.under("/api/v2/admin", "/api/v1/admin"));
        assertFalse(RequestPathSupport.under(null, "/api/v1/admin"));
        assertFalse(RequestPathSupport.under("/api/v1/admin", ""));
    }

    @Test
    @DisplayName("组合回归：绕过形态归一后仍落在受控区；app 用户区不因兄弟前缀被吞进匿名区")
    void bypassShapeStaysInProtectedZone() {
        String adminBypass = RequestPathSupport.normalize("/api/v1/admin;x/system/user/page");
        assertTrue(RequestPathSupport.under(adminBypass, "/api/v1/admin"));

        String appPublic = RequestPathSupport.normalize("/api/v1/app/update/latest");
        assertTrue(RequestPathSupport.under(appPublic, "/api/v1/app"));
        assertFalse(RequestPathSupport.under(appPublic, "/api/v1/app/user"));

        String appUser = RequestPathSupport.normalize("/api/v1/app/user;x/profile");
        assertTrue(RequestPathSupport.under(appUser, "/api/v1/app/user"));
    }
}
