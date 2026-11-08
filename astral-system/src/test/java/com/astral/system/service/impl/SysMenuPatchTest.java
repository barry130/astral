package com.astral.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.astral.dao.entity.SysMenu;
import com.astral.dao.mapper.SysMenuMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SysMenuPatchTest {
    @Test
    void sortingDoesNotOverwriteNameChangedAfterItsRead() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "menu"), SysMenu.class);
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:menu_" + UUID.randomUUID());
        try (Connection keepAlive = source.getConnection()) {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TABLE sys_menu (id BIGINT PRIMARY KEY, parent_id BIGINT, name VARCHAR(64),
                    icon VARCHAR(64), path VARCHAR(256), permission VARCHAR(128), sort INTEGER,
                    visible INTEGER, type INTEGER, update_time TIMESTAMP)
                    """);
            jdbc.update("INSERT INTO sys_menu(id,parent_id,name,sort) VALUES (1,0,'Original',1)");
            SysMenuMapper mapper = mock(SysMenuMapper.class);
            when(mapper.selectById(1L))
                    .thenAnswer(call -> jdbc.queryForObject("SELECT * FROM sys_menu WHERE id=1", (rs, row) -> {
                        SysMenu menu = new SysMenu();
                        menu.setId(rs.getLong("id"));
                        menu.setParentId(rs.getLong("parent_id"));
                        menu.setName(rs.getString("name"));
                        menu.setSort(rs.getInt("sort"));
                        return menu;
                    }));
            when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenAnswer(call -> {
                // Another committed edit arrives after the service read, before its update.
                jdbc.update("UPDATE sys_menu SET name='Concurrent name' WHERE id=1");
                LambdaUpdateWrapper<SysMenu> update = call.getArgument(1);
                String sql = "UPDATE sys_menu SET " + update.getSqlSet() + " WHERE " + update.getSqlSegment();
                var placeholders = Pattern.compile("#\\{ew\\.paramNameValuePairs\\.([^}]+)}")
                        .matcher(sql);
                var values = new ArrayList<Object>();
                StringBuilder prepared = new StringBuilder();
                while (placeholders.find()) {
                    values.add(update.getParamNameValuePairs().get(placeholders.group(1)));
                    placeholders.appendReplacement(prepared, "?");
                }
                placeholders.appendTail(prepared);
                return jdbc.update(prepared.toString(), values.toArray());
            });
            SysMenu patch = new SysMenu();
            patch.setSort(7);

            SysMenu result = new SysMenuServiceImpl(mapper).update(1L, patch);

            assertEquals("Concurrent name", result.getName());
            assertEquals(7, result.getSort());
        }
    }
}
