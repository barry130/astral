package com.astral.auth.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.SaTokenContextForThreadLocalStaff;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.auth.registry.PermissionRegistry;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.constant.PermissionType;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.dao.mapper.PermissionMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Real annotation consumers and login; only external permission storage is replaced. */
class PermissionAspectContractTest {
    @Test
    void interfaceDeclaredGroupsAreRegisteredAndEnforcedOnAnActualJdkProxy() {
        InterfaceDeclaredController target = new InterfaceDeclaredController();
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("auditInterfaceController", InterfaceDeclaredController.class, () -> target);
            context.refresh();
            PermissionMapper rows = mock(PermissionMapper.class);
            PermissionCache cache = new PermissionCache();
            PermissionRegistry registry =
                    new PermissionRegistry(context, rows, new PermissionCatalog(rows, cache), cache);
            var declarations = registry.collectDeclarations();
            assertNotNull(declarations.get("all:audit:request:view"));
            assertNotNull(declarations.get("all:audit:request:inspect"));
            assertEquals(
                    PermissionType.DATA,
                    declarations.get("all:audit:request:content:view").getType());
        }

        AtomicReference<List<String>> held = new AtomicReference<>(List.of("all:audit:request:inspect"));
        withLoggedInPermissions(held, permissions -> {
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.setInterfaces(DeclaredEndpoint.class);
            factory.addAspect(new PermissionAspect(new PermissionChecker(permissions)));
            DeclaredEndpoint controller = factory.getProxy();

            assertThrows(
                    BusinessException.class,
                    controller::content,
                    "an interface API declaration must not erase its independent DATA group");
            held.set(List.of("all:audit:request:content:view"));
            assertThrows(
                    BusinessException.class,
                    controller::content,
                    "an interface DATA declaration alone must not authorize its API");
            held.set(List.of("all:audit:request:inspect", "all:audit:request:content:view"));
            assertEquals(
                    "authorized",
                    controller.content().getData(),
                    "ANY within the API group must retain access while every group is satisfied");
        });
    }

    @Test
    void mostSpecificMethodOverridesTheCompleteInterfaceAndClassDeclarations() {
        MethodDeclaredController target = new MethodDeclaredController();
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("auditMethodController", MethodDeclaredController.class, () -> target);
            context.refresh();
            PermissionMapper rows = mock(PermissionMapper.class);
            PermissionCache cache = new PermissionCache();
            PermissionRegistry registry =
                    new PermissionRegistry(context, rows, new PermissionCatalog(rows, cache), cache);
            var declarations = registry.collectDeclarations();
            assertNotNull(declarations.get("all:audit:method:view"));
            assertNotNull(declarations.get("all:audit:method:inspect"));
            assertEquals(
                    false,
                    declarations.containsKey("all:audit:request:view"),
                    "the overridden interface's API declaration must not remain registered for this endpoint");
            assertEquals(
                    false,
                    declarations.containsKey("all:audit:request:content:view"),
                    "the overridden interface's DATA declaration must not remain registered for this endpoint");
        }
        AtomicReference<List<String>> held = new AtomicReference<>(List.of("all:audit:method:inspect"));
        withLoggedInPermissions(held, permissions -> {
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.setInterfaces(DeclaredEndpoint.class);
            factory.addAspect(new PermissionAspect(new PermissionChecker(permissions)));
            DeclaredEndpoint controller = factory.getProxy();
            assertEquals(
                    "method-authorized",
                    controller.content().getData(),
                    "the most-specific method's complete ANY declaration must replace both inherited group sets");
        });
    }

    @Test
    void inheritedTypeGroupsKeepAnyApiAndAllDataRequirementsOnAnActualClassProxy() {
        TypeDeclaredController target = new TypeDeclaredController();
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("auditTypeController", TypeDeclaredController.class, () -> target);
            context.refresh();
            PermissionMapper rows = mock(PermissionMapper.class);
            PermissionCache cache = new PermissionCache();
            PermissionRegistry registry =
                    new PermissionRegistry(context, rows, new PermissionCatalog(rows, cache), cache);
            var declarations = registry.collectDeclarations();
            assertEquals(
                    PermissionType.API,
                    declarations.get("all:audit:type:inspect").getType());
            assertEquals(
                    PermissionType.DATA,
                    declarations.get("all:audit:type:content:view").getType());
            assertEquals(
                    PermissionType.DATA,
                    declarations.get("all:audit:type:content:export").getType());
        }
        AtomicReference<List<String>> held = new AtomicReference<>(List.of("all:audit:type:inspect"));
        withLoggedInPermissions(held, permissions -> {
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.setProxyTargetClass(true);
            factory.addAspect(new PermissionAspect(new PermissionChecker(permissions)));
            TypeDeclaredController controller = factory.getProxy();
            assertThrows(
                    BusinessException.class,
                    controller::content,
                    "the complete inherited DATA group remains mandatory");
            held.set(List.of("all:audit:type:content:view", "all:audit:type:content:export"));
            assertThrows(
                    BusinessException.class,
                    controller::content,
                    "the inherited API group remains independently mandatory");
            held.set(List.of("all:audit:type:inspect", "all:audit:type:content:view"));
            assertThrows(
                    BusinessException.class,
                    controller::content,
                    "ALL inside the DATA group cannot be replaced by ANY");
            held.set(List.of("all:audit:type:inspect", "all:audit:type:content:view", "all:audit:type:content:export"));
            assertEquals(
                    "type-authorized",
                    controller.content().getData(),
                    "ANY inside the API group is sufficient only after the whole DATA group is satisfied");
        });
    }

    @RequiresPermission(
            value = {"all:audit:type:view", "all:audit:type:inspect"},
            mode = RequiresPermission.Mode.ANY,
            name = "Audit fixture type view")
    @RequiresPermission(
            value = {"all:audit:type:content:view", "all:audit:type:content:export"},
            mode = RequiresPermission.Mode.ALL,
            type = PermissionType.DATA,
            name = "Audit fixture type content")
    public interface TypeDeclaredEndpoint {
        Result<String> content();
    }

    @RestController
    @RequestMapping("/api/v1/all/audit/type")
    public static class TypeDeclaredController implements TypeDeclaredEndpoint {
        @Override
        @GetMapping("/content")
        public Result<String> content() {
            return Result.success("type-authorized");
        }
    }

    private void withLoggedInPermissions(AtomicReference<List<String>> held, Consumer<StpInterface> assertions) {
        StpInterface permissions = mock(StpInterface.class);
        when(permissions.getPermissionList(any(), any())).thenAnswer(ignored -> held.get());
        SaTokenContext previousContext = SaManager.getSaTokenContext();
        StpInterface previousPermissions = SaManager.getStpInterface();
        var previousBox = SaTokenContextForThreadLocalStaff.getModelBoxOrNull();
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaManager.setStpInterface(permissions);
        try {
            SaTokenContextMockUtil.setMockContext();
            StpUtil.login(910043L);
            assertions.accept(permissions);
        } finally {
            try {
                if (SaTokenContextForThreadLocalStaff.getModelBoxOrNull() != null && StpUtil.isLogin()) {
                    StpUtil.logout();
                }
            } finally {
                if (previousBox == null) {
                    SaTokenContextForThreadLocalStaff.modelBoxThreadLocal.remove();
                } else {
                    SaTokenContextForThreadLocalStaff.modelBoxThreadLocal.set(previousBox);
                }
                SaManager.setStpInterface(previousPermissions);
                SaManager.setSaTokenContext(previousContext);
            }
        }
    }

    @RestController
    @RequestMapping("/api/v1/all/audit/override")
    @RequiresPermission(value = "all:audit:class:view", name = "Audit fixture class view")
    @RequiresPermission(
            value = "all:audit:class:content:view",
            type = PermissionType.DATA,
            name = "Audit fixture class content")
    public static class MethodDeclaredController implements DeclaredEndpoint {
        @Override
        @GetMapping("/content")
        @RequiresPermission(
                value = {"all:audit:method:view", "all:audit:method:inspect"},
                mode = RequiresPermission.Mode.ANY,
                name = "Audit fixture method view")
        public Result<String> content() {
            return Result.success("method-authorized");
        }
    }

    public interface DeclaredEndpoint {
        @RequiresPermission(
                value = {"all:audit:request:view", "all:audit:request:inspect"},
                mode = RequiresPermission.Mode.ANY,
                name = "Audit fixture request view")
        @RequiresPermission(
                value = "all:audit:request:content:view",
                type = PermissionType.DATA,
                name = "Audit fixture request content")
        Result<String> content();
    }

    @RestController
    @RequestMapping("/api/v1/all/audit")
    public static class InterfaceDeclaredController implements DeclaredEndpoint {
        @Override
        @GetMapping("/content")
        public Result<String> content() {
            return Result.success("authorized");
        }
    }
}
