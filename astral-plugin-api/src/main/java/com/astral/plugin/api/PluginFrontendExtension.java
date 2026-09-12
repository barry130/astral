package com.astral.plugin.api;

import java.util.List;

public interface PluginFrontendExtension {

    String getPluginId();

    List<NavItem> getNavItems();

    class NavItem {
        private String label;
        private String path;
        private String icon;
        private String parentPath;
        private int sort;

        public NavItem() {}

        public NavItem(String label, String path, String icon, int sort) {
            this.label = label;
            this.path = path;
            this.icon = icon;
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
        public int getSort() { return sort; }
        public void setSort(int sort) { this.sort = sort; }
    }
}