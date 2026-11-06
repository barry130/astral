package com.astral.storage.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.astral.common.exception.BusinessException;
import com.astral.storage.dto.StorageDtos;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageFolderConfigEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.mapper.StorageFileMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class FileManagerServiceTest {
    private StorageFileMapper mapper;
    private StorageFileService files;
    private StorageFolderService folders;
    private FileManagerService manager;
    private StorageFileEntity file;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), StorageFileEntity.class);
        mapper = mock(StorageFileMapper.class);
        files = mock(StorageFileService.class);
        folders = mock(StorageFolderService.class);
        manager = new FileManagerService(mapper, files, folders, mock(StorageAuditService.class), new ObjectMapper());
        file = new StorageFileEntity();
        file.setId(1L);
        file.setPublicId("public-id");
        file.setFolderId(10L);
        file.setStorageConfigId(5L);
        file.setOriginalName("old.pdf");
        file.setStatus(StorageFileEntity.STATUS_AVAILABLE);
        file.setContentVersion(3L);
        file.setProviderLocatorJson("{\"objectKey\":\"unchanged\"}");
        when(files.getByPublicId("public-id")).thenReturn(file);
    }

    @Test
    void noReadableFoldersReturnsEmptyWithoutUnrestrictedQuery() {
        when(folders.listForUser("user"))
                .thenReturn(List.of(new StorageDtos.FolderMineView(
                        10L,
                        "uploads",
                        "/uploads",
                        "PRIVATE",
                        "ENABLED",
                        "UPLOAD",
                        null,
                        List.of(),
                        null,
                        null,
                        null)));
        var page = manager.page("user", false, 1, 24, new StorageDtos.FileQuery(null, null, null, null, null));
        assertEquals(0, page.getTotal());
        verifyNoInteractions(mapper);
    }

    @Test
    void explicitFolderCannotBypassReadPermission() {
        doThrow(new BusinessException("STORAGE013")).when(folders).requirePermission("user", 10L, "READ");
        assertThrows(
                BusinessException.class,
                () -> manager.page("user", false, 1, 24, new StorageDtos.FileQuery(10L, null, null, null, null)));
        verifyNoInteractions(mapper);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void allFilesQueryIncludesOnlyReadableFoldersAndHidesLocator() {
        when(folders.listForUser("user"))
                .thenReturn(List.of(
                        new StorageDtos.FolderMineView(
                                10L, "mine", "/mine", "PRIVATE", "ENABLED", "ALL", null, List.of(), null, null, null),
                        new StorageDtos.FolderMineView(
                                20L, "drop", "/drop", "PRIVATE", "ENABLED", "UPLOAD", null, List.of(), null, null,
                                null)));
        doAnswer(invocation -> {
                    Page<StorageFileEntity> page = invocation.getArgument(0);
                    return page.setRecords(List.of(file)).setTotal(1);
                })
                .when(mapper)
                .selectPage(any(Page.class), any(Wrapper.class));
        var result = manager.page("user", false, 1, 1000, new StorageDtos.FileQuery(null, null, null, null, null));
        ArgumentCaptor<Wrapper> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(Page.class), query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("folder_id IN"));
        var parameters = ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) query.getValue())
                .getParamNameValuePairs()
                .values();
        assertTrue(parameters.contains(10L));
        assertFalse(parameters.contains(20L));
        assertEquals(100, result.getSize());
        assertNull(result.getRecords().getFirst().getProviderLocatorJson());
    }

    @Test
    void crossStorageMoveIsRejectedBeforeAnyWrite() {
        StorageFolderEntity target = new StorageFolderEntity();
        target.setId(20L);
        target.setStatus("ENABLED");
        when(folders.getById(20L)).thenReturn(target);
        // 目标文件夹仅绑定配置 6：文件在配置 5 上，属跨存储移动，须在写库前拒绝
        when(folders.orderedBindings(20L)).thenReturn(List.of(binding(6L)));
        assertThrows(
                BusinessException.class,
                () -> manager.update("public-id", new StorageDtos.FileUpdateReq(null, 20L), "user", false));
        verify(folders).requirePermission("user", 10L, "UPDATE");
        verify(folders).requirePermission("user", 20L, "UPLOAD");
        verify(mapper, never()).update(nullable(StorageFileEntity.class), any(Wrapper.class));
        assertEquals(10L, file.getFolderId());
    }

    @Test
    @SuppressWarnings("rawtypes")
    void renameAndMoveUpdateOnlyMetadataAndKeepPublicIdentity() {
        StorageFolderEntity target = new StorageFolderEntity();
        target.setId(20L);
        target.setStatus("ENABLED");
        when(folders.getById(20L)).thenReturn(target);
        // 目标文件夹绑定配置 5：与文件同配置，允许移动
        when(folders.orderedBindings(20L)).thenReturn(List.of(binding(5L)));
        when(mapper.update(nullable(StorageFileEntity.class), any(Wrapper.class)))
                .thenReturn(1);
        var result = manager.update("public-id", new StorageDtos.FileUpdateReq(" new.pdf ", 20L), "admin", true);
        ArgumentCaptor<Wrapper> update = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), update.capture());
        String sql = update.getValue().getSqlSet();
        assertTrue(sql.contains("original_name="));
        assertTrue(sql.contains("folder_id="));
        assertFalse(sql.contains("provider_locator"));
        assertFalse(sql.contains("content_version"));
        assertEquals("public-id", result.getPublicId());
        assertEquals(3L, result.getContentVersion());
        assertEquals("new.pdf", result.getOriginalName());
        assertEquals(20L, result.getFolderId());
        verify(folders, never()).requirePermission(any(), any(), any());
    }

    @Test
    void newlineInFileNameIsRejectedBeforeWrite() {
        assertThrows(
                BusinessException.class,
                () -> manager.update("public-id", new StorageDtos.FileUpdateReq("a\nb.pdf", null), "user", false));
        verify(mapper, never()).update(nullable(StorageFileEntity.class), any(Wrapper.class));
    }

    private StorageFolderConfigEntity binding(Long configId) {
        StorageFolderConfigEntity row = new StorageFolderConfigEntity();
        row.setFolderId(20L);
        row.setStorageConfigId(configId);
        row.setPriority(100);
        return row;
    }
}
