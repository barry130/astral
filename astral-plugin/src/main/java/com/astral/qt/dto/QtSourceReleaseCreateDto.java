package com.astral.qt.dto;

import com.astral.qt.dto.vo.QtSourceArtifactVo;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 新建 / 编辑音源包发布记录请求体
 * <p>
 * <b>不含 sourceVersionCode / sourceVersionName</b>：这两个字段由后端按规则生成后随响应返回
 * （规则见 {@code QtSourceService#nextVersionCode}），客户端与发布脚本一律不上送。
 * </p>
 * <p>
 * 编辑时 platforms / channel 也会被应用（不再忽略）：移除平台后后端同步清理该平台的准入键。
 * </p>
 */
@Data
public class QtSourceReleaseCreateDto {

    /** 适用平台（1101/1102/1103/1104/1105/1106，可多个：一个包同时服务多平台） */
    private List<Long> platforms;

    /** 按平台准入的应用版本号（可空 = 不限制）：如 {"1103":[102,103],"1101":[304]} */
    private Map<String, List<Long>> appVersionCodes;

    /** 需要的宿主契约版本（可空，默认 1） */
    private Long hostApiVersion;

    /**
     * 发布渠道（可空，默认 stable）：stable 正式（所有用户可收到）/
     * beta 测试（仅拥有 user:qt:source:channel:beta 权限的用户可见，与版本更新渠道同源语义；
     * 权限只决定「可见渠道集合」，正式包版本号更高时所有用户（含有权限者）收到正式包）
     */
    private String channel;

    /** 更新说明 */
    private String notes;

    /**
     * 本次提交的产物条目（可空）。
     * <p>服务端按 path 合并到上一版之上：未提交的 path 自动继承上一版（url 与 version 不变），
     * 提交的 path 若 version 为空则自动取「上一版该 path 的 version + 1」。这就是「只发 chain」的实现方式。</p>
     * <p>继承时，上一版里<b>已在数据字典 qt_source_artifact_path 停用</b>的 path 会被摘掉（不再继承），
     * 避免已废弃的旧单包产物（chain.json / source-bundle.js）无限传递到新版本。</p>
     */
    private List<QtSourceArtifactVo> artifacts;

    /**
     * artifacts 提交语义（可空，缺省 false = 增量合并）。
     * <p>
     * true = 本次 {@link #artifacts} 即「当前生效全集」：上一版有、本次未提交的 path 会被<b>删除</b>。
     * 前端编辑弹窗回传整集时用它，让弹窗里的「删除」按钮真正生效（否则删掉的条目会被继承回来）。
     * false / 缺省 = 按 path 合并（「只发 chain」的增量语义）。
     * </p>
     * <p>注意：{@code replaceArtifacts=true} 且 {@link #artifacts} 为 null 时按「未提交」处理，仍走合并，
     * 避免「先拿号」的建单请求（不送 artifacts）把上一版产物清空。</p>
     */
    private Boolean replaceArtifacts;

    /** 指定回退到的版本号（可空） */
    private Long rollbackTo;
}
