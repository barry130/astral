package com.astral.cms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.SaTokenContextForThreadLocalStaff;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.cms.common.CmsException;
import com.astral.cms.dto.CategorySaveDto;
import com.astral.cms.dto.CommentSubmitDto;
import com.astral.cms.dto.PageSaveDto;
import com.astral.cms.mapper.CmsCategoryMapper;
import com.astral.cms.mapper.CmsCommentMapper;
import com.astral.cms.mapper.CmsPageMapper;
import com.astral.cms.mapper.CmsPostCategoryMapper;
import com.astral.cms.mapper.CmsPostMapper;
import com.astral.cms.mapper.CmsSettingMapper;
import com.astral.cms.mapper.CmsShareMapper;
import com.astral.cms.mapper.CmsTreeMapper;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfSystemProperty(named = "audit.postgres.port", matches = "[0-9]+")
class CmsPersistenceTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private SqlSessionTemplate session;
    private DataSourceTransactionManager transactions;
    private CmsPageService pages;
    private CmsCategoryService categories;
    private CmsCommentService comments;
    private CmsShareService shares;
    private volatile CountDownLatch contenderAtLock;

    @BeforeEach
    void setUp() {
        String url = "jdbc:postgresql://127.0.0.1:" + System.getProperty("audit.postgres.port") + "/postgres";
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "astral_audit", ""));
        schema = "cms_audit_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema, "astral_audit", "");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        new ResourceDatabasePopulator(new ClassPathResource("sql/cms-schema.sql")).execute(source);
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        config.addInterceptor(new LockGate());
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
        var pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        config.addInterceptor(pagination);
        config.addMappers("com.astral.cms.mapper");
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var settings = new CmsSettingService();
        ReflectionTestUtils.setField(settings, "settingMapper", map(CmsSettingMapper.class));
        var pageTarget = new CmsPageService();
        ReflectionTestUtils.setField(pageTarget, "pageMapper", map(CmsPageMapper.class));
        ReflectionTestUtils.setField(pageTarget, "treeMapper", map(CmsTreeMapper.class));
        pages = proxy(pageTarget);
        var categoryTarget = new CmsCategoryService();
        ReflectionTestUtils.setField(categoryTarget, "categoryMapper", map(CmsCategoryMapper.class));
        ReflectionTestUtils.setField(categoryTarget, "postMapper", map(CmsPostMapper.class));
        ReflectionTestUtils.setField(categoryTarget, "postCategoryMapper", map(CmsPostCategoryMapper.class));
        ReflectionTestUtils.setField(categoryTarget, "treeMapper", map(CmsTreeMapper.class));
        categories = proxy(categoryTarget);
        comments = new CmsCommentService();
        ReflectionTestUtils.setField(comments, "commentMapper", map(CmsCommentMapper.class));
        ReflectionTestUtils.setField(comments, "postMapper", map(CmsPostMapper.class));
        ReflectionTestUtils.setField(comments, "pageMapper", map(CmsPageMapper.class));
        ReflectionTestUtils.setField(comments, "settingService", settings);
        ReflectionTestUtils.setField(comments, "throttleWindowSeconds", 600L);
        ReflectionTestUtils.setField(comments, "throttleMax", 5);
        shares = new CmsShareService();
        ReflectionTestUtils.setField(shares, "shareMapper", map(CmsShareMapper.class));
        ReflectionTestUtils.setField(shares, "postMapper", map(CmsPostMapper.class));
        ReflectionTestUtils.setField(shares, "pageMapper", map(CmsPageMapper.class));
        ReflectionTestUtils.setField(shares, "settingService", settings);
    }

    @AfterEach
    void clean() {
        if (schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"private", "hidden", "draft"})
    void publicChildAndSharedTargetDoNotDiscloseInvisibleAncestors(String status) {
        Long parent = pages.create(page(null, status), 1L);
        Long child = pages.create(page(parent, "published"), 1L);
        assertTrue(pages.publicDetail(child.toString()).getBreadcrumb().isEmpty());
        pages.changeStatus(child, "private");
        var share = shares.create("page", child, null, null, 1L);
        var authorized = shares.findValidByToken(share.getToken());
        assertTrue(
                pages.publicDetailById(authorized.getTargetId()).getBreadcrumb().isEmpty());
    }

    @Test
    void commentsAndSharesKeepPostAndPageTitlesWhenTheirIdsCoincide() {
        jdbc.update("INSERT INTO cms_post(id,title,status) VALUES(10,'Post title','published')");
        jdbc.update("INSERT INTO cms_page(id,title,status) VALUES(10,'Page title','published')");
        for (String type : java.util.List.of("post", "page")) {
            var dto = new CommentSubmitDto();
            dto.setTargetType(type);
            dto.setTargetId(10L);
            dto.setNickname("Reader");
            dto.setContent("Comment");
            comments.submit(dto, "192.0.2.1", "fixture");
            shares.create(type, 10L, null, null, 1L);
        }
        var commentRows = comments.adminPage(1, 10, null, null, null).getRecords();
        var shareRows = shares.listAll();
        assertEquals(2, commentRows.size());
        assertEquals(2, shareRows.size());
        for (var row : commentRows)
            assertEquals(row.getTargetType().equals("post") ? "Post title" : "Page title", row.getTargetTitle());
        for (var row : shareRows)
            assertEquals(row.getTargetType().equals("post") ? "Post title" : "Page title", row.getTargetTitle());
    }

    @Test
    void deepTreesCannotBeReparentedIntoTheirOwnDescendants() {
        Long rootPage = pages.create(page(null, "published"), 1L);
        Long rootCategory = categories.create(category(null));
        Long parentPage = rootPage;
        Long parentCategory = rootCategory;
        for (int i = 0; i < 55; i++) {
            parentPage = pages.create(page(parentPage, "published"), 1L);
            parentCategory = categories.create(category(parentCategory));
        }
        var pageUpdate = page(parentPage, "published");
        var categoryUpdate = category(parentCategory);
        assertThrows(CmsException.class, () -> pages.update(rootPage, pageUpdate));
        assertThrows(CmsException.class, () -> categories.update(rootCategory, categoryUpdate));
        assertEquals(1, pages.adminTree().size());
        assertEquals(1, categories.adminTree().size());
    }

    @Test
    void authorizedAdministratorCanSeePrivateAncestorsButNotHiddenAncestors() {
        var oldContext = SaManager.getSaTokenContext();
        var oldPermissions = SaManager.getStpInterface();
        var oldBox = SaTokenContextForThreadLocalStaff.getModelBoxOrNull();
        StpInterface permissions = mock(StpInterface.class);
        when(permissions.getPermissionList(any(), any())).thenReturn(List.of("admin:cms:view"));
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaManager.setStpInterface(permissions);
        try {
            SaTokenContextMockUtil.setMockContext();
            StpUtil.login(99142L);
            Long parent = pages.create(page(null, "private"), 1L);
            Long child = pages.create(page(parent, "published"), 1L);
            assertEquals(
                    parent,
                    pages.publicDetail(child.toString())
                            .getBreadcrumb()
                            .getFirst()
                            .getId());
            pages.changeStatus(parent, "hidden");
            assertTrue(pages.publicDetail(child.toString()).getBreadcrumb().isEmpty());
        } finally {
            StpUtil.logout();
            if (oldBox == null) SaTokenContextForThreadLocalStaff.modelBoxThreadLocal.remove();
            else SaTokenContextForThreadLocalStaff.modelBoxThreadLocal.set(oldBox);
            SaManager.setStpInterface(oldPermissions);
            SaManager.setSaTokenContext(oldContext);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void oppositeConcurrentReparentingCannotCreateACycle(boolean pageTree) throws Exception {
        Long first = pageTree ? pages.create(page(null, "published"), 1L) : categories.create(category(null));
        Long second = pageTree ? pages.create(page(null, "published"), 1L) : categories.create(category(null));
        contenderAtLock = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                if (pageTree) pages.update(first, page(second, "published"));
                else categories.update(first, category(second));
                future.set(executor.submit(() -> {
                    Thread.currentThread().setName("cms-contender");
                    if (pageTree) pages.update(second, page(first, "published"));
                    else categories.update(second, category(first));
                }));
                awaitContender();
            });
            assertTrue(
                    assertThrows(ExecutionException.class, () -> future.get().get(10, TimeUnit.SECONDS))
                                    .getCause()
                            instanceof CmsException);
            assertEquals(
                    1,
                    pageTree ? pages.adminTree().size() : categories.adminTree().size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentChildCreationCannotAttachToADeletedParent(boolean pageTree) throws Exception {
        Long parent = pageTree ? pages.create(page(null, "published"), 1L) : categories.create(category(null));
        contenderAtLock = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                if (pageTree) pages.delete(parent);
                else categories.delete(parent);
                future.set(executor.submit(() -> {
                    Thread.currentThread().setName("cms-contender");
                    if (pageTree) pages.create(page(parent, "published"), 1L);
                    else categories.create(category(parent));
                }));
                awaitContender();
            });
            assertTrue(
                    assertThrows(ExecutionException.class, () -> future.get().get(10, TimeUnit.SECONDS))
                                    .getCause()
                            instanceof CmsException);
            assertEquals(
                    0,
                    pageTree ? pages.adminTree().size() : categories.adminTree().size());
        }
    }

    private void awaitContender() {
        try {
            assertTrue(contenderAtLock.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
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
    private class LockGate implements Interceptor {
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            if (contenderAtLock != null
                    && Thread.currentThread().getName().equals("cms-contender")
                    && statement.getId().contains("CmsTreeMapper.lock")) contenderAtLock.countDown();
            return invocation.proceed();
        }
    }

    private PageSaveDto page(Long parent, String status) {
        var dto = new PageSaveDto();
        dto.setTitle("Page");
        dto.setContent("Content");
        dto.setStatus(status);
        dto.setParentId(parent);
        return dto;
    }

    private CategorySaveDto category(Long parent) {
        var dto = new CategorySaveDto();
        dto.setName("Category");
        dto.setParentId(parent);
        return dto;
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
