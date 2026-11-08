package com.astral.qt.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import com.astral.qt.entity.QtAppUpdate;
import com.astral.qt.mapper.QtAppUpdateMapper;
import com.astral.qt.service.QtAppService;
import com.astral.qt.service.QtAppUpdateArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 覆盖 QtAdminController 的两个写接口安全修复：
 * 删除版本时级联清理产物（D-QT-04）、用户状态接口仅允许操作 App 用户（D-QT-05）。
 */
class QtAdminControllerSafetyTest {

    private QtAdminController controller;
    private QtAppUpdateMapper updateMapper;
    private QtAppUpdateArtifactService artifacts;
    private QtAppService appService;
    private UserMapper userMapper;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "qt-user-test"),
                User.class);
        controller = new QtAdminController();
        updateMapper = mock(QtAppUpdateMapper.class);
        artifacts = mock(QtAppUpdateArtifactService.class);
        appService = mock(QtAppService.class);
        userMapper = mock(UserMapper.class);
        ReflectionTestUtils.setField(controller, "qtUpdateMapper", updateMapper);
        ReflectionTestUtils.setField(controller, "artifactService", artifacts);
        ReflectionTestUtils.setField(controller, "appService", appService);
        ReflectionTestUtils.setField(controller, "userMapper", userMapper);
    }

    @Test
    void deleteUpdateCascadesArtifactsAndDeletesRow() {
        QtAppUpdate update = new QtAppUpdate();
        update.setId(7L);
        when(updateMapper.selectById(7L)).thenReturn(update);

        controller.deleteUpdate(7L);

        verify(artifacts).replaceAll(eq(7L), argThat(list -> list == null || list.isEmpty()));
        verify(updateMapper).deleteById(7L);
        verify(appService).evictUpdateCache();
    }

    @Test
    void deleteUpdateRejectsMissingVersion() {
        when(updateMapper.selectById(99L)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class, () -> controller.deleteUpdate(99L));

        assertEquals(300, error.getCode().intValue());
        verify(updateMapper, never()).deleteById(anyLong());
        verify(artifacts, never()).replaceAll(anyLong(), anyList());
    }

    @Test
    void changeUserStateRejectsNonAppUser() {
        User admin = new User();
        admin.setId(3L);
        admin.setUserType("ADMIN");
        when(userMapper.selectById(3L)).thenReturn(admin);

        assertThrows(BusinessException.class, () -> controller.changeUserState(3L, 0));

        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void changeUserStateAllowsAppUser() {
        User appUser = new User();
        appUser.setId(5L);
        appUser.setUserType("APP");
        when(userMapper.selectById(5L)).thenReturn(appUser);
        when(userMapper.update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(1);

        try (var sessions = mockStatic(cn.dev33.satoken.stp.StpUtil.class)) {
            controller.changeUserState(5L, 0);
        }

        verify(userMapper).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(userMapper, never()).updateById(any(User.class));
    }
}
