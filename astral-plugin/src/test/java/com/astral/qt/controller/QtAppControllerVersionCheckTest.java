package com.astral.qt.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.astral.auth.security.DataScopeResolver;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.qt.entity.QtAppUpdate;
import com.astral.qt.service.QtAppService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 覆盖版本校验接口的结果级权限脱敏（D-QT-02）：
 * 未发布或调用者无对应测试渠道时，只确认「是否官方」，不泄漏下载指针。
 */
class QtAppControllerVersionCheckTest {

    private QtAppController controller;
    private QtAppService appService;
    private DataScopeResolver dataScopeResolver;

    @BeforeEach
    void setUp() {
        controller = new QtAppController();
        appService = mock(QtAppService.class);
        dataScopeResolver = mock(DataScopeResolver.class);
        ReflectionTestUtils.setField(controller, "appService", appService);
        ReflectionTestUtils.setField(controller, "dataScopeResolver", dataScopeResolver);
        when(dataScopeResolver.resolveChannelsByToken(any(), any())).thenReturn(Set.of("stable"));
    }

    @Test
    void unpublishedVersionHasDownloadPointersStripped() {
        when(appService.getOfficialVersion(1101L, "2", "2.0", null)).thenReturn(version(false, "stable"));

        Result<QtAppUpdate> result = controller.checkVersion(1101L, "2", "2.0", null, null);

        QtAppUpdate data = result.getData();
        assertNull(data.getDownloadUrl());
        assertNull(data.getBrowserUrl());
        assertNull(data.getMd5());
        assertEquals(0L, data.getIsGithub());
    }

    @Test
    void publishedStableVersionKeepsDownloadPointers() {
        when(appService.getOfficialVersion(1101L, "2", "2.0", null)).thenReturn(version(true, "stable"));

        Result<QtAppUpdate> result = controller.checkVersion(1101L, "2", "2.0", null, null);

        assertEquals("https://example.test/app.apk", result.getData().getDownloadUrl());
    }

    @Test
    void publishedBetaWithoutChannelIsStripped() {
        when(appService.getOfficialVersion(1101L, "2", "2.0", null)).thenReturn(version(true, "beta"));

        Result<QtAppUpdate> result = controller.checkVersion(1101L, "2", "2.0", null, "token");

        assertNull(result.getData().getDownloadUrl());
    }

    @Test
    void missingVersionStillThrows() {
        when(appService.getOfficialVersion(1101L, "2", "2.0", null)).thenReturn(null);

        BusinessException error =
                assertThrows(BusinessException.class, () -> controller.checkVersion(1101L, "2", "2.0", null, null));

        assertEquals(321, error.getCode().intValue());
    }

    private static QtAppUpdate version(boolean published, String channel) {
        QtAppUpdate update = new QtAppUpdate();
        update.setVersionCode(2L);
        update.setVersionName("2.0");
        update.setChannel(channel);
        update.setIsPublished(published ? 1 : 0);
        update.setDownloadUrl("https://example.test/app.apk");
        update.setBrowserUrl("https://example.test/app");
        update.setMd5("md5");
        update.setFileSize(123L);
        update.setIsGithub(1L);
        return update;
    }
}
