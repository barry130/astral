package com.astral.plugin.api;

import java.util.List;

public interface PluginFrontendExtension {

    String getPluginId();

    List<NavItem> getNavItems();

    class NavItem {
        /**
         * 插件业务页的约定父级：管理端「插件管理」页的路由（对应宿主 {@code sys_menu.path}）。
         * 插件声明的导航项按约定都挂在这下面作为**二级菜单**，不要各写各的字面量。
         *
         * <p>父节点在某个用户的菜单树里不可见时（例如该用户没有 {@code admin:plugin:view}，
         * 看不到「插件管理」本身），前端会把该项**退回顶层展示而不是丢弃**——
         * 所以挂在这里不会让「只有 admin:qt:admin 的管理员」连轻听入口都看不到。</p>
         */
        public static final String PLUGIN_MANAGER_PATH = "/dashboard/plugin";

        /**
         * 声明一个「插件管理」下的二级导航项，等价于
         * {@code new NavItem(label, path, icon, permission, sort).withParent(PLUGIN_MANAGER_PATH)}。
         * 插件业务页优先用这个工厂方法，别自己拼路径。
         */
        public static NavItem pluginPage(String label, String path, String icon, String permission, int sort) {
            return new NavItem(label, path, icon, permission, sort).withParent(PLUGIN_MANAGER_PATH);
        }

        private String label;
        private String path;
        private String icon;
        /**
         * 父级菜单的路由路径（对应宿主 sys_menu 的 path）。
         * null / 空 = 顶层菜单项；非空 = 挂到该 path 对应的菜单节点下作为二级菜单。
         * 前端侧边栏按此字段嵌套（<b>不再无条件把所有插件项堆到 /dashboard/plugin</b>，
         * 也不会有前端硬编码的重复子项）。插件业务页用 {@link #PLUGIN_MANAGER_PATH}。
         */
        private String parentPath;
        /**
         * 访问该导航项所需的权限编码（可空=不做权限过滤）。
         * 由前端按登录用户权限列表过滤，避免「菜单可见但点进去全 403」。
         */
        private String permission;
        private int sort;

        public NavItem() {}

        public NavItem(String label, String path, String icon, int sort) {
            this.label = label;
            this.path = path;
            this.icon = icon;
            this.sort = sort;
        }

        public NavItem(String label, String path, String icon, String permission, int sort) {
            this.label = label;
            this.path = path;
            this.icon = icon;
            this.permission = permission;
            this.sort = sort;
        }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getIcon() { return icon; }
        public void setIcon(String icon) { this.icon = icon; }
        public String getParentPath() { return parentPath; }
        public void setParentPath(String parentPath) { this.parentPath = parentPath; }
        /** 链式声明父级路径（返回自身）：插件一行声明「挂在哪个菜单下」，null = 顶层 */
        public NavItem withParent(String parentPath) {
            this.parentPath = parentPath;
            return this;
        }
        public String getPermission() { return permission; }
        public void setPermission(String permission) { this.permission = permission; }
        public int getSort() { return sort; }
        public void setSort(int sort) { this.sort = sort; }
    }
}