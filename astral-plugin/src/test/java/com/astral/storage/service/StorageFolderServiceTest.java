package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.dto.StorageDtos;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.mapper.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StorageFolderServiceTest {
    private StorageFolderMapper folders;
    private StorageFileMapper files;
    private StorageFolderService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), StorageFolderEntity.class);
        folders = mock(StorageFolderMapper.class);
        files = mock(StorageFileMapper.class);
        service = new StorageFolderService(folders, mock(StorageFolderPermissionMapper.class), mock(StorageConfigMapper.class),
                files, mock(StorageAuditService.class), mock(UploadPolicyService.class));
    }

    @Test
    @SuppressWarnings("rawtypes")
    void renameRootChecksNullParentAndUpdatesEveryDescendantPath() {
        StorageFolderEntity root = folder(1L, null, "old", "/old");
        StorageFolderEntity child = folder(2L, 1L, "child", "/old/child");
        StorageFolderEntity grandchild = folder(3L, 2L, "leaf", "/old/child/leaf");
        when(folders.selectById(1L)).thenReturn(root);
        when(folders.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(folders.selectList(any(Wrapper.class))).thenReturn(List.of(child), List.of(grandchild), List.of());
        service.update(1L, new StorageDtos.FolderUpdateReq("new", null, null, null, null), "user");
        assertEquals("/new", root.getFolderPath());
        assertEquals("/new/child", child.getFolderPath());
        assertEquals("/new/child/leaf", grandchild.getFolderPath());
        ArgumentCaptor<Wrapper> duplicateQuery = ArgumentCaptor.forClass(Wrapper.class);
        verify(folders).selectCount(duplicateQuery.capture());
        assertTrue(duplicateQuery.getValue().getSqlSegment().contains("parent_id IS NULL"));
        verify(folders).updateById(root);
        verify(folders).updateById(child);
        verify(folders).updateById(grandchild);
        verify(files).lockUploadKey("storage-folder-tree");
    }

    @Test
    void duplicateSiblingIsRejectedBeforeUpdatingPaths() {
        when(folders.selectById(1L)).thenReturn(folder(1L, null, "old", "/old"));
        when(folders.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertThrows(BusinessException.class, () -> service.update(1L,
                new StorageDtos.FolderUpdateReq("existing", null, null, null, null), "user"));
        verify(folders, never()).updateById(any(StorageFolderEntity.class));
        verify(folders, never()).selectList(any(Wrapper.class));
    }

    @Test
    void controlCharactersInFolderNamesAreRejected() {
        when(folders.selectById(1L)).thenReturn(folder(1L, null, "old", "/old"));
        assertThrows(BusinessException.class, () -> service.update(1L,
                new StorageDtos.FolderUpdateReq("bad\nname", null, null, null, null), "user"));
        verify(folders, never()).updateById(any(StorageFolderEntity.class));
    }

    private StorageFolderEntity folder(Long id, Long parentId, String name, String path) {
        StorageFolderEntity result = new StorageFolderEntity();
        result.setId(id);
        result.setParentId(parentId);
        result.setFolderName(name);
        result.setFolderPath(path);
        return result;
    }
}
