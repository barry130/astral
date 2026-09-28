package com.astral.auth.config;

import cn.dev33.satoken.SaManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * 把 Sa-Token 的 JSON 模板换成 {@link TolerantSaJsonTemplate}。
 *
 * <p>Sa-Token 1.46 通过 SPI 插件 {@code SaTokenPluginForJackson3} 在 {@code SaManager}
 * 初始化时装上 Jackson 3 模板；该插件只在当前模板还是 {@code SaJsonTemplateDefaultImpl}
 * 时才覆盖，所以这里晚一步设置可以稳定生效。</p>
 *
 * <p>不装这个模板的话，旧版本（Spring Boot 3 + Sa-Token 1.42，Jackson 2）写进共享
 * Redis 的会话会因为 {@code LocalDateTime} 格式不匹配而反序列化失败，
 * 登录接口直接抛 {@code SaJsonConvertException}。</p>
 */
@Slf4j
@Configuration
public class SaTokenJsonConfig {

    @PostConstruct
    public void installTolerantJsonTemplate() {
        try {
            SaManager.setSaJsonTemplate(new TolerantSaJsonTemplate());
            log.info("Sa-Token JSON 模板已替换为 TolerantSaJsonTemplate（兼容 yyyy-MM-dd HH:mm:ss 与 ISO-8601）");
        } catch (Exception e) {
            log.error("Sa-Token JSON 模板替换失败，跨版本会话可能无法反序列化", e);
        }
    }
}
