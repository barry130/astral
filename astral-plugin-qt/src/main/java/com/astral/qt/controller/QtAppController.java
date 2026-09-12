package com.astral.qt.controller;

import com.astral.qt.common.QtRestResp;
import com.astral.qt.dto.vo.QtGithubAccelVo;
import com.astral.qt.entity.QtAppNotice;
import com.astral.qt.entity.QtAppUpdate;
import com.astral.qt.service.QtAppNoticeService;
import com.astral.qt.service.QtAppService;
import com.astral.qt.service.QtGithubAccelService;
import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 轻听 App 公共控制器
 * <p>公告与版本更新为免认证接口。</p>
 */
@Slf4j
@Tag(name = "轻听API-App公共")
@RestController
@RequestMapping("/api/v1/app")
public class QtAppController {

    @Resource
    private QtAppService appService;

    @Resource
    private QtAppNoticeService noticeService;

    @Resource
    private QtGithubAccelService accelService;

    @Operation(summary = "获取生效中的APP公告（按生效时间/版本/登录人群过滤）")
    @GetMapping("/notice")
    public QtRestResp<List<QtAppNotice>> getNotice(
            @RequestParam(value = "version", required = false) String version,
            @RequestHeader(value = "satoken", required = false) String satoken) {
        boolean loggedIn = satoken != null && StpUtil.getLoginIdByToken(satoken) != null;
        return QtRestResp.success(noticeService.listActive(version, loggedIn));
    }

    @Operation(summary = "获取APP更新信息")
    @GetMapping("/update")
    public QtRestResp<QtAppUpdate> getUpdate(@RequestParam("type") Long type,
                                             @RequestParam("version") String version,
                                             @RequestParam(value = "channel", required = false, defaultValue = "stable") String channel) {
        if (!QtAppUpdate.isSupportedType(type)) {
            return QtRestResp.error(320, "暂不支持该类型");
        }
        return QtRestResp.success(appService.getUpdate(type, version, channel));
    }

    @Operation(summary = "获取启用的GitHub加速节点列表（免认证，走后台缓存）")
    @GetMapping("/github/accels")
    public QtRestResp<List<QtGithubAccelVo>> listGithubAccels() {
        return QtRestResp.success(accelService.listEnabled());
    }

    @Operation(summary = "校验当前APP版本是否为官方版本")
    @GetMapping("/version/check")
    public QtRestResp<QtAppUpdate> checkVersion(
            @RequestParam("type") Long type,
            @RequestParam("version") String version,
            @RequestParam("versionName") String versionName) {
        QtAppUpdate official = appService.getOfficialVersion(type, version, versionName);
        if (official == null) {
            return QtRestResp.error(321, "非官方版本");
        }
        return QtRestResp.success(official);
    }
}