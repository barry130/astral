package com.astral.qt.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import com.astral.qt.QtPlugin;
import com.astral.qt.common.QtRestResp;
import com.astral.qt.dto.QtSourceReleaseCreateDto;
import com.astral.qt.dto.vo.QtSourceReleaseVo;
import com.astral.qt.entity.QtAppUpdate;
import com.astral.qt.entity.QtAppUpdateArtifact;
import com.astral.qt.entity.QtGithubAccel;
import com.astral.qt.service.QtAppUpdateArtifactService;
import com.astral.qt.dto.vo.QtGithubAccelProbeVo;
import com.astral.qt.mapper.QtAppUpdateMapper;
import com.astral.qt.service.QtAppService;
import com.astral.qt.service.QtGithubAccelService;
import com.astral.qt.service.QtSourceService;

import com.astral.qt.mapper.QtUserDakaMapper;
import com.astral.qt.service.QtDakaService;
import com.astral.common.util.PatchValues;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻听插件管理控制器（后台 Admin）
 * <p>挂载在 /api/v1/admin/qt，由宿主 Sa-Token 管理员认证保护；
 * 并统一要求 {@code admin:qt:admin} 权限（类级声明，避免仅登录的管理员即可操作轻听数据）。</p>
 * <p>轻听 App 用户已并入宿主 sys_user（user_type='APP'），此处仅管理 APP 用户。</p>
 */
@Slf4j
@Tag(name = "轻听API-后台管理")
@RestController
@RequestMapping("/api/v1/admin/qt")
@RequiresPermission(value = QtPlugin.PERM_ADMIN, name = "轻听管理", domain = "qt",
        description = "轻听插件后台管理（用户/版本更新/音源包/加速节点）")
public class QtAdminController {

    @Resource
    private UserMapper userMapper;

    @Resource
    private QtUserDakaMapper qtDakaMapper;

    @Resource
    private QtAppUpdateMapper qtUpdateMapper;

    /** App 端版本更新读取走进程内缓存，后台写完必须失效 */
    @Resource
    private QtAppService appService;

    /** 版本产物（一版本多包）读写：App 端挑_product 与后台维护共用 */
    @Resource
    private QtAppUpdateArtifactService artifactService;

    @Resource
    private QtDakaService dakaService;

    @Resource
    private QtGithubAccelService githubAccelService;

    @Resource
    private QtSourceService sourceService;

    @Operation(summary = "概览统计")
    @GetMapping("/overview")
    public QtRestResp<Map<String, Object>> overview() {
        Map<String, Object> map = new HashMap<>();
        map.put("userCount", userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUserType, "APP")));
        map.put("dakaCount", qtDakaMapper.selectCount(null));
        map.put("updateCount", qtUpdateMapper.selectCount(null));
        return QtRestResp.success(map);
    }

    @Operation(summary = "用户分页（仅 App 用户）")
    @GetMapping("/users")
    public QtRestResp<Page<User>> users(@RequestParam(defaultValue = "1") Integer pageNum,
                                         @RequestParam(defaultValue = "10") Integer pageSize,
                                         @RequestParam(required = false) String keyword) {
        Page<User> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUserType, "APP");
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(User::getUsername, keyword).or().like(User::getEmail, keyword));
        }
        wrapper.orderByDesc(User::getCreateTime);
        return QtRestResp.success(userMapper.selectPage(page, wrapper));
    }

    @Operation(summary = "封禁/解封用户")
    @PutMapping("/users/{id}/state")
    public QtRestResp<Void> changeUserState(@PathVariable Long id, @RequestParam Integer state) {
        User user = userMapper.selectById(id);
        if (user == null) {
            return QtRestResp.error("用户不存在");
        }
        user.setStatus(state == null ? 1 : state);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        return QtRestResp.success();
    }

    @Operation(summary = "版本更新列表")
    @GetMapping("/updates")
    public QtRestResp<Page<QtAppUpdate>> updates(@RequestParam(defaultValue = "1") Integer pageNum,
                                                  @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<QtAppUpdate> page = new Page<>(pageNum, pageSize);
        Page<QtAppUpdate> result = qtUpdateMapper.selectPage(page,
                new LambdaQueryWrapper<QtAppUpdate>().orderByDesc(QtAppUpdate::getVersionCode));
        // 列表页要展示「该版本有几个产物」，所以每条都附上产物清单（只读，不挑选）
        return QtRestResp.success((Page<QtAppUpdate>) result.setRecords(
                appService.attachArtifacts(result.getRecords())));
    }

    @Operation(summary = "某版本的产物列表（一版本多包：Windows x64/x86/arm64…）")
    @GetMapping("/updates/{id}/artifacts")
    public QtRestResp<List<QtAppUpdateArtifact>> listUpdateArtifacts(@PathVariable Long id) {
        if (qtUpdateMapper.selectById(id) == null) {
            return QtRestResp.error(300, "版本信息不存在");
        }
        return QtRestResp.success(artifactService.listByUpdateId(id));
    }

    /**
     * 整组保存某版本的产物（全量覆盖：传什么就是什么，空数组 = 清空）。
     * <p>写完顺带把第一条产物回填到主表 download_url / browser_url / md5 / file_size：
     * 主表这四个字段是「旧客户端 + 未上送 arch」的兜底值，不回填会让老版本 app
     * 在改成多产物后拿不到地址。</p>
     */
    @Operation(summary = "保存某版本的产物列表（全量覆盖）")
    @PutMapping("/updates/{id}/artifacts")
    public QtRestResp<List<QtAppUpdateArtifact>> saveUpdateArtifacts(
            @PathVariable Long id, @RequestBody List<QtAppUpdateArtifact> items) {
        QtAppUpdate exist = qtUpdateMapper.selectById(id);
        if (exist == null) {
            return QtRestResp.error(300, "版本信息不存在");
        }
        List<QtAppUpdateArtifact> saved;
        try {
            saved = artifactService.replaceAll(id, items);
        } catch (IllegalArgumentException e) {
            return QtRestResp.error(300, e.getMessage());
        }
        syncLegacyFields(exist, saved.isEmpty() ? null : saved.get(0));
        appService.evictUpdateCache();
        return QtRestResp.success(saved);
    }

    /**
     * 把兜底产物写回主表同名字段，保证不上送 arch 的旧客户端仍能拿到下载地址
     * （产物被清空时不覆盖，保留最后一次留下的地址，避免把已发布版本变成无链接状态）。
     */
    private void syncLegacyFields(QtAppUpdate exist, QtAppUpdateArtifact fallback) {
        if (fallback == null) {
            return;
        }
        qtUpdateMapper.update(null, new LambdaUpdateWrapper<QtAppUpdate>()
                .eq(QtAppUpdate::getId, exist.getId())
                .set(fallback.getDownloadUrl() != null && !fallback.getDownloadUrl().isBlank(),
                        QtAppUpdate::getDownloadUrl, fallback.getDownloadUrl())
                .set(QtAppUpdate::getBrowserUrl, fallback.getBrowserUrl())
                .set(QtAppUpdate::getIsGithub, fallback.getIsGithub() == null ? 0L : fallback.getIsGithub())
                .set(QtAppUpdate::getMd5, fallback.getMd5())
                .set(QtAppUpdate::getFileSize, fallback.getFileSize())
                .set(QtAppUpdate::getUpdateTime, LocalDateTime.now()));
    }

    @Operation(summary = "新增版本更新")
    @PostMapping("/updates")
    public QtRestResp<QtAppUpdate> createUpdate(@RequestBody QtAppUpdate update) {
        // 多产物版本：下载地址由 artifacts 逐条提供，主表可以不填直链（保存后由兜底产物回填）
        List<QtAppUpdateArtifact> artifacts = artifactService.fromVos(update.getArtifacts());
        if (artifacts.isEmpty()) {
            String err = validateUpdateLinks(update);
            if (err != null) {
                return QtRestResp.error(300, err);
            }
        } else {
            String err = artifactService.validate(artifacts);
            if (err != null) {
                return QtRestResp.error(300, err);
            }
        }
        update.setId(null);
        if (update.getType() == null) update.setType(QtAppUpdate.TYPE_ANDROID);
        if (!QtAppUpdate.isSupportedType(update.getType())) {
            return QtRestResp.error(300, "平台类型不支持(1101-Android 1102-iOS 1103-Windows 1104-Linux 1105-macOS 1106-HarmonyOS)");
        }
        if (update.getChannel() == null || update.getChannel().isBlank()) update.setChannel("stable");
        if (update.getIsGithub() == null) update.setIsGithub(0L);
        if (update.getIsForce() == null) update.setIsForce(0);
        if (update.getIsPublished() == null) update.setIsPublished(0);
        update.setCreateTime(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        qtUpdateMapper.insert(update);
        // 表单可以带产物一起提交（Windows 三架构一次性建好）：插入前已校验，这里只负责落库与回填兜底字段
        if (!artifacts.isEmpty()) {
            List<QtAppUpdateArtifact> saved = artifactService.replaceAll(update.getId(), artifacts);
            syncLegacyFields(update, saved.isEmpty() ? null : saved.get(0));
        }
        update.setArtifacts(artifactService.toVos(update.getId()));
        appService.evictUpdateCache();
        return QtRestResp.success(update);
    }

    @Operation(summary = "更新版本信息")
    @PutMapping("/updates/{id}")
    public QtRestResp<Void> updateAppUpdate(@PathVariable Long id, @RequestBody QtAppUpdate update) {
        // 编辑时以库中已有值为兜底校验（表单可能不回传未变更字段）
        QtAppUpdate exist = qtUpdateMapper.selectById(id);
        if (exist == null) {
            return QtRestResp.error("版本信息不存在");
        }
        // null = 本次没提交（保持原值），空串 = 显式清空（写 NULL，列本身可空）
        String downloadUrl = PatchValues.orCurrent(update.getDownloadUrl(), exist.getDownloadUrl());
        String browserUrl = PatchValues.orCurrent(update.getBrowserUrl(), exist.getBrowserUrl());
        Long isGithub = PatchValues.orDefault(update.getIsGithub(), exist.getIsGithub());
        String err = validateUpdateLinks(downloadUrl, browserUrl, isGithub);
        if (err != null) {
            return QtRestResp.error(300, err);
        }
        Long type = PatchValues.orDefault(update.getType(), exist.getType());
        if (!QtAppUpdate.isSupportedType(type)) {
            return QtRestResp.error(300, "平台类型不支持(1101-Android 1102-iOS 1103-Windows 1104-Linux 1105-macOS 1106-HarmonyOS)");
        }
        // 不用 updateById：MP 默认 updateStrategy=NOT_NULL 会跳过 null 字段，
        // 「清空版本说明/更新类型/文件大小/md5」保存后都还是旧值。白名单逐列显式 set。
        // fileSize 用原值写入（含 null）：整表单提交时 null 就是「清空」，文件大小列可空。
        qtUpdateMapper.update(null, new LambdaUpdateWrapper<QtAppUpdate>()
                .eq(QtAppUpdate::getId, id)
                .set(QtAppUpdate::getVersionCode, PatchValues.orDefault(update.getVersionCode(), exist.getVersionCode()))
                .set(QtAppUpdate::getType, type)
                .set(QtAppUpdate::getVersionName, PatchValues.orCurrent(update.getVersionName(), exist.getVersionName()))
                .set(QtAppUpdate::getVersionInfo, PatchValues.orCurrent(update.getVersionInfo(), exist.getVersionInfo()))
                .set(QtAppUpdate::getUpdateType, PatchValues.orCurrent(update.getUpdateType(), exist.getUpdateType()))
                .set(QtAppUpdate::getDownloadUrl, downloadUrl)
                .set(QtAppUpdate::getBrowserUrl, browserUrl)
                .set(QtAppUpdate::getChannel, PatchValues.orDefault(update.getChannel(), exist.getChannel()))
                // download_mode 已废弃，后台不再维护：这里显式清成空串，避免旧客户端读到残留值。
                // 该列是 NOT NULL DEFAULT 'app'，写不了 NULL，updateById(null) 又会被跳过（原实现因此没清掉）
                .set(QtAppUpdate::getDownloadMode, "")
                .set(QtAppUpdate::getIsGithub, isGithub)
                .set(QtAppUpdate::getIsForce, PatchValues.orDefault(update.getIsForce(), exist.getIsForce()))
                .set(QtAppUpdate::getIsPublished, PatchValues.orDefault(update.getIsPublished(), exist.getIsPublished()))
                .set(QtAppUpdate::getFileSize, update.getFileSize())
                .set(QtAppUpdate::getMd5, PatchValues.orCurrent(update.getMd5(), exist.getMd5()))
                .set(QtAppUpdate::getUpdateTime, LocalDateTime.now()));
        appService.evictUpdateCache();
        return QtRestResp.success();
    }

    /**
     * 版本保存服务端校验（UPDATE_DESIGN.md §2.2）：
     * downloadUrl 与 browserUrl 至少填一个；isGithub=1 时 downloadUrl 必填。
     */
    private String validateUpdateLinks(QtAppUpdate update) {
        return validateUpdateLinks(update.getDownloadUrl(), update.getBrowserUrl(), update.getIsGithub());
    }

    private String validateUpdateLinks(String downloadUrl, String browserUrl, Long isGithub) {
        boolean hasDirect = downloadUrl != null && !downloadUrl.isBlank();
        boolean hasBrowser = browserUrl != null && !browserUrl.isBlank();
        if (!hasDirect && !hasBrowser) {
            return "直链下载与浏览器下载至少填一个";
        }
        if (isGithub != null && isGithub == 1L && !hasDirect) {
            return "GitHub 下载必须填写直链下载地址";
        }
        return null;
    }

    // ==================== GitHub 加速节点（UPDATE_DESIGN.md §2.2） ====================

    @Operation(summary = "GitHub加速节点分页列表（直接查库，不走缓存）")
    @GetMapping("/github-accels")
    public QtRestResp<Page<QtGithubAccel>> githubAccels(@RequestParam(defaultValue = "1") Integer pageNum,
                                                        @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<QtGithubAccel> page = new Page<>(pageNum, pageSize);
        return QtRestResp.success(githubAccelService.page(page,
                new LambdaQueryWrapper<QtGithubAccel>().orderByAsc(QtGithubAccel::getSort)));
    }

    @Operation(summary = "新增GitHub加速节点（自动刷新缓存）")
    @PostMapping("/github-accels")
    public QtRestResp<QtGithubAccel> createGithubAccel(@RequestBody QtGithubAccel accel) {
        if (accel.getPrefixUrl() == null || accel.getPrefixUrl().isBlank()) {
            return QtRestResp.error(300, "加速前缀不能为空");
        }
        accel.setPrefixUrl(QtGithubAccelService.normalizePrefix(accel.getPrefixUrl()));
        if (accel.getIsShow() == null) accel.setIsShow(1L);
        if (accel.getSort() == null) accel.setSort(0L);
        accel.setCreateTime(LocalDateTime.now());
        accel.setUpdateTime(LocalDateTime.now());
        githubAccelService.save(accel);
        githubAccelService.refreshCache();
        return QtRestResp.success(accel);
    }

    @Operation(summary = "编辑GitHub加速节点（自动刷新缓存）")
    @PutMapping("/github-accels/{id}")
    public QtRestResp<Void> updateGithubAccel(@PathVariable Long id, @RequestBody QtGithubAccel accel) {
        // prefix_url 是 NOT NULL 列，整表单提交必带；这里不再容忍 null，避免白名单写入撞非空约束
        if (accel.getPrefixUrl() == null || accel.getPrefixUrl().isBlank()) {
            return QtRestResp.error(300, "加速前缀不能为空");
        }
        // 不用 updateById：MP 默认 updateStrategy=NOT_NULL 会跳过 null 字段，
        // 「清空备注」保存后仍是旧值。白名单逐列显式 set，可空列原样写入（含 null）。
        githubAccelService.update(new LambdaUpdateWrapper<QtGithubAccel>()
                .eq(QtGithubAccel::getId, id)
                .set(QtGithubAccel::getName, PatchValues.blankToNull(accel.getName()))
                .set(QtGithubAccel::getPrefixUrl, QtGithubAccelService.normalizePrefix(accel.getPrefixUrl()))
                .set(QtGithubAccel::getIsShow, PatchValues.orDefault(accel.getIsShow(), 1L))
                .set(QtGithubAccel::getSort, PatchValues.orDefault(accel.getSort(), 0L))
                .set(QtGithubAccel::getRemark, PatchValues.blankToNull(accel.getRemark()))
                .set(QtGithubAccel::getUpdateTime, LocalDateTime.now()));
        githubAccelService.refreshCache();
        return QtRestResp.success();
    }

    @Operation(summary = "删除GitHub加速节点（自动刷新缓存）")
    @DeleteMapping("/github-accels/{id}")
    public QtRestResp<Void> deleteGithubAccel(@PathVariable Long id) {
        githubAccelService.removeById(id);
        githubAccelService.refreshCache();
        return QtRestResp.success();
    }

    @Operation(summary = "手动失效GitHub加速缓存（清空+重新加载）")
    @PostMapping("/github-accels/cache/evict")
    public QtRestResp<Void> evictGithubAccelCache() {
        githubAccelService.evictCache();
        return QtRestResp.success();
    }

    @Operation(summary = "手动探活：并发探测所有启用节点（仅展示，不写库）")
    @PostMapping("/github-accels/probe")
    public QtRestResp<List<QtGithubAccelProbeVo>> probeGithubAccels() {
        return QtRestResp.success(githubAccelService.probe());
    }

    @Operation(summary = "删除版本信息")
    @DeleteMapping("/updates/{id}")
    public QtRestResp<Void> deleteUpdate(@PathVariable Long id) {
        qtUpdateMapper.deleteById(id);
        appService.evictUpdateCache();
        return QtRestResp.success();
    }

    // ==================== 音源包热更新（SOURCE_UPDATE_DESIGN §5.3） ====================

    @Operation(summary = "音源包分页列表（按平台/渠道/发布状态筛选，full=1 全量）")
    @GetMapping("/source-releases")
    public QtRestResp<Page<QtSourceReleaseVo>> sourceReleases(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Long platform,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) Boolean published,
            @RequestParam(defaultValue = "0") Integer full) {
        return QtRestResp.success(sourceService.pageReleases(
                pageNum, pageSize, platform, channel, published, full != null && full == 1));
    }

    @Operation(summary = "音源包装机分布统计（版本×平台×结果）")
    @GetMapping("/source-releases/stats")
    public QtRestResp<List<Map<String, Object>>> sourceReleaseStats() {
        return QtRestResp.success(sourceService.installStats());
    }

    @Operation(summary = "新建音源包 release（版本号后端生成，随响应返回）")
    @PostMapping("/source-releases")
    public QtRestResp<QtSourceReleaseVo> createSourceRelease(@RequestBody QtSourceReleaseCreateDto dto) {
        return QtRestResp.success(sourceService.createRelease(dto));
    }

    @Operation(summary = "编辑音源包（platforms/channel/notes/appVersionCodes/artifacts 按 path 合并）")
    @PutMapping("/source-releases/{id}")
    public QtRestResp<Void> updateSourceRelease(@PathVariable Long id,
                                                @RequestBody QtSourceReleaseCreateDto dto) {
        sourceService.updateRelease(id, dto);
        return QtRestResp.success();
    }

    @Operation(summary = "发布音源包")
    @PostMapping("/source-releases/{id}/publish")
    public QtRestResp<Void> publishSourceRelease(@PathVariable Long id) {
        sourceService.publish(id);
        return QtRestResp.success();
    }

    @Operation(summary = "撤回发布（停止投递，已装设备保持现状）")
    @PostMapping("/source-releases/{id}/unpublish")
    public QtRestResp<Void> unpublishSourceRelease(@PathVariable Long id) {
        sourceService.unpublish(id);
        return QtRestResp.success();
    }

    @Operation(summary = "标记坏包（可带 rollbackTo 指定回退版本）")
    @PostMapping("/source-releases/{id}/bad")
    public QtRestResp<Void> markSourceReleaseBad(@PathVariable Long id,
                                                 @RequestParam(required = false) Long rollbackTo) {
        sourceService.markBad(id, rollbackTo);
        return QtRestResp.success();
    }

    @Operation(summary = "删除音源包（仅未发布允许）")
    @DeleteMapping("/source-releases/{id}")
    public QtRestResp<Void> deleteSourceRelease(@PathVariable Long id) {
        sourceService.deleteRelease(id);
        return QtRestResp.success();
    }
}
