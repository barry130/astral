package com.astral.monitor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 统计事件传输对象
 * <p>
 * App 端批量上报的单个事件。字段与 uni_modules/qt-stat 采集插件对齐。
 * </p>
 */
@Data
public class StatEventDTO {

    /** 事件类型：launcher | show | hide | page | error | custom */
    @NotBlank
    @Size(max = 16)
    private String evt;

    /** 事件发生时间（毫秒时间戳） */
    @NotNull
    private Long ts;

    /** 客户端持久化匿名设备ID（UUID） */
    @NotBlank
    @Size(max = 64)
    private String deviceId;

    /** 平台：app-android | app-ios | app-windows | web */
    @NotBlank
    @Size(max = 16)
    private String ut;

    /** App 版本号 */
    @Size(max = 32)
    private String appVersion;

    /** 设备型号 */
    @Size(max = 128)
    private String model;

    /** 操作系统版本 */
    @Size(max = 64)
    private String os;

    /** 页面路由（page 事件必填） */
    @Size(max = 256)
    private String page;

    /** 距上次 show 的毫秒数（hide 事件必填） */
    private Long duration;

    /** 渠道（可空） */
    @Size(max = 64)
    private String ch;

    /** 错误类型：js | network | biz（error 事件必填） */
    @Size(max = 16)
    private String errorType;

    /** 错误信息（error 事件必填） */
    @Size(max = 1024)
    private String message;

    /** 堆栈（error 可选，服务端限制 ≤16KB） */
    private String stack;

    /** 发布标识（预留 Sentry release 对齐） */
    @Size(max = 64)
    private String release;

    /** 任意扩展（JSON对象，服务端限制序列化后 ≤2KB） */
    private Object extra;
}
