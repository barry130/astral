package com.astral.storage.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

class StorageWorkerAuthInterceptorTest {

    @Test
    void rejectsLongMinTimestampWithoutOverflowingIntoValidWindow() {
        StorageProperties properties = new StorageProperties();
        properties.setOriginSharedSecret("test-secret");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StorageWorkerAuthInterceptor interceptor = new StorageWorkerAuthInterceptor(properties, redis);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/all/storage/worker/ping");
        request.addHeader(StorageWorkerAuthInterceptor.HEADER_TIMESTAMP, String.valueOf(Long.MIN_VALUE));
        request.addHeader(StorageWorkerAuthInterceptor.HEADER_NONCE, "nonce");
        request.addHeader(StorageWorkerAuthInterceptor.HEADER_SIGNATURE, "signature");

        BusinessException ex = assertThrows(BusinessException.class, () -> interceptor.preHandle(request, null, null));

        assertEquals("STORAGE017", ex.getErrorCode());
        verifyNoInteractions(redis);
    }
}
