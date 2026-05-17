package com.astral.server;

import com.astral.schema.SchemaEntitySync;
import com.astral.schema.SchemaRegistry;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Astral序列管理系统启动类
 * <p>系统入口，负责初始化表结构注册、实体同步并启动Spring Boot应用</p>
 */
@SpringBootApplication
/** 扫描com.astral包下的所有组件（包含所有子模块） */
@ComponentScan(basePackages = {"com.astral"})
/** 扫描MyBatis Mapper接口所在的包 */
@MapperScan("com.astral.dao.mapper")
/** 启用异步方法支持（@Async注解） */
@EnableAsync
public class AstralApplication {
    /**
     * 应用程序入口
     * <p>启动流程：
     * 1. 初始化SchemaRegistry（加载表结构元数据）
     * 2. 执行实体类同步（确保Entity与Schema一致）
     * 3. 启动Spring Boot应用上下文</p>
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        // 初始化表结构注册中心
        SchemaRegistry.init();
        // 启动时同步实体类与表结构
        SchemaEntitySync.syncOnStartup();
        // 启动Spring Boot应用
        SpringApplication.run(AstralApplication.class, args);
    }
}