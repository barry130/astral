package com.astral.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.astral.dao.mapper.StatApiHourlyMapper;
import com.astral.dao.mapper.StatDeviceMapper;
import com.astral.dao.mapper.StatErrorLogMapper;
import com.astral.dao.mapper.StatMetricHourlyMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

class StatReportFilterTest {
    @Test
    void whitespaceAndAllFiltersUseTheSameDatabaseQueryAndCacheEntry() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:stat_" + UUID.randomUUID() + ";MODE=PostgreSQL");
        try (var keepAlive = source.getConnection()) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute(
                    "CREATE TABLE stat_device(id BIGINT,ut VARCHAR,app_version VARCHAR,first_date DATE,last_date DATE)");
            jdbc.execute(
                    "CREATE TABLE stat_metric_hourly(id BIGINT,ut VARCHAR,app_version VARCHAR,bucket_hour TIMESTAMP,pv BIGINT,visits BIGINT,launches BIGINT,total_duration_ms BIGINT,error_count BIGINT)");
            jdbc.execute("INSERT INTO stat_device VALUES(1,'android','1.0','2026-11-08','2026-11-08')");
            jdbc.execute("INSERT INTO stat_metric_hourly VALUES(1,'android','1.0','2026-11-08 12:00:00',7,1,1,30,0)");
            var config = new MybatisConfiguration();
            config.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
            config.addMapper(StatDeviceMapper.class);
            config.addMapper(StatMetricHourlyMapper.class);
            var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
            for (var order : List.of(
                    List.of(" android ", "android"), List.of("android", " android "), List.of(" all ", "all"))) {
                var service = new StatReportService(
                        session.getMapper(StatDeviceMapper.class),
                        session.getMapper(StatMetricHourlyMapper.class),
                        mock(StatErrorLogMapper.class),
                        mock(StatApiHourlyMapper.class));
                for (String platform : order) {
                    assertEquals(
                            1L,
                            service.getOverview(LocalDate.of(2026, 11, 8), platform, " 1.0 ")
                                    .get("today")
                                    .getNewDevices());
                    assertEquals(
                            7L,
                            service.getTrend("pv", LocalDate.of(2026, 11, 8), platform, " 1.0 ")
                                    .getToday()
                                    .get(12));
                }
            }
        }
    }
}
