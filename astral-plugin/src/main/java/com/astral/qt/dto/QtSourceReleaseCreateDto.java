package com.astral.qt.dto;

import com.astral.qt.dto.vo.QtSourceArtifactVo;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 新建音源包发布记录请求体
 * <p>
 * <b>不含 sourceVersionCode / sourceVersionName</b>：这两个字段由后端按规则生成后随响应返回
 * （规则见 {@code QtSourceService#nextVersionCode}），客户端与发布脚本一律不上送。
 * </p>
 */
@Data
public class QtSourceReleaseCreateDto {

    /** 适用平台（1101/1102/1103，可多个：一个包同时服务多平台） */
    private List<Long> platforms;

    /** 按平台准入的应用版本号（可空 = 不限制）：如 {"1103":[102,103],"1101":[304]} */
    private Map<String, List<Long>> appVersionCodes;

    /** 需要的宿主契约版本（可空，默认 1） */
    private Long hostApiVersion;

    /** 发布渠道（可空，默认 stable） */
    private String channel;

    /** 更新说明 */
    private String notes;

    /**
     * 本次提交的产物条目（可空）。
     * <p>服务端按 path 合并到上一版之上：未提交的 path 自动继承上一版（url 与 version 不变），
     * 提交的 path 若 version 为空则自动取「上一版该 path 的 version + 1」。这就是「只发 chain」的实现方式。</p>
     */
    private List<QtSourceArtifactVo> artifacts;

    /** 指定回退到的版本号（可空） */
    private Long rollbackTo;
}
