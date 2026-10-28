package com.astral.qt.controller;

import com.astral.common.result.Result;
import com.astral.qt.entity.QtAppUpdate;
import com.astral.qt.entity.QtAppUpdateArtifact;
import com.astral.qt.mapper.QtAppUpdateMapper;
import com.astral.qt.service.QtAppService;
import com.astral.qt.service.QtAppUpdateArtifactService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real controller through Spring's transaction advice, with H2-backed mapper doubles. */
class QtAdminControllerUpdateTest {
    private static final long ID = 42L;
    private static final long ANDROID = 1101L;
    private static final long HARMONY = 1106L;
    private static final Pattern PARAMETER = Pattern.compile("#\\{ew\\.paramNameValuePairs\\.([^},]+)(?:,[^}]*)?}");

    private QtAdminController controller;
    private QtAppUpdateMapper updateMapper;
    private QtAppUpdateArtifactService artifacts;
    private QtAppService appService;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private boolean failArtifactWrite;
    private final AtomicInteger artifactIds = new AtomicInteger(100);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "update-test"),
                QtAppUpdate.class);
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE qt_app_update (
                    id BIGINT PRIMARY KEY, platforms VARCHAR(64), version_code BIGINT,
                    version_name VARCHAR(100), version_info VARCHAR(1000), update_type VARCHAR(20),
                    download_url VARCHAR(1000), browser_url VARCHAR(1000), channel VARCHAR(20),
                    download_mode VARCHAR(20), is_github BIGINT, is_force INT, is_published INT,
                    file_size BIGINT, md5 VARCHAR(100), create_time TIMESTAMP, update_time TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE qt_app_update_artifact (
                    id BIGINT PRIMARY KEY, update_id BIGINT, platform BIGINT, arch VARCHAR(30),
                    download_url VARCHAR(1000), browser_url VARCHAR(1000), is_github BIGINT,
                    file_size BIGINT, md5 VARCHAR(100), sort BIGINT)
                """);
        updateMapper = mock(QtAppUpdateMapper.class);
        artifacts = spy(new QtAppUpdateArtifactService());
        appService = mock(QtAppService.class);
        when(updateMapper.selectByIdForUpdate(anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            List<QtAppUpdate> rows = jdbc.query("SELECT * FROM qt_app_update WHERE id = ? FOR UPDATE",
                    (rs, row) -> {
                        QtAppUpdate update = new QtAppUpdate();
                        update.setId(rs.getLong("id"));
                        update.setPlatforms(rs.getString("platforms"));
                        update.setDownloadUrl(rs.getString("download_url"));
                        update.setBrowserUrl(rs.getString("browser_url"));
                        update.setIsGithub(rs.getLong("is_github"));
                        update.setVersionCode(2L);
                        update.setChannel("stable");
                        return update;
                    }, id);
            return rows.isEmpty() ? null : rows.getFirst();
        });
        when(updateMapper.insert(any(QtAppUpdate.class))).thenAnswer(invocation -> {
            QtAppUpdate update = invocation.getArgument(0);
            update.setId(ID);
            return jdbc.update("INSERT INTO qt_app_update (id, platforms, download_url, browser_url, is_github) VALUES (?, ?, ?, ?, ?)",
                    ID, update.getPlatforms(), update.getDownloadUrl(), update.getBrowserUrl(), update.getIsGithub());
        });
        when(updateMapper.update(nullable(QtAppUpdate.class), any(Wrapper.class))).thenAnswer(invocation -> {
            LambdaUpdateWrapper<QtAppUpdate> wrapper = invocation.getArgument(1);
            // Render the actual whitelist wrapper, so assertions also cover clearing fields to SQL NULL.
            String sql = "UPDATE qt_app_update SET " + wrapper.getSqlSet() + " WHERE " + wrapper.getSqlSegment();
            Matcher matcher = PARAMETER.matcher(sql);
            List<Object> parameters = new ArrayList<>();
            StringBuilder prepared = new StringBuilder();
            while (matcher.find()) {
                parameters.add(wrapper.getParamNameValuePairs().get(matcher.group(1)));
                matcher.appendReplacement(prepared, "?");
            }
            matcher.appendTail(prepared);
            return jdbc.update(prepared.toString(), parameters.toArray());
        });
        doAnswer(invocation -> readArtifacts(invocation.getArgument(0)))
                .when(artifacts).listByUpdateIdForWrite(anyLong());
        doAnswer(invocation -> readArtifacts(invocation.getArgument(0)).stream().map(QtAppUpdateArtifact::toVo).toList())
                .when(artifacts).toVos(anyLong());
        doAnswer(invocation -> {
            long id = invocation.getArgument(0);
            List<QtAppUpdateArtifact> submitted = invocation.getArgument(1);
            jdbc.update("DELETE FROM qt_app_update_artifact WHERE update_id = ?", id);
            for (QtAppUpdateArtifact artifact : submitted) {
                insertArtifact(id, artifact);
                if (failArtifactWrite) {
                    // Fail after a real write, proving controller advice rolls both tables back.
                    throw new DataIntegrityViolationException("simulated artifact insert failure");
                }
            }
            return submitted;
        }).when(artifacts).replaceAll(anyLong(), anyList());

        QtAdminController target = new QtAdminController();
        ReflectionTestUtils.setField(target, "qtUpdateMapper", updateMapper);
        ReflectionTestUtils.setField(target, "artifactService", artifacts);
        ReflectionTestUtils.setField(target, "appService", appService);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        TransactionInterceptor advice = new TransactionInterceptor();
        advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        advice.afterPropertiesSet();
        factory.addAdvice(advice);
        controller = (QtAdminController) factory.getProxy();
    }

    @Test
    void createRejectsMissingPlatformEvenWithMainOrGenericDownloadLinks() {
        for (List<QtAppUpdateArtifact> submitted : List.of(
                List.of(artifact(ANDROID)), List.of(artifact(null)), List.<QtAppUpdateArtifact>of())) {
            QtAppUpdate request = request("1101,1106", submitted);
            request.setDownloadUrl("https://example.test/legacy.apk");
            request.setBrowserUrl("https://example.test/releases");
            assertRejected(controller.createUpdate(request), "缺少所选平台的产物");
        }
        assertEquals(0, parentCount());
        verify(updateMapper, never()).insert(any(QtAppUpdate.class));
        verify(artifacts, never()).replaceAll(anyLong(), anyList());
        verifyNoInteractions(appService);
    }

    @Test
    void createAcceptsEverySelectedPlatformAndNormalizesPlatforms() {
        Result<QtAppUpdate> response = controller.createUpdate(
                request(" 1101,1106,1101 ", List.of(artifact(ANDROID), artifact(HARMONY))));
        assertEquals(200, response.getCode());
        assertEquals("1101,1106", jdbc.queryForObject("SELECT platforms FROM qt_app_update", String.class));
        assertEquals(2, readArtifacts(ID).size());
        verify(appService).evictUpdateCache();
    }

    @Test
    void combinedEditUsesSubmittedArtifactsAndAllowsChangingPlatform() {
        seedAndroid();
        QtAppUpdateArtifact harmony = artifact(HARMONY);
        assertEquals(200, controller.updateAppUpdate(ID, request("1106", List.of(harmony))).getCode());
        assertEquals("1106", jdbc.queryForObject("SELECT platforms FROM qt_app_update", String.class));
        assertEquals(List.of(HARMONY), readArtifacts(ID).stream().map(QtAppUpdateArtifact::getPlatform).toList());
        verify(updateMapper).selectByIdForUpdate(ID);
        verify(artifacts, never()).listByUpdateIdForWrite(anyLong());
        verify(appService).evictUpdateCache();
    }

    @Test
    void combinedEditRejectsSubmittedListMissingCoverageDespiteExistingArtifact() {
        seedAndroid();
        insertArtifact(ID, artifact(HARMONY));
        assertRejected(controller.updateAppUpdate(ID, request("1101,1106", List.of(artifact(ANDROID)))),
                "缺少所选平台的产物");
        assertEquals("1101", jdbc.queryForObject("SELECT platforms FROM qt_app_update", String.class));
        assertEquals(2, readArtifacts(ID).size());
        verify(artifacts, never()).replaceAll(anyLong(), anyList());
        verifyNoInteractions(appService);
    }

    @Test
    void combinedEditRejectsEmptyOrGenericListEvenWithMainLinks() {
        seedAndroid();
        for (List<QtAppUpdateArtifact> submitted : List.of(List.<QtAppUpdateArtifact>of(), List.of(artifact(null)))) {
            QtAppUpdate request = request("1101", submitted);
            request.setDownloadUrl("https://example.test/main.apk");
            assertRejected(controller.updateAppUpdate(ID, request), "缺少所选平台的产物");
        }
        verify(artifacts, never()).replaceAll(anyLong(), anyList());
        verifyNoInteractions(appService);
    }

    @Test
    void omittedArtifactsAreValidatedFromAuthoritativeRowsRatherThanReadCache() {
        seedAndroid();
        doReturn(List.of(artifact(HARMONY))).when(artifacts).listByUpdateId(ID);
        QtAppUpdate request = new QtAppUpdate();
        request.setPlatforms("1106");
        assertRejected(controller.updateAppUpdate(ID, request), "缺少所选平台的产物");
        verify(artifacts).listByUpdateIdForWrite(ID);
        verify(artifacts, never()).listByUpdateId(anyLong());
        verify(artifacts, never()).replaceAll(anyLong(), anyList());
        verifyNoInteractions(appService);
    }

    @Test
    void omittedArtifactsStillRejectInvalidStoredLinks() {
        seedAndroid();
        jdbc.update("UPDATE qt_app_update_artifact SET download_url = '   ', browser_url = NULL WHERE update_id = ?", ID);
        QtAppUpdate request = new QtAppUpdate();
        request.setVersionName("renamed");
        assertRejected(controller.updateAppUpdate(ID, request), "每个产物都要填写");
        verify(artifacts).listByUpdateIdForWrite(ID);
        verify(artifacts, never()).replaceAll(anyLong(), anyList());
        verifyNoInteractions(appService);
    }

    @Test
    void omittedValidArtifactsArePreserved() {
        seedAndroid();
        QtAppUpdate request = new QtAppUpdate();
        request.setVersionName("renamed");
        assertEquals(200, controller.updateAppUpdate(ID, request).getCode());
        assertEquals(1, readArtifacts(ID).size());
        verify(artifacts).listByUpdateIdForWrite(ID);
        verify(artifacts, never()).replaceAll(anyLong(), anyList());
    }

    @Test
    void invalidArtifactLinksAreRejectedByEveryWriter() {
        seedAndroid();
        for (boolean githubWithoutDirect : List.of(false, true)) {
            QtAppUpdateArtifact invalid = new QtAppUpdateArtifact();
            invalid.setPlatform(ANDROID);
            invalid.setIsGithub(githubWithoutDirect ? 1L : 0L);
            if (githubWithoutDirect) invalid.setBrowserUrl("https://example.test/releases");
            String message = githubWithoutDirect ? "GitHub 产物必须填写直链下载地址" : "每个产物都要填写";
            assertRejected(controller.createUpdate(request("1101", List.of(invalid))), message);
            assertRejected(controller.updateAppUpdate(ID, request("1101", List.of(invalid))), message);
            assertRejected(controller.saveUpdateArtifacts(ID, List.of(invalid)), message);
        }
        assertEquals(1, parentCount());
        assertEquals(1, readArtifacts(ID).size());
        verify(artifacts, never()).replaceAll(anyLong(), anyList());
        verifyNoInteractions(appService);
    }

    @Test
    void artifactOnlyWriterRejectsRemovingAnySelectedPlatform() {
        seedAndroid();
        jdbc.update("UPDATE qt_app_update SET platforms = '1101,1106' WHERE id = ?", ID);
        insertArtifact(ID, artifact(HARMONY));
        assertRejected(controller.saveUpdateArtifacts(ID, List.of(artifact(ANDROID))), "缺少所选平台的产物");
        assertRejected(controller.saveUpdateArtifacts(ID, List.of(artifact(null))), "缺少所选平台的产物");
        assertRejected(controller.saveUpdateArtifacts(ID, List.of()), "缺少所选平台的产物");
        assertEquals(2, readArtifacts(ID).size());
        verify(updateMapper, times(3)).selectByIdForUpdate(ID);
        verify(artifacts, never()).replaceAll(anyLong(), anyList());
    }

    @Test
    void browserOnlyReplacementClearsOldDirectLinkAndGithubFlag() {
        seedAndroid();
        QtAppUpdateArtifact browserOnly = new QtAppUpdateArtifact();
        browserOnly.setPlatform(HARMONY);
        browserOnly.setBrowserUrl("https://example.test/harmony");
        assertEquals(200, controller.updateAppUpdate(ID, request("1106", List.of(browserOnly))).getCode());
        assertNull(jdbc.queryForObject("SELECT download_url FROM qt_app_update", String.class));
        assertEquals("https://example.test/harmony", jdbc.queryForObject("SELECT browser_url FROM qt_app_update", String.class));
        assertEquals(0L, jdbc.queryForObject("SELECT is_github FROM qt_app_update", Long.class));
    }

    @Test
    void artifactOnlySuccessClearsOldBrowserLink() {
        seedAndroid();
        assertEquals(200, controller.saveUpdateArtifacts(ID, List.of(artifact(ANDROID))).getCode());
        assertNull(jdbc.queryForObject("SELECT browser_url FROM qt_app_update", String.class));
        verify(appService).evictUpdateCache();
    }

    @Test
    void createRollsBackParentAndChildrenWhenArtifactWriteFails() {
        failArtifactWrite = true;
        assertThrows(DataIntegrityViolationException.class,
                () -> controller.createUpdate(request("1101", List.of(artifact(ANDROID)))));
        assertEquals(0, parentCount());
        assertEquals(0, readArtifacts(ID).size());
        verifyNoInteractions(appService);
    }

    @Test
    void combinedEditRollsBackPlatformAndRestoresOldArtifactsWhenWriteFails() {
        seedAndroid();
        failArtifactWrite = true;
        assertThrows(DataIntegrityViolationException.class,
                () -> controller.updateAppUpdate(ID, request("1106", List.of(artifact(HARMONY)))));
        assertEquals("1101", jdbc.queryForObject("SELECT platforms FROM qt_app_update", String.class));
        assertEquals(List.of(ANDROID), readArtifacts(ID).stream().map(QtAppUpdateArtifact::getPlatform).toList());
        verifyNoInteractions(appService);
    }

    @Test
    void artifactOnlyWriteRollsBackDeletedAndInsertedChildrenWhenWriteFails() {
        seedAndroid();
        failArtifactWrite = true;
        assertThrows(DataIntegrityViolationException.class,
                () -> controller.saveUpdateArtifacts(ID, List.of(artifact(ANDROID))));
        assertEquals("https://example.test/old.apk", readArtifacts(ID).getFirst().getDownloadUrl());
        verifyNoInteractions(appService);
    }

    @Test
    void cacheEvictionRunsOnlyAfterOuterTransactionCommits() {
        AtomicInteger committedRowsSeenByEviction = new AtomicInteger(-1);
        doAnswer(invocation -> {
            // An independent connection must see the parent; a pre-commit eviction would see zero.
            try (Connection connection = dataSource.getConnection();
                 var statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM qt_app_update")) {
                result.next();
                committedRowsSeenByEviction.set(result.getInt(1));
            }
            return null;
        }).when(appService).evictUpdateCache();
        transaction.executeWithoutResult(status -> {
            assertEquals(200, controller.createUpdate(request("1101", List.of(artifact(ANDROID)))).getCode());
            verifyNoInteractions(appService);
        });
        verify(appService).evictUpdateCache();
        assertEquals(1, committedRowsSeenByEviction.get());
    }

    @Test
    void outerRollbackNeverEvictsCache() {
        transaction.executeWithoutResult(status -> {
            assertEquals(200, controller.createUpdate(request("1101", List.of(artifact(ANDROID)))).getCode());
            status.setRollbackOnly();
        });
        assertEquals(0, parentCount());
        assertEquals(0, readArtifacts(ID).size());
        verifyNoInteractions(appService);
    }

    private void seedAndroid() {
        jdbc.update("INSERT INTO qt_app_update (id, platforms, download_url, browser_url, is_github) VALUES (?, '1101', ?, ?, 1)",
                ID, "https://example.test/old.apk", "https://example.test/old-browser");
        QtAppUpdateArtifact old = artifact(ANDROID);
        old.setDownloadUrl("https://example.test/old.apk");
        insertArtifact(ID, old);
    }

    private void insertArtifact(long updateId, QtAppUpdateArtifact artifact) {
        jdbc.update("""
                INSERT INTO qt_app_update_artifact
                (id, update_id, platform, arch, download_url, browser_url, is_github, file_size, md5, sort)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, artifactIds.incrementAndGet(), updateId, artifact.getPlatform(), artifact.getArch(),
                artifact.getDownloadUrl(), artifact.getBrowserUrl(), artifact.getIsGithub(), artifact.getFileSize(),
                artifact.getMd5(), artifactIds.get());
    }

    private List<QtAppUpdateArtifact> readArtifacts(long updateId) {
        return jdbc.query("SELECT * FROM qt_app_update_artifact WHERE update_id = ? ORDER BY sort", (rs, row) -> {
            QtAppUpdateArtifact artifact = new QtAppUpdateArtifact();
            artifact.setId(rs.getLong("id"));
            artifact.setPlatform(rs.getObject("platform", Long.class));
            artifact.setDownloadUrl(rs.getString("download_url"));
            artifact.setBrowserUrl(rs.getString("browser_url"));
            artifact.setIsGithub(rs.getObject("is_github", Long.class));
            return artifact;
        }, updateId);
    }

    private int parentCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM qt_app_update", Integer.class);
    }

    private static QtAppUpdate request(String platforms, List<QtAppUpdateArtifact> submitted) {
        QtAppUpdate update = new QtAppUpdate();
        update.setPlatforms(platforms);
        update.setVersionCode(2L);
        update.setArtifacts(submitted.stream().map(QtAppUpdateArtifact::toVo).toList());
        return update;
    }

    private static QtAppUpdateArtifact artifact(Long platform) {
        QtAppUpdateArtifact artifact = new QtAppUpdateArtifact();
        artifact.setPlatform(platform);
        artifact.setDownloadUrl("https://example.test/package-" + platform);
        return artifact;
    }

    private static void assertRejected(Result<?> response, String message) {
        assertEquals(300, response.getCode());
        assertTrue(response.getMsg().contains(message), response::getMsg);
    }
}
