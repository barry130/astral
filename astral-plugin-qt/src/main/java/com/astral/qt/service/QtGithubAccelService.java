package com.astral.qt.service;

import com.astral.qt.dto.vo.QtGithubAccelProbeVo;
import com.astral.qt.dto.vo.QtGithubAccelVo;
import com.astral.qt.entity.QtGithubAccel;
import com.astral.qt.mapper.QtGithubAccelMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * GitHub 加速前缀服务（UPDATE_DESIGN.md §2.3/§2.4）
 * <p>仿 SysConfigServiceImpl 的 Caffeine 缓存模式：</p>
 * <ul>
 *   <li>启动即加载（@PostConstruct → refreshCache）；</li>
 *   <li>App 端读取走缓存（listEnabled，未命中自动加载）；</li>
 *   <li>增删改自动刷新（各写操作末尾调 refreshCache）；</li>
 *   <li>手动失效（evictCache：清空 + 立即重载，避免空窗）；</li>
 *   <li>兜底过期 24h（节点极少修改，正常不会自然过期）。</li>
 * </ul>
 */
@Slf4j
@Service
public class QtGithubAccelService extends ServiceImpl<QtGithubAccelMapper, QtGithubAccel> {

    /** 缓存单键 */
    private static final String CACHE_KEY = "enabled";

    /**
     * 探测固定目标：GitHub raw 文件。
     * <p>加速代理一般只放行 raw/release/archive 路径，首页 {@code https://github.com/} 会被拒；
     * raw 小文件走 Range 0-0 快速返回 206，探测耗时真实。</p>
     */
    private static final String PROBE_TARGET = "https://github.com/microsoft/vscode/raw/main/README.md";

    /** 探测超时（秒），与设计一致最长等 5 秒 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    @Resource
    private QtGithubAccelMapper accelMapper;

    private Cache<String, List<QtGithubAccelVo>> cache;

    @PostConstruct
    public void init() {
        cache = Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(24, TimeUnit.HOURS)
                .build();
        // 启动时即加载入缓存
        refreshCache();
    }

    /** App 端读取：走缓存，未命中自动加载（仅 is_show=1，按 sort 升序） */
    public List<QtGithubAccelVo> listEnabled() {
        return cache.get(CACHE_KEY, k -> loadEnabledFromDb());
    }

    /** 增删改后调用 → 主动刷新缓存 */
    public void refreshCache() {
        cache.put(CACHE_KEY, loadEnabledFromDb());
        log.debug("[QtPlugin] GitHub 加速缓存已刷新: {} 条", cache.getIfPresent(CACHE_KEY) == null ? 0
                : cache.getIfPresent(CACHE_KEY).size());
    }

    /** 手动失效（运维接口触发）：清空 + 立即重新加载，避免空窗 */
    public void evictCache() {
        cache.invalidateAll();
        refreshCache();
    }

    private List<QtGithubAccelVo> loadEnabledFromDb() {
        return accelMapper.selectList(
                        new LambdaQueryWrapper<QtGithubAccel>()
                                .eq(QtGithubAccel::getIsShow, 1)
                                .orderByAsc(QtGithubAccel::getSort)
                ).stream()
                .map(a -> new QtGithubAccelVo(a.getId(), a.getName(), normalizePrefix(a.getPrefixUrl())))
                .toList();
    }

    /**
     * 前缀规范化：补尾斜杠。拼接规则是 {@code prefix + 原始链接} 直接串联，
     * 存量配置可能漏掉尾斜杠（拼出 {@code https://ghf.tophttps://...} 非法 URL），
     * 在读取出口统一修正，管理端保存与 App 端/探活共用。
     */
    public static String normalizePrefix(String prefixUrl) {
        if (prefixUrl == null || prefixUrl.isBlank()) {
            return prefixUrl;
        }
        return prefixUrl.endsWith("/") ? prefixUrl : prefixUrl + "/";
    }

    /**
     * 管理端手动探活（UPDATE_DESIGN.md §2.4）：并发探测所有启用节点，结果仅展示不写库。
     * <p>探测方式与 App 端一致：Range: bytes=0-0，HTTP 200/206 视为可用。</p>
     */
    public List<QtGithubAccelProbeVo> probe() {
        List<QtGithubAccelVo> nodes = listEnabled();
        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(PROBE_TIMEOUT)
                .build()) {
            List<CompletableFuture<QtGithubAccelProbeVo>> futures = nodes.stream()
                    .map(node -> CompletableFuture.supplyAsync(() -> probeOne(client, node)))
                    .toList();
            return futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
        }
    }

    /** 单节点探测：Range 0-0 请求前缀+目标，2xx 视为可用，记录耗时与失败原因 */
    private QtGithubAccelProbeVo probeOne(HttpClient client, QtGithubAccelVo node) {
        long start = System.currentTimeMillis();
        String url = node.getPrefixUrl() + PROBE_TARGET;
        boolean alive = false;
        String message;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(PROBE_TIMEOUT)
                    .header("Range", "bytes=0-0")
                    .header("User-Agent", "astral-qt-probe")
                    .GET()
                    .build();
            HttpResponse<Void> resp = client.send(request, HttpResponse.BodyHandlers.discarding());
            int code = resp.statusCode();
            alive = code >= 200 && code < 300;
            message = "HTTP " + code;
        } catch (IllegalArgumentException e) {
            message = "URL 拼接非法";
        } catch (java.net.http.HttpConnectTimeoutException e) {
            message = "连接超时";
        } catch (java.net.ConnectException e) {
            message = "连接失败";
        } catch (Exception e) {
            message = e.getClass().getSimpleName();
        }
        return new QtGithubAccelProbeVo(node.getId(), node.getName(), node.getPrefixUrl(),
                alive, System.currentTimeMillis() - start, message);
    }
}
