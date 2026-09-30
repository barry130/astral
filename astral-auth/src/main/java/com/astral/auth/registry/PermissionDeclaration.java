package com.astral.auth.registry;

import com.astral.auth.security.PermissionChecker;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.constant.PermissionType;
import com.astral.plugin.api.PermissionProvider;
import lombok.Getter;

/**
 * 权限声明（注册器的统一输入模型）。
 *
 * <p>来源有两处，都会被启动时的 {@link PermissionRegistry} 收拢：</p>
 * <ol>
 *   <li><b>注解扫描</b>：控制器方法/类上的 {@code @RequiresPermission}；</li>
 *   <li><b>插件 SPI</b>：插件实现的 {@link PermissionProvider}。</li>
 * </ol>
 */
@Getter
public class PermissionDeclaration {

    /** 权限码（端:域:资源:操作[:范围]） */
    private final String code;

    /** 权限名称（管理端展示），空则用编码兜底 */
    private final String name;

    /** 权限说明，可为空 */
    private final String description;

    /** 权限域，空则取编码首段 */
    private final String domain;

    /** 权限类型（1-3 菜单类 / 4 接口 / 5 数据） */
    private final int type;

    /** 声明来源，用于日志定位（如 {@code @RequiresPermission@UserController#list} 或 {@code plugin:qt}） */
    private final String source;

    public PermissionDeclaration(String code, String name, String description, String domain, int type, String source) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.domain = domain;
        this.type = type;
        this.source = source;
    }

    /** 从注解构建（方法/类级） */
    public static PermissionDeclaration fromAnnotation(RequiresPermission annotation, String source, String code) {
        return new PermissionDeclaration(
                code,
                blankToNull(annotation.name()),
                blankToNull(annotation.description()),
                blankToNull(annotation.domain()),
                annotation.type(),
                source);
    }

    /** 从插件 SPI 构建 */
    public static PermissionDeclaration fromPlugin(String pluginId, PermissionProvider.PermissionDef def) {
        int type = def.getType() == PermissionProvider.TYPE_DATA
                ? PermissionType.DATA : PermissionType.API;
        return new PermissionDeclaration(
                def.getCode(),
                blankToNull(def.getName()),
                blankToNull(def.getDescription()),
                blankToNull(def.getDomain()),
                type,
                "plugin:" + pluginId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 权限域：声明的域优先，否则按编码规范取「端」段之后的第 1 段（见 PermissionChecker.domainOf） */
    public String resolvedDomain() {
        if (domain != null && !domain.isBlank()) {
            return domain;
        }
        return PermissionChecker.domainOf(code);
    }

    /** 权限名称：声明的名称优先，否则用编码 */
    public String resolvedName() {
        return name != null && !name.isBlank() ? name : code;
    }

    /** 唯一键：权限码 */
    public String key() {
        return code;
    }
}
