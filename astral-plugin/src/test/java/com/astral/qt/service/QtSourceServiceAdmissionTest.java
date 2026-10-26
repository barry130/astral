package com.astral.qt.service;

import com.astral.system.service.DictDataService;
import com.astral.qt.entity.QtSourceRelease;
import com.astral.qt.mapper.QtSourceReleaseMapper;
import com.astral.qt.mapper.QtSourceReportMapper;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 应用版本准入（{@link QtSourceService#admissionOk}）的行为契约：
 * <ul>
 *   <li>平台缺省/空数组 = 不限制；</li>
 *   <li>配置了白名单时，命中的 appVersionCode 通过；</li>
 *   <li><b>appVersionCode 未上报（null）也放行</b>——客户端契约是「版本号取不到时宁多给包，
 *       也不能谁都拿不到」，且鸿蒙端 {@code getAppBaseInfo().appVersionCode} 官方不支持，
 *       缺参数是合法形态，绝不能把整张 manifest 挡成空。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class QtSourceServiceAdmissionTest {

    @Mock
    private QtSourceReleaseMapper releaseMapper;

    @Mock
    private QtSourceReportMapper reportMapper;

    @Mock
    private DictDataService dictDataService;

    @Mock
    private QtPackHeaderProbe packHeaderProbe;

    private QtSourceService service;

    @BeforeEach
    void setUp() {
        service = new QtSourceService(releaseMapper, reportMapper, dictDataService, new ObjectMapper(),
                packHeaderProbe);
    }

    private QtSourceRelease release(String appVersionCodes) {
        QtSourceRelease r = new QtSourceRelease();
        r.setId(1L);
        r.setSourceVersionCode(2026102601L);
        r.setSourceVersionName("2026.10.26.1");
        r.setPlatforms(QtSourceRelease.PLATFORM_ANDROID + "," + QtSourceRelease.PLATFORM_HARMONY);
        r.setHostApiVersion(1000L);
        r.setChannel(QtSourceRelease.CHANNEL_STABLE);
        r.setNotes("admission 测试");
        r.setAppVersionCodes(appVersionCodes);
        r.setArtifacts("[]");
        r.setIsBad(0);
        r.setIsPublished(1);
        r.setPublishedAt(LocalDateTime.now());
        return r;
    }

    @Test
    @DisplayName("appVersionCode 未上报（null）时仍放行——鸿蒙读不到版本号是合法形态")
    void admissionAllowsWhenAppVersionCodeOmitted() {
        when(releaseMapper.selectList(any())).thenReturn(List.of(release("{\"1106\":[308]}")));

        // 鸿蒙端读不到 appVersionCode：请求不带该参数
        assertNotNull(service.buildManifest(QtSourceRelease.PLATFORM_HARMONY, null, 1000L,
                Set.of(QtSourceRelease.CHANNEL_STABLE)).getRelease());
        assertEquals(2026102601L,
                service.buildManifest(QtSourceRelease.PLATFORM_HARMONY, null, 1000L,
                        Set.of(QtSourceRelease.CHANNEL_STABLE)).getRelease().getSourceVersionCode());
    }

    @Test
    @DisplayName("配置了白名单时，命中的 appVersionCode 通过")
    void admissionAllowsWhenVersionInWhitelist() {
        when(releaseMapper.selectList(any())).thenReturn(List.of(release("{\"1106\":[308]}")));

        assertNotNull(service.buildManifest(QtSourceRelease.PLATFORM_HARMONY, 308L, 1000L,
                Set.of(QtSourceRelease.CHANNEL_STABLE)).getRelease());
    }

    @Test
    @DisplayName("配置了白名单时，未命中的 appVersionCode 仍被拒（白名单本身不回归）")
    void admissionRejectsWhenVersionNotInWhitelist() {
        when(releaseMapper.selectList(any())).thenReturn(List.of(release("{\"1106\":[308]}")));

        assertNull(service.buildManifest(QtSourceRelease.PLATFORM_HARMONY, 200L, 1000L,
                Set.of(QtSourceRelease.CHANNEL_STABLE)).getRelease());
    }

    @Test
    @DisplayName("平台未配置白名单 = 不限制，任意版本号（含 null）都通过")
    void admissionAllowsWhenPlatformUnconfigured() {
        when(releaseMapper.selectList(any())).thenReturn(List.of(release("{}")));

        assertNotNull(service.buildManifest(QtSourceRelease.PLATFORM_ANDROID, 999L, 1000L,
                Set.of(QtSourceRelease.CHANNEL_STABLE)).getRelease());
        assertNotNull(service.buildManifest(QtSourceRelease.PLATFORM_ANDROID, null, 1000L,
                Set.of(QtSourceRelease.CHANNEL_STABLE)).getRelease());
    }
}
