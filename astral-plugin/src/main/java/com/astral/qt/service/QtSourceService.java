package com.astral.qt.service;

import com.astral.auth.security.DataScopeResolver;
import com.astral.dao.entity.DictData;
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
import com.astral.system.service.DictDataService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 * <p>
 * <b>产物继承与数据字典的联动</b>：artifacts 是「当前生效全集」，新建/编辑时会继承上一版未提交的
 * path。若某 path 已在字典 {@code qt_source_artifact_path} 里被<b>停用</b>（status=0，如单包时代的
 * chain.json / source-bundle.js），继承时会把它摘掉——否则废弃产物会一代代静默传递下去，
 * 让新建 release 的产物数越滚越多。显式提交的废弃 path 仍然保留（保证历史版本可编辑）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QtSourceService {

    private final QtSourceReleaseMapper releaseMapper;
    private final QtSourceReportMapper reportMapper;
    private final DictDataService dictDataService;
    private final ObjectMapper objectMapper;

    /** 产物 path 白名单字典（启用的出现在前端下拉，停用的不再被继承） */
    private static final String DICT_ARTIFACT_PATH = "qt_source_artifact_path";

    /** 版本号生成串行锁：单实例内保证「拿号」互斥，(code, channel) 唯一索引是最后一道防线 */
    private static final Object VERSION_LOCK = new Object();

    // ==================== 公开：manifest 与上报 ====================

    /**
     * 生成 manifest（GET /api/v1/app/source/manifest）。
     * <p>
     * 渠道即测试/正式标识（复用 channel 字段，与版本更新同源语义）：
     * 调用方传入当前用户的<b>可见渠道集合</b>（stable 为基础可见，beta 需
     * {@code user:qt:source:channel:beta} 结果级权限），本方法把不可见渠道的 release 视同不存在。
     * 客户端不上送 channel，服务端按版本号从新到旧统一选取：第一个「平台匹配 + 应用版本准入命中
     * + 未标坏 + 人群可见」的版本即候选，无命中则 release 为 null（客户端保持当前版本）。
     * 因此测试包版本号高于正式包时，有权限用户收到测试包、无权限用户收到最新正式包；
     * 正式包版本号更高时，所有用户（含有权限者）都收到该正式包，不会收到版本号更低的测试包。
     * hostApiVersion 不参与服务端筛选，由客户端按自身契约版本决定是否跳过（§2.3 第 3 步）。
     * </p>
     * <p>
     * 回退信号：若存在比候选更新的已发布坏包，则在响应里显式携带 rollbackTo
     * （坏包指定了目标则用之，否则回退到候选本身），客户端据此允许降级（§2.3 第 5 步）。
     * 无权限用户对测试坏包不可感知（连同回退信号一并跳过）。
     * </p>
     *
     * @param visibleChannels 可见渠道集合，如 {@code [stable]} / {@code [stable, beta]}；
     *                        为空表示没有任何可见渠道，直接返回空 manifest
     */
    public QtSourceManifestVo buildManifest(Long platform, Long appVersionCode, Long hostApiVersion,
                                            Set<String> visibleChannels) {
        QtSourceManifestVo manifest = new QtSourceManifestVo();
        if (!QtSourceRelease.isSupportedPlatform(platform)) {
            return manifest;
        }
        if (visibleChannels == null || visibleChannels.isEmpty()) {
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
            // 渠道可见性过滤：不可见渠道的包对该用户视同不存在
            // （前置于坏包登记，回退信号对无权限用户同样不可感知）
            if (!isChannelVisible(r, visibleChannels)) {
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

    /**
     * 渠道是否对当前用户可见。
     * <p>channel 为 beta 时需要用户可见集合包含 beta；其余值（stable 及历史脏数据）
     * 一律按正式包处理，只要用户在 stable 基础集合内即可见——保持升级前的投放行为。</p>
     */
    private boolean isChannelVisible(QtSourceRelease release, Set<String> visibleChannels) {
        String channel = release.getChannel() == null ? "" : release.getChannel().trim();
        if (QtSourceRelease.CHANNEL_BETA.equalsIgnoreCase(channel)) {
            return DataScopeResolver.visible(visibleChannels, QtSourceRelease.CHANNEL_BETA,
                    QtSourceRelease.CHANNEL_STABLE);
        }
        return DataScopeResolver.visible(visibleChannels, channel, QtSourceRelease.CHANNEL_STABLE);
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
     * <p>继承时已在字典 {@code qt_source_artifact_path} 停用的 path 会被摘掉（见类注释），
     * 因此「新建时只填 meta/play」不会再把单包时代的 chain.json / source-bundle.js 带进来。</p>
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
            entity.setArtifacts(writeJson(mergeArtifacts(readArtifacts(latestRelease(entity.getChannel())),
                    dto.getArtifacts(), Boolean.TRUE.equals(dto.getReplaceArtifacts()))));
            entity.setRollbackTo(dto.getRollbackTo());
            entity.setIsBad(0);
            entity.setIsPublished(0);
            releaseMapper.insert(entity);
            log.info("[QtSource] 新建音源包 release: code={} name={} channel={} platforms={} artifacts={}",
                    code, entity.getSourceVersionName(), entity.getChannel(), entity.getPlatforms(),
                    entity.getArtifacts());
            return toVo(entity, true);
        }
    }

    /**
     * 编辑（platforms / channel / notes / appVersionCodes / artifacts）。
     * <p>
     * platforms 与 channel 允许修改（广播错平台是常见误操作，删除重建代价高）；
     * 移除某平台时同步清理 appVersionCodes 中该平台的准入键（否则该平台会被当成「不限制」，
     * 与「默认全选现存版本」的预期相反）。artifacts 按 path 合并：只传变更项，其余继承；
     * 若 {@code replaceArtifacts=true}，则以本次提交为「当前生效全集」，未提交的 path 会被删除
     * （管理端编辑弹窗回传整集时用它，让弹窗里的「删除」按钮真正生效）。
     * sourceVersionCode / sourceVersionName 不可改（忽略上送值）；已发布的 release 也允许编辑，
     * 生效于客户端下次拉取 manifest 时。
     * </p>
     */
    public void updateRelease(Long id, QtSourceReleaseCreateDto dto) {
        QtSourceRelease exist = requireRelease(id);
        if (dto.getPlatforms() != null) {
            exist.setPlatforms(toPlatformsCsv(validatePlatforms(dto.getPlatforms())));
        }
        if (dto.getChannel() != null) {
            exist.setChannel(normalizeChannel(dto.getChannel()));
        }
        if (dto.getNotes() != null) {
            exist.setNotes(dto.getNotes());
        }
        if (dto.getAppVersionCodes() != null) {
            exist.setAppVersionCodes(writeJson(pruneAppVersionCodes(
                    appVersionCodesOrDefault(dto.getAppVersionCodes()), existingPlatforms(exist))));
        }
        if (dto.getArtifacts() != null) {
            exist.setArtifacts(writeJson(mergeArtifacts(readArtifacts(exist), dto.getArtifacts(),
                    Boolean.TRUE.equals(dto.getReplaceArtifacts()))));
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
     * <p>
     * 两条例外：
     * <ul>
     *   <li>{@code replace=true}：本次提交即全集，上一版未提交的 path 不再继承（删除生效）；</li>
     *   <li>继承时跳过已在字典 {@code qt_source_artifact_path} 停用的 path——废弃产物（单包时代的
     *       chain.json / source-bundle.js）不再一代代传下去。显式提交的 path 不受此限，
     *       以免历史版本无法编辑。字典不可用（查不到或异常）时不做任何过滤，行为与旧版一致。</li>
     * </ul>
     * </p>
     */
    private List<QtSourceArtifactVo> mergeArtifacts(List<QtSourceArtifactVo> previous,
                                                     List<QtSourceArtifactVo> submitted,
                                                     boolean replace) {
        Map<String, QtSourceArtifactVo> merged = new LinkedHashMap<>();
        Set<String> deprecated = deprecatedArtifactPaths();
        if (previous != null && !replace) {
            for (QtSourceArtifactVo a : previous) {
                if (a.getPath() != null && !a.getPath().isBlank() && !deprecated.contains(a.getPath())) {
                    merged.put(a.getPath(), a);
                }
            }
        }
        // replace 时 merged 只用于查旧值（继承 url/version），不承载未提交项
        Map<String, QtSourceArtifactVo> oldByPath = new LinkedHashMap<>();
        if (previous != null) {
            for (QtSourceArtifactVo a : previous) {
                if (a.getPath() != null && !a.getPath().isBlank()) {
                    oldByPath.put(a.getPath(), a);
                }
            }
        }
        if (submitted != null) {
            for (QtSourceArtifactVo s : submitted) {
                if (s.getPath() == null || s.getPath().isBlank()) {
                    throw new QtException("artifacts 条目的 path 不能为空");
                }
                QtSourceArtifactVo old = replace ? oldByPath.get(s.getPath()) : merged.get(s.getPath());
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

    /**
     * 已在字典 {@code qt_source_artifact_path} 停用（status=0）的产物 path 集合。
     * <p>查询失败一律返回空集：产物继承是发布主链路，不能因为字典读取异常而阻塞建单，
     * 最坏结果只是退化成「和旧版一样继续继承」。</p>
     */
    private Set<String> deprecatedArtifactPaths() {
        try {
            List<DictData> disabled = dictDataService.listDisabledByCode(DICT_ARTIFACT_PATH);
            if (disabled == null || disabled.isEmpty()) {
                return Collections.emptySet();
            }
            Set<String> paths = new HashSet<>();
            for (DictData d : disabled) {
                if (d.getDictValue() != null && !d.getDictValue().isBlank()) {
                    paths.add(d.getDictValue().trim());
                }
            }
            return paths;
        } catch (Exception e) {
            log.warn("[QtSource] 读取字典 {} 停用项失败，跳过废弃产物过滤: {}", DICT_ARTIFACT_PATH, e.getMessage());
            return Collections.emptySet();
        }
    }

    private QtSourceRelease requireRelease(Long id) {
        QtSourceRelease exist = releaseMapper.selectById(id);
        if (exist == null) {
            throw new QtException("release 不存在");
        }
        return exist;
    }

    /** 当前 release 的 platforms 列表（用于 appVersionCodes 增量更新时的键清理） */
    private List<Long> existingPlatforms(QtSourceRelease entity) {
        List<Long> platforms = new ArrayList<>();
        if (entity.getPlatforms() != null) {
            for (String part : entity.getPlatforms().split(",")) {
                if (!part.isBlank()) {
                    platforms.add(Long.valueOf(part.trim()));
                }
            }
        }
        return platforms;
    }

    /** 移除不在当前 platforms 中的准入键（否则残留键会被当作该平台「不限制」） */
    private Map<String, List<Long>> pruneAppVersionCodes(Map<String, List<Long>> codes, List<Long> platforms) {
        if (codes == null || codes.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Set<Long> asSet = new HashSet<>(platforms);
        Map<String, List<Long>> pruned = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> e : codes.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim();
            if (key.isEmpty()) {
                continue;
            }
            try {
                if (asSet.contains(Long.valueOf(key))) {
                    pruned.put(key, e.getValue());
                }
            } catch (NumberFormatException ignored) {
                log.warn("[QtSource] appVersionCodes 含非平台键 {}，已忽略", key);
            }
        }
        return pruned;
    }

    private List<Long> validatePlatforms(List<Long> platforms) {
        if (platforms == null || platforms.isEmpty()) {
            throw new QtException("至少选择一个适用平台（1101/1102/1103/1104/1105）");
        }
        for (Long p : platforms) {
            if (!QtSourceRelease.isSupportedPlatform(p)) {
                throw new QtException("平台类型不支持(1101-Android 1102-iOS 1103-Windows 1104-Linux 1105-macOS)");
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
