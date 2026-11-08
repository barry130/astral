package com.astral.log.aspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.astral.dao.entity.OperateLog;
import com.astral.log.service.LogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OperateLogWriterTest {

    @Test
    void saveAsyncPersistsUserIdAlongsideUsername() {
        LogService logService = mock(LogService.class);
        OperateLogWriter writer = new OperateLogWriter(logService);

        writer.saveAsync(42L, "alice", "CREATE", "/api/v1/admin/demo", "POST", "{}", "{}", "127.0.0.1", 1, null, 12L)
                .join();

        ArgumentCaptor<OperateLog> captor = ArgumentCaptor.forClass(OperateLog.class);
        verify(logService).saveOperateLog(captor.capture());
        OperateLog saved = captor.getValue();
        assertEquals(42L, saved.getUserId(), "操作日志必须记录操作人ID，供导出列与审计筛选使用");
        assertEquals("alice", saved.getUsername());
        assertEquals("CREATE", saved.getOperateType());
        assertEquals(1, saved.getStatus());
    }

    @Test
    void saveAsyncKeepsNullUserIdForAnonymousRequest() {
        LogService logService = mock(LogService.class);
        OperateLogWriter writer = new OperateLogWriter(logService);

        writer.saveAsync(null, "anonymous", "PUBLIC", "/api/v1/all/demo", "GET", "", "", "unknown", 1, null, 1L)
                .join();

        ArgumentCaptor<OperateLog> captor = ArgumentCaptor.forClass(OperateLog.class);
        verify(logService).saveOperateLog(captor.capture());
        assertNull(captor.getValue().getUserId(), "匿名请求的用户ID必须保持为空，不能伪造成 0");
    }
}
