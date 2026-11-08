package com.astral.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.astral.common.exception.BusinessException;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.entity.StorageFolderConfigEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.mapper.StorageConfigMapper;
import com.astral.storage.mapper.StorageFolderConfigMapper;
import com.astral.storage.mapper.StorageFolderPermissionMapper;
import com.astral.storage.mapper.StoragePermissionConfigMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class StorageTargetResolverSnapshotTest {

    private static final String SNAPSHOT = """
            {"v":2,"folderId":11,"folderVersion":3,"strategy":"FAILOVER","source":"FOLDER","tiers":[
              {"priority":1,"candidates":[
                {"configId":5,"configVersion":7,"providerType":"CUSTOM_HTTP"},
                {"configId":6,"configVersion":2,"providerType":"R2"}
              ]}
            ]}
            """;

    @Test
    void parseSnapshotUsesOnlySnapshotCandidatesAndVersions() {
        StorageTargetResolver resolver = resolver(null, null);

        StorageTargetResolver.Resolution resolution = resolver.parseSnapshot(SNAPSHOT, 11L, 3L);

        assertEquals("FAILOVER", resolution.strategy());
        assertEquals(11L, resolution.folderId());
        assertEquals(3L, resolution.folderVersion());
        assertEquals("FOLDER", resolution.source());
        assertEquals(2, resolution.flatCandidates().size());
        assertEquals(5L, resolution.flatCandidates().get(0).configId());
        assertEquals(7L, resolution.flatCandidates().get(0).configVersion());
        assertEquals("CUSTOM_HTTP", resolution.flatCandidates().get(0).providerType());
    }

    @Test
    void parseSnapshotFailsClosedOnFolderVersionMismatch() {
        StorageTargetResolver resolver = resolver(null, null);
        BusinessException ex = assertThrows(BusinessException.class, () -> resolver.parseSnapshot(SNAPSHOT, 11L, 4L));
        assertEquals("STORAGE047", ex.getErrorCode());
    }

    @Test
    void parseSnapshotRejectsV1DuplicateCandidatesAndNegativePriority() {
        StorageTargetResolver resolver = resolver(null, null);
        assertThrows(BusinessException.class, () -> resolver.parseSnapshot("{\"v\":1}", 11L, 3L));
        assertThrows(BusinessException.class, () -> resolver.parseSnapshot("""
                        {"v":2,"folderId":11,"folderVersion":3,"strategy":"FAILOVER","source":"FOLDER","tiers":[
                          {"priority":1,"candidates":[
                            {"configId":5,"configVersion":7,"providerType":"CUSTOM_HTTP"},
                            {"configId":5,"configVersion":8,"providerType":"R2"}
                          ]}
                        ]}
                        """, 11L, 3L));
        assertThrows(BusinessException.class, () -> resolver.parseSnapshot("""
                        {"v":2,"folderId":11,"folderVersion":3,"strategy":"FAILOVER","source":"FOLDER","tiers":[
                          {"priority":-1,"candidates":[
                            {"configId":5,"configVersion":7,"providerType":"CUSTOM_HTTP"}
                          ]}
                        ]}
                        """, 11L, 3L));
    }

    @Test
    void parseSnapshotAcceptsZeroPriorityFromFolderBindingContract() {
        StorageTargetResolver resolver = resolver(null, null);

        StorageTargetResolver.Resolution resolution = resolver.parseSnapshot("""
                {"v":2,"folderId":11,"folderVersion":3,"strategy":"FAILOVER","source":"FOLDER","tiers":[
                  {"priority":0,"candidates":[
                    {"configId":5,"configVersion":7,"providerType":"CUSTOM_HTTP"}
                  ]}
                ]}
                """, 11L, 3L);

        assertEquals(0, resolution.tiers().getFirst().priority());
        assertEquals(5L, resolution.firstCandidate().configId());
    }

    @Test
    void requireCurrentCandidateEnforcesFolderAndConfigVersions() {
        StorageFolderConfigMapper folderConfigMapper = mock(StorageFolderConfigMapper.class);
        StorageConfigMapper configMapper = mock(StorageConfigMapper.class);
        StorageFolderConfigEntity binding = new StorageFolderConfigEntity();
        binding.setId(101L);
        binding.setFolderId(11L);
        binding.setStorageConfigId(5L);
        binding.setPriority(1);
        StorageConfigEntity config = new StorageConfigEntity();
        config.setId(5L);
        config.setName("api");
        config.setProviderType("CUSTOM_HTTP");
        config.setStatus(StorageConfigEntity.STATUS_ENABLED);
        config.setConfigVersion(7L);
        when(folderConfigMapper.selectList(any())).thenReturn(List.of(binding));
        when(configMapper.selectByIds(anyCollection())).thenReturn(List.of(config));
        StorageTargetResolver resolver = resolver(folderConfigMapper, configMapper);

        StorageTargetResolver.Candidate current =
                resolver.requireCurrentCandidate(folder(), "owner", false, 11L, 3L, 5L, 7L);
        assertEquals(5L, current.configId());
        assertEquals(7L, current.configVersion());

        BusinessException folderStale = assertThrows(
                BusinessException.class,
                () -> resolver.requireCurrentCandidate(folder(), "owner", false, 11L, 2L, 5L, 7L));
        assertEquals("STORAGE047", folderStale.getErrorCode());

        BusinessException configStale = assertThrows(
                BusinessException.class,
                () -> resolver.requireCurrentCandidate(folder(), "owner", false, 11L, 3L, 5L, 8L));
        assertEquals("STORAGE047", configStale.getErrorCode());
    }

    @Test
    void requireCurrentCandidateRejectsCandidateOutsideCurrentBindings() {
        StorageFolderConfigMapper folderConfigMapper = mock(StorageFolderConfigMapper.class);
        StorageConfigMapper configMapper = mock(StorageConfigMapper.class);
        when(folderConfigMapper.selectList(any())).thenReturn(List.of());
        StorageTargetResolver resolver = resolver(folderConfigMapper, configMapper);

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> resolver.requireCurrentCandidate(folder(), "owner", false, 11L, 3L, 5L, 7L));
        assertEquals("STORAGE058", ex.getErrorCode());
    }

    private static StorageTargetResolver resolver(
            StorageFolderConfigMapper folderConfigMapper, StorageConfigMapper configMapper) {
        return new StorageTargetResolver(
                configMapper == null ? mock(StorageConfigMapper.class) : configMapper,
                folderConfigMapper == null ? mock(StorageFolderConfigMapper.class) : folderConfigMapper,
                mock(StorageFolderPermissionMapper.class),
                mock(StoragePermissionConfigMapper.class),
                mock(TargetSelectionCursor.class),
                new ObjectMapper());
    }

    private static StorageFolderEntity folder() {
        StorageFolderEntity folder = new StorageFolderEntity();
        folder.setId(11L);
        folder.setTargetVersion(3L);
        folder.setStatus(StorageFolderEntity.STATUS_ENABLED);
        folder.setOwnerType(StorageFolderEntity.OWNER_USER);
        folder.setOwnerId("owner");
        return folder;
    }
}
