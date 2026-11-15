package com.astral.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.astral.common.exception.BusinessException;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.entity.StorageFolderConfigEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.entity.StorageFolderPermissionEntity;
import com.astral.storage.entity.StoragePermissionConfigEntity;
import com.astral.storage.mapper.StorageConfigMapper;
import com.astral.storage.mapper.StorageFolderConfigMapper;
import com.astral.storage.mapper.StorageFolderPermissionMapper;
import com.astral.storage.mapper.StoragePermissionConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Qt 直传的实际入口：签发前按目录持久化设置解析候选，不调用解析器私有方法。 */
class StorageFolderUploadAclTest {
    private final StorageConfigMapper configs = mock(StorageConfigMapper.class);
    private final StorageFolderConfigMapper bindings = mock(StorageFolderConfigMapper.class);
    private final StorageFolderPermissionMapper permissions = mock(StorageFolderPermissionMapper.class);
    private final StoragePermissionConfigMapper permissionConfigs = mock(StoragePermissionConfigMapper.class);
    private final StorageTargetResolver resolver = new StorageTargetResolver(
            configs, bindings, permissions, permissionConfigs, mock(TargetSelectionCursor.class), new ObjectMapper());
    private final StorageTicketTargetService service = new StorageTicketTargetService(resolver, configs);

    @Test
    void uploadWithoutRequiredGrantsUsesTheFolderTargetsWithoutReadingGrantRows() {
        StorageFolderEntity folder = folder(false);
        StorageFolderConfigEntity binding = new StorageFolderConfigEntity();
        binding.setStorageConfigId(5L);
        binding.setPriority(1);
        when(bindings.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(binding));
        StorageConfigEntity config = config();
        when(configs.selectByIds(any())).thenReturn(List.of(config));
        when(configs.selectBatchIds(any())).thenReturn(List.of(config));
        when(configs.selectById(5L)).thenReturn(config);

        StorageConfigEntity selected = service.requireDirectUploadConfig(folder, "ordinary-user");

        assertEquals(5L, selected.getId());
        verifyNoInteractions(permissions, permissionConfigs);
    }

    @Test
    void requiredUploadGrantsStillRejectAnOrdinaryUser() {
        StorageFolderEntity folder = folder(true);
        givenValidTarget();
        when(permissions.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        BusinessException ex =
                assertThrows(BusinessException.class, () -> service.requireDirectUploadConfig(folder, "ordinary-user"));

        assertEquals("STORAGE013", ex.getErrorCode());
        verify(permissions).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void uploadAclDisabledAlsoSkipsTheGrantSubsetInFinalRecheck() {
        StorageFolderEntity folder = folder(false);
        givenValidTarget();

        var selected = resolver.requireCurrentCandidate(folder, "ordinary-user", false, 11L, 1L, 5L, 1L);

        assertEquals(5L, selected.configId());
        verifyNoInteractions(permissions, permissionConfigs);
    }

    @Test
    void requiredUploadGrantsStillRejectFinalRecheck() {
        StorageFolderEntity folder = folder(true);
        givenValidTarget();
        when(permissions.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> resolver.requireCurrentCandidate(folder, "ordinary-user", false, 11L, 1L, 5L, 1L));

        assertEquals("STORAGE013", ex.getErrorCode());
        verify(permissions).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void requiredUploadStillHonorsGrantBoundTargetSubsets() {
        StorageFolderEntity folder = folder(true);
        givenValidTarget();
        StorageFolderPermissionEntity grant = new StorageFolderPermissionEntity();
        grant.setId(31L);
        grant.setPermissions("READ,UPLOAD");
        when(permissions.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(grant));
        StoragePermissionConfigEntity allowed = new StoragePermissionConfigEntity();
        allowed.setPermissionId(31L);
        allowed.setStorageConfigId(5L);
        allowed.setPriority(1);
        when(permissionConfigs.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(allowed));

        assertEquals(
                5L,
                resolver.resolveForUpload(folder, "ordinary-user")
                        .firstCandidate()
                        .configId());
        assertEquals(
                5L,
                resolver.requireCurrentCandidate(folder, "ordinary-user", false, 11L, 1L, 5L, 1L)
                        .configId());
        BusinessException revoked = assertThrows(
                BusinessException.class,
                () -> resolver.requireCurrentCandidate(folder, "ordinary-user", false, 11L, 1L, 7L, 1L));
        assertEquals("STORAGE041", revoked.getErrorCode());
    }

    @Test
    void disabledFolderAndChangedFolderVersionAreStillCheckedWithUploadAclOff() {
        StorageFolderEntity folder = folder(false);
        assertThrows(
                BusinessException.class,
                () -> resolver.requireCurrentCandidate(folder, "ordinary-user", false, 11L, 99L, 5L, 1L));

        folder.setStatus(StorageFolderEntity.STATUS_DISABLED);
        BusinessException disabled = assertThrows(
                BusinessException.class,
                () -> resolver.requireCurrentCandidate(folder, "ordinary-user", false, 11L, 1L, 5L, 1L));
        assertEquals("STORAGE025", disabled.getErrorCode());
        verifyNoInteractions(permissions, permissionConfigs);
    }

    private void givenValidTarget() {
        StorageFolderConfigEntity binding = new StorageFolderConfigEntity();
        binding.setStorageConfigId(5L);
        binding.setPriority(1);
        when(bindings.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(binding));
        StorageConfigEntity config = config();
        when(configs.selectByIds(any())).thenReturn(List.of(config));
        when(configs.selectBatchIds(any())).thenReturn(List.of(config));
        when(configs.selectById(5L)).thenReturn(config);
    }

    private static StorageFolderEntity folder(boolean required) {
        StorageFolderEntity folder = new StorageFolderEntity();
        folder.setId(11L);
        folder.setStatus(StorageFolderEntity.STATUS_ENABLED);
        folder.setTargetVersion(1L);
        folder.setUploadAclRequired(required);
        return folder;
    }

    private static StorageConfigEntity config() {
        StorageConfigEntity config = new StorageConfigEntity();
        config.setId(5L);
        config.setStatus(StorageConfigEntity.STATUS_ENABLED);
        config.setProviderType("R2");
        config.setConfigVersion(1L);
        return config;
    }
}
