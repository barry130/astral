package com.astral.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.astral.common.exception.BusinessException;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageTaskEntity;
import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageTaskMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StorageFileServiceTest {
    private StorageFileMapper mapper;
    private StorageTaskMapper tasks;
    private StorageFolderService folders;
    private StorageConfigCache configCache;
    private UploadPolicyService policyService;
    private WorkerCachePurgeService purger;
    private StorageRemoteDeleteService remoteDelete;
    private StorageAuditService audit;
    private StorageFileService service;
    private StorageFileEntity file;

    @BeforeEach
    void setUp() {
        var metadata = new MapperBuilderAssistant(new MybatisConfiguration(), "audit-storage-file-test");
        metadata.setCurrentNamespace(StorageFileMapper.class.getName());
        TableInfoHelper.initTableInfo(metadata, StorageFileEntity.class);
        mapper = mock(StorageFileMapper.class);
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        tasks = mock(StorageTaskMapper.class);
        folders = mock(StorageFolderService.class);
        purger = mock(WorkerCachePurgeService.class);
        remoteDelete = mock(StorageRemoteDeleteService.class);
        configCache = mock(StorageConfigCache.class);
        policyService = mock(UploadPolicyService.class);
        audit = mock(StorageAuditService.class);
        service = spy(new StorageFileService(
                mapper,
                tasks,
                configCache,
                folders,
                audit,
                null,
                policyService,
                null,
                null,
                null,
                null,
                null,
                remoteDelete,
                null,
                null,
                purger));
        file = new StorageFileEntity();
        file.setId(1L);
        file.setPublicId("public-id");
        file.setFolderId(10L);
        file.setProviderType("TELEGRAM");
        file.setVisibility("PRIVATE");
        file.setContentVersion(1L);
        file.setStatus("AVAILABLE");
        doReturn(file).when(service).getByPublicId("public-id");
        when(mapper.deleteById(1L)).thenReturn(1);
    }

    @Test
    void adminCanDeleteFilesOwnedByOtherUsersAfterAcquiringLock() {
        service.requestDelete("public-id", "admin", true);
        var order = inOrder(mapper, service);
        order.verify(mapper).lockUploadKey("storage-file:public-id");
        order.verify(service).getByPublicId("public-id");
        verifyNoInteractions(folders);
        // 新语义：库行立即硬删，不再落 DELETING 中间态
        verify(mapper).deleteById(1L);
        verify(tasks).insert(any(StorageTaskEntity.class));
    }

    @Test
    void legacyDeleteOverloadStillChecksFolderPermission() {
        doThrow(new BusinessException("STORAGE013")).when(folders).requirePermission("user", 10L, "DELETE");
        assertThrows(BusinessException.class, () -> service.requestDelete("public-id", "user"));
        verifyNoInteractions(tasks);
        verify(mapper, never()).deleteById(any(Long.class));
    }

    @Test
    void adminVisibilityChangeLocksBeforeReadingAndIncrementsVersion() {
        service.changeVisibility("public-id", "admin", "PUBLIC", true);
        var order = inOrder(mapper, service);
        order.verify(mapper).lockUploadKey("storage-file:public-id");
        order.verify(service).getByPublicId("public-id");
        verifyNoInteractions(folders);
        assertEquals("PUBLIC", file.getVisibility());
        assertEquals(2L, file.getContentVersion());
    }

    @Test
    void nonAvailableFileCannotHaveVisibilityChanged() {
        file.setStatus("FAILED");
        assertThrows(BusinessException.class, () -> service.changeVisibility("public-id", "admin", "PUBLIC", true));
        verify(mapper, never()).updateById(any(StorageFileEntity.class));
        assertEquals("PRIVATE", file.getVisibility());
    }

    @Test
    void lostVisibilityCasDoesNotMutateMemoryPurgeCacheOrRecordSuccess() {
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        BusinessException ex = assertThrows(
                BusinessException.class, () -> service.changeVisibility("public-id", "admin", "PUBLIC", true));

        assertEquals(300, ex.getCode());
        assertEquals("PRIVATE", file.getVisibility());
        assertEquals(1L, file.getContentVersion());
        verifyNoInteractions(purger, audit);
        verify(mapper, never()).updateById(any(StorageFileEntity.class));
    }

    @Test
    void visibilityVersionOverflowFailsBeforeAnyMutation() {
        file.setContentVersion(Long.MAX_VALUE);

        assertThrows(BusinessException.class, () -> service.changeVisibility("public-id", "admin", "PUBLIC", true));

        assertEquals("PRIVATE", file.getVisibility());
        assertEquals(Long.MAX_VALUE, file.getContentVersion());
        verify(mapper, never()).updateById(any(StorageFileEntity.class));
        verify(mapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoInteractions(purger, audit);
    }

    @Test
    void zeroRowDeleteFailsBeforeAnyRemoteCleanupOrSuccessAudit() {
        file.setProviderType("R2");
        when(mapper.deleteById(1L)).thenReturn(0);

        BusinessException ex =
                assertThrows(BusinessException.class, () -> service.requestDelete("public-id", "admin", true));

        assertEquals("STORAGE021", ex.getErrorCode());
        verifyNoInteractions(remoteDelete, tasks, purger, audit);
    }

    @Test
    void visibilityChangeTriggersWorkerCachePurge() {
        // 可见性变更递增版本后，必须异步失效旧版本（递增前的 contentVersion=1）的边缘缓存键
        service.changeVisibility("public-id", "admin", "PUBLIC", true);
        verify(purger).purgeFileAsync(isNull(), eq("public-id"), eq(1L), isNull());
    }

    @Test
    void verificationUsesAtomicRejectionAndSkipsCleanupWhenFileAlreadyChanged() {
        file.setProviderType("CUSTOM_HTTP");
        file.setStorageConfigId(9L);
        StorageConfigEntity config = new StorageConfigEntity();
        config.setId(9L);
        config.setStatus(StorageConfigEntity.STATUS_ENABLED);
        when(configCache.getById(9L)).thenReturn(config);
        when(policyService.resolve(any()))
                .thenReturn(new UploadPolicyService.PolicySpec(
                        true,
                        0,
                        null,
                        java.util.List.of(),
                        java.util.List.of(),
                        null,
                        null,
                        UploadPolicyService.VERIFY_MAGIC,
                        null));
        when(policyService.rejectLegacyFile(1L)).thenReturn(false);

        BusinessException ex =
                assertThrows(BusinessException.class, () -> service.verifyContentAfterRegistration("public-id"));

        assertEquals("STORAGE019", ex.getErrorCode(), "CUSTOM_HTTP 无浏览器登记后的拉取验证实现");
        verify(policyService).rejectLegacyFile(1L);
        verify(mapper, never()).updateById(any(StorageFileEntity.class));
        verifyNoInteractions(remoteDelete, tasks);
    }

    @Test
    void telegramDeleteEnqueuesTaskPurgesEdgeCacheAndRemovesRow() {
        service.requestDelete("public-id", "admin", true);
        verify(tasks).insert(any(StorageTaskEntity.class));
        verify(purger).purgeFileAsync(isNull(), eq("public-id"), eq(1L), isNull());
        verify(mapper).deleteById(1L);
        // TELEGRAM 走任务表，不走异步线程池
        verifyNoInteractions(remoteDelete);
    }

    @Test
    void serverManagedDeleteRemovesRowAndDispatchesAsyncRemoteCleanup() {
        file.setProviderType("R2");
        service.requestDelete("public-id", "admin", true);
        verify(mapper).deleteById(1L);
        // 远端清理异步调度（无事务上下文直接派发），失败只在异步侧留痕
        verify(remoteDelete).deleteQuietly(eq("public-id"), eq("R2"), isNull(), isNull(), eq("admin"));
        verifyNoInteractions(tasks);
        verifyNoInteractions(purger);
    }

    @Test
    void customHttpDeleteOnlyRemovesRow() {
        file.setProviderType("CUSTOM_HTTP");
        service.requestDelete("public-id", "admin", true);
        verify(mapper).deleteById(1L);
        // 图床无远端删除接口：无任务、无异步远端清理
        verifyNoInteractions(tasks);
        verifyNoInteractions(remoteDelete);
        verifyNoInteractions(purger);
    }

    @Test
    void browserRegisterIdempotentReturnRejectsDifferentUploader() {
        StorageFileEntity existing = new StorageFileEntity();
        existing.setUploadId("up-1");
        existing.setUploaderId("owner");
        when(mapper.selectOne(any())).thenReturn(existing);

        BusinessException ex = assertThrows(
                BusinessException.class, () -> service.registerFromBrowser("up-1", "intruder", "127.0.0.1"));

        assertEquals("STORAGE013", ex.getErrorCode());
        verify(mapper).lockUploadKey("storage-upload:up-1");
    }

    @Test
    void browserRegisterIdempotentReturnAllowsSameUploader() {
        StorageFileEntity existing = new StorageFileEntity();
        existing.setUploadId("up-1");
        existing.setUploaderId("owner");
        when(mapper.selectOne(any())).thenReturn(existing);

        StorageFileEntity result = service.registerFromBrowser("up-1", "owner", "127.0.0.1");

        assertSame(existing, result);
    }
}

// ==================== SVG 检测（存储型 XSS 的第一道闸） ====================

class StorageFileServiceSvgTest {

    private static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void plainSvgIsDetected() {
        assertTrue(StorageFileService.looksLikeSvg(
                utf8("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>")));
    }

    @Test
    void svgWithDeclarationCommentAndDoctypeIsDetected() {
        String svg =
                "<?xml version=\"1.0\"?>\n<!-- figure -->\n<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"x\">\n<SVG onload=\"alert(1)\" xmlns=\"http://www.w3.org/2000/svg\"/>";
        assertTrue(StorageFileService.looksLikeSvg(utf8(svg)));
    }

    @Test
    void svgWithBomIsDetected() {
        assertTrue(StorageFileService.looksLikeSvg(utf8("\uFEFF<svg/>")));
    }

    @Test
    void binaryImagesAreNotSvg() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
        assertFalse(StorageFileService.looksLikeSvg(png));
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertFalse(StorageFileService.looksLikeSvg(jpeg));
        assertFalse(StorageFileService.looksLikeSvg(null));
        assertFalse(StorageFileService.looksLikeSvg(new byte[0]));
    }

    @Test
    void htmlIsNotMistakenForSvg() {
        assertFalse(StorageFileService.looksLikeSvg(utf8("<html><body>hi</body></html>")));
    }

    @Test
    void unterminatedDeclarationIsNotSvg() {
        assertFalse(StorageFileService.looksLikeSvg(utf8("<?xml version=\"1.0\"")));
    }
}
