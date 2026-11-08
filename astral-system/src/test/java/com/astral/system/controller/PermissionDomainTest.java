package com.astral.system.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.astral.auth.security.PermissionCache;
import com.astral.dao.entity.Permission;
import com.astral.dao.mapper.PermissionMapper;
import com.astral.dao.mapper.RolePermissionMapper;
import com.astral.system.service.impl.PermissionServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PermissionDomainTest {
    @Test
    void missingDomainUsesDomainSegmentOfCurrentCodesAndKeepsLegacyFallback() {
        PermissionMapper mapper = mock(PermissionMapper.class);
        when(mapper.selectList(any()))
                .thenReturn(List.of(
                        row("admin:ai:tool:view"),
                        row("app:qt:source:view"),
                        row("user:storage:file:view"),
                        row("system:user:view")));
        PermissionServiceImpl service = new PermissionServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        PermissionController controller =
                new PermissionController(service, mock(RolePermissionMapper.class), mock(PermissionCache.class));

        var groups = controller.getTree("domain").getData();

        assertEquals(
                List.of("ai", "qt", "storage", "system"),
                groups.stream().map(Permission::getDomain).toList());
        assertEquals(
                "admin:ai:tool:view", groups.getFirst().getChildren().getFirst().getPermissionCode());
    }

    private static Permission row(String code) {
        Permission row = new Permission();
        row.setPermissionCode(code);
        return row;
    }
}
