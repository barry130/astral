package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.dto.StorageDtos;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageBatchServiceTest {
    @Test
    void deniedFileDoesNotStopOtherDeletesAndDuplicatesRunOnce() {
        var files = mock(StorageFileService.class);
        var service = new StorageBatchService(files, mock(FileManagerService.class));
        doThrow(new BusinessException("STORAGE013")).when(files).requestDelete("denied", "user", false);
        var result = service.execute(new StorageDtos.FileBatchReq(List.of("first", "denied", "last", "first"),
                "delete", null, null), "user", false);
        assertEquals(List.of("first", "last"), result.succeeded());
        assertEquals("denied", result.failed().getFirst().publicId());
        verify(files, times(1)).requestDelete("first", "user", false);
        verify(files).requestDelete("last", "user", false);
    }

    @Test
    void invalidBatchIsRejectedBeforeAnyMutation() {
        var files = mock(StorageFileService.class);
        var manager = mock(FileManagerService.class);
        var service = new StorageBatchService(files, manager);
        assertThrows(BusinessException.class, () -> service.execute(new StorageDtos.FileBatchReq(
                Collections.nCopies(101, "file"), "delete", null, null), "admin", true));
        assertThrows(BusinessException.class, () -> service.execute(new StorageDtos.FileBatchReq(
                List.of("file"), "move", null, null), "admin", true));
        assertThrows(BusinessException.class, () -> service.execute(new StorageDtos.FileBatchReq(
                List.of("file"), "visibility", null, "invalid"), "admin", true));
        verifyNoInteractions(files, manager);
    }
}
