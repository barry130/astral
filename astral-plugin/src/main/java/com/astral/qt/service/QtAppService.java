package com.astral.qt.service;

import com.astral.qt.dto.vo.QtDataVo;
import com.astral.qt.entity.QtAppNotice;
import com.astral.qt.entity.QtAppUpdate;
import com.astral.qt.mapper.QtAppNoticeMapper;
import com.astral.qt.mapper.QtAppUpdateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 轻听 App 公告与版本更新服务
 */
@Slf4j
@Service
public class QtAppService {

    @Resource
    private QtAppNoticeMapper noticeMapper;

    @Resource
    private QtAppUpdateMapper updateMapper;

    /** 获取展示中的公告列表 */
    public QtDataVo<List<QtAppNotice>> getNotice() {
        List<QtAppNotice> list = noticeMapper.selectList(
                new LambdaQueryWrapper<QtAppNotice>()
                        .eq(QtAppNotice::getIsShow, 1)
                        .orderByDesc(QtAppNotice::getCreateTime)
        );
        return new QtDataVo<>(list);
    }

    /**
     * 获取版本更新信息
     *
     * @param type    1101 安卓 / 1102 iOS / 1103 Windows
     * @param version 客户端版本号（如 222 对应 2.2.2）
     * @param tester  当前用户是否为测试人群（拥有 qt_admin / qt_tester 权限，含超管 *:*:*），
     *                由控制器按请求携带的 satoken 判定，客户端不再上送 channel
     *                <p>测试人群：取 beta 与 stable 两个渠道中高于当前版本的最新版，返回版本号更大的那个
     *                （正式版版本号高于测试版时，正常收到正式版而不是版本号更低的测试版）</p>
     *                <p>非测试人群：只发正式版（stable）</p>
     *                <p>双链接直出 downloadUrl/browserUrl/isGithub（UPDATE_DESIGN.md §2.1①），
     *                旧的 && 多链接随机逻辑已随 downloadMode 一并废弃。</p>
     */
    public QtAppUpdate getUpdate(Long type, String version, boolean tester) {
        long versionCode;
        try {
            versionCode = Long.parseLong(version);
        } catch (NumberFormatException e) {
            log.warn("[QtPlugin] 版本号格式错误: {}", version);
            return null;
        }
        if (tester) {
            // 测试人群：同时取 beta 与 stable 的高版本，返回版本号更大的（官版更高时正常收到正式版）
            return pickHigher(
                    findLatest(type, versionCode, "beta"),
                    findLatest(type, versionCode, "stable")
            );
        }
        // 非测试人群：只查 stable（正式版所有用户都能收到）
        return findLatest(type, versionCode, "stable");
    }

    /**
     * 校验当前 APP 版本是否为官方发布版本。
     * <p>按 平台(type) + 版本号(versionCode) + 版本名称(versionName) 精确匹配 qt_app_update，
     * 若无记录则视为非官方版本，返回 null（由调用方报错）。</p>
     * <p><b>注意</b>：本方法<b>不过滤</b> is_published —— 未发布版本仅用于本地版本测试，
     * 也应通过"官方校验"，本地测试版本不被判为"非官方版本"。</p>
     *
     * @param type        1101 安卓 / 1102 iOS / 1103 Windows
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
        List<QtAppUpdate> list = updateMapper.selectList(
                new LambdaQueryWrapper<QtAppUpdate>()
                        .eq(QtAppUpdate::getType, type)
                        .eq(QtAppUpdate::getVersionCode, versionCode)
                        .eq(QtAppUpdate::getVersionName, versionName)
                        .last("LIMIT 1")
        );
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询指定渠道、高于当前版本、<b>已发布(is_published=1)</b> 的最新一条 */
    private QtAppUpdate findLatest(Long type, long versionCode, String channel) {
        return updateMapper.selectOne(
                new LambdaQueryWrapper<QtAppUpdate>()
                        .eq(QtAppUpdate::getType, type)
                        .eq(QtAppUpdate::getChannel, channel)
                        .eq(QtAppUpdate::getIsPublished, 1)
                        .gt(QtAppUpdate::getVersionCode, versionCode)
                        .orderByDesc(QtAppUpdate::getVersionCode)
                        .last("LIMIT 1")
        );
    }

    /** 取两个更新中版本号更大的一个（相同版本号时优先 beta；任一为空则取另一个） */
    private QtAppUpdate pickHigher(QtAppUpdate a, QtAppUpdate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.getVersionCode() >= b.getVersionCode() ? a : b;
    }
}