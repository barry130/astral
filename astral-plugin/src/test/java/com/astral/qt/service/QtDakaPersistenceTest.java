package com.astral.qt.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.astral.qt.common.QtException;
import com.astral.qt.dto.QtUserDakaDto;
import com.astral.qt.mapper.QtUserDakaMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

class QtDakaPersistenceTest {
    private SqlSession session;
    private JdbcTemplate jdbc;
    private QtUserDakaMapper mapper;

    @BeforeEach
    void setUp() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:daka_" + UUID.randomUUID() + ";MODE=PostgreSQL");
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("fixture", new JdbcTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        AtomicLong ids = new AtomicLong(1000);
        GlobalConfigUtils.setGlobalConfig(
                config,
                new GlobalConfig()
                        .setDbConfig(new GlobalConfig.DbConfig())
                        .setMetaObjectHandler(new MetaObjectHandler() {
                            @Override
                            public void insertFill(MetaObject object) {
                                setFieldValByName("id", ids.incrementAndGet(), object);
                            }

                            @Override
                            public void updateFill(MetaObject object) {
                                // Only business date and reward behavior are under test here.
                            }
                        }));
        config.addMapper(QtUserDakaMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true);
        session.getConnection();
        mapper = session.getMapper(QtUserDakaMapper.class);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE qt_user_daka(id BIGINT PRIMARY KEY,uid BIGINT,data DATE,integral BIGINT,
                is_use_code BIGINT,create_time TIMESTAMP,update_time TIMESTAMP,UNIQUE(uid,data))
                """);
    }

    @AfterEach
    void tearDown() {
        session.close();
    }

    @ParameterizedTest
    @CsvSource({"2027-01-01,6,3", "2026-12-01,29,5", "2027-01-01,364,10", "2026-11-08,0,1"})
    void completedStreakDeterminesRewardAndRepeatsNeverAwardTwice(String todayText, int previous, int reward) {
        LocalDate today = LocalDate.parse(todayText);
        var service = service(today);
        seed(today, previous);
        assertEquals(previous, service.getDakaDaysAndCode(1L).getDays());
        int before = service.getAllIntegral(1L);
        var dto = new QtUserDakaDto();
        dto.setTime(todayText);
        dto.setType("0");
        service.daka(1L, dto);
        var result = service.getDakaDaysAndCode(1L);
        assertEquals(previous + 1, result.getDays());
        assertEquals(before + reward, result.getCode());
        assertEquals(true, result.getTodayDone());
        assertThrows(QtException.class, () -> service.daka(1L, dto));
        assertEquals(before + reward, service.getAllIntegral(1L));
    }

    @ParameterizedTest
    @CsvSource({"2026-12-01", "2027-01-01"})
    void missingYesterdayBreaksTheStreak(String todayText) {
        LocalDate today = LocalDate.parse(todayText);
        seed(today.minusDays(1), 30);
        var service = service(today);
        assertEquals(0, service.getDakaDaysAndCode(1L).getDays());
        int before = service.getAllIntegral(1L);
        var dto = new QtUserDakaDto();
        dto.setTime(todayText);
        dto.setType("0");
        service.daka(1L, dto);
        assertEquals(before + 1, service.getAllIntegral(1L));
    }

    private QtDakaService service(LocalDate today) {
        return new QtDakaService(mapper, Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    private void seed(LocalDate today, int previous) {
        for (int i = 1; i <= previous; i++) {
            jdbc.update(
                    "INSERT INTO qt_user_daka(id,uid,data,integral,is_use_code) VALUES(?,1,?,1,0)",
                    i,
                    today.minusDays(i));
        }
    }
}
