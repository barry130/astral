package com.astral.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.astral.common.exception.BusinessException;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.mapper.StorageConfigMapper;
import org.junit.jupiter.api.Test;

class StorageTicketTargetServiceTest {

    @Test
    void disabledFolderIsRejectedBeforeResolvingAnyCandidate() {
        StorageTargetResolver resolver = mock(StorageTargetResolver.class);
        StorageConfigMapper configMapper = mock(StorageConfigMapper.class);
        StorageTicketTargetService service = new StorageTicketTargetService(resolver, configMapper);
        StorageFolderEntity folder = new StorageFolderEntity();
        folder.setId(9L);
        folder.setStatus(StorageFolderEntity.STATUS_DISABLED);

        BusinessException ex =
                assertThrows(BusinessException.class, () -> service.resolveUploadConfig(folder, "user-1"));

        assertEquals("STORAGE025", ex.getErrorCode());
        verifyNoInteractions(resolver, configMapper);
    }
}
