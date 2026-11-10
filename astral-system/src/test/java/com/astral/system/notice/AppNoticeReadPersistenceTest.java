package com.astral.system.notice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.astral.dao.mapper.SysNoticeMapper;
import com.astral.system.notice.controller.AppNoticeController;
import com.astral.system.service.SysConfigService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class AppNoticeReadPersistenceTest {
    @Test
    void appReadReceiptPersistsOnlyOwnDirectMessagesAndLeavesBroadcastCandidates() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:notice_read_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        try {
            var schema = new ObjectMapper()
                    .readTree(Files.readString(Path.of("../astral-schema/src/main/resources/schema/sys_notice.json")));
            var columns = new ArrayList<String>();
            for (var field : schema.path("fields"))
                columns.add(field.path("columnName").asString() + " "
                        + field.path("jdbcType").asString()
                        + (field.path("isPrimaryKey").asBoolean(false) ? " PRIMARY KEY" : ""));
            jdbc.execute(String.format("CREATE TABLE sys_notice (%s)", String.join(",", columns)));
            jdbc.update(
                    "INSERT INTO sys_notice(id,title,channel,notice_type,user_id,is_show,display,create_time) VALUES(1,'mine','all','notice',41,1,4,CURRENT_TIMESTAMP),(2,'other','all','notice',42,1,4,CURRENT_TIMESTAMP),(3,'broadcast','all','announce',NULL,1,4,CURRENT_TIMESTAMP)");
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
            config.addMapper(SysNoticeMapper.class);
            var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
            var service = new NoticeServiceImpl();
            ReflectionTestUtils.setField(service, "sysNoticeMapper", session.getMapper(SysNoticeMapper.class));
            ReflectionTestUtils.setField(service, "sysConfigService", mock(SysConfigService.class));
            var controller = new AppNoticeController();
            ReflectionTestUtils.setField(controller, "noticeService", service);
            var mvc = MockMvcBuilders.standaloneSetup(controller).build();
            mvc.perform(get("/api/v1/app/message/unread-count").requestAttr("userId", 41L))
                    .andExpect(jsonPath("$.data").value(2));
            mvc.perform(post("/api/v1/app/message/read-ack")
                            .requestAttr("userId", 41L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"ids\":[1,2,3,1]}"))
                    .andExpect(jsonPath("$.code").value(200));
            var readAt = jdbc.queryForObject("SELECT read_time FROM sys_notice WHERE id=1", java.sql.Timestamp.class);
            assertNotNull(readAt);
            assertNull(jdbc.queryForObject("SELECT read_time FROM sys_notice WHERE id=2", java.sql.Timestamp.class));
            assertNull(jdbc.queryForObject("SELECT read_time FROM sys_notice WHERE id=3", java.sql.Timestamp.class));
            mvc.perform(get("/api/v1/app/message/unread-count").requestAttr("userId", 41L))
                    .andExpect(jsonPath("$.data").value(1));
            service.readAck(41L, java.util.List.of(1L));
            assertEquals(
                    readAt,
                    jdbc.queryForObject("SELECT read_time FROM sys_notice WHERE id=1", java.sql.Timestamp.class));
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }
}
