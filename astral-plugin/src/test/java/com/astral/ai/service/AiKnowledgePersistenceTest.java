package com.astral.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.ai.config.AiProperties;
import com.astral.ai.config.AiSchemaInitializer;
import com.astral.ai.entity.AiAgentKnowledgeEntity;
import com.astral.ai.mapper.AiAgentKnowledgeMapper;
import com.astral.ai.mapper.AiKnowledgeChunkMapper;
import com.astral.ai.mapper.AiKnowledgeDocumentMapper;
import com.astral.ai.mapper.AiKnowledgeIndexJobMapper;
import com.astral.ai.mapper.AiKnowledgeSourceMapper;
import com.astral.common.exception.BusinessException;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
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
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

@EnabledIfSystemProperty(named = "audit.postgres.port", matches = "[0-9]+")
class AiKnowledgePersistenceTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private String schema;
    private AiKnowledgeService service;
    private Long source;
    private final ReadBounds bounds = new ReadBounds();

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + System.getProperty("audit.postgres.port") + "/postgres";
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "astral_audit", ""));
        schema = "knowledge_audit_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        dataSource = new DriverManagerDataSource(url + "?currentSchema=" + schema, "astral_audit", "");
        jdbc = new JdbcTemplate(dataSource);
        String ddl = Files.readString(Path.of("src/main/resources/sql/ai-schema.sql"));
        jdbc.execute(ddl.substring(0, ddl.indexOf("INSERT INTO sequence_segment")));
        jdbc.update("INSERT INTO ai_agent_version(id,agent_id,version_no,status) VALUES(1,1,1,'DRAFT')");
        jdbc.execute(Files.readString(Path.of(
                "../astral-server/src/main/resources/db/migration/V20261108004__knowledge_bounded_search.sql")));
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        config.addInterceptor(bounds);
        var pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        config.addInterceptor(pagination);
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
        config.addMappers("com.astral.ai.mapper");
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var target = new AiKnowledgeService(
                session.getMapper(AiKnowledgeSourceMapper.class),
                session.getMapper(AiKnowledgeDocumentMapper.class),
                session.getMapper(AiKnowledgeChunkMapper.class),
                session.getMapper(AiKnowledgeIndexJobMapper.class),
                session.getMapper(AiAgentKnowledgeMapper.class),
                new DataSourceTransactionManager(dataSource));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        service = (AiKnowledgeService) proxy.getProxy();
        source = service.createSource("fixture", "Fixture", null, null, null, true, null, 1L)
                .getId();
    }

    @AfterEach
    void clean() {
        if (schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void fallbackUpgradeMakesLegacyJobsRetryableAndPreservesNewProgressOnRestart() throws Exception {
        service.saveDocument(source, "first", "First", null, null, null, "alpha", 1L);
        service.saveDocument(source, "second", "Second", null, null, null, "beta", 1L);
        jdbc.execute("ALTER TABLE ai_knowledge_index_job DROP COLUMN last_document_id, DROP COLUMN end_document_id");
        jdbc.execute("ALTER TABLE ai_knowledge_index_job ALTER COLUMN status SET DEFAULT 'QUEUED'");
        jdbc.execute("ALTER TABLE ai_message ALTER COLUMN status SET DEFAULT 'SUCCEEDED'");
        jdbc.update(
                "INSERT INTO ai_knowledge_index_job(id,source_id,status,total_count,processed_count) VALUES(900,?,'RUNNING',2,1),(901,?,'QUEUED',2,0)",
                source,
                source);
        jdbc.execute(
                "CREATE TABLE sequence_segment(biz_key VARCHAR(128) PRIMARY KEY,min_value BIGINT,max_value BIGINT,current_max_value BIGINT,step BIGINT)");
        var initializer = new AiSchemaInitializer();
        ReflectionTestUtils.setField(initializer, "dataSource", dataSource);
        ReflectionTestUtils.setField(initializer, "aiProperties", new AiProperties());

        initializer.init();
        assertEquals("FAILED", service.requireJob(900L).getStatus());
        assertEquals("FAILED", service.requireJob(901L).getStatus());
        assertTrue(service.requireJob(900L).getErrorSummary().contains("重试"));
        var queuedRetry = service.retryJob(901L, 2L);
        service.cancelJob(queuedRetry.getId());
        var runningRetry = service.retryJob(900L, 2L);
        assertTrue(service.processNextIndexDocument());
        long cursor = service.requireJob(runningRetry.getId()).getLastDocumentId();
        assertTrue(cursor > 0);

        initializer.init();
        jdbc.execute(Files.readString(
                Path.of("../astral-server/src/main/resources/db/migration/V20261108008__knowledge_durable_jobs.sql")));
        assertEquals("RUNNING", service.requireJob(runningRetry.getId()).getStatus());
        assertEquals(cursor, service.requireJob(runningRetry.getId()).getLastDocumentId());
        assertEquals(1, service.requireJob(runningRetry.getId()).getProcessedCount());
        drain();
        assertEquals("SUCCEEDED", service.requireJob(runningRetry.getId()).getStatus());
        assertEquals(2, service.requireJob(runningRetry.getId()).getProcessedCount());
        assertEquals(
                "'PENDING'::character varying",
                jdbc.queryForObject(
                        "SELECT column_default FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='ai_knowledge_index_job' AND column_name='status'",
                        String.class));
        assertEquals(
                "'COMPLETED'::character varying",
                jdbc.queryForObject(
                        "SELECT column_default FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='ai_message' AND column_name='status'",
                        String.class));
        assertEquals(
                2L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN ('idx_ai_index_job_pending','uk_ai_index_job_active_source')",
                        Long.class));
    }

    @Test
    void rebuildingPreservesConcurrentSourceDisableAndRename() throws Exception {
        service.saveDocument(source, "term", "Document", null, null, null, "alpha only", 1L);
        service.replaceBindings(1L, List.of(binding(source, "0")));
        bounds.afterDocumentRead = () -> {
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(() -> service.updateSource(source, "Renamed", null, null, null, false, null, 2L))
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        };
        var job = service.syncSource(source, 1L);
        drain();
        assertEquals("SUCCEEDED", service.requireJob(job.getId()).getStatus());
        assertEquals(false, service.requireSource(source).getEnabled());
        assertEquals("Renamed", service.requireSource(source).getName());
        assertTrue(service.search(1L, "alpha", 5).isEmpty());
    }

    @Test
    void rebuildingReturnsADurablePendingJobBeforeProcessingAnyDocument() {
        service.saveDocument(source, "queued", "Document", null, null, null, "alpha", 1L);
        var job = service.syncSource(source, 1L);
        assertEquals("PENDING", job.getStatus());
        assertEquals(0, service.requireJob(job.getId()).getProcessedCount());
        assertEquals(job.getId(), service.syncSource(source, 2L).getId(), "one active job per source");
    }

    @Test
    void cancellationRetainsProgressAndRetryCreatesANewAuditableJob() {
        for (int i = 0; i < 3; i++) service.saveDocument(source, "doc" + i, "Document", null, null, null, "alpha", 1L);
        var job = service.syncSource(source, 1L);
        assertTrue(service.processNextIndexDocument());
        assertEquals(1, service.requireJob(job.getId()).getProcessedCount());
        service.cancelJob(job.getId());
        assertEquals("CANCELLED", service.requireJob(job.getId()).getStatus());
        assertEquals(false, service.processNextIndexDocument());
        var retry = service.retryJob(job.getId(), 2L);
        assertTrue(!retry.getId().equals(job.getId()));
        drain();
        assertEquals("SUCCEEDED", service.requireJob(retry.getId()).getStatus());
        assertEquals(3, service.requireJob(retry.getId()).getProcessedCount());
        assertEquals("CANCELLED", service.requireJob(job.getId()).getStatus());
        assertEquals(2L, service.requireJob(retry.getId()).getCreatedBy());
    }

    @Test
    void failedDocumentRollsBackItsChunksAndLeavesARetryableFailure() {
        var document = service.saveDocument(source, "doc", "Document", null, null, null, "original", 1L);
        var job = service.syncSource(source, 1L);
        bounds.afterDocumentRead = () -> {
            throw new IllegalStateException("fixture document failure");
        };
        service.processNextIndexDocument();
        assertEquals("FAILED", service.requireJob(job.getId()).getStatus());
        assertEquals(0, service.requireJob(job.getId()).getProcessedCount());
        assertEquals(
                "original",
                jdbc.queryForObject(
                        "SELECT content FROM ai_knowledge_chunk WHERE document_id=?", String.class, document.getId()));
        var retry = service.retryJob(job.getId(), 1L);
        drain();
        assertEquals("SUCCEEDED", service.requireJob(retry.getId()).getStatus());
    }

    @Test
    void aNewWorkerContinuesThePersistedCursorWithoutReprocessingCompletedDocuments() {
        for (int i = 0; i < 4; i++) service.saveDocument(source, "doc" + i, "Document", null, null, null, "alpha", 1L);
        var job = service.syncSource(source, 1L);
        service.processNextIndexDocument();
        long cursor = service.requireJob(job.getId()).getLastDocumentId();
        assertTrue(cursor > 0);
        var plugins = org.mockito.Mockito.mock(com.astral.plugin.api.PluginRegistry.class);
        org.mockito.Mockito.when(plugins.isEnabled("ai")).thenReturn(true);
        new AiKnowledgeIndexWorker(service, new com.astral.ai.config.AiProperties(), plugins).tick();
        assertEquals("SUCCEEDED", service.requireJob(job.getId()).getStatus());
        assertEquals(4, service.requireJob(job.getId()).getProcessedCount());
        assertEquals(
                4,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ai_knowledge_chunk WHERE source_id=?", Integer.class, source));
    }

    private void drain() {
        for (int i = 0; i < 100; i++) if (!service.processNextIndexDocument()) return;
        throw new AssertionError("fixture queue did not finish");
    }

    @Test
    void oversizedUpdateRejectsBeforeChangingTheExistingDocumentOrIndex() throws Exception {
        var original = service.saveDocument(source, "limit", "Document", null, null, null, "original term", 1L);
        assertThrows(
                BusinessException.class,
                () -> service.saveDocument(source, "limit", "Changed", null, null, null, "a".repeat(400_001), 1L));
        assertEquals("original term", service.requireDocument(original.getId()).getContent());
        var binding = binding(source, "0");
        service.replaceBindings(1L, List.of(binding));
        assertEquals(
                "original term", service.search(1L, "original", 5).getFirst().content());
        String boundary = "x".repeat(399_999) + "z";
        var saved = service.saveDocument(source, "limit", "Document", null, null, null, boundary, 1L);
        assertEquals(boundary, service.requireDocument(saved.getId()).getContent());
        assertEquals(
                HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(boundary.getBytes(StandardCharsets.UTF_8))),
                saved.getContentHash());
    }

    @Test
    void savedThresholdIsAppliedPerSourceBeforeGlobalRanking() {
        var document = service.saveDocument(source, "term", "Document", null, null, null, "alpha only", 1L);
        Long second = service.createSource("other", "Other", null, null, null, true, null, 1L)
                .getId();
        var otherDocument = service.saveDocument(second, "other", "Document", null, null, null, "alpha only", 1L);
        service.replaceBindings(1L, List.of(binding(source, "0.8"), binding(second, "0.2")));
        var hits = service.search(1L, "alpha beta", 5);
        assertEquals(1, hits.size());
        assertEquals(otherDocument.getId(), hits.getFirst().documentId());
        assertTrue(hits.getFirst().score() >= 0.2 && hits.getFirst().score() < 0.8);
        service.replaceBindings(1L, List.of(binding(source, "0.2")));
        assertEquals(
                document.getId(), service.search(1L, "alpha beta", 5).getFirst().documentId());
    }

    @Test
    void indexedCandidatesFindUniqueLateChunksWithoutLoadingUnrelatedBodies() {
        var document = service.saveDocument(source, "late", "Document", null, null, null, "unrelated", 1L);
        jdbc.update("""
                INSERT INTO ai_knowledge_chunk(id,source_id,document_id,chunk_no,content)
                SELECT 100000+n,?,?,n,'unrelated filler ' || n FROM generate_series(1,20000) n
                """, source, document.getId());
        jdbc.update(
                "INSERT INTO ai_knowledge_chunk(id,source_id,document_id,chunk_no,content) VALUES(999999,?,?,20001,'zebratarget 音乐')",
                source,
                document.getId());
        service.replaceBindings(1L, List.of(binding(source, "0")));
        bounds.maximumRows = 0;
        var hits = service.search(1L, "zebratarget", 5);
        assertEquals(1, hits.size());
        assertEquals(20001, hits.getFirst().chunkNo());
        assertEquals(1, service.search(1L, "音乐", 5).size());
        assertEquals(1, service.search(1L, "音", 5).size());
        assertTrue(bounds.maximumRows <= 20, "retrieval must not materialize every chunk or document body");
        jdbc.execute("ANALYZE ai_knowledge_chunk");
        String plan = String.join(
                "\n",
                jdbc.queryForList(
                        "EXPLAIN (ANALYZE,BUFFERS) SELECT id FROM ai_knowledge_chunk WHERE astral_knowledge_grams(content) && ARRAY['zeb']",
                        String.class));
        assertTrue(plan.contains("idx_ai_chunk_grams"), plan);
        var first = service.search(1L, "unrelated", 5);
        var repeated = service.search(1L, "unrelated", 5);
        assertEquals(5, first.size());
        assertEquals(first, repeated);
    }

    @Test
    void documentPagesDoNotLoadContentAndCountsUseAnAggregate() {
        jdbc.update("""
                INSERT INTO ai_knowledge_document(id,source_id,doc_key,title,content,chunk_count)
                SELECT 100000+n,?,'document-' || LPAD(n::text,4,'0'),'Document',REPEAT('body',10000),2
                FROM generate_series(1,205) n
                """, source);
        var page = service.listDocuments(source, 2, 20);
        assertEquals(205, page.getTotal());
        assertEquals(20, page.getRecords().size());
        assertEquals("document-0021", page.getRecords().getFirst().getDocKey());
        assertTrue(page.getRecords().stream().allMatch(row -> row.getContent() == null));
        assertEquals(410L, service.chunkCounts().get(source));
    }

    @Test
    void migrationConvertsWeightedLegacyScoresExactlyOnceAndPreservesNormalizedSettings() throws Exception {
        jdbc.execute(
                "INSERT INTO ai_agent_knowledge(id,agent_version_id,knowledge_source_id,min_score,score_scale) VALUES(20,20,20,0.2,'LEGACY'),(21,21,21,30,'LEGACY'),(22,22,22,0.8,'LEGACY')");
        jdbc.execute("ALTER TABLE ai_agent_knowledge ALTER COLUMN score_scale DROP NOT NULL");
        jdbc.execute("UPDATE ai_agent_knowledge SET score_scale=NULL");
        String migration = Files.readString(
                Path.of("../astral-server/src/main/resources/db/migration/V20261108004__knowledge_bounded_search.sql"));
        jdbc.execute(migration);
        jdbc.execute(migration);
        assertEquals(
                new BigDecimal("0.200000"),
                jdbc.queryForObject("SELECT min_score FROM ai_agent_knowledge WHERE id=20", BigDecimal.class));
        assertEquals(
                new BigDecimal("0.209790"),
                jdbc.queryForObject("SELECT min_score FROM ai_agent_knowledge WHERE id=21", BigDecimal.class));
        assertEquals(
                new BigDecimal("0.800000"),
                jdbc.queryForObject("SELECT min_score FROM ai_agent_knowledge WHERE id=22", BigDecimal.class));
        service.replaceBindings(1L, List.of(binding(source, "0.2")));
        assertThrows(BusinessException.class, () -> service.replaceBindings(1L, List.of(binding(source, "30"))));
        assertEquals(
                new BigDecimal("0.200000"), service.listBindings(1L).getFirst().getMinScore());
    }

    @Intercepts({
        @Signature(
                type = Executor.class,
                method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(
                type = Executor.class,
                method = "query",
                args = {
                    MappedStatement.class,
                    Object.class,
                    RowBounds.class,
                    ResultHandler.class,
                    org.apache.ibatis.cache.CacheKey.class,
                    org.apache.ibatis.mapping.BoundSql.class
                })
    })
    static class ReadBounds implements Interceptor {
        int maximumRows;

        @FunctionalInterface
        interface Action {
            void run() throws Exception;
        }

        Action afterDocumentRead;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            if (result instanceof List<?> rows) maximumRows = Math.max(maximumRows, rows.size());
            if (afterDocumentRead != null
                    && ((MappedStatement) invocation.getArgs()[0])
                            .getId()
                            .endsWith("AiKnowledgeDocumentMapper.selectList")) {
                Action action = afterDocumentRead;
                afterDocumentRead = null;
                action.run();
            }
            return result;
        }
    }

    private AiAgentKnowledgeEntity binding(Long sourceId, String minimum) {
        var binding = new AiAgentKnowledgeEntity();
        binding.setKnowledgeSourceId(sourceId);
        binding.setMinScore(new BigDecimal(minimum));
        binding.setRetrievalTopK(5);
        return binding;
    }
}
