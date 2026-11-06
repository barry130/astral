package com.astral.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import com.astral.storage.dto.UploadOperationDtos;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.entity.StorageUploadOperationEntity;
import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageUploadOperationMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class UploadOperationServiceTest {

    private StorageUploadOperationMapper operations;
    private StorageFolderService folderService;
    private UploadPolicyService policyService;
    private StorageTargetResolver resolver;
    private UploadOperationService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), StorageUploadOperationEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), StorageFileEntity.class);
        operations = mock(StorageUploadOperationMapper.class);
        folderService = mock(StorageFolderService.class);
        policyService = mock(UploadPolicyService.class);
        resolver = mock(StorageTargetResolver.class);
        service = new UploadOperationService(
                operations,
                mock(StorageFileMapper.class),
                folderService,
                policyService,
                mock(UploadQuotaService.class),
                mock(UploadAdmissionControl.class),
                mock(UploadImageValidator.class),
                resolver,
                mock(HttpUploadGateway.class),
                new StorageProperties(),
                new ObjectMapper(),
                mock(PlatformTransactionManager.class));
        StorageFolderEntity folder = new StorageFolderEntity();
        folder.setId(11L);
        folder.setTargetVersion(3L);
        // 两步上传仅允许 PUBLIC 目录（STORAGE059 门禁），测试基线文件夹按公开目录构造
        folder.setVisibility(StorageFolderEntity.VISIBILITY_PUBLIC);
        when(folderService.getById(11L)).thenReturn(folder);
        when(policyService.resolve(folder))
                .thenReturn(new UploadPolicyService.PolicySpec(
                        false, 0L, 1_000_000L, List.of("image/png"), List.of(), 0, null, null, null));
        when(resolver.resolveForUpload(any(), any()))
                .thenReturn(new StorageTargetResolver.Resolution(
                        "FAILOVER",
                        11L,
                        3L,
                        "FOLDER",
                        List.of(new StorageTargetResolver.Tier(
                                1, List.of(new StorageTargetResolver.Candidate(5L, 7L, "CUSTOM_HTTP", "api")))),
                        "{}"));
        when(operations.selectCount(any())).thenReturn(0L);
    }

    @Test
    void createPersistsIntentWithHashedKeyOnly() throws Exception {
        UploadOperationDtos.UploadCreateResp resp = service.create(req(), "u1", "key-1", false);
        assertEquals("CREATED", resp.state());
        assertEquals(true, resp.canSendContent());

        ArgumentCaptor<StorageUploadOperationEntity> captor =
                ArgumentCaptor.forClass(StorageUploadOperationEntity.class);
        verify(operations).insert(captor.capture());
        StorageUploadOperationEntity saved = captor.getValue();
        assertEquals("u1", saved.getActorId());
        assertEquals(11L, saved.getFolderId());
        assertEquals(5L, saved.getChosenConfigId());
        assertEquals(7L, saved.getChosenConfigVersion());
        assertEquals(3L, saved.getFolderVersion());
        assertNotEquals("key-1", saved.getIdempotencyKeyHash());
        assertEquals(sha256Hex("idem:key-1"), saved.getIdempotencyKeyHash());
        assertNotNull(saved.getIntentFingerprint());
        assertEquals(0, saved.getQuotaExempt());
        assertEquals(StorageUploadOperationEntity.CHARGE_NONE, saved.getQuotaCharge());
    }

    @Test
    void replayWithSameKeyAndFingerprintReturnsOriginalWithoutResolvingAgain() {
        UploadOperationDtos.UploadCreateResp first = service.create(req(), "u1", "key-1", false);
        ArgumentCaptor<StorageUploadOperationEntity> captor =
                ArgumentCaptor.forClass(StorageUploadOperationEntity.class);
        verify(operations).insert(captor.capture());
        when(operations.selectOne(any())).thenReturn(captor.getValue());

        UploadOperationDtos.UploadCreateResp replay = service.create(req(), "u1", "key-1", false);
        assertEquals(first.uploadId(), replay.uploadId());
        assertEquals(first.publicId(), replay.publicId());
        verify(operations, times(1)).insert(any(StorageUploadOperationEntity.class));
        verify(resolver, times(1)).resolveForUpload(any(), any());
    }

    @Test
    void replayWithChangedFingerprintIsRejected() {
        StorageUploadOperationEntity existing = new StorageUploadOperationEntity();
        existing.setUploadId("u");
        existing.setPublicId("p");
        existing.setState(StorageUploadOperationEntity.STATE_CREATED);
        existing.setIntentFingerprint("different");
        when(operations.selectOne(any())).thenReturn(existing);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(req(), "u1", "key-1", false));
        assertEquals("STORAGE043", ex.getErrorCode());
    }

    @Test
    void missingOrInvalidIdempotencyKeyIsRejected() {
        BusinessException missing =
                assertThrows(BusinessException.class, () -> service.create(req(), "u1", null, false));
        assertEquals(Integer.valueOf(320), missing.getCode());
        BusinessException blank = assertThrows(BusinessException.class, () -> service.create(req(), "u1", " ", false));
        assertEquals(Integer.valueOf(320), blank.getCode());
        BusinessException unicode =
                assertThrows(BusinessException.class, () -> service.create(req(), "u1", "键-1", false));
        assertEquals(Integer.valueOf(320), unicode.getCode());
    }

    @Test
    void declaredSizeABoveDirectoryLimitIsRejected() {
        UploadOperationDtos.UploadCreateReq tooBig =
                new UploadOperationDtos.UploadCreateReq(11L, "a.png", "image/png", 1_000_001L, null);
        BusinessException ex =
                assertThrows(BusinessException.class, () -> service.create(tooBig, "u1", "key-1", false));
        assertEquals("STORAGE045", ex.getErrorCode());
    }

    private UploadOperationDtos.UploadCreateReq req() {
        return new UploadOperationDtos.UploadCreateReq(11L, "a.png", "image/png", 1024L, null);
    }

    private String sha256Hex(String value) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
