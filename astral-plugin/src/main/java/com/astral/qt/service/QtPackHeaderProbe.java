package com.astral.qt.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.astral.qt.dto.vo.QtSourceArtifactVo;
import com.astral.qt.entity.QtSourceRelease;
import com.astral.qt.mapper.QtSourceReleaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 音源包产物包头（{@code /*__QT_PACK__{...}&#42;/}）探测与回填。
 *
 * <p><b>为什么需要它：</b>manifest 渠道里 play 的版本号可以直接用 release.sourceVersionCode，
 * 而 meta 的真实版本号只存在于包文件首行包头里——历史上客户端要自己发 Range 请求探测
 * （qt-uniappx 的 meta 更新因此被 cnb.cool 302 跳转坑过：探测失败 → 静默跳过 → 用户看到
 * 「已经是最新」）。服务端在发布链路上把包头的 packId / packVersionCode / packVersionName
 * 探测好、随 manifest 直发，客户端（qt-pc ≥ 本次改动 / qt-uniappx ≥ 本次改动）优先直接比版，
 * 免探测、口径与 play 统一；字段缺失（旧服务端发的旧 release）时客户端回退老逻辑。</p>
 *
 * <p><b>补齐时机：</b>createRelease / updateRelease 后 fire-and-forget 异步补齐
 * （{@link #fillAsync}，跑在 sequenceAsyncExecutor 虚拟线程上）；publish 前同步补齐
 * （{@link #fillSync}，短超时逐条尽力，失败只告警、绝不阻塞发布）。回填只按 url 对号入座，
 * 探测期间管理员改了别的字段不会被覆盖（只 update artifacts 一列，且只写 url 未变的条目）。</p>
 *
 * <p><b>解析口径：</b>与三个宿主/工具完全同款——找 {@code /*__QT_PACK__} 标记，取其后到最近
 * {@code &#42;/} 之间的 JSON（qt-pc source_install.rs、qt-uniappx source-update.uts、
 * qt-sources build-sources.mjs）。探测 GET 带 {@code Range: bytes=0-8191}，包头永远在
 * 第一行，整包动辄 MB 级不能全量拉；跟随 302/301 跳转（cnb.cool 短链 → OSS）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QtPackHeaderProbe {

    /** 包头自述标记（与宿主/构建脚本同款）：产物首行 /*__QT_PACK__{json}*&#47; */
    public static final String PACK_HEADER_MARK = "/*__QT_PACK__";

    /** Range 只取首 8KB：包头永远在第一行 */
    private static final int PROBE_RANGE_BYTES = 8192;

    /** 建单/编辑后异步补齐的单请求超时 */
    private static final Duration ASYNC_PROBE_TIMEOUT = Duration.ofSeconds(4);

    /** 发布前同步补齐的单请求超时（2 个产物 × 3s 最坏 6s，管理端按钮可接受） */
    private static final Duration SYNC_PROBE_TIMEOUT = Duration.ofSeconds(3);

    private final QtSourceReleaseMapper releaseMapper;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** 包头解析结果（只关心升级比版需要的字段） */
    public record PackHeader(String kind, String id, String name, long versionCode, String versionName) {
    }

    /**
     * 宿主同款解析：找 {@code /*__QT_PACK__} 标记，取其后到最近 {@code &#42;/} 之间的 JSON。
     * <p>文本缺失 / 无标记 / JSON 损坏 / 关键字段（kind、id、versionCode≥1）缺失一律返回 null
     * ——探测是尽力而为的旁路，绝不能把半截解析结果当有效版本号写进 manifest。</p>
     */
    public static PackHeader parsePackHeader(String text, ObjectMapper mapper) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int mark = text.indexOf(PACK_HEADER_MARK);
        if (mark < 0) {
            return null;
        }
        int start = mark + PACK_HEADER_MARK.length();
        int end = text.indexOf("*/", start);
        if (end < 0) {
            return null;
        }
        String json = text.substring(start, end).trim();
        try {
            JsonNode node = mapper.readTree(json);
            String kind = node.path("kind").asString("").trim();
            String id = node.path("id").asString("").trim();
            long versionCode = node.path("versionCode").asLong(0);
            if (kind.isEmpty() || id.isEmpty() || versionCode <= 0) {
                return null;
            }
            return new PackHeader(kind, id,
                    node.path("name").asString("").trim(),
                    versionCode,
                    node.path("versionName").asString("").trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 探测一个产物 URL 的包头。任何失败（非法 URL / 超时 / 非 2xx / 无包头 / 包头损坏）
     * 一律返回 null——探测是旁路，不能阻塞发布主链路。
     */
    public PackHeader probe(String url, Duration timeout) {
        if (url == null || url.isBlank() || !(url.startsWith("https://") || url.startsWith("http://"))) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Range", "bytes=0-" + (PROBE_RANGE_BYTES - 1))
                    .header("User-Agent", "astral-pack-probe/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status != 200 && status != 206) {
                log.warn("[QtPackProbe] 产物探测非 2xx: status={} url={}", status, url);
                return null;
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                return null;
            }
            return parsePackHeader(new String(body, java.nio.charset.StandardCharsets.UTF_8), objectMapper);
        } catch (Exception e) {
            log.warn("[QtPackProbe] 产物探测失败: url={} err={}", url, e.getMessage());
            return null;
        }
    }

    /** 建单/编辑后异步补齐（fire-and-forget）：补不上客户端会回退自己探测，不影响主链路 */
    @Async("sequenceAsyncExecutor")
    public void fillAsync(Long releaseId) {
        try {
            fill(releaseId, ASYNC_PROBE_TIMEOUT);
        } catch (Exception e) {
            log.warn("[QtPackProbe] 异步补齐失败 id={}: {}", releaseId, e.getMessage());
        }
    }

    /**
     * 发布前同步补齐（短超时逐条尽力）。返回本次成功补齐的条数；探测不到的条目只告警，
     * 发布继续（客户端直发字段缺失时回退自己探测）。
     */
    public int fillSync(Long releaseId) {
        return fill(releaseId, SYNC_PROBE_TIMEOUT);
    }

    /**
     * 补齐主流程：探测 → 二次读最新行 → 只给「url 与探测时一致且缺直发字段」的条目对号补齐
     * → 只 update artifacts 一列。探测期间管理员并发改单不会丢字段：url 变了的条目跳过，
     * 等它自己的下一次补齐；其余并发修改的列完全不碰。
     */
    private int fill(Long releaseId, Duration timeout) {
        QtSourceRelease row = releaseMapper.selectById(releaseId);
        if (row == null) {
            return 0;
        }
        List<QtSourceArtifactVo> snapshot = readArtifacts(row);
        // url → 探测结果（同 url 多条目只探一次）
        java.util.LinkedHashMap<String, PackHeader> headers = new java.util.LinkedHashMap<>();
        for (QtSourceArtifactVo a : snapshot) {
            if (a == null || a.getUrl() == null || a.getUrl().isBlank()) {
                continue;
            }
            if (hasDirectFields(a)) {
                continue;
            }
            if (headers.containsKey(a.getUrl())) {
                continue;
            }
            PackHeader header = probe(a.getUrl(), timeout);
            headers.put(a.getUrl(), header);
            if (header == null) {
                log.warn("[QtPackProbe] 产物包头探测失败: id={} path={} url={}（客户端将回退自行探测）",
                        releaseId, a.getPath(), a.getUrl());
                continue;
            }
            // 发布前对账：产物包头版本与 release 版本号不一致 → 极可能是「新版本挂了旧产物」
            if (row.getSourceVersionCode() != null && header.versionCode() != row.getSourceVersionCode()) {
                log.warn("[QtPackProbe] 产物包头版本与 release 不一致: id={} path={} 包头={} release={} "
                                + "（请确认产物是否本次构建；客户端会以包头为准拒绝安装版本不符的更新）",
                        releaseId, a.getPath(), header.versionCode(), row.getSourceVersionCode());
            }
        }
        if (headers.isEmpty()) {
            return 0;
        }
        // 二次读：探测期间行可能被并发编辑，只把探测结果合并进最新行里 url 未变的条目
        QtSourceRelease fresh = releaseMapper.selectById(releaseId);
        if (fresh == null) {
            return 0;
        }
        List<QtSourceArtifactVo> arts = readArtifacts(fresh);
        int filled = 0;
        boolean dirty = false;
        for (QtSourceArtifactVo a : arts) {
            if (a == null || a.getUrl() == null || hasDirectFields(a)) {
                continue;
            }
            PackHeader header = headers.get(a.getUrl());
            if (header == null) {
                continue;
            }
            a.setPackId(header.id());
            a.setPackVersionCode(header.versionCode());
            a.setPackVersionName(header.versionName());
            filled++;
            dirty = true;
        }
        if (dirty) {
            String merged = writeArtifacts(arts);
            if (merged == null) {
                // 写不出 JSON（序列化失败）就不落库：MP update 对 null 字段是跳过语义，
                // 与其让 update 变成只改 updateTime 的空转，不如直接放弃本次补齐
                return filled;
            }
            QtSourceRelease patch = new QtSourceRelease();
            patch.setArtifacts(merged);
            patch.setUpdateTime(LocalDateTime.now());
            releaseMapper.update(patch, new LambdaQueryWrapper<QtSourceRelease>()
                    .eq(QtSourceRelease::getId, releaseId));
            log.info("[QtPackProbe] 已回填产物直发字段: id={} count={} fields={}",
                    releaseId, filled, headers.keySet());
        }
        return filled;
    }

    private boolean hasDirectFields(QtSourceArtifactVo a) {
        return a.getPackVersionCode() != null && a.getPackVersionCode() > 0
                && a.getPackId() != null && !a.getPackId().isBlank();
    }

    private List<QtSourceArtifactVo> readArtifacts(QtSourceRelease row) {
        String json = row.getArtifacts();
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<QtSourceArtifactVo>>() {
            });
        } catch (Exception e) {
            log.warn("[QtPackProbe] artifacts 解析失败 id={}: {}", row.getId(), e.getMessage());
            return new ArrayList<>();
        }
    }

    private String writeArtifacts(List<QtSourceArtifactVo> arts) {
        try {
            return objectMapper.writeValueAsString(arts);
        } catch (Exception e) {
            // 只在此处兜底：调用方 fill() 依赖返回 null 判断写失败
            return null;
        }
    }
}
