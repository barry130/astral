package com.astral.qt.controller;

import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import com.astral.qt.common.QtRestResp;
import com.astral.qt.dto.QtSourceReleaseCreateDto;
import com.astral.qt.dto.vo.QtSourceReleaseVo;
import com.astral.qt.entity.QtAppNotice;
import com.astral.qt.entity.QtAppUpdate;
import com.astral.qt.entity.QtGithubAccel;
import com.astral.qt.dto.vo.QtGithubAccelProbeVo;
import com.astral.qt.mapper.QtAppNoticeMapper;
import com.astral.qt.mapper.QtAppUpdateMapper;
import com.astral.qt.service.QtGithubAccelService;
import com.astral.qt.service.QtSourceService;

import com.astral.qt.mapper.QtUserDakaMapper;
import com.astral.qt.service.QtDakaService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 * <p>挂载在 /api/v1/admin/qt，由宿主 Sa-Token 管理员认证保护。</p>
 * <p>轻听 App 用户已并入宿主 sys_user（user_type='APP'），此处仅管理 APP 用户。</p>
 */
@Slf4j
@Tag(name = "轻听API-后台管理")
@RestController
@RequestMapping("/api/v1/admin/qt")
public class QtAdminController {

    @Resource
    private UserMapper userMapper;

    @Resource
    private QtUserDakaMapper qtDakaMapper;

    @Resource
    private QtAppNoticeMapper qtNoticeMapper;

    @Resource
    private QtAppUpdateMapper qtUpdateMapper;

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
        map.put("noticeCount", qtNoticeMapper.selectCount(null));
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

    @Operation(summary = "公告列表")
    @GetMapping("/notices")
    public QtRestResp<Page<QtAppNotice>> notices(@RequestParam(defaultValue = "1") Integer pageNum,
                                                  @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<QtAppNotice> page = new Page<>(pageNum, pageSize);
        return QtRestResp.success(qtNoticeMapper.selectPage(page,
                new LambdaQueryWrapper<QtAppNotice>().orderByDesc(QtAppNotice::getCreateTime)));
    }

    @Operation(summary = "新增公告")
    @PostMapping("/notices")
    public QtRestResp<QtAppNotice> createNotice(@RequestBody QtAppNotice notice) {
        if (notice.getIsShow() == null) notice.setIsShow(1L);
        if (notice.getType() == null) notice.setType(0L);
        if (notice.getIsTop() == null) notice.setIsTop(0L);
        if (notice.getDialogClosable() == null) notice.setDialogClosable(1L);
        if (notice.getFirstLoginOnly() == null) notice.setFirstLoginOnly(0L);
        if (notice.getMarquee() == null) notice.setMarquee(0L);
        if (notice.getAudience() == null || notice.getAudience().isBlank()) notice.setAudience("ALL");
        notice.setCreateTime(LocalDateTime.now());
        notice.setUpdateTime(LocalDateTime.now());
        qtNoticeMapper.insert(notice);
        return QtRestResp.success(notice);
    }

    @Operation(summary = "更新公告")
    @PutMapping("/notices/{id}")
    public QtRestResp<Void> updateNotice(@PathVariable Long id, @RequestBody QtAppNotice notice) {
        notice.setId(id);
        notice.setUpdateTime(LocalDateTime.now());
        qtNoticeMapper.updateById(notice);
        return QtRestResp.success();
    }

    @Operation(summary = "删除公告")
    @DeleteMapping("/notices/{id}")
    public QtRestResp<Void> deleteNotice(@PathVariable Long id) {
        qtNoticeMapper.deleteById(id);
        return QtRestResp.success();
    }

    @Operation(summary = "版本更新列表")
    @GetMapping("/updates")
    public QtRestResp<Page<QtAppUpdate>> updates(@RequestParam(defaultValue = "1") Integer pageNum,
                                                  @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<QtAppUpdate> page = new Page<>(pageNum, pageSize);
        return QtRestResp.success(qtUpdateMapper.selectPage(page,
                new LambdaQueryWrapper<QtAppUpdate>().orderByDesc(QtAppUpdate::getVersionCode)));
    }

    @Operation(summary = "新增版本更新")
    @PostMapping("/updates")
    public QtRestResp<QtAppUpdate> createUpdate(@RequestBody QtAppUpdate update) {
        String err = validateUpdateLinks(update);
        if (err != null) {
            return QtRestResp.error(300, err);
        }
        update.setId(null);
        if (update.getType() == null) update.setType(QtAppUpdate.TYPE_ANDROID);
        if (!QtAppUpdate.isSupportedType(update.getType())) {
            return QtRestResp.error(300, "平台类型不支持(1101-Android 1102-iOS 1103-Windows)");
        }
        if (update.getChannel() == null || update.getChannel().isBlank()) update.setChannel("stable");
        if (update.getIsGithub() == null) update.setIsGithub(0L);
        if (update.getIsForce() == null) update.setIsForce(0);
        if (update.getIsPublished() == null) update.setIsPublished(0);
        update.setCreateTime(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        qtUpdateMapper.insert(update);
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
        if (update.getDownloadUrl() == null) update.setDownloadUrl(exist.getDownloadUrl());
        if (update.getBrowserUrl() == null) update.setBrowserUrl(exist.getBrowserUrl());
        if (update.getIsGithub() == null) update.setIsGithub(exist.getIsGithub());
        String err = validateUpdateLinks(update);
        if (err != null) {
            return QtRestResp.error(300, err);
        }
        // downloadMode 已废弃，后台不再维护：显式清空传值，避免旧客户端残留
        update.setDownloadMode(null);
        update.setId(id);
        if (update.getType() == null) update.setType(exist.getType());
        if (!QtAppUpdate.isSupportedType(update.getType())) {
            return QtRestResp.error(300, "平台类型不支持(1101-Android 1102-iOS 1103-Windows)");
        }
        update.setUpdateTime(LocalDateTime.now());
        qtUpdateMapper.updateById(update);
        return QtRestResp.success();
    }

    /**
     * 版本保存服务端校验（UPDATE_DESIGN.md §2.2）：
     * downloadUrl 与 browserUrl 至少填一个；isGithub=1 时 downloadUrl 必填。
     */
    private String validateUpdateLinks(QtAppUpdate update) {
        boolean hasDirect = update.getDownloadUrl() != null && !update.getDownloadUrl().isBlank();
        boolean hasBrowser = update.getBrowserUrl() != null && !update.getBrowserUrl().isBlank();
        if (!hasDirect && !hasBrowser) {
            return "直链下载与浏览器下载至少填一个";
        }
        Long github = update.getIsGithub();
        if (github != null && github == 1L && !hasDirect) {
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
        if (accel.getPrefixUrl() != null && accel.getPrefixUrl().isBlank()) {
            return QtRestResp.error(300, "加速前缀不能为空");
        }
        if (accel.getPrefixUrl() != null) {
            accel.setPrefixUrl(QtGithubAccelService.normalizePrefix(accel.getPrefixUrl()));
        }
        accel.setId(id);
        accel.setUpdateTime(LocalDateTime.now());
        githubAccelService.updateById(accel);
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

    @Operation(summary = "编辑音源包（notes/appVersionCodes/artifacts 按 path 合并）")
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
