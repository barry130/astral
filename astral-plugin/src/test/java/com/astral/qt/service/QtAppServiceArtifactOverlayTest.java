package com.astral.qt.service;

import com.astral.qt.entity.QtAppUpdate;
import com.astral.qt.entity.QtAppUpdateArtifact;
import com.astral.qt.mapper.QtAppUpdateMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QtAppServiceArtifactOverlayTest {
    private QtAppService service;
    private QtAppUpdateArtifactService artifacts;
    private QtAppUpdate update;

    @BeforeEach
    void setUp() {
        QtAppUpdateMapper mapper = mock(QtAppUpdateMapper.class);
        artifacts = spy(new QtAppUpdateArtifactService());
        service = new QtAppService();
        ReflectionTestUtils.setField(service, "updateMapper", mapper);
        ReflectionTestUtils.setField(service, "artifactService", artifacts);
        update = new QtAppUpdate();
        update.setId(42L);
        update.setPlatforms("1101,1106");
        update.setVersionCode(2L);
        update.setVersionName("2.0");
        update.setChannel("stable");
        update.setIsPublished(1);
        update.setDownloadUrl("https://example.test/old-android.apk");
        update.setBrowserUrl("https://example.test/old-browser");
        update.setIsGithub(1L);
        update.setFileSize(100L);
        update.setMd5("old-md5");
        when(mapper.selectList(nullable(Wrapper.class))).thenReturn(List.of(update));
    }

    @Test
    void browserOnlyPlatformArtifactClearsOldDirectLinkAndGithubFlag() {
        QtAppUpdateArtifact harmony = new QtAppUpdateArtifact();
        harmony.setPlatform(1106L);
        harmony.setBrowserUrl("https://example.test/harmony");
        setArtifacts(harmony);
        QtAppUpdate response = service.getUpdate(1106L, "1", Set.of("stable"), null);
        assertNotNull(response);
        assertNull(response.getDownloadUrl());
        assertEquals("https://example.test/harmony", response.getBrowserUrl());
        assertEquals(0L, response.getIsGithub());
        assertNull(response.getMd5());
        assertNull(response.getFileSize());
        assertEquals(1, response.getArtifacts().size());
    }

    @Test
    void directOnlyPlatformArtifactClearsOldBrowserLink() {
        QtAppUpdateArtifact harmony = new QtAppUpdateArtifact();
        harmony.setPlatform(1106L);
        harmony.setDownloadUrl("https://example.test/harmony.hap");
        harmony.setIsGithub(1L);
        harmony.setMd5("harmony-md5");
        harmony.setFileSize(200L);
        setArtifacts(harmony);
        QtAppUpdate response = service.getUpdate(1106L, "1", Set.of("stable"), null);
        assertNotNull(response);
        assertEquals("https://example.test/harmony.hap", response.getDownloadUrl());
        assertNull(response.getBrowserUrl());
        assertEquals(1L, response.getIsGithub());
        assertEquals("harmony-md5", response.getMd5());
        assertEquals(200L, response.getFileSize());
    }

    @Test
    void exactPlatformArtifactWinsOverGenericFallback() {
        QtAppUpdateArtifact generic = new QtAppUpdateArtifact();
        generic.setDownloadUrl("https://example.test/generic");
        QtAppUpdateArtifact harmony = new QtAppUpdateArtifact();
        harmony.setPlatform(1106L);
        harmony.setBrowserUrl("https://example.test/harmony");
        setArtifacts(generic, harmony);
        QtAppUpdate response = service.getUpdate(1106L, "1", Set.of("stable"), null);
        assertNotNull(response);
        assertNull(response.getDownloadUrl());
        assertEquals("https://example.test/harmony", response.getBrowserUrl());
        assertEquals(2, response.getArtifacts().size());
    }

    @Test
    void officialVersionResponseAlsoClearsStaleLinks() {
        QtAppUpdateArtifact harmony = new QtAppUpdateArtifact();
        harmony.setPlatform(1106L);
        harmony.setBrowserUrl("https://example.test/harmony");
        setArtifacts(harmony);
        QtAppUpdate response = service.getOfficialVersion(1106L, "2", "2.0", null);
        assertNotNull(response);
        assertNull(response.getDownloadUrl());
        assertEquals("https://example.test/harmony", response.getBrowserUrl());
        assertEquals(0L, response.getIsGithub());
    }

    private void setArtifacts(QtAppUpdateArtifact... items) {
        doReturn(List.of(items)).when(artifacts).listByUpdateId(42L);
    }
}
