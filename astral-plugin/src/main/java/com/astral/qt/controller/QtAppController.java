package com.astral.qt.controller;

import cn.hutool.crypto.digest.DigestUtil;
import com.astral.auth.security.DataScopeResolver;
import com.astral.auth.security.PermissionChecker;
import com.astral.qt.common.QtRestResp;
import com.astral.qt.dto.QtSourceReportDto;
import com.astral.qt.dto.vo.QtGithubAccelVo;
import com.astral.qt.dto.vo.QtSourceManifestVo;
import com.astral.qt.entity.QtAppUpdate;
import com.astral.qt.service.QtAppService;
import com.astral.qt.service.QtGithubAccelService;
import com.astral.qt.service.QtSourceService;
import tools.jackson.databind.ObjectMapper;
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
import java.util.Set;
import java.util.TreeSet;

/**
 * 轻听 App 公共控制器
 * <p>版本更新、音源包、GitHub 加速节点等公共接口免认证（公告请走反馈插件统一通知 /api/v1/app/message/**）。</p>
 * <p><b>权限</b>：本控制器整体落在「App 匿名公共区」（AuthInterceptor 对
 * {@code /api/v1/app/**} 中非 {@code /user/**} 子树一律放行），游客即可访问，
 * 因此<b>不标注</b> {@code @RequiresPermission}（接口可达性无需权限）。
 * 版本更新/音源包的测试渠道属<b>结果级权限</b>，由 {@link DataScopeResolver}
 * 按 token 解析可见渠道集合（{@code user:qt:update:channel:beta} / {@code user:qt:source:channel:beta}），
 * 只决定「能看见哪些渠道」，不是「能不能调用」。</p>
 */
@Slf4j
@Tag(name = "轻听API-App公共")
@RestController
@RequestMapping("/api/v1/app")
public class QtAppController {

    @Resource
    private QtAppService appService;

    @Resource
    private QtGithubAccelService accelService;

    @Resource
    private QtSourceService sourceService;

    @Resource
    private PermissionChecker permissionChecker;

    /** 结果级权限解析器：按 token 解析可见渠道集合（stable 基础 + 授权的 beta） */
    @Resource
    private DataScopeResolver dataScopeResolver;

    @Resource
    private ObjectMapper objectMapper;

    @Operation(summary = "获取APP更新信息（按 user:qt:update:channel 可见集合投放，satoken 可选头识别人群）")
    @GetMapping("/update")
    public QtRestResp<QtAppUpdate> getUpdate(@RequestParam("type") Long type,
                                             @RequestParam("version") String version,
                                             // 架构为可选参数：不上送（旧客户端）也能用，只是拿不到按架构挑选的那一份
                                             @RequestParam(value = "arch", required = false) String arch,
                                             @RequestHeader(value = "satoken", required = false) String satoken) {
        if (!QtAppUpdate.isSupportedType(type)) {
            return QtRestResp.error(320, "暂不支持该类型");
        }
        // 可见集合 = {stable} ∪ 用户被授予的渠道（如 user:qt:update:channel:beta → beta；超管为全部已登记渠道）。
        // 权限只决定「能看见哪些渠道」，最终发哪个版本由业务在集合内按版本号最大者决定：
        // 正式版版本号更高时，持有测试权限的用户依然收到正式版。
        Set<String> channels = dataScopeResolver.resolveChannelsByToken(
                satoken, PermissionChecker.QT_UPDATE_CHANNEL_SCOPE);
        return QtRestResp.success(appService.getUpdate(type, version, channels, arch));
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
            @RequestParam("versionName") String versionName,
            @RequestParam(value = "arch", required = false) String arch) {
        QtAppUpdate official = appService.getOfficialVersion(type, version, versionName, arch);
        if (official == null) {
            return QtRestResp.error(321, "非官方版本");
        }
        return QtRestResp.success(official);
    }

    // ==================== 音源包热更新（SOURCE_UPDATE_DESIGN §五） ====================

    /**
     * 音源包 manifest（§2.2）：响应体包 QtRestResp（code=200，data 为 QtSourceManifestVo），
     * 保持免认证。带 ETag/If-None-Match 协商缓存，命中直接 304，客户端零成本结束本轮检查。
     * <p>
     * 人群语义（复用 channel 字段）：可见渠道集合 = {stable} ∪ 用户被授予的渠道
     * （user:qt:source:channel:beta → beta；超管为全部已登记渠道）。客户端不上送 channel，
     * 识别方式：请求头带 satoken 时按 token 反查登录用户并解析其可见渠道集合，
     * 无 token / token 失效一律只可见 stable。ETag 计算混入可见集合，避免同一客户端
     * 登录态变化后拿到错误命中的 304 缓存。
     * </p>
     */
    @Operation(summary = "音源包manifest（免认证+ETag/304，按 user:qt:source:channel 可见集合投放）")
    @GetMapping("/source/manifest")
    public ResponseEntity<String> sourceManifest(
            @RequestParam("platform") Long platform,
            @RequestParam(value = "appVersionCode", required = false) Long appVersionCode,
            @RequestParam(value = "hostApiVersion", required = false) Long hostApiVersion,
            @RequestHeader(value = "satoken", required = false) String satoken,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) throws Exception {
        // 可见渠道集合：无权限者只有 stable，有 user:qt:source:channel:beta 者额外可见 beta
        Set<String> channels = dataScopeResolver.resolveChannelsByToken(
                satoken, PermissionChecker.QT_SOURCE_CHANNEL_SCOPE);
        QtSourceManifestVo manifest = sourceService.buildManifest(platform, appVersionCode, hostApiVersion, channels);
        // ETag 必须按「不含 generatedAt」的稳定内容计算：generatedAt 每次请求都变，
        // 直接对响应体做摘要会让 If-None-Match 永远不命中。稳定拷贝按包装后的结构来
        tools.jackson.databind.node.ObjectNode data =
                (tools.jackson.databind.node.ObjectNode) objectMapper.valueToTree(manifest);
        data.remove("generatedAt");
        tools.jackson.databind.node.ObjectNode stable = objectMapper.createObjectNode();
        stable.put("code", 200);
        stable.set("data", data);
        stable.put("channels", String.join(",", new TreeSet<>(channels)));
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