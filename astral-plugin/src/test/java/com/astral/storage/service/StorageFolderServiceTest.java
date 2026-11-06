package com.astral.storage.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.astral.common.exception.BusinessException;
import com.astral.storage.dto.StorageDtos;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.entity.StorageFolderConfigEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.entity.StorageFolderPermissionEntity;
import com.astral.storage.entity.StoragePermissionConfigEntity;
import com.astral.storage.mapper.StorageConfigMapper;
import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageFolderConfigMapper;
import com.astral.storage.mapper.StorageFolderMapper;
import com.astral.storage.mapper.StorageFolderPermissionMapper;
import com.astral.storage.mapper.StoragePermissionConfigMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class StorageFolderServiceTest {
    private StorageFolderMapper folders;
    private StorageFileMapper files;
    private StorageFolderConfigMapper folderConfigs;
    private StoragePermissionConfigMapper grantConfigs;
    private StorageConfigMapper configs;
    private StorageFolderService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), StorageFolderEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), StorageFolderConfigEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), StorageFolderPermissionEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), StoragePermissionConfigEntity.class);
        folders = mock(StorageFolderMapper.class);
        files = mock(StorageFileMapper.class);
        folderConfigs = mock(StorageFolderConfigMapper.class);
        grantConfigs = mock(StoragePermissionConfigMapper.class);
        configs = mock(StorageConfigMapper.class);
        service = new StorageFolderService(
                folders,
                folderConfigs,
                mock(StorageFolderPermissionMapper.class),
                grantConfigs,
                configs,
                files,
                mock(StorageAuditService.class),
                mock(UploadPolicyService.class),
                mock(StorageTargetResolver.class));
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
        service.update(1L, new StorageDtos.FolderUpdateReq("new", null, null), "user");
        assertEquals("/new", root.getFolderPath());
        assertEquals("/new/child", child.getFolderPath());
        assertEquals("/new/child/leaf", grandchild.getFolderPath());
        ArgumentCaptor<Wrapper> duplicateQuery = ArgumentCaptor.forClass(Wrapper.class);
        verify(folders).selectCount(duplicateQuery.capture());
        assertTrue(duplicateQuery.getValue().getSqlSegment().contains("parent_id IS NULL"));
        // update 走纯实体 updateById：策略/版本列在实体上兜底后随行写回，
        // 不再叠加 wrapper 显式 set（同列双赋值会被 PostgreSQL 拒绝）。
        verify(folders).updateById(root);
        verify(folders).updateById(child);
        verify(folders).updateById(grandchild);
        verify(files).lockUploadKey("storage-folder-tree");
    }

    @Test
    void duplicateSiblingIsRejectedBeforeUpdatingPaths() {
        when(folders.selectById(1L)).thenReturn(folder(1L, null, "old", "/old"));
        when(folders.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertThrows(
                BusinessException.class,
                () -> service.update(1L, new StorageDtos.FolderUpdateReq("existing", null, null), "user"));
        verify(folders, never()).updateById(any(StorageFolderEntity.class));
        verify(folders, never()).selectList(any(Wrapper.class));
    }

    @Test
    void controlCharactersInFolderNamesAreRejected() {
        when(folders.selectById(1L)).thenReturn(folder(1L, null, "old", "/old"));
        assertThrows(
                BusinessException.class,
                () -> service.update(1L, new StorageDtos.FolderUpdateReq("bad\nname", null, null), "user"));
        verify(folders, never()).updateById(any(StorageFolderEntity.class));
    }

    @Test
    void createBindsConfigsInPriorityOrderAndStartsAtVersionOne() {
        when(folders.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(configs.selectByIds(any())).thenReturn(List.of(enabledConfig(5L), enabledConfig(7L)));
        StorageFolderEntity created = service.create(
                new StorageDtos.FolderCreateReq(
                        null,
                        "docs",
                        null,
                        null,
                        null,
                        List.of(new StorageDtos.BindingReq(5L, 200), new StorageDtos.BindingReq(7L, 100))),
                "u1");
        assertEquals(StorageFolderEntity.STRATEGY_FAILOVER, created.getStorageStrategy());
        assertEquals(1L, created.getTargetVersion());
        InOrder inOrder = inOrder(folderConfigs);
        ArgumentCaptor<StorageFolderConfigEntity> captor = ArgumentCaptor.forClass(StorageFolderConfigEntity.class);
        inOrder.verify(folderConfigs, times(2)).insert(captor.capture());
        List<StorageFolderConfigEntity> rows = captor.getAllValues();
        assertEquals(7L, rows.get(0).getStorageConfigId());
        assertEquals(100, rows.get(0).getPriority());
        assertEquals(5L, rows.get(1).getStorageConfigId());
        assertEquals(200, rows.get(1).getPriority());
    }

    @Test
    void createWithoutBindingsInsertsNoBindingRows() {
        when(folders.selectCount(any(Wrapper.class))).thenReturn(0L);
        StorageFolderEntity created =
                service.create(new StorageDtos.FolderCreateReq(null, "docs", null, null, null, null), "u1");
        assertEquals(StorageFolderEntity.STRATEGY_FAILOVER, created.getStorageStrategy());
        verify(folderConfigs, never()).insert(any(StorageFolderConfigEntity.class));
    }

    @Test
    void createRejectsUnknownOrDisabledConfigBinding() {
        when(folders.selectCount(any(Wrapper.class))).thenReturn(0L);
        BusinessException missing = assertThrows(
                BusinessException.class,
                () -> service.create(
                        new StorageDtos.FolderCreateReq(
                                null, "docs", null, null, null, List.of(new StorageDtos.BindingReq(5L, null))),
                        "u1"));
        assertEquals("STORAGE002", missing.getErrorCode());

        StorageConfigEntity disabled = enabledConfig(5L);
        disabled.setStatus(StorageConfigEntity.STATUS_DISABLED);
        when(configs.selectByIds(any())).thenReturn(List.of(disabled));
        BusinessException off = assertThrows(
                BusinessException.class,
                () -> service.create(
                        new StorageDtos.FolderCreateReq(
                                null, "docs", null, null, null, List.of(new StorageDtos.BindingReq(5L, null))),
                        "u1"));
        assertEquals("STORAGE002", off.getErrorCode());
        verify(folders, never()).insert(any(StorageFolderEntity.class));
    }

    @Test
    void createRejectsDuplicateBindingAndIllegalStrategy() {
        when(folders.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(configs.selectByIds(any())).thenReturn(List.of(enabledConfig(5L)));
        BusinessException duplicate = assertThrows(
                BusinessException.class,
                () -> service.create(
                        new StorageDtos.FolderCreateReq(
                                null,
                                "docs",
                                null,
                                null,
                                null,
                                List.of(new StorageDtos.BindingReq(5L, null), new StorageDtos.BindingReq(5L, 1))),
                        "u1"));
        assertEquals("COMMON002", duplicate.getErrorCode());

        BusinessException illegal = assertThrows(
                BusinessException.class,
                () -> service.create(
                        new StorageDtos.FolderCreateReq(
                                null, "docs", null, null, "BOGUS", List.of(new StorageDtos.BindingReq(5L, null))),
                        "u1"));
        assertEquals("STORAGE058", illegal.getErrorCode());
        verify(folders, never()).insert(any(StorageFolderEntity.class));
    }

    @Test
    void updateReplacesBindingsAndBumpsTargetVersion() {
        StorageFolderEntity folder = targetFolder(1L, null, "old", "/old", 2L);
        when(folders.selectById(1L)).thenReturn(folder);
        when(folders.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(configs.selectByIds(any())).thenReturn(List.of(enabledConfig(5L)));
        when(folderConfigs.selectList(any(Wrapper.class)))
                .thenReturn(List.of(folderBinding(21L, 7L, 100)))
                .thenReturn(List.of(folderBinding(22L, 5L, 100)));

        service.update(
                1L,
                new StorageDtos.FolderUpdateReq(
                        null, null, null, null, null, List.of(new StorageDtos.BindingReq(5L, 100))),
                "u1");

        assertEquals(3L, folder.getTargetVersion());
        verify(folderConfigs).delete(any(Wrapper.class));
        ArgumentCaptor<StorageFolderConfigEntity> captor = ArgumentCaptor.forClass(StorageFolderConfigEntity.class);
        verify(folderConfigs).insert(captor.capture());
        assertEquals(5L, captor.getValue().getStorageConfigId());
    }

    @Test
    void renameOnlyDoesNotBumpTargetVersion() {
        StorageFolderEntity folder = targetFolder(1L, null, "old", "/old", 3L);
        when(folders.selectById(1L)).thenReturn(folder);
        when(folders.selectCount(any(Wrapper.class))).thenReturn(0L);
        service.update(1L, new StorageDtos.FolderUpdateReq("new", null, null), "u1");
        assertEquals(3L, folder.getTargetVersion());
    }

    @Test
    void targetAndVisibilityChangesBumpTargetVersion() {
        StorageFolderEntity folder = targetFolder(1L, null, "old", "/old", 3L);
        when(folders.selectById(1L)).thenReturn(folder);
        when(folders.selectCount(any(Wrapper.class))).thenReturn(0L);

        service.update(1L, new StorageDtos.FolderUpdateReq(null, "PUBLIC", null, null, null, null), "u1");
        assertEquals(4L, folder.getTargetVersion());

        service.update(1L, new StorageDtos.FolderUpdateReq(null, "PUBLIC", null, null, null, null), "u1");
        assertEquals(4L, folder.getTargetVersion());
    }

    @Test
    void savePermissionsStoresGrantSubsetBindingsAndBumpsVersion() {
        StorageFolderEntity folder = targetFolder(1L, null, "docs", "/docs", 3L);
        when(folders.selectById(1L)).thenReturn(folder);
        // 文件夹绑定配置 7，授权行绑定其子集 {7}
        when(folderConfigs.selectList(any(Wrapper.class))).thenReturn(List.of(folderBinding(21L, 7L, 100)));
        when(configs.selectByIds(any())).thenReturn(List.of(enabledConfig(7L)));

        service.savePermissions(
                1L,
                List.of(new StorageDtos.FolderPermRow(
                        "USER", "u9", "UPLOAD", List.of(new StorageDtos.BindingReq(7L, 20)))),
                "admin");

        ArgumentCaptor<StoragePermissionConfigEntity> captor =
                ArgumentCaptor.forClass(StoragePermissionConfigEntity.class);
        verify(grantConfigs).insert(captor.capture());
        assertEquals(7L, captor.getValue().getStorageConfigId());
        assertEquals(20, captor.getValue().getPriority());
        verify(folders).updateById(any(StorageFolderEntity.class));
    }

    @Test
    void savePermissionsRejectsGrantConfigOutsideFolderBindings() {
        when(folders.selectById(1L)).thenReturn(folder(1L, null, "docs", "/docs"));
        when(folderConfigs.selectList(any(Wrapper.class))).thenReturn(List.of(folderBinding(21L, 7L, 100)));
        when(configs.selectByIds(any())).thenReturn(List.of(enabledConfig(5L)));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.savePermissions(
                        1L,
                        List.of(new StorageDtos.FolderPermRow(
                                "USER", "u9", "UPLOAD", List.of(new StorageDtos.BindingReq(5L, null)))),
                        "admin"));
        assertEquals("STORAGE058", ex.getErrorCode());
        verify(grantConfigs, never()).insert(any(StoragePermissionConfigEntity.class));
    }

    private StorageConfigEntity enabledConfig(Long id) {
        StorageConfigEntity config = new StorageConfigEntity();
        config.setId(id);
        config.setName("c" + id);
        config.setStatus(StorageConfigEntity.STATUS_ENABLED);
        return config;
    }

    private StorageFolderConfigEntity folderBinding(Long id, Long configId, int priority) {
        StorageFolderConfigEntity row = new StorageFolderConfigEntity();
        row.setId(id);
        row.setFolderId(1L);
        row.setStorageConfigId(configId);
        row.setPriority(priority);
        return row;
    }

    private StorageFolderEntity targetFolder(Long id, Long parentId, String name, String path, Long targetVersion) {
        StorageFolderEntity folder = folder(id, parentId, name, path);
        folder.setStorageStrategy(StorageFolderEntity.STRATEGY_FAILOVER);
        folder.setTargetVersion(targetVersion);
        folder.setVisibility(StorageFolderEntity.VISIBILITY_PRIVATE);
        folder.setStatus(StorageFolderEntity.STATUS_ENABLED);
        return folder;
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
