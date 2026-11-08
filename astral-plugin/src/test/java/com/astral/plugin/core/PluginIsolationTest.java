package com.astral.plugin.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import com.astral.cms.CmsPlugin;
import com.astral.common.exception.BusinessException;
import com.astral.plugin.api.PluginRegistry;
import com.astral.qt.QtPlugin;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

class PluginIsolationTest {
    @Test
    void mvcResolvedPathsEnforceTheGateWithMatrixParametersAndContextPaths() throws Exception {
        var registry = mock(PluginRegistry.class);
        when(registry.getAllPlugins()).thenReturn(List.of(new CmsPlugin()));
        var mvc = MockMvcBuilders.standaloneSetup(new RouteFixture())
                .addInterceptors(new PluginApiInterceptor(registry))
                .build();
        for (String context : List.of("", "/astral")) {
            for (String path : List.of("/api/v1/app/cms/post/news", "/api/v1/app/cms;x/post/news")) {
                when(registry.isEnabled("cms")).thenReturn(false);
                Exception rejected = assertThrows(
                        Exception.class, () -> mvc.perform(get(context + path).contextPath(context)));
                assertTrue(rejected.getCause() instanceof BusinessException);
                when(registry.isEnabled("cms")).thenReturn(true);
                mvc.perform(get(context + path).contextPath(context))
                        .andExpect(content().string("content"));
            }
            when(registry.isEnabled("cms")).thenReturn(false);
            mvc.perform(post(context + "/api/v1/admin/plugin/cms/enable").contextPath(context))
                    .andExpect(content().string("enabled"));
        }
    }

    @RestController
    static class RouteFixture {
        @GetMapping("/api/v1/app/cms/post/{slug}")
        String article() {
            return "content";
        }

        @PostMapping("/api/v1/admin/plugin/{id}/enable")
        String enable() {
            return "enabled";
        }
    }

    @Test
    void disablingQtOnlyBlocksItsOwnRoutes() {
        var registry = mock(PluginRegistry.class);
        when(registry.getAllPlugins()).thenReturn(List.of(new QtPlugin(), new CmsPlugin()));
        when(registry.isEnabled("cms")).thenReturn(true);
        var interceptor = new PluginApiInterceptor(registry);
        for (String path : List.of(
                "/api/v1/app/cms/post/news",
                "/api/v1/app/message/inbox",
                "/api/v1/app/ai/agents",
                "/api/v1/app/storage/folders")) {
            assertTrue(interceptor.preHandle(
                    new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), null));
        }
        for (String path : List.of(
                "/api/v1/app/user/info",
                "/api/v1/app/update",
                "/api/v1/app/github/accels",
                "/api/v1/app/version/check",
                "/api/v1/app/source/manifest",
                "/api/v1/admin/qt/users")) {
            assertThrows(
                    BusinessException.class,
                    () -> interceptor.preHandle(
                            new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), null));
        }
    }

    @Test
    void contentSlugsCannotMasqueradeAsPluginManagementEndpoints() {
        var registry = mock(PluginRegistry.class);
        when(registry.getAllPlugins()).thenReturn(List.of(new CmsPlugin()));
        var interceptor = new PluginApiInterceptor(registry);
        for (String slug : List.of("enable", "disable", "toggle")) {
            assertThrows(
                    BusinessException.class,
                    () -> interceptor.preHandle(
                            new MockHttpServletRequest("GET", "/api/v1/app/cms/post/" + slug),
                            new MockHttpServletResponse(),
                            null));
        }
        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest("POST", "/api/v1/admin/plugin/cms/enable"),
                new MockHttpServletResponse(),
                null));
        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/v1/app/cms-other"), new MockHttpServletResponse(), null));
    }
}
