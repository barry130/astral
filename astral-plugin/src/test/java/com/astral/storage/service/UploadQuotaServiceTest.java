package com.astral.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.astral.common.exception.BusinessException;
import com.astral.storage.entity.StorageUploadOperationEntity;
import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageUploadOperationMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.LocalDate;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UploadQuotaServiceTest {

    private StorageUploadOperationMapper operations;
    private StorageFileMapper files;
    private UploadQuotaService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), StorageUploadOperationEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                com.astral.storage.entity.StorageFileEntity.class);
        operations = mock(StorageUploadOperationMapper.class);
        files = mock(StorageFileMapper.class);
        service = new UploadQuotaService(
                operations, files, mock(com.astral.storage.mapper.StorageUploadChargeMapper.class));
    }

    @Test
    void unlimitedQuotaMarksExemptWithoutLocking() {
        StorageUploadOperationEntity operation = operation();
        service.reserve(operation, 0L);
        assertEquals(StorageUploadOperationEntity.CHARGE_NONE, operation.getQuotaCharge());
        assertEquals(1, operation.getQuotaExempt());
        verify(files, never()).lockUploadKey(any());
    }

    @Test
    void withinLimitHoldsChargeAfterQuotaLock() {
        when(files.selectCount(any())).thenReturn(1L);
        when(operations.selectCount(any())).thenReturn(0L);
        StorageUploadOperationEntity operation = operation();
        service.reserve(operation, 3L);
        assertEquals(StorageUploadOperationEntity.CHARGE_HELD, operation.getQuotaCharge());
        assertEquals(0, operation.getQuotaExempt());
        verify(files).lockUploadKey("storage-quota:11:u1");
    }

    @Test
    void usageAtLimitIsRejectedWithoutHolding() {
        when(files.selectCount(any())).thenReturn(2L);
        when(operations.selectCount(any())).thenReturn(1L);
        StorageUploadOperationEntity operation = operation();
        BusinessException ex = assertThrows(BusinessException.class, () -> service.reserve(operation, 3L));
        assertEquals("STORAGE032", ex.getErrorCode());
        assertNull(operation.getQuotaCharge());
    }

    @Test
    void sameDayHeldRetryReusesReservationWithoutCountingItself() {
        StorageUploadOperationEntity operation = operation();
        operation.setQuotaCharge(StorageUploadOperationEntity.CHARGE_HELD);
        operation.setQuotaDay(LocalDate.now());

        service.reserve(operation, 1L);

        assertEquals(StorageUploadOperationEntity.CHARGE_HELD, operation.getQuotaCharge());
        verify(operations, never()).update(any(), any());
        verify(files, never()).selectCount(any());
        verify(operations, never()).selectCount(any());
    }

    @Test
    void crossDayHeldRetryRefundsOldReservationBeforeCountingNewDay() {
        StorageUploadOperationEntity operation = operation();
        operation.setId(7L);
        operation.setQuotaCharge(StorageUploadOperationEntity.CHARGE_HELD);
        operation.setQuotaDay(LocalDate.now().minusDays(1));
        when(files.selectCount(any())).thenReturn(0L);
        when(operations.selectCount(any())).thenReturn(0L);

        service.reserve(operation, 1L);

        verify(operations).update(isNull(), any());
        assertEquals(LocalDate.now(), operation.getQuotaDay());
        assertEquals(StorageUploadOperationEntity.CHARGE_HELD, operation.getQuotaCharge());
    }

    private StorageUploadOperationEntity operation() {
        StorageUploadOperationEntity operation = new StorageUploadOperationEntity();
        operation.setFolderId(11L);
        operation.setActorId("u1");
        return operation;
    }
}
