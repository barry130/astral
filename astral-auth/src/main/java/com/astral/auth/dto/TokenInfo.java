package com.astral.auth.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token信息DTO
 * <p>用于展示Sa-Token的Token详细信息，包含关联的用户数据</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenInfo {

    /**
     * 会话标识 = token 的 SHA-256 指纹（32 位小写十六进制）。
     * <p>不是明文 token：指纹单向不可反推、不能当凭据使用，但同一 token 恒定，
     * 因此可以直接回传给吊销接口当键（见 {@code TokenService#revokeToken}）。</p>
     */
    private String id;

    /** 关联的用户ID */
    private Long userId;

    /** 用户名 */
    private String username;

    /**
     * 会话标识（与 {@link #id} 同值，即 token 指纹），保留该字段名是为了兼容既有前端契约。
     * <p><b>永远不回传明文 token</b>：在线会话列表只要下发凭据本身，
     * 任何只持「查看」权限的角色都能抄走超管令牌直接接管账号
     * （详见 {@code TokenFingerprint} 类注释）。</p>
     */
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
