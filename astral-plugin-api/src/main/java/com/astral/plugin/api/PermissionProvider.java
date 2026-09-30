package com.astral.plugin.api;

import java.util.Collections;
import java.util.List;

/**
 * 插件权限声明 SPI。
 *
 * <p>插件（宿主内置业务插件与未来外部插件）通过实现本接口，把自身需要的权限编码
 * 声明为代码的一部分，由宿主启动时的权限注册器（astral-auth 的 PermissionRegistry）
 * 自动登记到 {@code sys_permission}，不再依赖手工 Flyway INSERT 或后台点选，
 * 权限树因此永远与实际代码一致。</p>
 *
 * <p><b>权限码规范</b>（宿主强制，见 AGENTS.md「权限码规范」）：</p>
 * <pre>
 * 域:资源:操作[:范围]
 * </pre>
 * <ul>
 *   <li>全小写，段间用冒号分隔；域取插件/模块名（qt、storage、feedback、system…）；</li>
 *   <li>操作段用动词或语义名（view/edit/publish）；</li>
 *   <li><b>范围段</b>（可选）用于「结果级权限」：同一接口对不同人群返回不同结果，
 *       例如 {@code user:qt:update:channel:beta} 表示「有资格接收 beta 渠道的版本更新」。
 *       范围权限是<b>可见性资格</b>，不是「强制返回该值」——业务侧先解析出可见值集合，
 *       再在集合内按业务规则选取（正式版版本号更高时，持有该权限的用户依然收到正式版）。</li>
 * </ul>
 *
 * <p>插件声明的权限会自动获得 {@code domain}（取权限码首段），管理端按域分组展示。</p>
 *
 * @see PermissionDef
 */
public interface PermissionProvider {

    /** 类型：接口权限——控制某个/某类后端接口是否可达 */
    int TYPE_API = 4;

    /** 类型：数据权限——控制同一接口的结果可见范围（结果级权限） */
    int TYPE_DATA = 5;

    /**
     * 声明本插件需要的权限。
     *
     * @return 权限定义列表，无权限需求返回空列表
     */
    default List<PermissionDef> getPermissions() {
        return Collections.emptyList();
    }

    /**
     * 权限定义。
     *
     * <p>与插件导航扩展（{@link PluginFrontendExtension.NavItem}）保持同样的自包含风格：
     * astral-plugin-api 不依赖任何其他模块，插件实现方无需额外引入依赖。</p>
     */
    class PermissionDef {

        /** 权限编码，如 {@code user:qt:update:channel:beta} */
        private String code;

        /** 权限名称（管理端展示），为空时回退为编码本身 */
        private String name;

        /** 权限域，为空时回退为编码首段 */
        private String domain;

        /** 类型，取 {@link PermissionProvider#TYPE_API} / {@link PermissionProvider#TYPE_DATA} */
        private int type = TYPE_API;

        /** 说明文案（管理端 tooltip），可为空 */
        private String description;

        public PermissionDef() {}

        public PermissionDef(String code, String name, int type) {
            this.code = code;
            this.name = name;
            this.type = type;
        }

        public PermissionDef(String code, String name, String domain, int type, String description) {
            this.code = code;
            this.name = name;
            this.domain = domain;
            this.type = type;
            this.description = description;
        }

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }

        public int getType() { return type; }
        public void setType(int type) { this.type = type; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
