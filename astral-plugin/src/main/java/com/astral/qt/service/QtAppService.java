package com.astral.qt.service;

import com.astral.qt.entity.QtAppUpdate;
import com.astral.qt.entity.QtAppUpdateArtifact;
import com.astral.qt.mapper.QtAppUpdateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 轻听 App 版本更新服务
 */
@Slf4j
@Service
public class QtAppService {

    @Resource
    private QtAppUpdateMapper updateMapper;

    /** 版本产物（一版本多包：Windows x64/x86/arm64…） */
    @Resource
    private QtAppUpdateArtifactService artifactService;

    /**
     * 版本更新表按平台维度的进程内缓存（key=type，TTL 60s）。
     * <p>App 启动轮询 {@code /api/v1/app/update} 与 {@code /api/v1/app/version/check}
     * 是全量高频读，原先每个请求都打一轮 qt_app_update 查询（/update 还按渠道逐条 selectOne）。
     * 这里缓存该平台下的<b>全量行</b>（含未发布——getOfficialVersion 官方校验刻意不过滤
     * is_published），渠道/版本过滤在内存完成；后台版本 CRUD 后由 {@link #evictUpdateCache()}
     * 主动失效，TTL 60s 兜底。缓存的实体只读不写，序列化直出。</p>
     */
    private final Cache<Long, List<QtAppUpdate>> updateCache = Caffeine.newBuilder()
            .maximumSize(16)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    /**
     * 获取版本更新信息。
     *
     * @param type            1101 安卓 / 1102 iOS / 1103 Windows / 1104 Linux / 1105 macOS / 1106 鸿蒙
     * @param version         客户端版本号（如 222 对应 2.2.2）
     * @param visibleChannels 当前用户<b>可见</b>的渠道集合，由 DataScopeResolver 按结果级权限
     *                        （qt:update:channel:*）解析，无权限者只有 stable。
     *                        <p>本方法在可见集合内取版本号最大者：因此正式版版本号高于测试版时，
     *                        持有测试权限的用户收到的仍是正式版——权限只决定「能看见哪些渠道」，
     *                        不决定「必须拿哪个版本」。</p>
     *                        <p>双链接直出 downloadUrl/browserUrl/isGithub（UPDATE_DESIGN.md §2.1①），
     *                        旧的 && 多链接随机逻辑已随 downloadMode 一并废弃。</p>
     */
    public QtAppUpdate getUpdate(Long type, String version, Set<String> visibleChannels) {
        long versionCode;
        try {
            versionCode = Long.parseLong(version);
        } catch (NumberFormatException e) {
            log.warn("[QtPlugin] 版本号格式错误: {}", version);
            return null;
        }
        if (visibleChannels == null || visibleChannels.isEmpty()) {
            return null;
        }
        // 在可见渠道集合内取「高于当前版本且已发布」的最高版本
        QtAppUpdate picked = null;
        for (String channel : visibleChannels) {
            picked = pickHigher(picked, findLatest(type, versionCode, channel));
        }
        return picked;
    }

    /**
     * 校验当前 APP 版本是否为官方发布版本。
     * <p>按 平台(type) + 版本号(versionCode) + 版本名称(versionName) 精确匹配 qt_app_update，
     * 若无记录则视为非官方版本，返回 null（由调用方报错）。</p>
     * <p><b>注意</b>：本方法<b>不过滤</b> is_published —— 未发布版本仅用于本地版本测试，
     * 也应通过"官方校验"，本地测试版本不被判为"非官方版本"。</p>
     *
     * @param type        1101 安卓 / 1102 iOS / 1103 Windows / 1104 Linux / 1105 macOS / 1106 鸿蒙
     * @param version     客户端版本号（如 222 对应 2.2.2）
     * @param versionName 客户端版本名称（如 2.2.2）
     */
    public QtAppUpdate getOfficialVersion(Long type, String version, String versionName) {
        if (!QtAppUpdate.isSupportedType(type)) {
            return null;
        }
        long versionCode;
        try {
            versionCode = Long.parseLong(version);
        } catch (NumberFormatException e) {
            log.warn("[QtPlugin] 版本号格式错误: {}", version);
            return null;
        }
        List<QtAppUpdate> list = listUpdatesByType(type);
        QtAppUpdate official = list.stream()
                .filter(u -> u.getVersionCode() != null && u.getVersionCode() == versionCode
                        && versionName != null && versionName.equals(u.getVersionName()))
                .findFirst()
                .orElse(null);
        return official;
    }

    /** 查询指定渠道、高于当前版本、<b>已发布(is_published=1)</b> 的最新一条（进程内缓存 + 内存过滤） */
    private QtAppUpdate findLatest(Long type, long versionCode, String channel) {
        return listUpdatesByType(type).stream()
                .filter(u -> channel.equals(u.getChannel())
                        && Integer.valueOf(1).equals(u.getIsPublished())
                        && u.getVersionCode() != null
                        && u.getVersionCode() > versionCode)
                .max(Comparator.comparing(QtAppUpdate::getVersionCode))
                .orElse(null);
    }

    /** 该平台的全量版本行（含未发布），带进程内缓存；后台 CRUD 主动失效，TTL 60s 兜底 */
    private List<QtAppUpdate> listUpdatesByType(Long type) {
        return updateCache.get(type, t -> updateMapper.selectList(
                new LambdaQueryWrapper<QtAppUpdate>().eq(QtAppUpdate::getType, t)));
    }

    /** 后台版本更新 CRUD 后调用：失效全平台版本缓存（QtAdminController 写接口） */
    public void evictUpdateCache() {
        updateCache.invalidateAll();
        artifactService.evictAll();
    }

    /** 取两个更新中版本号更大的一个（相同版本号时优先 beta；任一为空则取另一个） */
    private QtAppUpdate pickHigher(QtAppUpdate a, QtAppUpdate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.getVersionCode() >= b.getVersionCode() ? a : b;
    }

    // ==================== 一版本多产物（UPDATE_ARTIFACT_DESIGN） ====================

    /**
     * 获取版本更新信息（多产物版）：在 {@link #getUpdate} 的基础上按客户端架构挑选产物。
     * <p>命中产物时把它的 downloadUrl / browserUrl / md5 / fileSize 覆盖到主表同名字段上，
     * 这样「不会读 artifacts 的旧客户端」天然拿到正确架构的包（对用户最友好：
     * 旧 qt-uniappx / 未升级的 qt-pc 不会因为改了后端数据结构就下错包）。
     * 没命中任何产物时保持主表原值（旧数据的 download_url 还在，仍是兜底）。</p>
     * <p>artifacts 全量清单始终下发：客户端可以在服务端挑不中时自行按 platform/arch 挑。</p>
     *
     * @param arch 客户端 CPU 架构（x64 / x86 / arm64…），移动端传 null 或空
     */
    public QtAppUpdate getUpdate(Long type, String version, Set<String> visibleChannels, String arch) {
        QtAppUpdate picked = getUpdate(type, version, visibleChannels);
        return attachArtifacts(picked, type, arch);
    }

    /** 官方版本校验的多产物版（语义同 {@link #getUpdate(Long, String, Set, String)}） */
    public QtAppUpdate getOfficialVersion(Long type, String version, String versionName, String arch) {
        QtAppUpdate official = getOfficialVersion(type, version, versionName);
        return attachArtifacts(official, type, arch);
    }

    /**
     * 给一条更新附上产物清单，并把「命中的那一条」的值提升为主表字段。
     * <p>查不到产物清单时（旧数据）什么都不动：主表 download_url 仍是那唯一一个包。</p>
     */
    private QtAppUpdate attachArtifacts(QtAppUpdate update, Long type, String arch) {
        if (update == null) {
            return null;
        }
        Long updateId = update.getId();
        if (updateId == null) {
            return update;
        }
        List<QtAppUpdateArtifact> all = artifactService.listByUpdateId(update.getId());
        if (all == null || all.isEmpty()) {
            return update;
        }
        update.setArtifacts(artifactService.toVos(updateId));
        QtAppUpdateArtifact hit = artifactService.select(updateId, type, arch);
        if (hit == null) {
            return update;
        }
        if (hit.getDownloadUrl() != null && !hit.getDownloadUrl().isBlank()) {
            update.setDownloadUrl(hit.getDownloadUrl());
            update.setIsGithub(hit.getIsGithub() == null ? 0L : hit.getIsGithub());
        }
        if (hit.getBrowserUrl() != null && !hit.getBrowserUrl().isBlank()) {
            update.setBrowserUrl(hit.getBrowserUrl());
        }
        update.setMd5(hit.getMd5());
        update.setFileSize(hit.getFileSize());
        return update;
    }

    /** 后台用：给列表里的每条版本附上产物清单（不挑选、不覆盖主表字段） */
    public List<QtAppUpdate> attachArtifacts(List<QtAppUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return updates;
        }
        for (QtAppUpdate u : updates) {
            if (u == null || u.getId() == null) {
                continue;
            }
            u.setArtifacts(artifactService.toVos(u.getId()));
        }
        return updates;
    }
}