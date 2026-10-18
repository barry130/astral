package com.astral.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 插件配置（前缀 {@code astral.plugins.ai}）。
 *
 * <p>这里只放「部署级」参数：开关、密钥环、超时上限、事件保留策略等。
 * 智能体、模型、Prompt、工具、知识源、额度的<b>业务配置全部落在数据库</b>，
 * 由后台页面维护，不允许写在代码或本配置类里——设计文档 §4.1 要求智能体可后台独立配置。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "astral.plugins.ai")
public class AiProperties {

    /** 插件总开关；关闭后所有 /api/v1/{admin,app}/ai/** 接口返回 AI001 */
    private boolean enabled = true;

    /**
     * API Key 落库加密口令（AES-GCM 的 KEK）。
     * <p>生产环境必须通过环境变量 {@code ASTRAL_AI_SECRET_KEY} 注入；
     * 留空时插件退化为「只允许 {@code secret_ref} 引用」模式，不会明文落库。</p>
     */
    private String secretKey = "";

    /** 是否允许插件启动时自动建表（false 时完全依赖 Flyway 迁移） */
    private boolean autoSchema = true;

    /**
     * 是否在启动时补齐种子配置（三个默认智能体、提示词、知识源、额度策略）。
     * <p>种子只是<b>初始数据</b>：已有同编码的智能体就整块跳过，绝不覆盖后台改过的配置。</p>
     */
    private boolean seedEnabled = true;

    /** 单次请求输入字符硬上限（即使智能体配置更大也会被这里截断） */
    private int hardMaxInputChars = 60000;

    /** 单次请求输出 Token 硬上限 */
    private int hardMaxOutputTokens = 8192;

    /** 单次请求上下文 Token 硬上限 */
    private int hardMaxContextTokens = 128000;

    /** 请求总时长上限（秒），超时置 FAILED AI006 */
    private int requestTimeoutSeconds = 180;

    /** 事件流单次轮询最多返回条数 */
    private int eventBatchSize = 200;

    /** 事件保留天数（超期由清理任务删除） */
    private int eventRetentionDays = 14;

    /** 请求运行中时，事件被读取最长可等待的毫秒数（长轮询） */
    private long eventLongPollMillis = 25000;

    /** 不参与取号的表（与 sequence 模块一致的排除语义，插件侧仅保留说明用途） */
    private boolean logPayload = false;
}
