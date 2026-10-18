package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageTaskEntity;
import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StorageFileServiceTest {
    private StorageFileMapper mapper;
    private StorageTaskMapper tasks;
    private StorageFolderService folders;
    private WorkerCachePurgeService purger;
    private StorageFileService service;
    private StorageFileEntity file;

    @BeforeEach
    void setUp() {
        mapper = mock(StorageFileMapper.class);
        tasks = mock(StorageTaskMapper.class);
        folders = mock(StorageFolderService.class);
        purger = mock(WorkerCachePurgeService.class);
        var configCache = mock(StorageConfigCache.class);
        service = spy(new StorageFileService(mapper, tasks, configCache, folders, mock(StorageAuditService.class),
                null, null, null, null, null, null, null, null, null, purger));
        file = new StorageFileEntity();
        file.setId(1L);
        file.setPublicId("public-id");
        file.setFolderId(10L);
        file.setProviderType("TELEGRAM");
        file.setVisibility("PRIVATE");
        file.setContentVersion(1L);
        file.setStatus("AVAILABLE");
        doReturn(file).when(service).getByPublicId("public-id");
    }

    @Test
    void adminCanDeleteFilesOwnedByOtherUsersAfterAcquiringLock() {
        service.requestDelete("public-id", "admin", true);
        var order = inOrder(mapper, service);
        order.verify(mapper).lockUploadKey("storage-file:public-id");
        order.verify(service).getByPublicId("public-id");
        verifyNoInteractions(folders);
        assertEquals("DELETING", file.getStatus());
        verify(tasks).insert(any(StorageTaskEntity.class));
    }

    @Test
    void legacyDeleteOverloadStillChecksFolderPermission() {
        doThrow(new BusinessException("STORAGE013")).when(folders).requirePermission("user", 10L, "DELETE");
        assertThrows(BusinessException.class, () -> service.requestDelete("public-id", "user"));
        verifyNoInteractions(tasks);
        verify(mapper, never()).updateById(any(StorageFileEntity.class));
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
    void deletingFileCannotHaveVisibilityChanged() {
        file.setStatus("DELETING");
        assertThrows(BusinessException.class, () -> service.changeVisibility("public-id", "admin", "PUBLIC", true));
        verify(mapper, never()).updateById(any(StorageFileEntity.class));
        assertEquals("PRIVATE", file.getVisibility());
    }

    @Test
    void visibilityChangeTriggersWorkerCachePurge() {
        // 可见性变更递增版本后，必须异步失效旧版本（递增前的 contentVersion=1）的边缘缓存键
        service.changeVisibility("public-id", "admin", "PUBLIC", true);
        verify(purger).purgeFileAsync(isNull(), eq("public-id"), eq(1L), isNull());
    }

    @Test
    void telegramDeleteEnqueuesTaskAndPurgesEdgeCache() {
        service.requestDelete("public-id", "admin", true);
        verify(tasks).insert(any(StorageTaskEntity.class));
        verify(purger).purgeFileAsync(isNull(), eq("public-id"), eq(1L), isNull());
    }
}
