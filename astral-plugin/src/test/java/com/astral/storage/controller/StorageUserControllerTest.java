package com.astral.storage.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.astral.auth.security.PermissionChecker;
import com.astral.common.exception.BusinessException;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.security.UploadTicketService;
import com.astral.storage.service.FileManagerService;
import com.astral.storage.service.StorageBatchService;
import com.astral.storage.service.StorageFileService;
import com.astral.storage.service.StorageFolderService;
import com.astral.storage.service.StorageTicketTargetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class StorageUserControllerTest {

    private StorageFileService fileService;
    private StorageUserController controller;

    @BeforeEach
    void setUp() {
        StorageFolderService folderService = mock(StorageFolderService.class);
        StorageTicketTargetService ticketTargetService = mock(StorageTicketTargetService.class);
        fileService = mock(StorageFileService.class);
        UploadTicketService ticketService = mock(UploadTicketService.class);
        PermissionChecker permissionChecker = mock(PermissionChecker.class);
        FileManagerService fileManagerService = mock(FileManagerService.class);
        StorageBatchService batchService = mock(StorageBatchService.class);
        controller = new StorageUserController(
                folderService,
                ticketTargetService,
                fileService,
                ticketService,
                permissionChecker,
                fileManagerService,
                batchService);
    }

    @Test
    void deletedFilePreviewIsRejectedBeforePermissionChecks() {
        StorageFileEntity deleted = new StorageFileEntity();
        deleted.setStatus(StorageFileEntity.STATUS_DELETED);
        deleted.setUploaderId("owner");
        when(fileService.getByPublicId("pub-1")).thenReturn(deleted);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", "owner");

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.file("pub-1", request));

        assertEquals("STORAGE021", ex.getErrorCode());
    }
}
