'use client';

import { useEffect, useState, useRef, useMemo, ReactNode } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { Layout, Menu, Button, Spin, Avatar, Dropdown, Tabs, Breadcrumb, Input } from 'antd';
import {
  DashboardOutlined,
  ToolOutlined,
  ApiOutlined,
  LogoutOutlined,
  FileTextOutlined,
  UserOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MenuOutlined,
  CloseOutlined,
  ReloadOutlined,
  MoreOutlined,
  CloseCircleOutlined,
  SettingOutlined,
  TeamOutlined,
  BookOutlined,
  SafetyOutlined,
  KeyOutlined,
  TableOutlined,
  BarChartOutlined,
  BlockOutlined,
  CustomerServiceOutlined,
  MailOutlined,
  SafetyCertificateOutlined,
  LockOutlined,
  PieChartOutlined,
  InboxOutlined,
  SearchOutlined,
  SunOutlined,
  MoonOutlined,
} from '@ant-design/icons';
import { useAuth } from '@/context/AuthContext';
import { useTheme } from '../providers';
import { getNavExtensions } from '@/api/plugin';
import { menuApi, SysMenu } from '@/api/menu';
import type { NavExtension } from '@/api/plugin';
import { initStatTracker, trackPage } from '@/lib/statTracker';
import NoticeBell from '@/components/NoticeBell';

const { Header, Sider, Content } = Layout;

/** 分组节点 key 前缀：分组本身不是路由，点击不应导航 */
const GROUP_KEY_PREFIX = '/dashboard/group/';

/** 图标名称到组件的映射（后端菜单表 / 插件导航扩展都以字符串存图标名） */
const iconMap: Record<string, React.ReactNode> = {
  DashboardOutlined: <DashboardOutlined />,
  ToolOutlined: <ToolOutlined />,
  ApiOutlined: <ApiOutlined />,
  FileTextOutlined: <FileTextOutlined />,
  SettingOutlined: <SettingOutlined />,
  TeamOutlined: <TeamOutlined />,
  BookOutlined: <BookOutlined />,
  SafetyOutlined: <SafetyOutlined />,
  SafetyCertificateOutlined: <SafetyCertificateOutlined />,
  LockOutlined: <LockOutlined />,
  KeyOutlined: <KeyOutlined />,
  TableOutlined: <TableOutlined />,
  BarChartOutlined: <BarChartOutlined />,
  PieChartOutlined: <PieChartOutlined />,
  BlockOutlined: <BlockOutlined />,
  MailOutlined: <MailOutlined />,
  InboxOutlined: <InboxOutlined />,
  MenuOutlined: <MenuOutlined />,
  CustomerServiceOutlined: <CustomerServiceOutlined />,
  SearchOutlined: <SearchOutlined />,
  SunOutlined: <SunOutlined />,
  MoonOutlined: <MoonOutlined />,
  AppstoreOutlined: <BlockOutlined />,
  AppstoreAddOutlined: <BlockOutlined />,
};

/**
 * 按路由路径覆盖图标的兜底表。
 * 后端 sys_menu 把语义相近的页面登记成同一图标（如「角色权限」和「权限管理」都是 SafetyOutlined），
 * 菜单里两个入口长得完全一样会破坏可辨识性。前端无法区分同名图标属于哪个页面，
 * 只能按路径覆盖。彻底修复需改后端 sys_menu.icon 数据。
 */
const PATH_ICON_OVERRIDE: Record<string, React.ReactNode> = {
  '/dashboard/system/role': <SafetyCertificateOutlined />,
  '/dashboard/system/permission': <LockOutlined />,
  '/dashboard/system/mail/log': <PieChartOutlined />,
};

/** 解析菜单图标：路径覆盖 > 图标名映射 > 兜底 */
const iconFor = (
  path: string | undefined,
  iconName: string | undefined,
  fallback: React.ReactNode = null,
): React.ReactNode => {
  if (path && PATH_ICON_OVERRIDE[path]) return PATH_ICON_OVERRIDE[path];
  if (iconName && iconMap[iconName]) return iconMap[iconName];
  return fallback;
};

/** 标签页项接口 */
interface TabItem {
  /** 标签页唯一标识（路由路径） */
  key: string;
  /** 标签页显示名称 */
  label: string;
  /** 标签页图标 */
  icon: React.ReactNode;
}

/** 菜单分组标识（后端菜单树不可用时的兜底数据源使用） */
type MenuGroup = 'overview' | 'system' | 'ops';

/**
 * 侧边栏菜单配置：定义所有可访问的页面及其图标、名称、所属业务域
 * 约束：同一图标不允许在菜单里重复出现——语义相近的页面必须配不同图标，
 * 否则用户在菜单里只能靠位置而不是语义区分入口
 */
const menuConfig: {
  key: string;
  icon: React.ReactNode;
  label: string;
  permission?: string;
  group: MenuGroup;
}[] = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '仪表盘', group: 'overview' },
  { key: '/dashboard/statistics', icon: <BarChartOutlined />, label: '数据统计', permission: 'statistics:view', group: 'overview' },
  { key: '/dashboard/system/user', icon: <TeamOutlined />, label: '用户管理', permission: 'system:user:view', group: 'system' },
  { key: '/dashboard/system/role', icon: <SafetyCertificateOutlined />, label: '角色权限', permission: 'system:role:view', group: 'system' },
  { key: '/dashboard/system/permission', icon: <LockOutlined />, label: '权限管理', permission: 'system:permission:view', group: 'system' },
  { key: '/dashboard/system/dict', icon: <BookOutlined />, label: '数据字典', permission: 'system:dict:view', group: 'system' },
  { key: '/dashboard/system/config', icon: <SettingOutlined />, label: '系统配置', permission: 'system:config:view', group: 'system' },
  { key: '/dashboard/system/token', icon: <KeyOutlined />, label: 'Token管理', permission: 'system:token:view', group: 'system' },
  { key: '/dashboard/system/table-schema', icon: <TableOutlined />, label: '表结构管理', permission: 'system:schema:view', group: 'system' },
  { key: '/dashboard/system/mail', icon: <MailOutlined />, label: '邮箱管理', permission: 'system:mail:view', group: 'system' },
  { key: '/dashboard/system/mail/log', icon: <PieChartOutlined />, label: '邮箱统计', permission: 'system:mail:statistics:view', group: 'system' },
  { key: '/dashboard/log', icon: <FileTextOutlined />, label: '日志管理', permission: 'log:view', group: 'ops' },
  { key: '/dashboard/plugin', icon: <BlockOutlined />, label: '插件管理', permission: 'plugin:view', group: 'ops' },
];

/** 分组元信息：分组 key（非路由）、图标、名称、展示顺序 */
const MENU_GROUPS: Array<{ group: MenuGroup; key: string; icon: React.ReactNode; label: string }> = [
  { group: 'overview', key: `${GROUP_KEY_PREFIX}overview`, icon: <DashboardOutlined />, label: '概览' },
  { group: 'system', key: `${GROUP_KEY_PREFIX}system`, icon: <SettingOutlined />, label: '系统管理' },
  { group: 'ops', key: `${GROUP_KEY_PREFIX}ops`, icon: <FileTextOutlined />, label: '运维' },
];

/**
 * 仪表盘布局组件
 * 包含侧边栏菜单、顶部导航栏、标签页栏和内容区域
 */
export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  /** 从认证上下文获取登录状态和用户信息 */
  const { isLogin, logout, loading, user } = useAuth();
  /** 明暗主题：切换后写入 localStorage 并同步到 <html data-theme> */
  const { mode: themeMode, toggle: toggleTheme } = useTheme();
  const router = useRouter();
  const pathname = usePathname();
  const [collapsed, setCollapsed] = useState(false);
  /** 菜单搜索关键词：有值时侧边栏从分组树切换为扁平匹配列表 */
  const [menuKeyword, setMenuKeyword] = useState('');

  /** 视口是否处于移动端（≤768px）：驱动侧边栏抽屉模式与头部/内容区留白 */
  const [isMobile, setIsMobile] = useState(false);
  /** 移动端侧边栏抽屉是否展开 */
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  useEffect(() => {
    const mq = window.matchMedia('(max-width: 768px)');
    const apply = () => setIsMobile(mq.matches);
    apply();
    mq.addEventListener('change', apply);
    return () => mq.removeEventListener('change', apply);
  }, []);

  /** 侧边栏实际折叠态：移动端抽屉模式强制展开（需完整菜单文案） */
  const siderCollapsed = isMobile ? false : collapsed;
  const [openTabs, setOpenTabs] = useState<TabItem[]>([]);
  const [activeTab, setActiveTab] = useState(pathname);
  const [initialized, setInitialized] = useState(false);
  // 记录上一次的 pathname，用于区分「路由真正跳转」与「openTabs 变化」，
  // 避免关闭页签后 setOpenTabs 触发 useEffect 把刚关闭的页签重新加回。
  const prevPathnameRef = useRef(pathname);

  const userPermissions = user?.permissions || [];
  const filteredMenuConfig = menuConfig.filter(item => {
    if (!item.permission) return true;
    return userPermissions.includes(item.permission) || userPermissions.includes('*:*:*');
  });

  /** 动态加载后端菜单配置 */
  const [backendMenuTree, setBackendMenuTree] = useState<SysMenu[] | null>(null);
  useEffect(() => {
    menuApi.getTree().then(res => {
      if (res.code === 200) setBackendMenuTree(res.data);
    }).catch(() => {});
  }, []);

  /** 全端统计 Web 端埋点：进入/路由变化上报 */
  useEffect(() => {
    initStatTracker();
  }, []);
  useEffect(() => {
    if (pathname) {
      trackPage(pathname);
    }
  }, [pathname]);

  /**
   * 将后端菜单树转换为侧边栏菜单项格式，并过滤权限
   */
  const buildMenuItemsFromTree = (tree: SysMenu[], parentPermission?: string): any[] => {
    const result: any[] = [];
    for (const node of tree) {
      if (node.visible === 0) continue;
      if (node.permission && !userPermissions.includes(node.permission) && !userPermissions.includes('*:*:*')) continue;
      const hasChildren = !!(node.children && node.children.length > 0);
      // 无 path 的节点不能导航到 /menu-<id> 这种不存在的路由：
      // - 有子菜单的作为分组（展开即可，不跳转）
      // - 叶子节点则禁用，避免点击 404
      const item: any = { key: node.path || `menu-group-${node.id}`, icon: iconFor(node.path, node.icon), label: node.name };
      if (!node.path && !hasChildren) {
        item.disabled = true;
      }
      if (hasChildren) {
        const children = buildMenuItemsFromTree(node.children || [], node.permission);
        if (children.length > 0) item.children = children;
      }
      result.push(item);
    }
    return result;
  };

  /** 从后端菜单构建扁平标签列表（按 path 去重） */
  const buildFlatTabItems = (tree: SysMenu[], seenKeys?: Set<string>): { key: string; icon: ReactNode; label: string }[] => {
    seenKeys = seenKeys || new Set();
    const result: { key: string; icon: ReactNode; label: string }[] = [];
    for (const node of tree) {
      if (node.visible === 0) continue;
      if (node.permission && !userPermissions.includes(node.permission) && !userPermissions.includes('*:*:*')) continue;
      if (node.path) {
        if (seenKeys.has(node.path)) continue;
        seenKeys.add(node.path);
        result.push({ key: node.path, icon: iconFor(node.path, node.icon), label: node.name });
      }
      if (node.children) result.push(...buildFlatTabItems(node.children, seenKeys));
    }
    return result;
  };
  const [pluginNavItems, setPluginNavItems] = useState<NavExtension[]>([]);
  useEffect(() => {
    getNavExtensions()
      .then((data) => {
        if (data) setPluginNavItems(data);
      })
      .catch(() => {});
  }, []);

  /** 构建侧边栏菜单和标签项，使用 useMemo 让数据就绪后自动重算并触发初始化补齐 */
  const { allTabItems, menuItems } = useMemo(() => {
    let allTabItems: any[] = [];
    let menuItems: any[] = [];

    if (backendMenuTree && backendMenuTree.length > 0) {
      // 使用后端菜单配置
      const treeItems = buildMenuItemsFromTree(backendMenuTree);
      const flatItems = buildFlatTabItems(backendMenuTree);
      // 合并插件导航项
      const pluginManagerIdx = treeItems.findIndex((item: any) => item.key === '/dashboard/plugin');
      if (pluginManagerIdx >= 0 && pluginNavItems.length > 0) {
        const pluginNode = treeItems[pluginManagerIdx];
        pluginNode.children = [
          { key: '/dashboard/plugin', label: '插件管理', icon: <BlockOutlined /> },
          ...pluginNavItems.map(n => ({
            key: n.path, label: n.label, icon: iconFor(n.path, n.icon, <BlockOutlined />),
          })),
        ];
      }
      const pluginFlatItems = pluginNavItems.map(n => ({
        key: n.path, icon: iconFor(n.path, n.icon, <BlockOutlined />), label: n.label,
      }));
      allTabItems = flatItems.length > 0 ? [...flatItems, ...pluginFlatItems] : [...filteredMenuConfig, ...pluginFlatItems];
      menuItems = treeItems;
    } else {
      // 使用硬编码配置（fallback）：按业务域分组，避免 14 项扁平一级列表难以定位
      const byGroup = (group: MenuGroup) => filteredMenuConfig.filter(item => item.group === group);
      const fallbackMenu: any[] = MENU_GROUPS.flatMap(meta => {
        const items = byGroup(meta.group);
        if (items.length === 0) return [];
        return [{
          key: meta.key,
          icon: meta.icon,
          label: meta.label,
          children: items.map(item => ({ key: item.key, label: item.label, icon: item.icon })),
        }];
      });

      // 插件导航动态挂到「插件管理」下
      const opsGroup = fallbackMenu.find((item: any) => item.key === `${GROUP_KEY_PREFIX}ops`);
      const pluginNode = opsGroup?.children?.find((item: any) => item.key === '/dashboard/plugin');
      if (pluginNode) {
        pluginNode.children = pluginNavItems.map(item => ({
          key: item.path, label: item.label, icon: iconFor(item.path, item.icon, <BlockOutlined />),
        }));
      }

      const fallbackFlat = [
        ...filteredMenuConfig,
        ...pluginNavItems.map(item => ({
          key: item.path, icon: iconFor(item.path, item.icon, <BlockOutlined />), label: item.label, permission: undefined,
        })),
      ];
      allTabItems = fallbackFlat;
      menuItems = fallbackMenu;
    }

    return { allTabItems, menuItems };
  }, [backendMenuTree, filteredMenuConfig, pluginNavItems]);

  /**
   * 菜单搜索：菜单超过 10 项后逐项翻找成本高，输入关键词时把分组树扁平成
   * 命中的叶子项（分组本身不是路由，不作为可导航结果返回）
   */
  const displayedMenuItems = useMemo(() => {
    const keyword = menuKeyword.trim().toLowerCase();
    if (!keyword) return menuItems;
    const matches: any[] = [];
    const walk = (items: any[]) => {
      for (const item of items) {
        if (!item || item.disabled || !item.label) continue;
        if (item.children && item.children.length > 0) {
          walk(item.children);
        } else if (String(item.label).toLowerCase().includes(keyword)) {
          matches.push(item);
        }
      }
    };
    walk(menuItems);
    return matches;
  }, [menuItems, menuKeyword]);

  /** 面包屑：在菜单树中定位当前路径，生成「首页 → 分组 → 当前页」；
   *  仅两级及以上才渲染，单级页面（如仪表盘）与未登记路由不显示，保持克制 */
  const breadcrumbItems = useMemo(() => {
    type MenuNode = { key: string; label: string; disabled?: boolean; children?: MenuNode[] };
    const trail: MenuNode[] = [];
    const walk = (items: any[]): boolean => {
      for (const item of items) {
        if (!item || item.disabled || !item.key) continue;
        trail.push(item);
        if (item.key === pathname) return true;
        if (item.children && walk(item.children)) return true;
        trail.pop();
      }
      return false;
    };
    walk(menuItems);
    if (trail.length <= 1) return [];
    const last = trail[trail.length - 1];
    // 分组节点（/dashboard/group/*）不是路由，不能做成可点击的面包屑项
    const ancestors = trail.slice(0, -1).filter(item => !String(item.key).startsWith(GROUP_KEY_PREFIX));
    return [
      { key: '/dashboard', title: <a onClick={() => router.push('/dashboard')}>首页</a> },
      ...ancestors.map((item) => ({
        key: item.key,
        title: <a onClick={() => router.push(item.key)}>{item.label}</a>,
      })),
      { key: last.key, title: last.label },
    ];
  }, [pathname, menuItems, router]);

  useEffect(() => {
    if (!loading && !isLogin) {
      router.push('/');
    }
  }, [isLogin, loading, router]);

  /** 路由变化时自动管理标签页：新增或切换 */
  useEffect(() => {
    if (!initialized) {
      // 首次加载（含刷新）：只添加当前页签。
      // 若菜单/权限数据尚未就绪（currentTab 找不到），保持 initialized=false，
      // 待 allTabItems 变化（useMemo 依赖 backendMenuTree/pluginNavItems 等）后重跑本 effect 补初始化。
      const currentTab = allTabItems.find(item => item.key === pathname);
      if (currentTab) {
        setOpenTabs([currentTab]);
        setInitialized(true);
      }
      prevPathnameRef.current = pathname;
      return;
    }
    // 仅当路由真正跳转（pathname 变化）时才新增页签。关闭页签触发 setOpenTabs 时
    // pathname 未变，prevPathnameRef 守卫阻止把刚关闭的页签重新加回（关闭竞态 bug）。
    if (prevPathnameRef.current !== pathname) {
      prevPathnameRef.current = pathname;
      const currentTab = allTabItems.find(item => item.key === pathname);
      if (currentTab && !openTabs.find(tab => tab.key === pathname)) {
        setOpenTabs(prev => [...prev, currentTab]);
      }
    }
    setActiveTab(pathname);
    // 依赖：pathname（路由跳转触发追加）、initialized（首次初始化）、
    // openTabs（关闭后重跑但被 prevPathname 守卫拦下）、allTabItems（菜单就绪后补初始化）。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathname, initialized, openTabs, allTabItems]);

  /** 加载中显示全屏loading */
  if (loading) {
    return (
      <div style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--color-bg-base)' }}>
        <Spin size="large" />
      </div>
    );
  }

  /** 未登录时不渲染任何内容（由useEffect处理跳转） */
  if (!isLogin) return null;

  /** 菜单点击：导航到对应页面；分组节点不是路由不导航；搜索命中后清空关键词 */
  const handleMenuClick = ({ key }: { key: string }) => {
    if (key.startsWith(GROUP_KEY_PREFIX)) return;
    setMenuKeyword('');
    router.push(key);
    if (isMobile) setMobileNavOpen(false);
  };

  /** 标签页切换：导航到对应页面 */
  const handleTabChange = (key: string) => {
    router.push(key);
  };

  /** 标签页编辑（关闭）：移除标签并自动切换到相邻页或仪表盘 */
  const handleTabEdit = (targetKey: any, action: 'remove' | 'add') => {
    if (action === 'remove' && typeof targetKey === 'string') {
      // 首页仪表盘不可关闭；其他页签均可关闭（含当前页）
      if (targetKey === '/dashboard') return;
      const idx = openTabs.findIndex(tab => tab.key === targetKey);
      const newTabs = openTabs.filter(tab => tab.key !== targetKey);
      setOpenTabs(newTabs);
      // 若关闭的是当前激活页签，跳转到相邻页签（优先右侧）或仪表盘
      if (activeTab === targetKey) {
        if (newTabs.length > 0) {
          const next = newTabs[Math.min(idx, newTabs.length - 1)];
          router.push(next.key);
        } else {
          router.push('/dashboard');
        }
      }
    }
  };

  /** 关闭其他标签页：只保留当前激活的标签 */
  const handleCloseOtherTabs = () => {
    const currentTab = openTabs.find(tab => tab.key === activeTab);
    if (currentTab) {
      setOpenTabs([currentTab]);
    }
  };

  /** 关闭所有标签页：清空列表并跳转到仪表盘 */
  const handleCloseAllTabs = () => {
    setOpenTabs([]);
    router.push('/dashboard');
  };

  /** 关闭左侧标签页 */
  const handleCloseLeftTabs = () => {
    const idx = openTabs.findIndex(tab => tab.key === activeTab);
    if (idx > 0) {
      setOpenTabs(openTabs.slice(idx));
    }
  };

  /** 关闭右侧标签页 */
  const handleCloseRightTabs = () => {
    const idx = openTabs.findIndex(tab => tab.key === activeTab);
    if (idx >= 0 && idx < openTabs.length - 1) {
      setOpenTabs(openTabs.slice(0, idx + 1));
    }
  };

  /** 刷新当前标签页 */
  const handleRefreshTab = () => {
    router.refresh();
  };

  /** 当前激活标签页索引 */
  const activeTabIndex = openTabs.findIndex(tab => tab.key === activeTab);

  /** 标签页操作菜单项 */
  const tabActionItems = [
    { key: 'refresh', icon: <ReloadOutlined />, label: '刷新当前' },
    { type: 'divider' as const },
    { key: 'closeCurrent', icon: <CloseOutlined />, label: '关闭当前', disabled: openTabs.length <= 1 },
    { key: 'closeOther', icon: <CloseOutlined />, label: '关闭其他', disabled: openTabs.length <= 1 },
    { key: 'closeLeft', icon: <CloseOutlined />, label: '关闭左侧', disabled: activeTabIndex <= 0 },
    { key: 'closeRight', icon: <CloseOutlined />, label: '关闭右侧', disabled: activeTabIndex >= openTabs.length - 1 || activeTabIndex < 0 },
    { type: 'divider' as const },
    { key: 'closeAll', icon: <CloseCircleOutlined />, label: '关闭全部', danger: true },
  ];

  /** 标签页操作处理 */
  const handleTabAction = (actionKey: string) => {
    switch (actionKey) {
      case 'refresh':
        handleRefreshTab();
        break;
      case 'closeCurrent':
        if (openTabs.length > 1) {
          handleTabEdit(activeTab, 'remove');
        }
        break;
      case 'closeOther':
        handleCloseOtherTabs();
        break;
      case 'closeLeft':
        handleCloseLeftTabs();
        break;
      case 'closeRight':
        handleCloseRightTabs();
        break;
      case 'closeAll':
        handleCloseAllTabs();
        break;
    }
  };

  /** 用户登出处理 */
  const handleLogout = async () => {
    await logout();
    router.push('/');
  };

  /** 用户下拉菜单项 */
  const userMenuItems = [
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
      onClick: handleLogout,
    },
  ];

  /** 将标签页数据转换为Ant Design Tabs组件所需格式 */
  const tabItems = openTabs.map(tab => ({
    key: tab.key,
    // 首页仪表盘常驻不可关闭，其余页签均可关闭
    closable: tab.key !== '/dashboard',
    label: (
      <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        {tab.icon}
        <span>{tab.label}</span>
      </span>
    ),
  }));

  /** 头部图标按钮统一命中区：视觉尺寸不变，热区扩到 36×36 便于点选 */
  const headerIconButtonStyle: React.CSSProperties = {
    width: 36,
    height: 36,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: 18,
    marginRight: 8,
    flexShrink: 0,
  };

  return (
    <Layout className="dashboard-layout" style={{ minHeight: '100vh' }}>
        {isMobile && mobileNavOpen && (
          <div className="nav-mask" onClick={() => setMobileNavOpen(false)} />
        )}
        <Sider
          width={220} 
          collapsedWidth={80}
          collapsible 
          collapsed={siderCollapsed}
          onCollapse={setCollapsed}
          className={isMobile ? 'dashboard-sider drawer-open' : 'dashboard-sider'}
          trigger={null}
          style={{
            position: 'fixed',
            left: 0,
            top: 0,
            bottom: 0,
            zIndex: isMobile ? (mobileNavOpen ? 200 : -1) : 100,
            // 移动端：抽屉模式，收起时移出视口
            transform: isMobile ? (mobileNavOpen ? 'translateX(0)' : 'translateX(-100%)') : undefined,
            transition: isMobile ? 'transform 0.24s ease' : undefined,
          }}
        >
          <div className="sider-logo">
            {siderCollapsed ? (
              <div
                aria-hidden
                style={{
                  width: 32,
                  height: 32,
                  borderRadius: 8,
                  background: 'var(--color-brand)',
                  color: '#fff',
                  fontSize: 15,
                  fontWeight: 700,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  letterSpacing: 0,
                }}
              >
                A
              </div>
            ) : (
              <h1>Astral<span>.</span></h1>
            )}
          </div>
          <div className="sider-menu-wrap">
            {!siderCollapsed && (
              <Input
                allowClear
                size="small"
                className="sider-search"
                prefix={<SearchOutlined />}
                placeholder="搜索菜单"
                value={menuKeyword}
                onChange={(e) => setMenuKeyword(e.target.value)}
                aria-label="搜索菜单"
                style={{ margin: '0 12px 6px' }}
              />
            )}
            {menuKeyword.trim() && displayedMenuItems.length === 0 && !siderCollapsed ? (
              <div className="sider-search-empty">
                <InboxOutlined aria-hidden />
                <span>未找到匹配的菜单</span>
              </div>
            ) : (
              <Menu
                mode="inline"
                selectedKeys={[pathname]}
                items={displayedMenuItems}
                onClick={handleMenuClick}
                inlineCollapsed={siderCollapsed}
                style={{
                  borderRight: 'none',
                  background: 'transparent',
                  marginTop: menuKeyword.trim() ? 0 : 8,
                }}
              />
            )}
          </div>
        </Sider>
        <Layout style={{ marginLeft: isMobile ? 0 : (collapsed ? 80 : 220), transition: 'margin-left 0.2s' }}>
          <Header className="dashboard-header">
            <Button
              type="text"
              icon={isMobile ? <MenuOutlined /> : (collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />)}
              onClick={() => (isMobile ? setMobileNavOpen(true) : setCollapsed(!collapsed))}
              style={headerIconButtonStyle}
              title={isMobile ? '打开菜单' : (collapsed ? '展开侧边栏' : '收起侧边栏')}
              aria-label={isMobile ? '打开菜单' : (collapsed ? '展开侧边栏' : '收起侧边栏')}
            />
            <Button
              type="text"
              icon={themeMode === 'dark' ? <SunOutlined /> : <MoonOutlined />}
              onClick={toggleTheme}
              style={headerIconButtonStyle}
              title={themeMode === 'dark' ? '切换到浅色模式' : '切换到深色模式'}
              aria-label={themeMode === 'dark' ? '切换到浅色模式' : '切换到深色模式'}
            />
            <NoticeBell buttonStyle={headerIconButtonStyle} />
            {!isMobile && breadcrumbItems.length > 1 && (
              <Breadcrumb
                className="dashboard-breadcrumb"
                items={breadcrumbItems}
                style={{ flex: 1, marginLeft: 4 }}
              />
            )}
            <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
              <div className="header-user" style={{ cursor: 'pointer' }}>
                <Avatar
                  size={isMobile ? 32 : 36}
                  style={{
                    background: 'var(--color-brand)',
                    fontWeight: 600
                  }}
                  icon={<UserOutlined />}
                />
                <span className="username-text" style={{ fontWeight: 500, color: 'var(--color-text)' }}>
                  {user?.username || '管理员'}
                </span>
              </div>
            </Dropdown>
          </Header>
          <div className="tabs-wrapper" style={{
            background: 'var(--color-bg-white)',
            padding: '8px 16px 0',
            borderBottom: '1px solid var(--color-border-light)',
            position: 'sticky',
            top: 64,
            zIndex: 10,
            display: 'flex',
            alignItems: 'center',
            overflow: 'hidden'
          }}>
            <style>{`
              .tabs-wrapper .ant-tabs { margin-bottom: -1px; flex: 1; min-width: 0; overflow: hidden; }
              .tabs-wrapper .ant-tabs-nav { overflow-x: auto !important; scrollbar-width: none; }
              .tabs-wrapper .ant-tabs-nav::-webkit-scrollbar { display: none; }
              .tabs-wrapper .ant-tabs-nav-list { white-space: nowrap; }
            `}</style>
            <Tabs
              activeKey={activeTab}
              onChange={handleTabChange}
              type="editable-card"
              onEdit={handleTabEdit}
              hideAdd
              items={tabItems}
            />
            {openTabs.length > 0 && (
              <Dropdown
                menu={{
                  items: tabActionItems,
                  onClick: ({ key }) => handleTabAction(key),
                }}
                placement="bottomRight"
                trigger={['click']}
              >
                <Button
                  type="text"
                  icon={<MoreOutlined />}
                  style={{
                    marginLeft: 8,
                    flexShrink: 0,
                    width: 40,
                    height: 40,
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                  title="标签操作"
                  aria-label="标签操作"
                />
              </Dropdown>
            )}
          </div>
          <Content className="dashboard-content fade-in" style={{ minHeight: 'calc(100vh - 130px)' }}>
            {children}
          </Content>
        </Layout>
      </Layout>
  );
}