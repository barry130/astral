package com.astral.qt.service;

import com.astral.qt.dto.vo.QtAppUpdateArtifactVo;
import com.astral.qt.entity.QtAppUpdateArtifact;
import com.astral.qt.mapper.QtAppUpdateArtifactMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 版本更新产物服务（UPDATE_ARTIFACT_DESIGN）
 * <p>一个版本可以有多个安装包（Windows 三架构 x64 / x86 / arm64、macOS x86_64 / aarch64、
 * Linux deb 与 AppImage…）。产物明细存 qt_app_update_artifact，本服务负责三件事：</p>
 * <ol>
 *   <li>按 updateId 查全量产物（带进程内缓存，后台写完后主动失效）；</li>
 *   <li>按「平台 + 架构」挑出最匹配的一条（见 {@link #select} 的优先级说明）；</li>
 *   <li>整组替换某个版本的产物列表。</li>
 * </ol>
 * <p><b>为什么不做消息做成 batched all-product queries</b>：版本更新是低基数量（每个平台几十行），
 * 命中 AR 取击中行即可，没必要引入批量接口增加复杂度。</p>
 */
@Slf4j
@Service
public class QtAppUpdateArtifactService {

    @Resource
    private QtAppUpdateArtifactMapper artifactMapper;

    /**
     * 产物缓存（key = updateId，TTL 60s）。
     * <p>与 QtAppService#updateCache 同一套思路：版本更新读是全量高频，
     * 写只在后台，且写完一定调 {@link #evict} / {@link #evictAll}。</p>
     */
    private final Cache<Long, List<QtAppUpdateArtifact>> artifactCache = Caffeine.newBuilder()
            .maximumSize(512)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    /** 某个版本下的全部产物（按 sort 升序、id 升序，保证同一档位内取到的第一条稳定） */
    public List<QtAppUpdateArtifact> listByUpdateId(Long updateId) {
        if (updateId == null) {
            return List.of();
        }
        return artifactCache.get(updateId, id -> {
            List<QtAppUpdateArtifact> list = artifactMapper.selectList(
                    new LambdaQueryWrapper<QtAppUpdateArtifact>()
                            .eq(QtAppUpdateArtifact::getUpdateId, id)
                            .orderByAsc(QtAppUpdateArtifact::getSort)
                            .orderByAsc(QtAppUpdateArtifact::getId));
            return list == null ? new ArrayList<>() : list;
        });
    }

    /**
     * 按「平台 + 架构」挑出最匹配的一条产物；没有命中返回 null（调用方保留主表兜底值）。
     * <p>platform / arch 两列都允许为空 =「不限」。命中优先级：</p>
     * <ol>
     *   <li>platform 精确 + arch 精确（Windows x64、Windows x86…各自一条）</li>
     *   <li>platform 精确 + arch 为空（该平台不分架构，如安卓 APK）</li>
     *   <li>platform 为空 + arch 精确（跨平台的同架构包）</li>
     *   <li>platform 为空 + arch 为空（通用兜底包）</li>
     * </ol>
     * 同一档位内按 sort 升序取第一条；arch 比对大小写不敏感（x64 / X64 等价）。
     *
     * @param updateId 版本 ID
     * @param platform 客户端平台（1101-1106），null 视为「不限」
     * @param arch     客户端架构（x64/x86/arm64…），null 或空白视为「不限」
     */
    public QtAppUpdateArtifact select(Long updateId, Long platform, String arch) {
        List<QtAppUpdateArtifact> list = listByUpdateId(updateId);
        if (list.isEmpty()) {
            return null;
        }
        String want = normalizeArch(arch);
        // 四档优先级由 score 表达：越大越优先，同档内靠 sort/id 的排序稳定取第一条
        QtAppUpdateArtifact best = null;
        int bestScore = -1;
        for (QtAppUpdateArtifact a : list) {
            int score = matchScore(a, platform, want);
            if (score < 0) {
                continue;
            }
            if (score > bestScore) {
                bestScore = score;
                best = a;
            }
        }
        return best;
    }

    /**
     * 匹配打分：-1 = 不匹配；0 = 通用兜底（双不限）；1 = 只命中 arch；2 = 只命中 platform；3 = 双精确。
     */
    private int matchScore(QtAppUpdateArtifact a, Long platform, String wantArch) {
        boolean hitPlatform = platform != null && platform.equals(a.getPlatform());
        boolean hitArch = wantArch != null && wantArch.equals(normalizeArch(a.getArch()));
        boolean anyPlatform = a.getPlatform() == null;
        boolean anyArch = normalizeArch(a.getArch()) == null;
        if (!hitPlatform && !anyPlatform) {
            return -1;
        }
        if (!hitArch && !anyArch) {
            return -1;
        }
        if (hitPlatform && hitArch) {
            return 3;
        }
        if (hitPlatform) {
            return 2;
        }
        if (hitArch) {
            return 1;
        }
        return 0;
    }

    /** 架构归一化：去空白转小写，空串视为「不限」返回 null */
    public String normalizeArch(String arch) {
        if (arch == null) {
            return null;
        }
        String v = arch.trim().toLowerCase();
        return v.isEmpty() ? null : v;
    }

    /** 产物 VO 清单（App 端响应体用） */
    public List<QtAppUpdateArtifactVo> toVos(Long updateId) {
        List<QtAppUpdateArtifactVo> vos = new ArrayList<>();
        for (QtAppUpdateArtifact a : listByUpdateId(updateId)) {
            vos.add(a.toVo());
        }
        return vos;
    }

    /**
     * VO → 实体的转换（后台新增版本时可以连产物一起提交）。
     * <p>id / updateId / sort 一律忽略：前者靠全局序列自动取号，
     * 后者由 {@link #replaceAll} 按路径参数与数组顺序重新指派。</p>
     */
    public List<QtAppUpdateArtifact> fromVos(List<QtAppUpdateArtifactVo> vos) {
        List<QtAppUpdateArtifact> items = new ArrayList<>();
        if (vos == null || vos.isEmpty()) {
            return items;
        }
        for (QtAppUpdateArtifactVo vo : vos) {
            if (vo == null) {
                continue;
            }
            QtAppUpdateArtifact item = new QtAppUpdateArtifact();
            item.setPlatform(vo.getPlatform());
            item.setArch(normalizeArch(vo.getArch()));
            item.setDownloadUrl(vo.getDownloadUrl());
            item.setBrowserUrl(vo.getBrowserUrl());
            item.setIsGithub(vo.getIsGithub());
            item.setFileSize(vo.getFileSize());
            item.setMd5(vo.getMd5());
            items.add(item);
        }
        return items;
    }

    /** 失效某个版本的产物缓存（后台写接口调用） */
    public void evict(Long updateId) {
        if (updateId != null) {
            artifactCache.invalidate(updateId);
        }
    }

    /** 失效全部产物缓存 */
    public void evictAll() {
        artifactCache.invalidateAll();
    }

    /**
     * 校验产物列表合法性：返回错误信息，合法返回 null。
     * <p>与 {@link #replaceAll} 共用规则（二者必须一致，否则会出现「能保存但下游拿不到」）。
     * 由 controller 在写库前调用，避免「插入主表成功、产物校验失败」的半成品状态。</p>
     */
    public String validate(List<QtAppUpdateArtifact> items) {
        if (items == null) {
            return null;
        }
        for (QtAppUpdateArtifact item : items) {
            if (item == null) {
                continue;
            }
            boolean hasDirect = item.getDownloadUrl() != null && !item.getDownloadUrl().isBlank();
            boolean hasBrowser = item.getBrowserUrl() != null && !item.getBrowserUrl().isBlank();
            if (!hasDirect && !hasBrowser) {
                return "每个产物都要填写直链或浏览器下载地址之一";
            }
            if (Boolean.TRUE.equals(isGithubFlag(item)) && !hasDirect) {
                return "GitHub 产物必须填写直链下载地址";
            }
        }
        return null;
    }

    /**
     * 整组替换某个版本的产物列表（全量覆盖语义，与音源包 artifacts 一致）。
     * <p>先删后插：产物是「当前生效全集」，不存在增量补丁语义，
     * 按行 update 反而要处理「谁该被删」的判定。空数组 = 清空产物
     * （此时客户端回退主表 download_url / browser_url）。</p>
     *
     * @param updateId 版本 ID
     * @param items    产物（id 一律忽略，updateId 以路径参数为准）
     * @return 落库后的产物列表
     */
    @Transactional(rollbackFor = Exception.class)
    public List<QtAppUpdateArtifact> replaceAll(Long updateId, List<QtAppUpdateArtifact> items) {
        String err = validate(items);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        artifactMapper.delete(new LambdaQueryWrapper<QtAppUpdateArtifact>()
                .eq(QtAppUpdateArtifact::getUpdateId, updateId));
        List<QtAppUpdateArtifact> saved = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            evict(updateId);
            return saved;
        }
        long sort = 0;
        for (QtAppUpdateArtifact item : items) {
            if (item == null) {
                continue;
            }
            QtAppUpdateArtifact entity = new QtAppUpdateArtifact();
            entity.setId(null);
            entity.setUpdateId(updateId);
            entity.setPlatform(item.getPlatform());
            entity.setArch(normalizeArch(item.getArch()));
            entity.setDownloadUrl(item.getDownloadUrl());
            entity.setBrowserUrl(item.getBrowserUrl());
            entity.setIsGithub(item.getIsGithub() == null ? 0L : item.getIsGithub());
            entity.setFileSize(item.getFileSize());
            entity.setMd5(item.getMd5());
            entity.setSort(sort++);
            artifactMapper.insert(entity);
            saved.add(entity);
        }
        evict(updateId);
        return saved;
    }

    private Boolean isGithubFlag(QtAppUpdateArtifact item) {
        Long v = item.getIsGithub();
        return v != null && v == 1L;
    }

    /**
     * 取该版本的「兜底产物」：sort 最小的一条。
     * <p>后台保存时用它回填主表 download_url / browser_url / md5 / file_size，
     * 让不上送 arch 的旧客户端（qt-uniappx 已发布包、旧 qt-pc）仍能拿到一个能下的地址。</p>
     */
    public QtAppUpdateArtifact firstArtifact(Long updateId) {
        return listByUpdateId(updateId).stream()
                .min(Comparator.comparing(a -> a.getSort() == null ? 0L : a.getSort()))
                .orElse(null);
    }
}
