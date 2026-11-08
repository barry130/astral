package com.astral.sequence.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SequenceConfig;
import com.astral.dao.mapper.SequenceConfigMapper;
import com.astral.dao.mapper.SequenceSegmentMapper;
import com.astral.dao.mapper.SequenceStatisticsMapper;
import com.astral.sequence.config.EntityIdSequenceProvider;
import com.astral.sequence.controller.dto.SequenceConfigUpdate;
import com.astral.sequence.service.GeneratorFactory;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/** 管理端改步长必须同步 sequence_segment，否则号段模式配置不生效。 */
@ExtendWith(MockitoExtension.class)
class SequenceConfigControllerTest {

    @Mock
    private SequenceConfigMapper configMapper;

    @Mock
    private SequenceStatisticsMapper statisticsMapper;

    @Mock
    private SequenceSegmentMapper segmentMapper;

    @Mock
    private EntityIdSequenceProvider entityIdSequenceProvider;

    @Mock
    private GeneratorFactory generatorFactory;

    private SequenceConfigController controller;

    @BeforeEach
    void setUp() {
        // 注册真实 lambda 列元数据；仅 Mapper 为替身，不启动数据库或替换更新 wrapper。
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "isolated-sequence-config-test");
        assistant.setCurrentNamespace(SequenceConfigMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, SequenceConfig.class);
        controller = new SequenceConfigController(
                configMapper, statisticsMapper, segmentMapper, entityIdSequenceProvider, generatorFactory);
    }

    @Test
    void segmentStepChangeSyncsSegmentRowAndEvictsCache() {
        SequenceConfig existing = existing("order", "SEGMENT");
        when(configMapper.lockById(1L)).thenReturn(existing);
        when(entityIdSequenceProvider.isEntityIdBizKey("order")).thenReturn(false);
        when(configMapper.update(isNull(), any())).thenReturn(1);

        SequenceConfigUpdate request = new SequenceConfigUpdate();
        request.setBizKey("order");
        request.setSequenceType("SEGMENT");
        request.setStep(100);

        controller.update(1L, request);

        verify(segmentMapper).updateStepByBizKey("order", 100);
        verify(generatorFactory).evictConfigCache();
    }

    @Test
    void nonSegmentStepChangeDoesNotTouchSegmentRow() {
        SequenceConfig existing = existing("order", "SNOWFLAKE");
        when(configMapper.lockById(1L)).thenReturn(existing);
        when(entityIdSequenceProvider.isEntityIdBizKey("order")).thenReturn(false);
        when(configMapper.update(isNull(), any())).thenReturn(1);

        SequenceConfigUpdate request = new SequenceConfigUpdate();
        request.setBizKey("order");
        request.setSequenceType("SNOWFLAKE");
        request.setStep(100);

        controller.update(1L, request);

        verify(segmentMapper, never()).updateStepByBizKey(anyString(), anyInt());
    }

    @Test
    void unchangedSegmentStepDoesNotBumpSegmentVersion() {
        SequenceConfig existing = existing("order", "SEGMENT");
        when(configMapper.lockById(1L)).thenReturn(existing);
        when(entityIdSequenceProvider.isEntityIdBizKey("order")).thenReturn(false);
        when(configMapper.update(isNull(), any())).thenReturn(1);

        SequenceConfigUpdate request = new SequenceConfigUpdate();
        request.setBizKey("order");
        request.setSequenceType("SEGMENT");
        request.setStep(1000);

        controller.update(1L, request);

        verify(segmentMapper, never()).updateStepByBizKey(anyString(), anyInt());
        verify(generatorFactory).evictConfigCache();
    }

    @Test
    void partialUpdatePreservesFieldsThatFrontendDoesNotSubmit() {
        SequenceConfig existing = existing("order", "SEGMENT");
        existing.setMinValue(5000L);
        existing.setPrefix("ORD");
        existing.setSuffix("X");
        existing.setDateFormat("yyyyMM");
        existing.setDescription("旧说明");
        when(configMapper.lockById(1L)).thenReturn(existing);
        when(entityIdSequenceProvider.isEntityIdBizKey("order")).thenReturn(false);
        when(configMapper.update(isNull(), any())).thenReturn(1);

        SequenceConfigUpdate request = new SequenceConfigUpdate();
        request.setBizKey("order");
        request.setSequenceType("SEGMENT");
        request.setEnabled(false);

        when(configMapper.selectById(1L)).thenAnswer(call -> {
            SequenceConfig persisted = new SequenceConfig();
            org.springframework.beans.BeanUtils.copyProperties(existing, persisted);
            if (request.getEnabled() != null) persisted.setEnabled(request.getEnabled());
            if (request.getPrefix() != null)
                persisted.setPrefix(com.astral.common.util.PatchValues.blankToNull(request.getPrefix()));
            return persisted;
        });
        SequenceConfig updated = controller.update(1L, request).getData();

        assertEquals(1000, updated.getStep());
        assertEquals(5000L, updated.getMinValue());
        assertEquals("ORD", updated.getPrefix());
        assertEquals("X", updated.getSuffix());
        assertEquals("yyyyMM", updated.getDateFormat());
        assertEquals("旧说明", updated.getDescription());
        assertEquals(false, updated.getEnabled());
    }

    @Test
    void blankPrefixInRequestStillClearsStoredPrefix() {
        SequenceConfig existing = existing("order", "SEGMENT");
        existing.setPrefix("ORD");
        when(configMapper.lockById(1L)).thenReturn(existing);
        when(entityIdSequenceProvider.isEntityIdBizKey("order")).thenReturn(false);
        when(configMapper.update(isNull(), any())).thenReturn(1);

        SequenceConfigUpdate request = new SequenceConfigUpdate();
        request.setBizKey("order");
        request.setSequenceType("SEGMENT");
        request.setPrefix("");

        when(configMapper.selectById(1L)).thenAnswer(call -> {
            SequenceConfig persisted = new SequenceConfig();
            org.springframework.beans.BeanUtils.copyProperties(existing, persisted);
            if (request.getEnabled() != null) persisted.setEnabled(request.getEnabled());
            if (request.getPrefix() != null)
                persisted.setPrefix(com.astral.common.util.PatchValues.blankToNull(request.getPrefix()));
            return persisted;
        });
        SequenceConfig updated = controller.update(1L, request).getData();

        assertEquals(null, updated.getPrefix());
    }

    @Test
    void concurrentDuplicateCreateMapsUniqueConflictToBusinessError() {
        when(entityIdSequenceProvider.isEntityIdBizKey("order")).thenReturn(false);
        when(configMapper.selectByBizKey("order")).thenReturn(null);
        when(configMapper.insert(any(SequenceConfig.class)))
                .thenThrow(new DuplicateKeyException("uk_sequence_config_biz_key"));

        SequenceConfig request = new SequenceConfig();
        request.setBizKey("order");
        request.setSequenceType("SEGMENT");
        request.setStep(100);

        BusinessException error = assertThrows(BusinessException.class, () -> controller.create(request));
        assertEquals("SEQ003", error.getErrorCode());
        verify(generatorFactory, never()).evictConfigCache();
    }

    @Test
    void outOfRangeStepIsRejectedBeforeWriting() {
        SequenceConfig existing = existing("order", "SEGMENT");
        when(configMapper.lockById(1L)).thenReturn(existing);
        when(entityIdSequenceProvider.isEntityIdBizKey("order")).thenReturn(false);

        SequenceConfigUpdate request = new SequenceConfigUpdate();
        request.setBizKey("order");
        request.setSequenceType("SEGMENT");
        request.setStep(0);

        BusinessException error = assertThrows(BusinessException.class, () -> controller.update(1L, request));
        assertEquals(320, error.getCode().intValue());
        verify(configMapper, never()).update(isNull(), any());
    }

    private static SequenceConfig existing(String bizKey, String type) {
        SequenceConfig config = new SequenceConfig();
        config.setId(1L);
        config.setBizKey(bizKey);
        config.setSequenceType(type);
        config.setStep(1000);
        config.setEnabled(true);
        return config;
    }
}
