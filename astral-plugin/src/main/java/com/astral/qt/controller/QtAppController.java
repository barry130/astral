package com.astral.qt.controller;

import cn.hutool.crypto.digest.DigestUtil;
import com.astral.qt.common.QtRestResp;
import com.astral.qt.dto.QtSourceReportDto;
import com.astral.qt.dto.vo.QtGithubAccelVo;
import com.astral.qt.dto.vo.QtSourceManifestVo;
import com.astral.qt.entity.QtAppNotice;
import com.astral.qt.entity.QtAppUpdate;
import com.astral.qt.service.QtAppNoticeService;
import com.astral.qt.service.QtAppService;
import com.astral.qt.service.QtGithubAccelService;
import com.astral.qt.service.QtSourceService;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @Resource
    private QtSourceService sourceService;

    @Resource
    private ObjectMapper objectMapper;

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

    // ==================== 音源包热更新（SOURCE_UPDATE_DESIGN §五） ====================

    /**
     * 音源包 manifest（§2.2）：响应体包 QtRestResp（code=200，data 为 QtSourceManifestVo），
     * 保持免认证。带 ETag/If-None-Match 协商缓存，命中直接 304，客户端零成本结束本轮检查。
     */
    @Operation(summary = "音源包manifest（免认证，QtRestResp包装+ETag/304）")
    @GetMapping("/source/manifest")
    public ResponseEntity<String> sourceManifest(
            @RequestParam("platform") Long platform,
            @RequestParam(value = "appVersionCode", required = false) Long appVersionCode,
            @RequestParam(value = "hostApiVersion", required = false) Long hostApiVersion,
            @RequestParam(value = "channel", required = false, defaultValue = "stable") String channel,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) throws Exception {
        QtSourceManifestVo manifest = sourceService.buildManifest(platform, appVersionCode, hostApiVersion, channel);
        // ETag 必须按「不含 generatedAt」的稳定内容计算：generatedAt 每次请求都变，
        // 直接对响应体做摘要会让 If-None-Match 永远不命中。稳定拷贝按包装后的结构来
        com.fasterxml.jackson.databind.node.ObjectNode data =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.valueToTree(manifest);
        data.remove("generatedAt");
        com.fasterxml.jackson.databind.node.ObjectNode stable = objectMapper.createObjectNode();
        stable.put("code", 200);
        stable.set("data", data);
        String etag = "\"" + DigestUtil.md5Hex(objectMapper.writeValueAsString(stable)) + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(etag).build();
        }
        String body = objectMapper.writeValueAsString(QtRestResp.success(manifest));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .eTag(etag)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** 装载结果上报（免认证）：装机分布统计与坏包发现 */
    @Operation(summary = "音源包装载结果上报（免认证）")
    @PostMapping("/source/report")
    public QtRestResp<Void> reportSource(@RequestBody QtSourceReportDto dto) {
        sourceService.recordReport(dto);
        return QtRestResp.success();
    }
}