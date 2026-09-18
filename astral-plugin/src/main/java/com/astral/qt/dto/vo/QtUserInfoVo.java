package com.astral.qt.dto.vo;

import com.astral.dao.entity.User;
import lombok.Data;

import java.util.List;

/** 用户信息 + token（用户已并入宿主 sys_user，统一走 Sa-Token */
@Data
public class QtUserInfoVo {

    private String token;

    /** token 有效期（秒） */
    private Long expiresIn;

    private User user;

    /** 用户角色编码列表 */
    private List<String> roles;

    /** 用户权限编码列表 */
    private List<String> permissions;
}