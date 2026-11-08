package com.astral.sequence.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SequenceConfig;
import com.astral.dao.mapper.SequenceConfigMapper;
import com.astral.dao.mapper.SequenceHistoryMapper;
import com.astral.sequence.config.EntityIdSequenceProvider;
import com.astral.sequence.generator.DatabaseGenerator;
import com.astral.sequence.generator.SegmentGenerator;
import com.astral.sequence.generator.SimpleGenerator;
import com.astral.sequence.generator.SnowflakeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

/** GeneratorFactory 并发创建、禁用配置与未知类型的业务回归。 */
@ExtendWith(MockitoExtension.class)
class GeneratorFactoryTest {

    @Mock
    private SnowflakeGenerator snowflakeGenerator;

    @Mock
    private SimpleGenerator simpleGenerator;

    @Mock
    private DatabaseGenerator databaseGenerator;

    @Mock
    private SegmentGenerator segmentGenerator;

    @Mock
    private SequenceHistoryMapper historyMapper;

    @Mock
    private SequenceConfigMapper configMapper;

    @Mock
    private EntityIdSequenceProvider entityIdSequenceProvider;

    @Mock
    private SequenceAsyncWriter sequenceAsyncWriter;

    private GeneratorFactory factory;

    @BeforeEach
    void setUp() {
        factory = new GeneratorFactory(
                snowflakeGenerator,
                simpleGenerator,
                databaseGenerator,
                segmentGenerator,
                historyMapper,
                configMapper,
                entityIdSequenceProvider,
                sequenceAsyncWriter);
        ReflectionTestUtils.setField(factory, "defaultType", "SEGMENT");
    }

    @Test
    void batchRejectsNonPositiveCountBeforeTouchingDatabase() {
        BusinessException error =
                assertThrows(BusinessException.class, () -> factory.batchResult("order", 0, "SEGMENT"));
        assertEquals("SEQ004", error.getErrorCode());
        verify(configMapper, never()).selectByBizKey(anyString());
    }

    @Test
    void unknownConfiguredTypeFailsInsteadOfSilentlyFallingBackToSnowflake() {
        SequenceConfig config = config("order", "SEGMNET", true);
        when(entityIdSequenceProvider.isEntityIdBizKey("order")).thenReturn(false);
        when(configMapper.selectByBizKey("order")).thenReturn(config);

        // 不传请求类型，实际读取未知的持久化配置，而不是在入参校验阶段提前退出。
        BusinessException error = assertThrows(BusinessException.class, () -> factory.nextResult("order", null));
        assertEquals("SEQ002", error.getErrorCode());
        verify(snowflakeGenerator, never()).next(anyString());
    }

    @Test
    void disabledConfigStopsIssuingNumbers() {
        SequenceConfig config = config("order", "SEGMENT", false);
        when(entityIdSequenceProvider.isEntityIdBizKey("order")).thenReturn(false);
        when(configMapper.selectByBizKey("order")).thenReturn(config);

        BusinessException error = assertThrows(BusinessException.class, () -> factory.nextResult("order", "SEGMENT"));
        assertEquals("SEQ009", error.getErrorCode());
        verify(segmentGenerator, never()).next(anyString());
    }

    @Test
    void concurrentAutoCreateRereadsWinningConfigAfterRealUniqueConflict() {
        SequenceConfig winner = config("order", "SIMPLE", true);
        when(entityIdSequenceProvider.isEntityIdBizKey("order")).thenReturn(false);
        when(configMapper.selectByBizKey("order")).thenReturn(null, winner);
        when(configMapper.insert(any(SequenceConfig.class)))
                .thenThrow(new DuplicateKeyException("uk_sequence_config_biz_key"));
        when(simpleGenerator.next("order")).thenReturn(42L);

        SequenceResult result = factory.nextResult("order", "SIMPLE");

        assertEquals(42L, result.value());
        assertEquals("SIMPLE", result.type());
        verify(configMapper, times(2)).selectByBizKey("order");
    }

    @Test
    void concurrentAutoCreateCannotSilentlyChangeRequestedType() {
        when(configMapper.selectByBizKey("order")).thenReturn(null, config("order", "SEGMENT", true));
        when(configMapper.insert(any(SequenceConfig.class)))
                .thenThrow(new DuplicateKeyException("uk_sequence_config_biz_key"));

        BusinessException error = assertThrows(BusinessException.class, () -> factory.nextResult("order", "SIMPLE"));

        assertEquals("SEQ006", error.getErrorCode());
        verify(segmentGenerator, never()).next(anyString());
    }

    @Test
    void nonDuplicateInsertFailureIsNotSwallowed() {
        when(entityIdSequenceProvider.isEntityIdBizKey("order")).thenReturn(false);
        when(configMapper.selectByBizKey("order")).thenReturn(null);
        when(configMapper.insert(any(SequenceConfig.class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThrows(DataAccessResourceFailureException.class, () -> factory.nextResult("order", "SEGMENT"));
        verify(configMapper, times(1)).selectByBizKey("order");
    }

    @Test
    void omittedRequestTypeReusesConfiguredTypeInsteadOfDefaultingToSegment() {
        SequenceConfig config = config("order", "SIMPLE", true);
        when(entityIdSequenceProvider.isEntityIdBizKey("order")).thenReturn(false);
        when(configMapper.selectByBizKey("order")).thenReturn(config);
        when(simpleGenerator.next("order")).thenReturn(42L);

        SequenceResult result = factory.nextResult("order", null);

        assertEquals(42L, result.value());
        assertEquals("SIMPLE", result.type());
        verify(segmentGenerator, never()).next(anyString());
        verify(configMapper, never()).insert(any(SequenceConfig.class));
    }

    @Test
    void invalidTypeIsRejectedBeforeAnyConfigIsInserted() {
        BusinessException error = assertThrows(BusinessException.class, () -> factory.nextResult("order", "SEGMENTT"));

        assertEquals("SEQ002", error.getErrorCode());
        verify(configMapper, never()).selectByBizKey(anyString());
        verify(configMapper, never()).insert(any(SequenceConfig.class));
    }

    @Test
    void entityIdSequenceCannotSwitchAwayFromSegment() {
        when(entityIdSequenceProvider.isEntityIdBizKey("demo_id")).thenReturn(true);

        BusinessException error =
                assertThrows(BusinessException.class, () -> factory.nextResult("demo_id", "SNOWFLAKE"));
        assertEquals("SEQ006", error.getErrorCode());
        verify(configMapper, never()).selectByBizKey(anyString());
    }

    @Test
    void omittedEntityIdTypeStillUsesSegmentWhenGlobalDefaultChanges() {
        ReflectionTestUtils.setField(factory, "defaultType", "SNOWFLAKE");
        when(entityIdSequenceProvider.isEntityIdBizKey("demo_id")).thenReturn(true);

        assertEquals("SEGMENT", factory.nextResult("demo_id", null).type());
        verify(snowflakeGenerator, never()).next(anyString());
    }

    private static SequenceConfig config(String bizKey, String type, boolean enabled) {
        SequenceConfig config = new SequenceConfig();
        config.setBizKey(bizKey);
        config.setSequenceType(type);
        config.setEnabled(enabled);
        return config;
    }
}
