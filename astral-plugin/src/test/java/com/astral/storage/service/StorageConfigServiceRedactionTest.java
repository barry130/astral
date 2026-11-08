package com.astral.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.astral.common.exception.BusinessException;
import com.astral.storage.dto.StorageDtos;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.mapper.StorageConfigMapper;
import com.astral.storage.mapper.StorageFileMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 旧配置路由对 CUSTOM_HTTP 的边界：出参脱敏 + create/update/test/default/delete 统一 STORAGE039。
 *
 * <p>不启动 Spring 上下文，直接注入 Mapper 桩，验证「摘要副本」与「STORAGE039」两条硬约束。</p>
 */
class StorageConfigServiceRedactionTest {

    private static final String ENVELOPE_JSON =
            "{\"schemaVersion\":1,\"profile\":{},\"credentials\":[{\"name\":\"apiKey\",\"value\":\"s3cr3t-value\"}]}";

    private StorageConfigMapper configMapper;

    private StorageFileMapper fileMapper;

    private StorageConfigService service;

    @BeforeEach
    void setUp() {
        configMapper = mock(StorageConfigMapper.class);
        fileMapper = mock(StorageFileMapper.class);
        service = new StorageConfigService();
        ReflectionTestUtils.setField(service, "configMapper", configMapper);
        ReflectionTestUtils.setField(service, "fileMapper", fileMapper);
        ReflectionTestUtils.setField(
                service, "objectMapper", JsonMapper.builder().build());
    }

    private static StorageConfigEntity customHttp() {
        StorageConfigEntity config = new StorageConfigEntity();
        config.setId(2001L);
        config.setName("cdn-a");
        config.setProviderType(StorageConfigEntity.PROVIDER_CUSTOM_HTTP);
        config.setStatus(StorageConfigEntity.STATUS_ENABLED);
        config.setConfigVersion(7L);
        config.setIsDefault(0);
        config.setHealthStatus(StorageConfigEntity.HEALTH_UNKNOWN);
        config.setProviderOptions(ENVELOPE_JSON);
        config.setChatId("should-not-leak");
        config.setWorkerBaseUrl("https://worker.internal");
        config.setMaxFileSize(1048576L);
        config.setRemark("第三方直连");
        return config;
    }

    private static StorageConfigEntity r2() {
        StorageConfigEntity config = new StorageConfigEntity();
        config.setId(2002L);
        config.setName("r2-main");
        config.setProviderType(StorageConfigEntity.PROVIDER_R2);
        config.setStatus(StorageConfigEntity.STATUS_ENABLED);
        config.setConfigVersion(3L);
        config.setIsDefault(1);
        config.setProviderOptions("{\"endpoint\":\"https://acc.r2.cloudflarestorage.com\","
                + "\"bucket\":\"b\",\"accessKeyId\":\"AK-1\",\"secretAccessKey\":\"sk-live-1\"}");
        return config;
    }

    private static String errorCodeOf(Runnable action) {
        return assertThrows(BusinessException.class, action::run).getErrorCode();
    }

    @Test
    void listAllReturnsSafeSummaryForCustomHttp() {
        StorageConfigEntity entity = customHttp();
        when(configMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(entity)));

        List<StorageConfigEntity> result = service.listAll();

        StorageConfigEntity safe = result.get(0);
        assertEquals(Long.valueOf(2001L), safe.getId());
        assertEquals("cdn-a", safe.getName());
        assertEquals(StorageConfigEntity.PROVIDER_CUSTOM_HTTP, safe.getProviderType());
        assertEquals(StorageConfigEntity.STATUS_ENABLED, safe.getStatus());
        assertEquals(Long.valueOf(7L), safe.getConfigVersion());
        assertEquals(Integer.valueOf(0), safe.getIsDefault());
        assertEquals("第三方直连", safe.getRemark());
        // 凭据信封与非必要连接字段一律不出现
        assertNull(safe.getProviderOptions());
        assertNull(safe.getChatId());
        assertNull(safe.getWorkerBaseUrl());
        assertNull(safe.getMaxFileSize());
        // 返回的是副本：原始实体仍可被服务端内部链路使用
        assertNotNull(entity.getProviderOptions());
    }

    @Test
    void pageReturnsSafeSummaryForCustomHttp() {
        StorageConfigEntity entity = customHttp();
        Page<StorageConfigEntity> page = new Page<>(1, 10);
        page.setRecords(new ArrayList<>(List.of(entity)));
        page.setTotal(1);
        when(configMapper.selectPage(any(), any())).thenReturn(page);

        Page<StorageConfigEntity> result = service.page(1, 10);

        assertNull(result.getRecords().get(0).getProviderOptions());
        assertEquals(1L, result.getTotal());
    }

    @Test
    void getByIdRedactsCustomHttp() {
        when(configMapper.selectById(2001L)).thenReturn(customHttp());

        StorageConfigEntity safe = service.getById(2001L);

        assertNull(safe.getProviderOptions());
        assertEquals("cdn-a", safe.getName());
    }

    @Test
    void getByIdKeepsMaskedSecretForLegacyProviders() {
        when(configMapper.selectById(2002L)).thenReturn(r2());

        StorageConfigEntity safe = service.getById(2002L);

        assertNotNull(safe.getProviderOptions());
        assertTrue(safe.getProviderOptions().contains("******"));
        assertTrue(safe.getProviderOptions().contains("AK-1"));
        assertTrue(!safe.getProviderOptions().contains("sk-live-1"));
    }

    @Test
    void redactsUnknownSensitiveOptionsButKeepsCredentialIdentifiers() {
        StorageConfigEntity config = r2();
        config.setProviderOptions("{\"endpoint\":\"https://acc.r2.cloudflarestorage.com\","
                + "\"bucket\":\"b\",\"accessKeyId\":\"AK-1\",\"secretId\":\"SID-1\","
                + "\"clientSecret\":\"unknown-secret\",\"apiToken\":\"unknown-token\","
                + "\"secretAccessKey\":\"sk-live-1\"}");
        when(configMapper.selectById(2002L)).thenReturn(config);

        StorageConfigEntity safe = service.getById(2002L);

        assertTrue(safe.getProviderOptions().contains("AK-1"));
        assertTrue(safe.getProviderOptions().contains("SID-1"));
        assertTrue(!safe.getProviderOptions().contains("unknown-secret"));
        assertTrue(!safe.getProviderOptions().contains("unknown-token"));
        assertTrue(!safe.getProviderOptions().contains("sk-live-1"));
        assertTrue(safe.getProviderOptions().contains("******"));
    }

    @Test
    void redactsSensitiveKeysInsideNestedObjectsAndArrays() {
        StorageConfigEntity config = r2();
        config.setProviderOptions("{\"endpoint\":\"https://acc.r2.cloudflarestorage.com\","
                + "\"bucket\":\"b\",\"accessKeyId\":\"AK-1\",\"secretAccessKey\":\"top-secret\","
                + "\"nested\":{\"clientSecret\":\"nested-secret\",\"privateKey\":\"private-key\",\"keep\":\"visible\"},"
                + "\"items\":[{\"apiToken\":\"array-token\",\"apiKey\":\"api-key\"}]}");
        when(configMapper.selectById(2002L)).thenReturn(config);

        StorageConfigEntity safe = service.getById(2002L);

        assertTrue(safe.getProviderOptions().contains("AK-1"));
        assertTrue(safe.getProviderOptions().contains("visible"));
        assertTrue(!safe.getProviderOptions().contains("nested-secret"));
        assertTrue(!safe.getProviderOptions().contains("array-token"));
        assertTrue(safe.getProviderOptions().contains("******"));
    }

    @Test
    void setDefaultRejectsCustomHttp() {
        when(configMapper.selectById(2001L)).thenReturn(customHttp());

        assertEquals("STORAGE039", errorCodeOf(() -> service.setDefault(2001L, "tester")));
        verify(configMapper, never()).update(any(), any());
    }

    @Test
    void deleteRejectsCustomHttpBeforeReferenceChecks() {
        when(configMapper.selectById(2001L)).thenReturn(customHttp());

        assertEquals("STORAGE039", errorCodeOf(() -> service.delete(2001L, "tester")));
        verify(fileMapper, never()).selectCount(any());
        verify(configMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void legacyCrudRoutesRejectCustomHttpUniformly() {
        when(configMapper.selectById(2001L)).thenReturn(customHttp());

        var createReq = new StorageDtos.ConfigCreateReq(
                "cdn-b", StorageConfigEntity.PROVIDER_CUSTOM_HTTP, null, null, Map.of(), null, null);
        assertEquals("STORAGE039", errorCodeOf(() -> service.create(createReq, "tester")));
        verify(configMapper, never()).insert(any(StorageConfigEntity.class));

        var updateReq = new StorageDtos.ConfigUpdateReq(null, null, Map.of(), null, null, null);
        assertEquals("STORAGE039", errorCodeOf(() -> service.update(2001L, updateReq, "tester")));
        verify(configMapper, never()).updateById(any(StorageConfigEntity.class));

        assertEquals("STORAGE039", errorCodeOf(() -> service.test(2001L, "tester")));
    }

    @Test
    void unknownProviderTypeStillReportsCommon002() {
        var req = new StorageDtos.ConfigCreateReq("cdn-c", "FOO_BAR", null, null, Map.of(), null, null);

        assertEquals("COMMON002", errorCodeOf(() -> service.create(req, "tester")));
        verify(configMapper, never()).insert(any(StorageConfigEntity.class));
    }

    @Test
    void missingConfigStillReportsStorage002() {
        when(configMapper.selectById(4040L)).thenReturn(null);

        assertEquals("STORAGE002", errorCodeOf(() -> service.getById(4040L)));
    }
}
