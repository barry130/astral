package com.astral.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.ai.common.AiJson;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import com.astral.common.constant.NoticeConstants;
import com.astral.common.util.SqlIdentifiers;
import com.astral.dao.mapper.SysConfigMapper;
import com.astral.dao.mapper.SysNoticeMapper;
import com.astral.system.notice.NoticeServiceImpl;
import com.astral.system.service.impl.SysConfigServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.reflection.MetaObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.node.ObjectNode;

class AdminToolNoticePersistenceTest {
    private JdbcTemplate jdbc;
    private AdminToolProvider provider;
    private NoticeServiceImpl notices;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:publish_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        for (String table : List.of("sys_notice", "sys_config")) {
            var schema = AiJson.read(
                    Files.readString(Path.of("../astral-schema/src/main/resources/schema/" + table + ".json")));
            var columns = new ArrayList<String>();
            for (var field : schema.path("fields"))
                columns.add(field.path("columnName").asString() + " "
                        + field.path("jdbcType").asString().replace("TINYINT", "SMALLINT")
                        + (field.path("isPrimaryKey").asBoolean(false) ? " PRIMARY KEY" : ""));
            jdbc.execute(String.format(
                    "CREATE TABLE %s (%s)", SqlIdentifiers.requireSafe(table), String.join(",", columns)));
        }
        jdbc.update(
                "INSERT INTO sys_config(id,config_key,config_value) VALUES(1,'notice.feedback.retention_days','7')");
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        var ids = new AtomicLong(100);
        GlobalConfigUtils.setGlobalConfig(
                config,
                new GlobalConfig()
                        .setDbConfig(new GlobalConfig.DbConfig())
                        .setMetaObjectHandler(new MetaObjectHandler() {
                            @Override
                            public void insertFill(MetaObject row) {
                                setFieldValByName("id", ids.incrementAndGet(), row);
                                setFieldValByName("createTime", LocalDateTime.now(), row);
                            }

                            @Override
                            public void updateFill(MetaObject row) {}
                        }));
        config.addMapper(SysNoticeMapper.class);
        config.addMapper(SysConfigMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var configs = new SysConfigServiceImpl();
        configs.init();
        ReflectionTestUtils.setField(configs, "baseMapper", session.getMapper(SysConfigMapper.class));
        notices = new NoticeServiceImpl();
        ReflectionTestUtils.setField(notices, "sysNoticeMapper", session.getMapper(SysNoticeMapper.class));
        ReflectionTestUtils.setField(notices, "sysConfigService", configs);
        provider = new AdminToolProvider(
                null, null, null, null, null, null, null, null, null, null, null, notices, null, null, jdbc, null);
    }

    @AfterEach
    void close() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void approvedAnnouncementImmediatelyReachesEveryPlatformWithWarmCache() {
        assertTrue(notices.listForWeb(null, false, null).isEmpty());
        assertEquals(AiToolResult.SUCCEEDED, publish(arguments()).status());
        for (String platform : List.of(
                NoticeConstants.CHANNEL_ANDROID,
                NoticeConstants.CHANNEL_IOS,
                NoticeConstants.CHANNEL_WINDOWS,
                NoticeConstants.CHANNEL_LINUX,
                NoticeConstants.CHANNEL_MACOS,
                NoticeConstants.CHANNEL_HARMONY,
                NoticeConstants.CHANNEL_WEB)) {
            var visible = notices.listForChannel(List.of(platform), null, false, null);
            assertEquals(1, visible.size(), platform);
            assertEquals(NoticeConstants.TYPE_ANNOUNCE, visible.getFirst().getNoticeType());
            assertEquals("ai", visible.getFirst().getSender());
        }
    }

    @Test
    void unknownAudienceIsRejectedAndLoggedInAnnouncementNeverLeaksToVisitors() {
        assertEquals(
                AiToolResult.REJECTED,
                publish(arguments().put("audience", "ADMIN")).status());
        assertEquals(
                AiToolResult.REJECTED,
                publish(arguments().put("noticeType", "SYSTEM")).status());
        assertTrue(notices.listAdminInbox(3L).isEmpty());
        assertEquals(
                AiToolResult.SUCCEEDED,
                publish(arguments().put("audience", "LOGGED_IN")).status());
        assertTrue(notices.listForWeb(null, false, null).isEmpty());
        assertEquals(1, notices.listForWeb(null, true, 3L).size());
        // Simulate time passing at the database boundary, then observe only public reads.
        jdbc.update("UPDATE sys_notice SET create_time=?", LocalDateTime.now().minusDays(30));
        notices.evictNoticeCache();
        assertEquals(1, notices.listForWeb(null, true, 3L).size());
    }

    private ObjectNode arguments() {
        return AiJson.mapper().createObjectNode().put("title", "Fixture").put("content", "Body");
    }

    private AiToolResult publish(ObjectNode args) {
        return provider.handlers().stream()
                .filter(h -> "publish_notice".equals(h.definition().toolCode()))
                .findFirst()
                .orElseThrow()
                .execute(new AiToolContext(1L, 2L, 3L, 4L, "fixture", "ADMIN", "ASTRAL_FRONT", args, null, true, 3L));
    }
}
