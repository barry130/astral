package com.astral.common.constant;

import lombok.Getter;

@Getter
public enum PermissionType {
    MENU(1, "菜单"),
    BUTTON(2, "按钮"),
    API(3, "接口");

    private final int code;
    private final String description;

    PermissionType(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public static PermissionType fromCode(int code) {
        for (PermissionType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown PermissionType code: " + code);
    }
}
