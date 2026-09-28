package com.astral.server;

import com.astral.schema.SchemaEntitySync;
import com.astral.schema.SchemaRegistry;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
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
@EnableCaching
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
        // 启动时同步实体类与表结构。
        // 注意：此代码在 Spring 容器启动前执行，因此无法读取 application.yml 的配置，
        // 只能通过 JVM 系统属性或环境变量控制（见 DEPLOY_GUIDE / 开发文档）。
        // 生产环境务必关闭，否则它会删除「没有对应 schema JSON」的 entity .java 源文件。
        if (schemaSyncEnabled()) {
            SchemaEntitySync.syncOnStartup();
        }
        // 启动Spring Boot应用
        SpringApplication.run(AstralApplication.class, args);
    }

    /**
     * 是否启用启动时实体同步
     * <p>优先级：JVM 系统属性 {@code astral.schema.sync-on-startup} > 环境变量
     * {@code ASTRAL_SCHEMA_SYNC_ON_STARTUP} > 默认 true。
     * 任一显式为 false（忽略大小写）即关闭。</p>
     *
     * @return 是否启用
     */
    private static boolean schemaSyncEnabled() {
        String prop = System.getProperty("astral.schema.sync-on-startup");
        if (prop != null) {
            return !"false".equalsIgnoreCase(prop.trim());
        }
        String env = System.getenv("ASTRAL_SCHEMA_SYNC_ON_STARTUP");
        if (env != null) {
            return !"false".equalsIgnoreCase(env.trim());
        }
        System.err.println("[SchemaSync][WARN] 启动时实体同步默认开启：会删除无对应 schema JSON 的 entity .java 源文件。"
                + " 生产环境请设置 ASTRAL_SCHEMA_SYNC_ON_STARTUP=false 关闭。");
        return true;
    }
}