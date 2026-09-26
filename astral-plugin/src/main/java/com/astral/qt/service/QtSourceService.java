package com.astral.qt.service;

import com.astral.qt.common.QtException;
import com.astral.qt.dto.QtSourceReleaseCreateDto;
import com.astral.qt.dto.QtSourceReportDto;
import com.astral.qt.dto.vo.QtSourceArtifactVo;
import com.astral.qt.dto.vo.QtSourceManifestVo;
import com.astral.qt.dto.vo.QtSourceReleaseVo;
import com.astral.qt.entity.QtSourceRelease;
import com.astral.qt.entity.QtSourceReport;
import com.astral.qt.mapper.QtSourceReleaseMapper;
import com.astral.qt.mapper.QtSourceReportMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 音源包热更新服务（SOURCE_UPDATE_DESIGN §五）
 * <p>
 * 职责：manifest 动态生成（平台匹配 / 应用版本准入 / 渠道 / 撤回预筛）、
 * 版本号自动生成（§5.5，客户端与发布脚本一律不上送）、发布 / 撤回 / 标坏、
 * artifacts 按 path 合并（「只发 chain」的实现基础）、装载结果上报统计。
 * </p>
 * <p>
 * app_version_codes / artifacts 两列以 TEXT 存 JSON（见 qt-schema.sql 注释），
 * 序列化统一在本层用 Jackson 完成，实体字段保持 String。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QtSourceService {

    private final QtSourceReleaseMapper releaseMapper;
    private final QtSourceReportMapper reportMapper;
    private final ObjectMapper objectMapper;

    /** 版本号生成串行锁：单实例内保证「拿号」互斥，(code, channel) 唯一索引是最后一道防线 */
    private static final Object VERSION_LOCK = new Object();

    // ==================== 公开：manifest 与上报 ====================

    /**
     * 生成 manifest（GET /api/v1/app/source/manifest）。
     * <p>
     * 渠道即测试/正式标识（复用 channel 字段，与版本更新同源语义）：
     * channel=beta 为测试包，仅对拥有 qt_admin / qt_tester 权限（含超管 *:*:*）的用户投放；
     * 其余（stable 正式包）对所有用户投放。客户端不再上送 channel，由服务端按版本号
     * 从新到旧统一选取：第一个「平台匹配 + 应用版本准入命中 + 未标坏 + 人群可见」的版本即候选，
     * 无命中则 release 为 null（客户端保持当前版本）。
     * 因此测试包版本号高于正式包时，有权限用户收到测试包、无权限用户收到最新正式包；
     * 正式包版本号更高时，所有用户（含有权限者）都收到该正式包，不会收到版本号更低的测试包。
     * hostApiVersion 不参与服务端筛选，由客户端按自身契约版本决定是否跳过（§2.3 第 3 步）。
     * </p>
     * <p>
     * 回退信号：若存在比候选更新的已发布坏包，则在响应里显式携带 rollbackTo
     * （坏包指定了目标则用之，否则回退到候选本身），客户端据此允许降级（§2.3 第 5 步）。
     * 无权限用户对测试坏包不可感知（连同回退信号一并跳过）。
     * </p>
     */
    public QtSourceManifestVo buildManifest(Long platform, Long appVersionCode, Long hostApiVersion, boolean tester) {
        QtSourceManifestVo manifest = new QtSourceManifestVo();
        if (!QtSourceRelease.isSupportedPlatform(platform)) {
            return manifest;
        }
        List<QtSourceRelease> published = releaseMapper.selectList(new LambdaQueryWrapper<QtSourceRelease>()
                .eq(QtSourceRelease::getIsPublished, 1)
                .orderByDesc(QtSourceRelease::getSourceVersionCode));
        QtSourceRelease candidate = null;
        QtSourceRelease newestBad = null;
        for (QtSourceRelease r : published) {
            if (!platformMatches(r, platform)) {
                continue;
            }
            // 测试包人群过滤：无 qt_admin/qt_tester 权限的用户视同不存在该测试包
            // （前置于坏包登记，回退信号对无权限用户同样不可感知）
            if (isTestRelease(r) && !tester) {
                continue;
            }
            if (r.getIsBad() != null && r.getIsBad() == 1) {
                // 列表按 code 倒序，遇到的第一个坏包即为「比候选更新」的最新坏包
                if (newestBad == null) {
                    newestBad = r;
                }
                continue;
            }
            if (!admissionOk(r, platform, appVersionCode)) {
                continue;
            }
            candidate = r;
            break;
        }
        if (candidate == null) {
            return manifest;
        }
        manifest.setRelease(toVo(candidate, false));
        if (newestBad != null && newestBad.getSourceVersionCode() > candidate.getSourceVersionCode()) {
            manifest.getRelease().setRollbackTo(newestBad.getRollbackTo() != null
                    ? newestBad.getRollbackTo() : candidate.getSourceVersionCode());
        }
        return manifest;
    }

    /** 渠道是否为测试包（beta）；其余值（stable 及历史脏数据）一律按正式包投放 */
    private boolean isTestRelease(QtSourceRelease release) {
        return QtSourceRelease.CHANNEL_BETA.equalsIgnoreCase(
                release.getChannel() == null ? "" : release.getChannel().trim());
    }

    /** 装载结果上报（POST /api/v1/app/source/report，免认证），只做落库，失败不阻塞客户端 */
    public void recordReport(QtSourceReportDto dto) {
        if (dto == null || dto.getSourceVersionCode() == null) {
            throw new QtException("sourceVersionCode 不能为空");
        }
        QtSourceReport report = new QtSourceReport();
        report.setPlatform(dto.getPlatform() == null ? 0L : dto.getPlatform());
        report.setAppVersionCode(dto.getAppVersionCode() == null ? 0L : dto.getAppVersionCode());
        report.setSourceVersionCode(dto.getSourceVersionCode());
        // 严格校验：未知 result 不能静默归为 smoke_failed（会虚增坏包信号污染统计），空值按 ok 兜底
        String result = dto.getResult() == null || dto.getResult().isBlank()
                ? QtSourceReport.RESULT_OK : dto.getResult().trim().toLowerCase();
        if (!QtSourceReport.RESULT_OK.equals(result) && !QtSourceReport.RESULT_SMOKE_FAILED.equals(result)) {
            throw new QtException("result 只支持 ok / smoke_failed");
        }
        report.setResult(result);
        report.setDetail(dto.getDetail());
        reportMapper.insert(report);
    }

    // ==================== 管理端：列表 / 新建 / 编辑 / 生命周期 ====================

    /** 分页列表（按平台 / 渠道 / 发布状态筛选；full=1 时不分页返回全量，供调试） */
    public Page<QtSourceReleaseVo> pageReleases(Integer pageNum, Integer pageSize, Long platform,
                                                 String channel, Boolean published, boolean full) {
        Page<QtSourceRelease> page = new Page<>(pageNum == null ? 1 : pageNum,
                full || pageSize == null ? (full ? -1 : 10) : pageSize);
        LambdaQueryWrapper<QtSourceRelease> wrapper = new LambdaQueryWrapper<QtSourceRelease>()
                .orderByDesc(QtSourceRelease::getSourceVersionCode);
        if (channel != null && !channel.isBlank()) {
            wrapper.eq(QtSourceRelease::getChannel, channel.trim());
        }
        if (published != null) {
            wrapper.eq(QtSourceRelease::getIsPublished, published ? 1 : 0);
        }
        List<QtSourceRelease> records = releaseMapper.selectPage(page, wrapper).getRecords();
        List<QtSourceReleaseVo> vos = new ArrayList<>();
        for (QtSourceRelease r : records) {
            // 平台是列表级筛选：逐条匹配（platforms 是 CSV，不适合 SQL 精确条件）
            if (platform != null && !platformMatches(r, platform)) {
                continue;
            }
            vos.add(toVo(r, true));
        }
        Page<QtSourceReleaseVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(vos);
        return voPage;
    }

    /**
     * 新建 release：生成版本号 / 版本名（§5.5），artifacts 未提交的 path 自动继承上一版。
     * <p>请求体不含 sourceVersionCode / sourceVersionName，由本方法生成后随响应返回。</p>
     */
    public QtSourceReleaseVo createRelease(QtSourceReleaseCreateDto dto) {
        List<Long> platforms = validatePlatforms(dto.getPlatforms());
        synchronized (VERSION_LOCK) {
            long code = nextVersionCode();
            QtSourceRelease entity = new QtSourceRelease();
            entity.setSourceVersionCode(code);
            entity.setSourceVersionName(versionNameOf(code));
            entity.setPlatforms(toPlatformsCsv(platforms));
            entity.setHostApiVersion(dto.getHostApiVersion() == null ? 1L : dto.getHostApiVersion());
            entity.setChannel(normalizeChannel(dto.getChannel()));
            entity.setNotes(dto.getNotes());
            entity.setAppVersionCodes(writeJson(appVersionCodesOrDefault(dto.getAppVersionCodes())));
            entity.setArtifacts(writeJson(mergeArtifacts(readArtifacts(latestRelease(entity.getChannel())), dto.getArtifacts())));
            entity.setRollbackTo(dto.getRollbackTo());
            entity.setIsBad(0);
            entity.setIsPublished(0);
            releaseMapper.insert(entity);
            log.info("[QtSource] 新建音源包 release: code={} name={} channel={} platforms={}",
                    code, entity.getSourceVersionName(), entity.getChannel(), entity.getPlatforms());
            return toVo(entity, true);
        }
    }

    /**
     * 编辑（notes / appVersionCodes / artifacts）。artifacts 按 path 合并：只传变更项，其余继承。
     * sourceVersionCode / sourceVersionName / platforms / channel 不可改（忽略上送值）。
     */
    public void updateRelease(Long id, QtSourceReleaseCreateDto dto) {
        QtSourceRelease exist = requireRelease(id);
        if (dto.getNotes() != null) {
            exist.setNotes(dto.getNotes());
        }
        if (dto.getAppVersionCodes() != null) {
            exist.setAppVersionCodes(writeJson(appVersionCodesOrDefault(dto.getAppVersionCodes())));
        }
        if (dto.getArtifacts() != null) {
            exist.setArtifacts(writeJson(mergeArtifacts(readArtifacts(exist), dto.getArtifacts())));
        }
        exist.setUpdateTime(LocalDateTime.now());
        releaseMapper.updateById(exist);
    }

    /** 发布（is_published=1，记录发布时间） */
    public void publish(Long id) {
        QtSourceRelease exist = requireRelease(id);
        exist.setIsPublished(1);
        exist.setPublishedAt(LocalDateTime.now());
        exist.setUpdateTime(LocalDateTime.now());
        releaseMapper.updateById(exist);
        log.info("[QtSource] 发布音源包: code={} channel={}", exist.getSourceVersionCode(), exist.getChannel());
    }

    /** 撤回发布（停止投递，已装设备保持现状） */
    public void unpublish(Long id) {
        QtSourceRelease exist = requireRelease(id);
        exist.setIsPublished(0);
        exist.setUpdateTime(LocalDateTime.now());
        releaseMapper.updateById(exist);
        log.info("[QtSource] 撤回音源包: code={} channel={}", exist.getSourceVersionCode(), exist.getChannel());
    }

    /** 标记坏包（is_bad=1，可指定 rollbackTo；已标坏的版本不再参与 manifest 候选） */
    public void markBad(Long id, Long rollbackTo) {
        QtSourceRelease exist = requireRelease(id);
        exist.setIsBad(1);
        if (rollbackTo != null) {
            exist.setRollbackTo(rollbackTo);
        }
        exist.setUpdateTime(LocalDateTime.now());
        releaseMapper.updateById(exist);
        log.warn("[QtSource] 标记坏包: code={} channel={} rollbackTo={}",
                exist.getSourceVersionCode(), exist.getChannel(), exist.getRollbackTo());
    }

    /** 删除（仅未发布的 release 允许删除，防误删已投递历史） */
    public void deleteRelease(Long id) {
        QtSourceRelease exist = requireRelease(id);
        if (exist.getIsPublished() != null && exist.getIsPublished() == 1) {
            throw new QtException("已发布的版本不允许删除，请先撤回");
        }
        releaseMapper.deleteById(id);
    }

    /** 装机分布统计：按 版本号 × 平台 × 结果 聚合上报记录 */
    public List<Map<String, Object>> installStats() {
        return reportMapper.selectMaps(new QueryWrapper<QtSourceReport>()
                .select("source_version_code", "platform", "result", "count(*) as cnt")
                .groupBy("source_version_code", "platform", "result")
                .orderByDesc("source_version_code"));
    }

    // ==================== 版本号生成（§5.5） ====================

    /**
     * 生成下一个 sourceVersionCode：yyyyMMddNN。
     * <pre>
     * datePrefix = yyyyMMdd（服务端当前日期）
     * seq        = 当天已有 release 数 + 1
     * code       = datePrefix * 100 + seq
     * code       = max(code, 现有最大 code + 1)   // 兜底：严格单调，防时钟回拨
     * </pre>
     * 序列全局唯一（不按渠道分开），避免设备切渠道后看到更小的 code 拒绝更新。
     * 调用方持 {@link #VERSION_LOCK} 串行；跨实例并发由 (code, channel) 唯一索引兜底。
     */
    private long nextVersionCode() {
        LocalDate today = LocalDate.now();
        long dayBase = today.getYear() * 1000000L + today.getMonthValue() * 10000L + today.getDayOfMonth() * 100L;
        Long todayCount = releaseMapper.selectCount(new LambdaQueryWrapper<QtSourceRelease>()
                .ge(QtSourceRelease::getSourceVersionCode, dayBase)
                .lt(QtSourceRelease::getSourceVersionCode, dayBase + 100));
        long code = dayBase + (todayCount == null ? 0 : todayCount) + 1;
        Object maxObj = releaseMapper.selectObjs(new QueryWrapper<QtSourceRelease>()
                        .select("MAX(source_version_code) AS max_code")).stream()
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
        if (maxObj instanceof Number max && code <= max.longValue()) {
            code = max.longValue() + 1;
        }
        return code;
    }

    /** 由 code 反解展示名：2026091801 → 2026.09.18.1（纯派生，不参与判定） */
    public static String versionNameOf(long code) {
        String s = String.valueOf(code);
        if (s.length() != 10) {
            return String.valueOf(code);
        }
        return s.substring(0, 4) + "." + s.substring(4, 6) + "." + s.substring(6, 8) + "."
                + Integer.parseInt(s.substring(8));
    }

    // ==================== 内部工具 ====================

    /** platforms 字符串是否包含指定平台（CSV：如 "1101,1103"） */
    private boolean platformMatches(QtSourceRelease release, Long platform) {
        String csv = release.getPlatforms();
        if (csv == null || csv.isBlank()) {
            return false;
        }
        for (String part : csv.split(",")) {
            if (String.valueOf(platform).equals(part.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 应用版本准入（§2.3/§5.3）：平台缺省或空数组 = 不限制；
     * 配置了白名单时，请求的 appVersionCode 必须在其中。
     */
    private boolean admissionOk(QtSourceRelease release, Long platform, Long appVersionCode) {
        Map<String, List<Long>> codes = readAppVersionCodes(release);
        List<Long> allowed = codes.get(String.valueOf(platform));
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        return appVersionCode != null && allowed.contains(appVersionCode);
    }

    /** 同渠道最新一条 release；渠道内没有则回退到全局最新（artifacts 继承不区分渠道，URL 全集语义） */
    private QtSourceRelease latestRelease(String channel) {
        QtSourceRelease latest = releaseMapper.selectOne(new LambdaQueryWrapper<QtSourceRelease>()
                .eq(QtSourceRelease::getChannel, channel)
                .orderByDesc(QtSourceRelease::getSourceVersionCode)
                .last("LIMIT 1"));
        if (latest == null) {
            latest = releaseMapper.selectOne(new LambdaQueryWrapper<QtSourceRelease>()
                    .orderByDesc(QtSourceRelease::getSourceVersionCode)
                    .last("LIMIT 1"));
        }
        return latest;
    }

    /**
     * artifacts 按 path 合并（「只发 chain」的实现基础）：
     * 提交的 path 覆盖 / 新增（url 缺省继承上一版；version 缺省时，url 与上一版相同则保持原
     * version，变了才 +1——支持前端把编辑弹窗里的全集原样回传而不误伤未改动文件），
     * 未提交的 path 原样继承。结果始终保持「当前生效的全集」。
     */
    private List<QtSourceArtifactVo> mergeArtifacts(List<QtSourceArtifactVo> previous,
                                                     List<QtSourceArtifactVo> submitted) {
        Map<String, QtSourceArtifactVo> merged = new LinkedHashMap<>();
        if (previous != null) {
            for (QtSourceArtifactVo a : previous) {
                if (a.getPath() != null && !a.getPath().isBlank()) {
                    merged.put(a.getPath(), a);
                }
            }
        }
        if (submitted != null) {
            for (QtSourceArtifactVo s : submitted) {
                if (s.getPath() == null || s.getPath().isBlank()) {
                    throw new QtException("artifacts 条目的 path 不能为空");
                }
                QtSourceArtifactVo old = merged.get(s.getPath());
                QtSourceArtifactVo mergedItem = new QtSourceArtifactVo();
                mergedItem.setPath(s.getPath());
                if (s.getUrl() != null && !s.getUrl().isBlank()) {
                    mergedItem.setUrl(s.getUrl());
                } else if (old != null) {
                    mergedItem.setUrl(old.getUrl());
                } else {
                    throw new QtException("新增产物 " + s.getPath() + " 必须提供下载地址");
                }
                if (s.getVersion() != null) {
                    mergedItem.setVersion(s.getVersion());
                } else if (old != null && old.getUrl() != null && old.getUrl().equals(mergedItem.getUrl())) {
                    // url 没变 = 文件没换：version 保持不变（前端编辑回传全集时未改动项不能被 +1）
                    mergedItem.setVersion(old.getVersion());
                } else {
                    mergedItem.setVersion(old != null && old.getVersion() != null ? old.getVersion() + 1 : 1L);
                }
                merged.put(s.getPath(), mergedItem);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private QtSourceRelease requireRelease(Long id) {
        QtSourceRelease exist = releaseMapper.selectById(id);
        if (exist == null) {
            throw new QtException("release 不存在");
        }
        return exist;
    }

    private List<Long> validatePlatforms(List<Long> platforms) {
        if (platforms == null || platforms.isEmpty()) {
            throw new QtException("至少选择一个适用平台（1101/1102/1103）");
        }
        for (Long p : platforms) {
            if (!QtSourceRelease.isSupportedPlatform(p)) {
                throw new QtException("平台类型不支持(1101-Android 1102-iOS 1103-Windows)");
            }
        }
        return platforms;
    }

    private String toPlatformsCsv(List<Long> platforms) {
        StringBuilder sb = new StringBuilder();
        for (Long p : platforms) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private String normalizeChannel(String channel) {
        return channel == null || channel.isBlank() ? QtSourceRelease.CHANNEL_STABLE : channel.trim();
    }

    /** 实体 → 对外 VO；manage=true 时保留管理端字段，公开 manifest 时隐藏 */
    private QtSourceReleaseVo toVo(QtSourceRelease entity, boolean manage) {
        QtSourceReleaseVo vo = new QtSourceReleaseVo();
        vo.setId(manage ? entity.getId() : null);
        vo.setSourceVersionCode(entity.getSourceVersionCode());
        vo.setSourceVersionName(entity.getSourceVersionName());
        List<Long> platforms = new ArrayList<>();
        if (entity.getPlatforms() != null) {
            for (String part : entity.getPlatforms().split(",")) {
                if (!part.isBlank()) {
                    platforms.add(Long.valueOf(part.trim()));
                }
            }
        }
        vo.setPlatforms(platforms);
        vo.setHostApiVersion(entity.getHostApiVersion());
        vo.setAppVersionCodes(readAppVersionCodes(entity));
        vo.setChannel(entity.getChannel());
        vo.setNotes(entity.getNotes());
        vo.setArtifacts(readArtifacts(entity));
        vo.setRollbackTo(entity.getRollbackTo());
        vo.setBad(entity.getIsBad() != null && entity.getIsBad() == 1);
        vo.setPublished(manage ? entity.getIsPublished() != null && entity.getIsPublished() == 1 : null);
        vo.setPublishedAt(entity.getPublishedAt());
        vo.setCreateTime(manage ? entity.getCreateTime() : null);
        return vo;
    }

    private Map<String, List<Long>> readAppVersionCodes(QtSourceRelease entity) {
        // 首次建库 / 渠道尚无历史版本时 latestRelease 会返回 null，这里必须容空
        if (entity == null) {
            return new LinkedHashMap<>();
        }
        String json = entity.getAppVersionCodes();
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, List<Long>>>() {
            });
        } catch (Exception e) {
            log.warn("[QtSource] app_version_codes 解析失败 id={}: {}", entity.getId(), e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private List<QtSourceArtifactVo> readArtifacts(QtSourceRelease entity) {
        // createRelease 会拿 latestRelease() 的结果进来合并 artifacts；库空时为 null，不能 NPE
        if (entity == null) {
            return new ArrayList<>();
        }
        String json = entity.getArtifacts();
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<QtSourceArtifactVo>>() {
            });
        } catch (Exception e) {
            log.warn("[QtSource] artifacts 解析失败 id={}: {}", entity.getId(), e.getMessage());
            return new ArrayList<>();
        }
    }

    private Map<String, List<Long>> appVersionCodesOrDefault(Map<String, List<Long>> codes) {
        return codes == null ? new LinkedHashMap<>() : codes;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new QtException("JSON 序列化失败: " + e.getMessage());
        }
    }
}
