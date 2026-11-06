package com.astral.storage.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UploadAdmissionControlTest {

    private StorageProperties properties;
    private UploadAdmissionControl control;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        properties.getUploadOperation().setMaxConcurrentUploads(2);
        properties.getUploadOperation().setMaxConcurrentPerUser(1);
        control = new UploadAdmissionControl(properties);
    }

    @Test
    void sameUploadIdCannotBeProcessedTwice() {
        try (UploadAdmissionControl.Permit ignored = control.acquire("op-1", "u1")) {
            assertTrue(control.isInflight("op-1"));
            BusinessException ex = assertThrows(BusinessException.class, () -> control.acquire("op-1", "u1"));
            assertEquals("STORAGE042", ex.getErrorCode());
        }
        assertFalse(control.isInflight("op-1"));
    }

    @Test
    void perUserLimitRejectsSecondUploadButOtherUserPasses() {
        try (UploadAdmissionControl.Permit ignored = control.acquire("op-1", "u1")) {
            BusinessException ex = assertThrows(BusinessException.class, () -> control.acquire("op-2", "u1"));
            assertEquals("STORAGE042", ex.getErrorCode());
            assertFalse(control.isInflight("op-2"));
            assertDoesNotThrow(() -> {
                try (UploadAdmissionControl.Permit other = control.acquire("op-3", "u2")) {
                    assertTrue(control.isInflight("op-3"));
                }
            });
        }
    }

    @Test
    void globalLimitRejectsWhenAllSlotsTaken() {
        properties.getUploadOperation().setMaxConcurrentUploads(1);
        properties.getUploadOperation().setMaxConcurrentPerUser(5);
        try (UploadAdmissionControl.Permit ignored = control.acquire("op-1", "u1")) {
            BusinessException ex = assertThrows(BusinessException.class, () -> control.acquire("op-2", "u2"));
            assertEquals("STORAGE042", ex.getErrorCode());
            assertFalse(control.isInflight("op-2"));
        }
        assertDoesNotThrow(() -> {
            try (UploadAdmissionControl.Permit next = control.acquire("op-2", "u2")) {
                assertTrue(control.isInflight("op-2"));
            }
        });
    }

    @Test
    void closeIsIdempotent() {
        UploadAdmissionControl.Permit permit = control.acquire("op-1", "u1");
        permit.close();
        permit.close();
        assertFalse(control.isInflight("op-1"));
        assertDoesNotThrow(() -> {
            try (UploadAdmissionControl.Permit next = control.acquire("op-1", "u1")) {
                assertTrue(control.isInflight("op-1"));
                assert next != null;
            }
        });
    }
}
