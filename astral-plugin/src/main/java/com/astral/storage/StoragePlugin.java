package com.astral.storage;

import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PluginFrontendExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件存储插件（Telegram + Cloudflare Worker 图床 MVP）
 * <p>
 * 职责：文件夹/权限/文件元数据管理、上传凭证签发、下载签名 URL 签发、
 * Worker 回调接收（上传结果、远端删除任务）。文件正文不经过 Astral：
 * 上传由浏览器直传 Cloudflare Worker，Worker 使用自身 Secret 中的 Bot Token
 * 流式转发到 Telegram；下载由 Worker 验签后回源/缓存。
 * </p>
 * <p>默认开启（astral.plugins.storage.enabled=true，允许在插件管理页禁用）。</p>
 */
@Slf4j
@Component
public class StoragePlugin implements AstralPlugin, PluginFrontendExtension {

    @Override
    public String getPluginId() {
        return "storage";
    }

    @Override
    public String getPluginName() {
        return "文件存储";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "统一文件存储与图床：Telegram 通道 + Cloudflare Worker 分发，文件夹权限与签名 URL";
    }

    @Override
    public List<String> getApiPrefixes() {
        // 精确前缀：禁用 storage 时只拦截本插件接口，不影响其他插件
        return List.of("/api/v1/admin/plugin/storage", "/api/v1/all/storage");
    }

    @Override
    public boolean isRequired() {
        return false;
    }

    @Override
    public void onEnable() {
        log.info("[StoragePlugin] 文件存储插件已启用");
    }

    @Override
    public void onDisable() {
        log.warn("[StoragePlugin] 文件存储插件已禁用，/api/v1/admin/plugin/storage、/api/v1/all/storage 接口不可用");
    }

    // ==================== 前端导航扩展 ====================

    @Override
    public List<NavItem> getNavItems() {
        return List.of(
                new NavItem("文件存储", "/dashboard/plugin/storage", "CloudUploadOutlined", 220)
        );
    }
}
