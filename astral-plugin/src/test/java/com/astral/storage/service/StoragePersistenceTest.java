package com.astral.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import com.astral.storage.dto.StorageDtos;
import com.astral.storage.dto.UploadOperationDtos;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.mapper.StorageAuditMapper;
import com.astral.storage.mapper.StorageConfigMapper;
import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageFolderConfigMapper;
import com.astral.storage.mapper.StorageFolderMapper;
import com.astral.storage.mapper.StorageFolderPermissionMapper;
import com.astral.storage.mapper.StoragePermissionConfigMapper;
import com.astral.storage.mapper.StorageTaskMapper;
import com.astral.storage.mapper.StorageUploadOperationMapper;
import com.astral.storage.security.UploadTicketService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Actual storage services, schema, mappers and PG locks; tickets are an isolated Redis adapter. */
@EnabledIfSystemProperty(named = "audit.postgres.port", matches = "[0-9]+")
class StoragePersistenceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentHashMap<String, String> tickets = new ConcurrentHashMap<>();
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private SqlSessionTemplate session;
    private DataSourceTransactionManager transactions;
    private StorageFolderService folders;
    private StorageFileService files;
    private UploadPolicyService policies;
    private UploadQuotaService quotas;
    private UploadOperationService operations;
    private UploadAdmissionControl admission;
    private HttpUploadGateway gateway;
    private UploadTicketService ticketService;
    private LegacyUploadReceiptService receipts;
    private final QueryGate queryGate = new QueryGate();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + System.getProperty("audit.postgres.port") + "/postgres";
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "astral_audit", ""));
        schema = "storage_audit_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema, "astral_audit", "");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        try (var paths = Files.list(Path.of("src/main/resources/schema"))) {
            for (Path path : paths.filter(p -> p.getFileName().toString().startsWith("sys_storage_"))
                    .toList()) {
                var table = json.readTree(Files.readString(path));
                var columns = new ArrayList<String>();
                for (var field : table.path("fields")) {
                    columns.add(field.path("columnName").asString() + " "
                            + field.path("jdbcType").asString().replace("TINYINT", "SMALLINT")
                            + ("CHAR".equals(field.path("jdbcType").asString())
                                    ? "(" + field.path("length").asInt(64) + ")"
                                    : "")
                            + (field.path("isPrimaryKey").asBoolean(false) ? " PRIMARY KEY" : ""));
                }
                jdbc.execute(
                        "CREATE TABLE " + table.path("tableName").asString() + " (" + String.join(",", columns) + ")");
            }
        }
        jdbc.execute("CREATE UNIQUE INDEX upload_key ON sys_storage_file(upload_id)");
        jdbc.execute(
                "CREATE UNIQUE INDEX operation_key ON sys_storage_upload_operation(actor_type,actor_id,idempotency_key_hash)");
        jdbc.execute("CREATE UNIQUE INDEX charge_key ON sys_storage_upload_charge(upload_id)");
        jdbc.execute("CREATE UNIQUE INDEX ticket_key ON sys_storage_upload_ticket(upload_id)");
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        config.addInterceptor(queryGate);
        AtomicLong ids = new AtomicLong(1000);
        GlobalConfigUtils.setGlobalConfig(
                config,
                new GlobalConfig()
                        .setDbConfig(new GlobalConfig.DbConfig())
                        .setMetaObjectHandler(new MetaObjectHandler() {
                            @Override
                            public void insertFill(MetaObject object) {
                                if (getFieldValByName("id", object) == null)
                                    setFieldValByName("id", ids.incrementAndGet(), object);
                                setFieldValByName("createTime", LocalDateTime.now(), object);
                                setFieldValByName("updateTime", LocalDateTime.now(), object);
                            }

                            @Override
                            public void updateFill(MetaObject object) {
                                setFieldValByName("updateTime", LocalDateTime.now(), object);
                            }
                        }));
        config.addMappers("com.astral.storage.mapper");
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        quotas = proxy(new UploadQuotaService(
                map(StorageUploadOperationMapper.class),
                map(StorageFileMapper.class),
                map(com.astral.storage.mapper.StorageUploadChargeMapper.class)));
        policies = new UploadPolicyService(map(StorageFileMapper.class), json, quotas);
        var resolver = new StorageTargetResolver(
                map(StorageConfigMapper.class),
                map(StorageFolderConfigMapper.class),
                map(StorageFolderPermissionMapper.class),
                map(StoragePermissionConfigMapper.class),
                new TargetSelectionCursor(),
                json);
        receipts = proxy(new LegacyUploadReceiptService(
                map(com.astral.storage.mapper.StorageUploadTicketMapper.class),
                map(StorageFileMapper.class),
                map(StorageFolderMapper.class),
                map(StorageConfigMapper.class),
                map(StorageTaskMapper.class),
                resolver,
                json));
        var audit = new StorageAuditService();
        ReflectionTestUtils.setField(audit, "auditMapper", map(StorageAuditMapper.class));
        folders = proxy(new StorageFolderService(
                map(StorageFolderMapper.class),
                map(StorageFolderConfigMapper.class),
                map(StorageFolderPermissionMapper.class),
                map(StoragePermissionConfigMapper.class),
                map(StorageConfigMapper.class),
                map(StorageFileMapper.class),
                audit,
                policies,
                resolver,
                map(StorageUploadOperationMapper.class),
                receipts));
        var properties = new StorageProperties();
        properties.setUploadTicketKey("fixture-ticket-key-only");
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> tickets.get(call.getArgument(0, String.class)));
        org.mockito.Mockito.doAnswer(call -> {
                    tickets.put(call.getArgument(0), call.getArgument(1));
                    return null;
                })
                .when(values)
                .set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(java.time.Duration.class));
        ticketService = new UploadTicketService(properties, redis, json, null, null, null, null, policies, receipts);
        var cache = new StorageConfigCache();
        ReflectionTestUtils.setField(cache, "configMapper", map(StorageConfigMapper.class));
        files = proxy(new StorageFileService(
                map(StorageFileMapper.class),
                map(StorageTaskMapper.class),
                cache,
                folders,
                audit,
                ticketService,
                policies,
                null,
                null,
                null,
                null,
                null,
                null,
                properties,
                json,
                null));
        admission = new UploadAdmissionControl(properties);
        gateway = mock(HttpUploadGateway.class);
        when(gateway.loadChannelForSend(5L))
                .thenReturn(new HttpUploadGateway.Channel(5L, 1L, "CUSTOM_HTTP", null, null, null));
        operations = new UploadOperationService(
                map(StorageUploadOperationMapper.class),
                map(StorageFileMapper.class),
                folders,
                policies,
                quotas,
                admission,
                new UploadImageValidator(),
                resolver,
                gateway,
                properties,
                json,
                transactions);
        jdbc.update(
                "INSERT INTO sys_storage_folder(id,folder_name,folder_path,status,visibility,owner_type,owner_id,target_version,storage_strategy,upload_policy) VALUES(11,'fixture','/fixture','ENABLED','PUBLIC','USER','u1',1,'FAILOVER','{\"dailyUploadLimit\":1}')");
        jdbc.update(
                "INSERT INTO sys_storage_config(id,name,provider_type,status,config_version) VALUES(5,'fixture','CUSTOM_HTTP','ENABLED',1)");
        jdbc.update("INSERT INTO sys_storage_folder_config(id,folder_id,storage_config_id,priority) VALUES(6,11,5,1)");
    }

    @AfterEach
    void clean() {
        if (schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void deletingAnUploadedFileDoesNotRefundItsDailyAllowance() {
        StorageFileEntity first = callback("first");
        assertEquals(1, policies.countTodayUploads(11L, "u1"));
        files.requestDelete(first.getPublicId(), "u1", true);
        assertEquals(1, policies.countTodayUploads(11L, "u1"));
        assertThrows(BusinessException.class, () -> callback("second"));
    }

    @Test
    void folderWithPendingUploadCannotDisappearBeforeRegistration() {
        var created = operations.create(
                new UploadOperationDtos.UploadCreateReq(11L, "fixture.png", "image/png", 4L, null),
                "u1",
                "pending-intent",
                true);
        assertEquals("CREATED", created.state());
        assertThrows(BusinessException.class, () -> folders.delete(11L, "u1"));
        assertEquals(11L, folders.getById(11L).getId());
    }

    @Test
    void legacyTicketProtectsFolderUntilCallbackCanCommit() {
        var issued = issueLegacy();
        assertThrows(BusinessException.class, () -> folders.delete(11L, "u1"));
        assertTrue(issued.uploadId().startsWith("up_"));
    }

    @Test
    void legacyCallbackSurvivesRedisExpiryAndPreservesRemoteReceipt() {
        var issued = issueLegacy();
        tickets.clear();
        var file = files.registerFromCallback(new StorageDtos.WorkerCallbackReq(
                issued.uploadId(), "message", "file", "unique", 4L, "image/png", "fixture.png"));
        assertEquals("AVAILABLE", file.getStatus());
        assertEquals(
                "COMMITTED",
                jdbc.queryForObject(
                        "SELECT state FROM sys_storage_upload_ticket WHERE upload_id=?",
                        String.class,
                        issued.uploadId()));
        assertTrue(jdbc.queryForObject(
                        "SELECT receipt_json FROM sys_storage_upload_ticket WHERE upload_id=?",
                        String.class,
                        issued.uploadId())
                .contains("message"));
    }

    @Test
    void historicalMissingFolderLeavesDurableReceiptAndCleanupTask() {
        var issued = issueLegacy();
        jdbc.update("DELETE FROM sys_storage_folder WHERE id=11");
        assertThrows(
                BusinessException.class,
                () -> files.registerFromCallback(new StorageDtos.WorkerCallbackReq(
                        issued.uploadId(), "message", "file", "unique", 4L, "image/png", "fixture.png")));
        assertEquals(
                "CLEANUP_PENDING",
                jdbc.queryForObject(
                        "SELECT state FROM sys_storage_upload_ticket WHERE upload_id=?",
                        String.class,
                        issued.uploadId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_storage_task", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_storage_file", Integer.class));
        files.ackDeleteTask(jdbc.queryForObject("SELECT id FROM sys_storage_task", Long.class), true, null);
        assertEquals(
                "CLEANED",
                jdbc.queryForObject(
                        "SELECT state FROM sys_storage_upload_ticket WHERE upload_id=?",
                        String.class,
                        issued.uploadId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"size", "mime"})
    void rejectedRemoteReceiptStillPersistsTheLocatorAndCleanup(String invalid) {
        var issued = issueLegacy();
        var request = new StorageDtos.WorkerCallbackReq(
                issued.uploadId(),
                "message",
                "file",
                "unique",
                invalid.equals("size") ? 30_000_000L : 4L,
                invalid.equals("mime") ? "image/jpeg" : "image/png",
                "fixture.png");
        assertThrows(BusinessException.class, () -> files.registerFromCallback(request));
        assertEquals(
                "CLEANUP_PENDING",
                jdbc.queryForObject(
                        "SELECT state FROM sys_storage_upload_ticket WHERE upload_id=?",
                        String.class,
                        issued.uploadId()));
        assertTrue(jdbc.queryForObject(
                        "SELECT receipt_json FROM sys_storage_upload_ticket WHERE upload_id=?",
                        String.class,
                        issued.uploadId())
                .contains("message"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_storage_task", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_storage_file", Integer.class));
    }

    @Test
    void duplicateCleanupAcknowledgmentCannotClaimTheOriginalObjectIsDeleted() {
        var issued = issueLegacy();
        assertThrows(
                BusinessException.class,
                () -> files.registerFromCallback(new StorageDtos.WorkerCallbackReq(
                        issued.uploadId(),
                        "original-message",
                        "original-file",
                        "first",
                        30_000_000L,
                        "image/png",
                        "fixture.png")));
        assertThrows(
                BusinessException.class,
                () -> files.registerFromCallback(new StorageDtos.WorkerCallbackReq(
                        issued.uploadId(),
                        "duplicate-message",
                        "duplicate-file",
                        "second",
                        4L,
                        "image/png",
                        "fixture.png")));
        files.ackDeleteTask(
                jdbc.queryForObject(
                        "SELECT id FROM sys_storage_task WHERE payload_json LIKE '%duplicate-message%'", Long.class),
                true,
                null);
        assertEquals(
                "CLEANUP_PENDING",
                jdbc.queryForObject(
                        "SELECT state FROM sys_storage_upload_ticket WHERE upload_id=?",
                        String.class,
                        issued.uploadId()));
        files.ackDeleteTask(
                jdbc.queryForObject(
                        "SELECT id FROM sys_storage_task WHERE payload_json LIKE '%original-message%'", Long.class),
                true,
                null);
        assertEquals(
                "CLEANED",
                jdbc.queryForObject(
                        "SELECT state FROM sys_storage_upload_ticket WHERE upload_id=?",
                        String.class,
                        issued.uploadId()));
    }

    @Test
    void concurrentReplayPathsQueueOnlyOneTaskForTheSameExtraRemoteObject() throws Exception {
        var issued = issueLegacy();
        var context = ticketService.getPayload(issued.uploadId());
        var first = files.registerFromCallback(new StorageDtos.WorkerCallbackReq(
                issued.uploadId(), "original-message", "original-file", "first", 4L, "image/png", "fixture.png"));
        var replay = new StorageDtos.WorkerCallbackReq(
                issued.uploadId(), "duplicate-message", "duplicate-file", "second", 4L, "image/png", "fixture.png");
        CountDownLatch ready = new CountDownLatch(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                ready.countDown();
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                receipts.receive(context, replay);
                return true;
            });
            var b = pool.submit(() -> {
                ready.countDown();
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                receipts.receiveReplay(first, replay);
                return true;
            });
            assertTrue(a.get(5, TimeUnit.SECONDS));
            assertTrue(b.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_storage_task", Integer.class));
    }

    @Test
    void localRegistrationFailureIsRecoveredWithoutSendingAnotherUpload() {
        var issued = issueLegacy();
        jdbc.execute(
                "CREATE FUNCTION reject_registration() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''fixture registration failure''; END'");
        jdbc.execute(
                "CREATE TRIGGER register_failure BEFORE INSERT ON sys_storage_file FOR EACH ROW EXECUTE FUNCTION reject_registration()");
        assertThrows(
                RuntimeException.class,
                () -> files.registerFromCallback(new StorageDtos.WorkerCallbackReq(
                        issued.uploadId(), "message", "file", "unique", 4L, "image/png", "fixture.png")));
        assertEquals(
                "REMOTE_SUCCEEDED",
                jdbc.queryForObject(
                        "SELECT state FROM sys_storage_upload_ticket WHERE upload_id=?",
                        String.class,
                        issued.uploadId()));
        jdbc.execute("DROP TRIGGER register_failure ON sys_storage_file");
        new LegacyUploadRecoveryJob(map(com.astral.storage.mapper.StorageUploadTicketMapper.class), files, json)
                .recover();
        assertEquals(
                "COMMITTED",
                jdbc.queryForObject(
                        "SELECT state FROM sys_storage_upload_ticket WHERE upload_id=?",
                        String.class,
                        issued.uploadId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_storage_file", Integer.class));
    }

    @Test
    void olderUnrecoverableReceiptsDoNotStarveALaterValidReceipt() throws Exception {
        var issued = issueLegacy();
        String request = json.writeValueAsString(new StorageDtos.WorkerCallbackReq(
                issued.uploadId(), "message", "file", "unique", 4L, "image/png", "fixture.png"));
        jdbc.update(
                "UPDATE sys_storage_upload_ticket SET state='REMOTE_SUCCEEDED',receipt_json=?,update_time=CURRENT_TIMESTAMP WHERE upload_id=?",
                request,
                issued.uploadId());
        for (int i = 0; i < 20; i++) {
            jdbc.update(
                    "INSERT INTO sys_storage_upload_ticket(id,upload_id,folder_id,payload_json,receipt_json,state,update_time) VALUES(?, ?, 99, '{}', 'invalid', 'REMOTE_SUCCEEDED', CURRENT_TIMESTAMP - interval '1 day')",
                    2000 + i,
                    "invalid-" + i);
        }
        var recovery = new LegacyUploadRecoveryJob(
                map(com.astral.storage.mapper.StorageUploadTicketMapper.class), files, json);
        recovery.recover();
        recovery.recover();
        assertEquals(
                "COMMITTED",
                jdbc.queryForObject(
                        "SELECT state FROM sys_storage_upload_ticket WHERE upload_id=?",
                        String.class,
                        issued.uploadId()));
    }

    @Test
    void repeatedTicketReturnsOriginalAndQueuesOnlyDuplicateRemoteObjectForCleanup() {
        var issued = issueLegacy();
        var controller =
                new com.astral.storage.controller.StorageWorkerController(files, map(StorageConfigMapper.class), json);
        var first = controller
                .uploadCallback(new StorageDtos.WorkerCallbackReq(
                        issued.uploadId(), "message", "first-file", "first", 4L, "image/png", "fixture.png"))
                .getData();
        var second = controller
                .uploadCallback(new StorageDtos.WorkerCallbackReq(
                        issued.uploadId(), "second-message", "second-file", "second", 4L, "image/png", "fixture.png"))
                .getData();
        assertEquals(first.publicId(), second.publicId());
        assertEquals(true, first.contentMatches());
        assertEquals(false, second.contentMatches());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_storage_file", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT payload_json FROM sys_storage_task", String.class)
                .contains("second-message"));
        files.requestDelete(first.publicId(), "u1", true);
        assertThrows(
                BusinessException.class,
                () -> files.registerFromCallback(new StorageDtos.WorkerCallbackReq(
                        issued.uploadId(), "third-message", "third-file", "third", 4L, "image/png", "fixture.png")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_storage_file", Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_storage_task WHERE payload_json LIKE '%third-message%'",
                        Integer.class));
    }

    @Test
    void configurationDisableCommittedBeforeSendAuthorizationPreventsSending() throws Exception {
        jdbc.update("UPDATE sys_storage_folder SET upload_policy=NULL WHERE id=11");
        var first = operations.create(
                new UploadOperationDtos.UploadCreateReq(11L, "fixture.txt", "text/plain", 4L, null),
                "u1",
                "first",
                true);
        var prepared = operations.prepareContent(first.uploadId(), 4L, "text/plain", "u1", true, false);
        ReflectionTestUtils.setField(
                operations, "gateway", new HttpUploadGateway(map(StorageConfigMapper.class), null, null, null));
        CountDownLatch disabled = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var update = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("UPDATE sys_storage_config SET status='DISABLED' WHERE id=5");
                disabled.countDown();
                try {
                    assertTrue(commit.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }));
            assertTrue(disabled.await(5, TimeUnit.SECONDS));
            var sending = pool.submit(() -> operations.sendContent(prepared, new byte[4]));
            try {
                assertThrows(
                        java.util.concurrent.TimeoutException.class, () -> sending.get(300, TimeUnit.MILLISECONDS));
            } finally {
                commit.countDown();
            }
            update.get(5, TimeUnit.SECONDS);
            var failure =
                    assertThrows(java.util.concurrent.ExecutionException.class, () -> sending.get(5, TimeUnit.SECONDS));
            assertEquals("STORAGE002", ((BusinessException) failure.getCause()).getErrorCode());
        }
        assertEquals("CREATED", operations.get(first.uploadId(), "u1", false).state());
    }

    private UploadTicketService.IssuedTicket issueLegacy() {
        jdbc.update(
                "UPDATE sys_storage_config SET provider_type='TELEGRAM',worker_base_url='https://fixture.test',chat_id='fixture' WHERE id=5");
        return ticketService.issue(
                map(StorageConfigMapper.class).selectById(5L),
                folders.getById(11L),
                "u1",
                "fixture.png",
                "image/png",
                4);
    }

    @Test
    void recoveryKeepsRemoteResultIfHistoricalFolderHasAlreadyDisappeared() {
        jdbc.update(
                "INSERT INTO sys_storage_upload_operation(id,upload_id,public_id,actor_type,actor_id,folder_id,chosen_config_id,state,version,quota_charge,safe_result_json,expected_mime,original_name) VALUES(50,'remote','public','USER','u1',99,5,'REMOTE_SUCCEEDED',1,'HELD','{\"url\":\"https://example.test/fixture.png\"}','image/png','fixture.png')");
        var recovery = new UploadOperationRecoveryJob(
                map(StorageUploadOperationMapper.class),
                map(StorageFileMapper.class),
                admission,
                json,
                transactions,
                map(StorageFolderMapper.class));
        recovery.recover();
        assertEquals("REMOTE_SUCCEEDED", operations.get("remote", "u1", false).state());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_storage_file WHERE folder_id=99", Integer.class));
        assertTrue(jdbc.queryForObject(
                        "SELECT safe_result_json FROM sys_storage_upload_operation WHERE id=50", String.class)
                .contains("fixture.png"));
    }

    @Test
    void differentFoldersCannotRacePastTheSameUsersLastIntentSlot() throws Exception {
        operations.create(
                new UploadOperationDtos.UploadCreateReq(11L, "fixture.png", "image/png", 4L, null),
                "u1",
                "first",
                true);
        for (long id : new long[] {12, 13}) {
            jdbc.update(
                    "INSERT INTO sys_storage_folder(id,folder_name,folder_path,status,visibility,owner_type,owner_id,target_version,storage_strategy) VALUES(?, 'fixture','/fixture','ENABLED','PUBLIC','USER','u1',1,'FAILOVER')",
                    id);
            jdbc.update(
                    "INSERT INTO sys_storage_folder_config(id,folder_id,storage_config_id,priority) VALUES(?,?,5,1)",
                    id,
                    id);
        }
        CountDownLatch resolved = new CountDownLatch(2);
        queryGate.after = statement -> {
            if (statement.getId().endsWith("StorageConfigMapper.selectByIds")) {
                resolved.countDown();
                try {
                    assertTrue(resolved.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> createOrRejected(12L, "second"));
            var b = pool.submit(() -> createOrRejected(13L, "third"));
            assertEquals(1, a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_storage_upload_operation WHERE state='CREATED'", Integer.class));
    }

    @Test
    void concurrentLegacyRegistrationsShareTheAllowanceAndReplayDoesNotChargeAgain() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var a = pool.submit(() -> {
                start.await();
                return callbackOrRejected("a");
            });
            var b = pool.submit(() -> {
                start.await();
                return callbackOrRejected("b");
            });
            start.countDown();
            assertEquals(1, a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS));
        }
        String existing = jdbc.queryForObject("SELECT upload_id FROM sys_storage_file", String.class);
        callback(existing);
        assertEquals(1, policies.countTodayUploads(11L, "u1"));
    }

    @Test
    void newUploadReservationAlsoBlocksLegacyRegistration() {
        var intent = operations.create(
                new UploadOperationDtos.UploadCreateReq(11L, "fixture.png", "image/png", 4L, null), "u1", "new", true);
        try (var held = operations.prepareContent(intent.uploadId(), 4L, "image/png", "u1", true, false)) {
            assertThrows(BusinessException.class, () -> callback("legacy"));
            assertEquals(1, policies.countTodayUploads(11L, "u1"));
        }
    }

    @Test
    void sendingUploadKeepsItsIntentSlotUntilItsOutcomeIsKnown() throws Exception {
        jdbc.update("UPDATE sys_storage_folder SET upload_policy=NULL WHERE id=11");
        var first = operations.create(
                new UploadOperationDtos.UploadCreateReq(11L, "fixture.txt", "text/plain", 4L, null),
                "u1",
                "first",
                true);
        operations.create(
                new UploadOperationDtos.UploadCreateReq(11L, "fixture.txt", "text/plain", 4L, null),
                "u1",
                "second",
                true);
        var prepared = operations.prepareContent(first.uploadId(), 4L, "text/plain", "u1", true, false);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(gateway.send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    sending.countDown();
                    assertTrue(finish.await(5, TimeUnit.SECONDS));
                    return HttpUploadGateway.Result.notSent("CONNECT_REFUSED");
                });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var upload = pool.submit(() -> operations.sendContent(prepared, new byte[4]));
            try {
                assertTrue(sending.await(5, TimeUnit.SECONDS));
                var error = assertThrows(
                        BusinessException.class,
                        () -> operations.create(
                                new UploadOperationDtos.UploadCreateReq(11L, "fixture.txt", "text/plain", 4L, null),
                                "u1",
                                "third",
                                true));
                assertEquals("STORAGE042", error.getErrorCode());
            } finally {
                finish.countDown();
            }
            upload.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void verificationRefundFailureRollsBackFileStateAndCanBeRetried() {
        var file = callback("bad-content");
        jdbc.update(
                "UPDATE sys_storage_folder SET upload_policy='{\"dailyUploadLimit\":1,\"verifyContent\":\"magic\"}' WHERE id=11");
        // Fail at the durable refund, after the FAILED write; no external request is made for CUSTOM_HTTP.
        jdbc.execute(
                "CREATE FUNCTION reject_refund() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''fixture refund failure''; END'");
        jdbc.execute(
                "CREATE TRIGGER refund_failure BEFORE UPDATE ON sys_storage_upload_charge FOR EACH ROW EXECUTE FUNCTION reject_refund()");
        assertThrows(RuntimeException.class, () -> files.verifyContentAfterRegistration(file.getPublicId()));
        assertEquals("AVAILABLE", files.getByPublicId(file.getPublicId()).getStatus());
        assertEquals(1, policies.countTodayUploads(11L, "u1"));
        jdbc.execute("DROP TRIGGER refund_failure ON sys_storage_upload_charge");
        assertThrows(BusinessException.class, () -> files.verifyContentAfterRegistration(file.getPublicId()));
        assertEquals("FAILED", files.getByPublicId(file.getPublicId()).getStatus());
        assertEquals(0, policies.countTodayUploads(11L, "u1"));
    }

    @Test
    void senderReturnsSuccessWhenRecoveryCommitsBeforeItsRegistrationTransaction() throws Exception {
        jdbc.update("UPDATE sys_storage_folder SET upload_policy=NULL WHERE id=11");
        var first = operations.create(
                new UploadOperationDtos.UploadCreateReq(11L, "fixture.txt", "text/plain", 4L, null),
                "u1",
                "first",
                true);
        var prepared = operations.prepareContent(first.uploadId(), 4L, "text/plain", "u1", true, false);
        var recovery = new UploadOperationRecoveryJob(
                map(StorageUploadOperationMapper.class),
                map(StorageFileMapper.class),
                admission,
                json,
                transactions,
                map(StorageFolderMapper.class));
        // The transport observes SENDING outside any transaction and arranges a competing node's recovery
        // immediately after the durable remote receipt transaction, before the caller's next transaction.
        when(gateway.send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    assertEquals(
                            false,
                            org.springframework.transaction.support.TransactionSynchronizationManager
                                    .isActualTransactionActive());
                    queryGate.after = statement -> {
                        if (statement.getId().endsWith("StorageUploadOperationMapper.selectById")) {
                            queryGate.after = ignored -> {};
                            org.springframework.transaction.support.TransactionSynchronizationManager
                                    .registerSynchronization(
                                            new org.springframework.transaction.support.TransactionSynchronization() {
                                                @Override
                                                public void afterCompletion(int status) {
                                                    try (var pool = Executors.newSingleThreadExecutor()) {
                                                        pool.submit(recovery::recover)
                                                                .get(5, TimeUnit.SECONDS);
                                                    } catch (Exception e) {
                                                        throw new AssertionError(e);
                                                    }
                                                }
                                            });
                        }
                    };
                    return new HttpUploadGateway.Result(
                            HttpUploadGateway.Kind.REMOTE_CREATED,
                            200,
                            "CREATED",
                            "https://fixture.test/file",
                            null,
                            "fixture-id",
                            null,
                            null,
                            null);
                });
        var response = operations.sendContent(prepared, new byte[4]);
        assertEquals("COMMITTED", response.operation().state());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_storage_file", Integer.class));
    }

    @Test
    void directoryChangeCannotCommitBetweenAuthorizationAndSending() throws Exception {
        jdbc.update("UPDATE sys_storage_folder SET upload_policy=NULL WHERE id=11");
        var first = operations.create(
                new UploadOperationDtos.UploadCreateReq(11L, "fixture.txt", "text/plain", 4L, null),
                "u1",
                "first",
                true);
        var prepared = operations.prepareContent(first.uploadId(), 4L, "text/plain", "u1", true, false);
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        var armed = new java.util.concurrent.atomic.AtomicBoolean(true);
        queryGate.after = statement -> {
            if (statement.getId().endsWith("StorageConfigMapper.selectByIds") && armed.compareAndSet(true, false)) {
                checked.countDown();
                try {
                    assertTrue(proceed.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        };
        when(gateway.send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(HttpUploadGateway.Result.notSent("CONNECT_REFUSED"));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var result = pool.submit(() -> operations.sendContent(prepared, new byte[4]));
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            var change = pool.submit(
                    () -> folders.update(11L, new StorageDtos.FolderUpdateReq(null, null, "DISABLED"), "u1"));
            try {
                // If the sender owns the authorization lock, the update must wait for its SENDING commit.
                assertThrows(java.util.concurrent.TimeoutException.class, () -> change.get(300, TimeUnit.MILLISECONDS));
            } finally {
                proceed.countDown();
            }
            result.get(5, TimeUnit.SECONDS);
            change.get(5, TimeUnit.SECONDS);
        }
        assertEquals("DISABLED", folders.getById(11L).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"avatar-direct", "avatar-proxy", "cover-direct", "cover-proxy"})
    void reapplyingTheSameMediaNeverDeletesTheCurrentlyReferencedFile(String mode) throws Exception {
        var media = mediaFixture();
        for (int repeat = 0; repeat < 2; repeat++) {
            switch (mode) {
                case "avatar-direct" -> media.avatarComplete(41L, "avatar-upload", null);
                case "avatar-proxy" -> media.avatarProxyApply(41L, "avatar-upload");
                case "cover-direct" -> media.coverComplete(41L, "list", "fixture", "cover-upload", null);
                case "cover-proxy" -> media.coverProxyApply(41L, "list", "fixture", "cover-upload");
                default -> throw new AssertionError(mode);
            }
        }
        String publicId = mode.startsWith("avatar") ? "aaaaaaaaaaaaaaaa" : "bbbbbbbbbbbbbbbb";
        assertEquals("AVAILABLE", files.getByPublicId(publicId).getStatus());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_storage_task WHERE task_type='DELETE_REMOTE'", Integer.class));
    }

    @Test
    void clearingCoverRemovesThePersistedUrlAndAdvancesSyncSequence() throws Exception {
        var media = mediaFixture();
        media.coverComplete(41L, "list", "fixture", "cover-upload", null);
        media.coverClear(41L, "list", "fixture");
        var row = map(com.astral.qt.mapper.QtLikePlaylistMapper.class).selectById(201L);
        assertEquals(null, row.getPicUrl());
        assertEquals(2L, row.getUpdatedSeq());
    }

    private com.astral.qt.service.QtMediaService mediaFixture() throws Exception {
        for (String name : new String[] {"qt_like_playlist", "qt_like_song", "sys_user"}) {
            Path path = name.equals("sys_user")
                    ? Path.of("../astral-schema/src/main/resources/schema/sys_user.json")
                    : Path.of("src/main/resources/schema/" + name + ".json");
            var table = json.readTree(Files.readString(path));
            var columns = new ArrayList<String>();
            for (var field : table.path("fields"))
                columns.add(field.path("columnName").asString() + " "
                        + field.path("jdbcType").asString().replace("TINYINT", "SMALLINT")
                        + (field.path("isPrimaryKey").asBoolean(false) ? " PRIMARY KEY" : ""));
            jdbc.execute("CREATE TABLE " + name + " (" + String.join(",", columns) + ")");
        }
        var configuration = session.getSqlSessionFactory().getConfiguration();
        configuration.addMapper(com.astral.dao.mapper.UserMapper.class);
        configuration.addMapper(com.astral.qt.mapper.QtLikePlaylistMapper.class);
        configuration.addMapper(com.astral.qt.mapper.QtLikeSyncMapper.class);
        jdbc.update("INSERT INTO sys_user(id,username,status,deleted) VALUES(41,'fixture',1,0)");
        jdbc.update("INSERT INTO qt_like_playlist(id,uid,pid,platform,updated_seq) VALUES(201,41,'list','fixture',0)");
        jdbc.update(
                "INSERT INTO sys_storage_folder(id,folder_name,folder_path,status,visibility,owner_type,owner_id) VALUES(10,'qt-media','/qt-media','ENABLED','PUBLIC','ADMIN','qt'),(12,'playlist-cover','/qt-media/playlist-cover','ENABLED','PUBLIC','ADMIN','qt')");
        jdbc.update("UPDATE sys_storage_folder SET parent_id=10,folder_name='avatar',upload_policy=NULL WHERE id=11");
        jdbc.update("UPDATE sys_storage_folder SET parent_id=10 WHERE id=12");
        jdbc.update(
                "UPDATE sys_storage_config SET provider_type='TELEGRAM',is_default=1,worker_base_url='https://fixture.test' WHERE id=5");
        jdbc.update(
                "INSERT INTO sys_storage_file(id,public_id,upload_id,folder_id,storage_config_id,provider_type,status,uploader_id,visibility,content_version) VALUES(200,'aaaaaaaaaaaaaaaa','avatar-upload',11,5,'TELEGRAM','AVAILABLE','41','PUBLIC',1),(201,'bbbbbbbbbbbbbbbb','cover-upload',12,5,'TELEGRAM','AVAILABLE','41','PUBLIC',1)");
        jdbc.update(
                "INSERT INTO sys_storage_upload_operation(id,upload_id,actor_type,actor_id,folder_id,state,file_id) VALUES(200,'avatar-upload','USER','41',11,'COMMITTED',200),(201,'cover-upload','USER','41',12,'COMMITTED',201)");
        var cache = new StorageConfigCache();
        ReflectionTestUtils.setField(cache, "configMapper", map(StorageConfigMapper.class));
        var configService = new StorageConfigService();
        ReflectionTestUtils.setField(configService, "configCache", cache);
        ReflectionTestUtils.setField(configService, "configMapper", map(StorageConfigMapper.class));
        var media = new com.astral.qt.service.QtMediaService();
        ReflectionTestUtils.setField(media, "storageConfigService", configService);
        ReflectionTestUtils.setField(media, "storageFolderMapper", map(StorageFolderMapper.class));
        ReflectionTestUtils.setField(media, "storageFileMapper", map(StorageFileMapper.class));
        ReflectionTestUtils.setField(media, "storageFileService", files);
        ReflectionTestUtils.setField(media, "uploadOperationService", operations);
        ReflectionTestUtils.setField(media, "userMapper", map(com.astral.dao.mapper.UserMapper.class));
        ReflectionTestUtils.setField(media, "playlistMapper", map(com.astral.qt.mapper.QtLikePlaylistMapper.class));
        ReflectionTestUtils.setField(media, "likeSyncMapper", map(com.astral.qt.mapper.QtLikeSyncMapper.class));
        ReflectionTestUtils.setField(media, "transactionTemplate", new TransactionTemplate(transactions));
        return proxy(media);
    }

    private int callbackOrRejected(String id) {
        try {
            callback(id);
            return 1;
        } catch (BusinessException e) {
            assertEquals("STORAGE032", e.getErrorCode());
            return 0;
        }
    }

    private int createOrRejected(Long folder, String key) {
        try {
            operations.create(
                    new UploadOperationDtos.UploadCreateReq(folder, "fixture.png", "image/png", 4L, null),
                    "u1",
                    key,
                    true);
            return 1;
        } catch (BusinessException e) {
            assertEquals("STORAGE042", e.getErrorCode());
            return 0;
        }
    }

    @Intercepts(
            @Signature(
                    type = Executor.class,
                    method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class QueryGate implements Interceptor {
        volatile Consumer<MappedStatement> after = ignored -> {};

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            after.accept((MappedStatement) invocation.getArgs()[0]);
            return result;
        }
    }

    private StorageFileEntity callback(String uploadId) {
        tickets.put(
                "storage:ticket:" + uploadId,
                "{\"uploadId\":\"" + uploadId
                        + "\",\"folderId\":11,\"uploaderId\":\"u1\",\"configId\":5,\"chatId\":\"fixture\",\"mime\":\"image/png\",\"maxSize\":1000}");
        return files.registerFromCallback(new StorageDtos.WorkerCallbackReq(
                uploadId, "message", "file", "unique", 4L, "image/png", "fixture.png"));
    }

    private <T> T map(Class<T> type) {
        return session.getMapper(type);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
