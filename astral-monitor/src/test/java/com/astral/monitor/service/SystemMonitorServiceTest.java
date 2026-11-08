package com.astral.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.astral.monitor.service.impl.SystemMonitorServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SystemMonitorServiceTest {
    @Test
    void hostMemoryAndJvmHeapAreReportedSeparately() {
        var os = mock(com.sun.management.OperatingSystemMXBean.class);
        when(os.getTotalMemorySize()).thenReturn(8_000_000_000L);
        when(os.getFreeMemorySize()).thenReturn(2_000_000_000L);
        when(os.getCpuLoad()).thenReturn(0.25);
        var service = new SystemMonitorServiceImpl();
        ReflectionTestUtils.setField(service, "osBean", os);
        var result = service.getSystemInfo();
        assertEquals(8_000_000_000L, result.getTotalMemory());
        assertEquals(6_000_000_000L, result.getUsedMemory());
        assertEquals(2_000_000_000L, result.getAvailableMemory());
        assertEquals(75.0, result.getMemoryUsage());
        assertEquals(25.0, result.getCpuUsage());
        assertNotNull(result.getHeapUsed());
        assertNotNull(result.getHeapMax());
    }

    @Test
    void unavailableHostMetricsRemainUnknown() {
        var os = mock(java.lang.management.OperatingSystemMXBean.class);
        var service = new SystemMonitorServiceImpl();
        ReflectionTestUtils.setField(service, "osBean", os);
        var result = service.getSystemInfo();
        assertNull(result.getMemoryUsage());
        assertNull(result.getTotalMemory());
        assertNull(result.getAvailableMemory());
        assertNull(result.getCpuUsage());
        assertNotNull(result.getHeapUsed());
    }
}
