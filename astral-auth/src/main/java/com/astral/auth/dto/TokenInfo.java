package com.astral.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Token信息DTO
 * <p>用于展示Sa-Token的Token详细信息，包含关联的用户数据</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenInfo {

    /** Token唯一标识 */
    private String id;

    /** 关联的用户ID */
    private Long userId;

    /** 用户名 */
    private String username;

    /** Token值 */
    private String token;

    /** Token过期时间 */
    private LocalDateTime expireTime;

    /** 登录IP地址 */
    private String loginIp;

    /**
     * 登录设备标识（登录时 SaLoginModel.setDevice 写入，如 ADMIN / APP）。
     * is-share=false 后同账号每端一个 token，靠它区分；改前发行的旧 token 无此记录，为 null。
     */
    private String device;

    /** 状态：1-有效 */
    private Integer status;

    /** Token创建时间 */
    private LocalDateTime createTime;
}
