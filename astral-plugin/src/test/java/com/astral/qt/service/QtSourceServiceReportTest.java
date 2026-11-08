package com.astral.qt.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.astral.qt.common.QtException;
import com.astral.qt.dto.QtSourceReportDto;
import com.astral.qt.entity.QtSourceReport;
import com.astral.qt.mapper.QtSourceReportMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 覆盖匿名上报接口的入参约束（D-QT-06）：detail 截断上限、非法 platform 拒绝。
 */
class QtSourceServiceReportTest {

    private QtSourceReportMapper reportMapper;
    private QtSourceService service;

    @BeforeEach
    void setUp() {
        reportMapper = mock(QtSourceReportMapper.class);
        // 仅 recordReport 用到 reportMapper；其余协作者在该路径不被触及，传 null 避免不必要的 mock。
        service = new QtSourceService(null, reportMapper, null, null, null);
    }

    @Test
    void recordReportTruncatesOverlongDetail() {
        QtSourceReportDto dto = new QtSourceReportDto();
        dto.setPlatform(1101L);
        dto.setSourceVersionCode(5L);
        dto.setResult("smoke_failed");
        dto.setDetail("x".repeat(5000));

        service.recordReport(dto);

        ArgumentCaptor<QtSourceReport> captor = ArgumentCaptor.forClass(QtSourceReport.class);
        verify(reportMapper).insert(captor.capture());
        assertEquals(500, captor.getValue().getDetail().length());
    }

    @Test
    void recordReportRejectsUnknownPlatform() {
        QtSourceReportDto dto = new QtSourceReportDto();
        dto.setPlatform(9999L);
        dto.setSourceVersionCode(5L);
        dto.setResult("ok");

        assertThrows(QtException.class, () -> service.recordReport(dto));
        verify(reportMapper, never()).insert(any(QtSourceReport.class));
    }
}
