'use client';

import { Fragment, useEffect, useState, useRef, useMemo, type ReactNode } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import {
  LayoutDashboard,
  Wrench,
  PlugZap,
  LogOut,
  FileText,
  User,
  PanelLeftClose,
  PanelLeftOpen,
  Menu as MenuIcon,
  X,
  RotateCw,
  MoreHorizontal,
  XCircle,
  Settings,
  Users,
  BookText,
  ShieldCheck,
  KeyRound,
  Table2,
  BarChart3,
  Blocks,
  Headphones,
  Mail,
  Shield,
  Lock,
  PieChart,
  Inbox,
  Search,
  Sun,
  Moon,
  ChevronDown,
  type LucideIcon,
} from 'lucide-react';

import { useAuth } from '@/context/AuthContext';
import { useTheme } from '../providers';
import { getNavExtensions } from '@/api/plugin';
import { menuApi, SysMenu } from '@/api/menu';
import type { NavExtension } from '@/api/plugin';
import { initStatTracker, trackPage } from '@/lib/statTracker';
import { hasPermissionIn } from '@/lib/perm';
import NoticeBell from '@/components/NoticeBell';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from '@/components/ui/breadcrumb';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';

/** 分组节点 key 前缀：分组本身不是路由，点击不应导航 */
const GROUP_KEY_PREFIX = '/dashboard/group/';

/** 图标名称到组件的映射（后端菜单表 / 插件导航扩展都以字符串存图标名） */
const iconMap: Record<string, ReactNode> = {
  DashboardOutlined: <LayoutDashboard />,
  ToolOutlined: <Wrench />,
  ApiOutlined: <PlugZap />,
  FileTextOutlined: <FileText />,
  SettingOutlined: <Settings />,
  TeamOutlined: <Users />,
  BookOutlined: <BookText />,
  SafetyOutlined: <Shield />,
  SafetyCertificateOutlined: <ShieldCheck />,
  LockOutlined: <Lock />,
  KeyOutlined: <KeyRound />,
  TableOutlined: <Table2 />,
  BarChartOutlined: <BarChart3 />,
  PieChartOutlined: <PieChart />,
  BlockOutlined: <Blocks />,
  MailOutlined: <Mail />,
  InboxOutlined: <Inbox />,
  MenuOutlined: <MenuIcon />,
  CustomerServiceOutlined: <Headphones />,
  SearchOutlined: <Search />,
  SunOutlined: <Sun />,
  MoonOutlined: <Moon />,
  AppstoreOutlined: <Blocks />,
  AppstoreAddOutlined: <Blocks />,
};

/**
 * 按路由路径覆盖图标的兜底表。
 * 后端 sys_menu 把语义相近的页面登记成同一图标（如「角色权限」和「权限管理」都是 SafetyOutlined），
 * 菜单里两个入口长得完全一样会破坏可辨识性。前端无法区分同名图标属于哪个页面，
 * 只能按路径覆盖。彻底修复需改后端 sys_menu.icon 数据。
 */
const PATH_ICON_OVERRIDE: Record<string, ReactNode> = {
  '/dashboard/system/role': <ShieldCheck />,
  '/dashboard/system/permission': <Lock />,
  '/dashboard/system/mail/log': <PieChart />,
};

/** 解析菜单图标：路径覆盖 > 图标名映射 > 兜底 */
const iconFor = (path: string | undefined, iconName: string | undefined, fallback: ReactNode = null): ReactNode => {
  if (path && PATH_ICON_OVERRIDE[path]) return PATH_ICON_OVERRIDE[path];
  if (iconName && iconMap[iconName]) return iconMap[iconName];
  return fallback;
};

/** 按插件声明排序（sort 缺省按 0） */
const byPluginSort = (a: { sort?: number }, b: { sort?: number }) => (a.sort ?? 0) - (b.sort ?? 0);

/**
 * 把插件导航项按 `NavItem.parentPath` 挂到菜单树对应父节点：
 * - parentPath 命中现有节点（逐层按 key 找）→ 作为该节点 children，同级按 sort 排序；
 * - parentPath 为空或**父节点不存在** → 作为顶层叶子追加（按 sort 排序）。
 *
 * 嵌套与否完全由插件自己声明（`NavItem.pluginPage(...)` 即挂在「插件管理」下的二级菜单），
 * 前端不做任何硬编码塞入，也没有旧代码里那个硬编码的重复「插件管理」子项。
 * 第二条兜底很重要：用户没有 `admin:plugin:view` 时看不到「插件管理」本身，
 * 此时他有权访问的插件页会退到顶层展示，而不是从侧边栏消失。
 */
const attachPluginNav = (items: MenuItemModel[], navItems: NavExtension[]): void => {
  const parents = new Map<string, MenuItemModel>();
  const walk = (nodes: MenuItemModel[]) => {
    for (const n of nodes) {
      if (n.key) parents.set(n.key, n);
      if (n.children && n.children.length > 0) walk(n.children);
    }
  };
  walk(items);
  const tops: MenuItemModel[] = [];
  for (const n of [...navItems].sort(byPluginSort)) {
    const child: MenuItemModel = {
      key: n.path,
      label: n.label,
      icon: iconFor(n.path, n.icon, <Blocks />),
      sort: n.sort ?? 0,
    };
    if (n.parentPath) {
      const parent = parents.get(n.parentPath);
      if (parent) {
        if (!parent.children) parent.children = [];
        parent.children.push(child);
        parent.children.sort(byPluginSort);
        continue;
      }
    }
    tops.push(child);
  }
  items.push(...tops);
};

/** 标签页项接口 */
interface TabItem {
  /** 标签页唯一标识（路由路径） */
  key: string;
  /** 标签页显示名称 */
  label: string;
  /** 标签页图标 */
  icon: ReactNode;
}

/** 菜单分组标识（后端菜单树不可用时的兜底数据源使用） */
type MenuGroup = 'overview' | 'system' | 'ops';

/** 菜单项渲染模型（antd Menu items 结构的等价物） */
interface MenuItemModel {
  key: string;
  label: string;
  icon?: ReactNode;
  disabled?: boolean;
  /** 插件导航项的排序权重（NavItem.sort），用于同级排序 */
  sort?: number;
  children?: MenuItemModel[];
}

/**
 * 侧边栏菜单配置：定义所有可访问的页面及其图标、名称、所属业务域
 * 约束：同一图标不允许在菜单里重复出现——语义相近的页面必须配不同图标，
 * 否则用户在菜单里只能靠位置而不是语义区分入口
 */
const menuConfig: {
  key: string;
  icon: ReactNode;
  label: string;
  permission?: string;
  group: MenuGroup;
}[] = [
  { key: '/dashboard', icon: <LayoutDashboard />, label: '仪表盘', group: 'overview' },
  { key: '/dashboard/statistics', icon: <BarChart3 />, label: '数据统计', permission: 'admin:statistics:view', group: 'overview' },
  { key: '/dashboard/system/user', icon: <Users />, label: '用户管理', permission: 'admin:system:user:view', group: 'system' },
  { key: '/dashboard/system/role', icon: <ShieldCheck />, label: '角色权限', permission: 'admin:system:role:view', group: 'system' },
  { key: '/dashboard/system/permission', icon: <Lock />, label: '权限管理', permission: 'admin:system:permission:view', group: 'system' },
  { key: '/dashboard/system/dict', icon: <BookText />, label: '数据字典', permission: 'admin:system:dict:view', group: 'system' },
  { key: '/dashboard/system/config', icon: <Settings />, label: '系统配置', permission: 'admin:system:config:view', group: 'system' },
  { key: '/dashboard/system/token', icon: <KeyRound />, label: 'Token管理', permission: 'admin:system:token:view', group: 'system' },
  { key: '/dashboard/system/table-schema', icon: <Table2 />, label: '表结构管理', permission: 'admin:system:schema:view', group: 'system' },
  { key: '/dashboard/system/mail', icon: <Mail />, label: '邮箱管理', permission: 'admin:system:mail:view', group: 'system' },
  { key: '/dashboard/system/mail/log', icon: <PieChart />, label: '邮箱统计', permission: 'admin:system:mail:statistics:view', group: 'system' },
  { key: '/dashboard/log', icon: <FileText />, label: '日志管理', permission: 'admin:log:view', group: 'ops' },
  { key: '/dashboard/plugin', icon: <Blocks />, label: '插件管理', permission: 'admin:plugin:view', group: 'ops' },
];

/** 分组元信息：分组 key（非路由）、图标、名称、展示顺序 */
const MENU_GROUPS: Array<{ group: MenuGroup; key: string; icon: ReactNode; label: string }> = [
  { group: 'overview', key: `${GROUP_KEY_PREFIX}overview`, icon: <LayoutDashboard />, label: '概览' },
  { group: 'system', key: `${GROUP_KEY_PREFIX}system`, icon: <Settings />, label: '系统管理' },
  { group: 'ops', key: `${GROUP_KEY_PREFIX}ops`, icon: <FileText />, label: '运维' },
];

/* ============================ 侧边栏菜单渲染 ============================ */

/** 单个菜单叶子项（shadcn 风格：浅灰胶囊选中态 + 图标 + 文案） */
function NavLeaf({
  item,
  active,
  collapsed,
  onClick,
}: {
  item: MenuItemModel;
  active: boolean;
  collapsed: boolean;
  onClick: () => void;
}) {
  const content = (
    <button
      type="button"
      disabled={item.disabled}
      onClick={onClick}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'flex w-full items-center gap-2.5 rounded-md px-2.5 py-2 text-left text-sm transition-colors cursor-pointer',
        'disabled:pointer-events-none disabled:opacity-50',
        collapsed && 'justify-center px-0',
        active
          ? 'bg-[var(--menu-selected-bg)] font-medium text-foreground'
          : 'text-muted-foreground hover:bg-[var(--menu-hover-bg)] hover:text-foreground',
      )}
    >
      {item.icon && <span className="flex size-4 shrink-0 items-center justify-center [&_svg]:size-4">{item.icon}</span>}
      {!collapsed && <span className="truncate">{item.label}</span>}
    </button>
  );

  if (collapsed) {
    return (
      <Tooltip>
        <TooltipTrigger asChild>{content}</TooltipTrigger>
        <TooltipContent side="right">{item.label}</TooltipContent>
      </Tooltip>
    );
  }
  return content;
}

/** 可折叠分组（组头 + 子项列表）；折叠侧边栏时组头只剩图标，点击展开侧边栏 */
function NavGroup({
  item,
  open,
  collapsed,
  activePath,
  onToggle,
  onLeafClick,
}: {
  item: MenuItemModel;
  open: boolean;
  collapsed: boolean;
  activePath: string;
  onToggle: () => void;
  onLeafClick: (item: MenuItemModel) => void;
}) {
  const groupActive = (item.children || []).some(
    (child) => child.key === activePath || (child.children || []).some((c) => c.key === activePath),
  );

  /**
   * 组头点击：分组自身是真实路由（如 /dashboard/plugin 被插件项挂成父级）时，
   * 既跳转到该页面又展开子项——避免「组里只有子项、父页面反而进不去」；
   * 纯分组（menu-group-*）只做展开/收起。
   */
  const isNavigateGroup = !!item.key && item.key.startsWith('/') && !item.key.startsWith(GROUP_KEY_PREFIX);
  const headerOnClick = () => {
    if (isNavigateGroup) onLeafClick(item);
    onToggle();
  };
  const header = (
    <button
      type="button"
      onClick={headerOnClick}
      aria-expanded={open}
      className={cn(
        'flex w-full items-center gap-2.5 rounded-md px-2.5 py-2 text-left text-sm transition-colors cursor-pointer',
        collapsed && 'justify-center px-0',
        groupActive ? 'font-medium text-foreground' : 'text-muted-foreground hover:bg-[var(--menu-hover-bg)] hover:text-foreground',
      )}
    >
      {item.icon && <span className="flex size-4 shrink-0 items-center justify-center [&_svg]:size-4">{item.icon}</span>}
      {!collapsed && (
        <>
          <span className="flex-1 truncate">{item.label}</span>
          <ChevronDown className={cn('size-3.5 shrink-0 transition-transform', open && 'rotate-180')} />
        </>
      )}
    </button>
  );

  return (
    <div>
      {collapsed ? (
        <Tooltip>
          <TooltipTrigger asChild>{header}</TooltipTrigger>
          <TooltipContent side="right">{item.label}</TooltipContent>
        </Tooltip>
      ) : (
        header
      )}
      {open && !collapsed && (
        <div className="mt-0.5 space-y-0.5 pl-4">
          {(item.children || []).map((child) =>
            child.key.startsWith(GROUP_KEY_PREFIX) ? null : (
              <NavLeaf key={child.key} item={child} active={child.key === activePath} collapsed={false} onClick={() => onLeafClick(child)} />
            ),
          )}
        </div>
      )}
    </div>
  );
}

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
  /** 展开的分组 key 集合 */
  const [openGroupKeys, setOpenGroupKeys] = useState<Set<string>>(new Set());

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

  // 必须 memo：下面 useMemo / effect 依赖 filteredMenuConfig，
  // 每次渲染新建数组会击穿下游 3 个 useMemo 和标签页初始化 effect。
  const userPermissions = useMemo(() => user?.permissions || [], [user?.permissions]);
  const filteredMenuConfig = useMemo(
    () =>
      menuConfig.filter((item) => hasPermissionIn(userPermissions, item.permission)),
    [userPermissions],
  );

  /** 动态加载后端菜单配置 */
  const [backendMenuTree, setBackendMenuTree] = useState<SysMenu[] | null>(null);
  useEffect(() => {
    menuApi
      .getTree()
      .then((res) => {
        if (res.code === 200) setBackendMenuTree(res.data);
      })
      .catch(() => {});
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
  const buildMenuItemsFromTree = (tree: SysMenu[]): MenuItemModel[] => {
    const result: MenuItemModel[] = [];
    for (const node of tree) {
      if (node.visible === 0) continue;
      if (!hasPermissionIn(userPermissions, node.permission)) continue;
      const hasChildren = !!(node.children && node.children.length > 0);
      // 无 path 的节点不能导航到 /menu-<id> 这种不存在的路由：
      // - 有子菜单的作为分组（展开即可，不跳转）
      // - 叶子节点则禁用，避免点击 404
      const item: MenuItemModel = {
        key: node.path || `menu-group-${node.id}`,
        icon: iconFor(node.path, node.icon),
        label: node.name,
        disabled: !node.path && !hasChildren,
      };
      if (hasChildren) {
        const children = buildMenuItemsFromTree(node.children || []);
        if (children.length > 0) item.children = children;
      }
      result.push(item);
    }
    return result;
  };

  /** 从后端菜单构建扁平标签列表（按 path 去重） */
  const buildFlatTabItems = (tree: SysMenu[], seenKeys?: Set<string>): TabItem[] => {
    seenKeys = seenKeys || new Set();
    const result: TabItem[] = [];
    for (const node of tree) {
      if (node.visible === 0) continue;
      if (!hasPermissionIn(userPermissions, node.permission)) continue;
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

  /**
   * 插件导航项按登录用户权限过滤（NavItem.permission）：
   * 插件页签的后端接口已声明权限，不过滤会出现「菜单可见、点进去全 403」。
   */
  const visiblePluginNavItems = useMemo(
    () => pluginNavItems.filter((n) => hasPermissionIn(userPermissions, n.permission)),
    [pluginNavItems, userPermissions],
  );

  /** 构建侧边栏菜单和标签项，使用 useMemo 让数据就绪后自动重算并触发初始化补齐 */
  const { allTabItems, menuItems } = useMemo(() => {
    let allTabItems: TabItem[] = [];
    let menuItems: MenuItemModel[] = [];

    if (backendMenuTree && backendMenuTree.length > 0) {
      // 使用后端菜单配置
      const treeItems = buildMenuItemsFromTree(backendMenuTree);
      const flatItems = buildFlatTabItems(backendMenuTree);
      // 插件导航项按 NavItem.parentPath 挂载（插件自己声明挂哪；不再塞重复的「插件管理」子项）
      if (visiblePluginNavItems.length > 0) {
        attachPluginNav(treeItems, visiblePluginNavItems);
      }
      const pluginFlatItems: TabItem[] = visiblePluginNavItems.map((n) => ({
        key: n.path,
        icon: iconFor(n.path, n.icon, <Blocks />),
        label: n.label,
      }));
      allTabItems = flatItems.length > 0 ? [...flatItems, ...pluginFlatItems] : [...filteredMenuConfig, ...pluginFlatItems];
      menuItems = treeItems;
    } else {
      // 使用硬编码配置（fallback）：按业务域分组，避免 14 项扁平一级列表难以定位
      const byGroup = (group: MenuGroup) => filteredMenuConfig.filter((item) => item.group === group);
      const fallbackMenu: MenuItemModel[] = MENU_GROUPS.flatMap((meta) => {
        const items = byGroup(meta.group);
        if (items.length === 0) return [];
        return [
          {
            key: meta.key,
            icon: meta.icon,
            label: meta.label,
            children: items.map((item) => ({ key: item.key, label: item.label, icon: item.icon })),
          },
        ];
      });

      // 插件导航按 parentPath 挂载（fallback 模式同样生效；无父声明则顶层追加）
      if (visiblePluginNavItems.length > 0) {
        attachPluginNav(fallbackMenu, visiblePluginNavItems);
      }

      const fallbackFlat: TabItem[] = [
        ...filteredMenuConfig,
        ...visiblePluginNavItems.map((item) => ({
          key: item.path,
          icon: iconFor(item.path, item.icon, <Blocks />),
          label: item.label,
        })),
      ];
      allTabItems = fallbackFlat;
      menuItems = fallbackMenu;
    }

    return { allTabItems, menuItems };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [backendMenuTree, filteredMenuConfig, visiblePluginNavItems]);

  /**
   * 菜单搜索：菜单超过 10 项后逐项翻找成本高，输入关键词时把分组树扁平成
   * 命中的叶子项（分组本身不是路由，不作为可导航结果返回）
   */
  const displayedMenuItems = useMemo(() => {
    const keyword = menuKeyword.trim().toLowerCase();
    if (!keyword) return menuItems;
    const matches: MenuItemModel[] = [];
    const walk = (items: MenuItemModel[]) => {
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
  const breadcrumbTrail = useMemo(() => {
    const trail: MenuItemModel[] = [];
    const walk = (items: MenuItemModel[]): boolean => {
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
    return trail;
  }, [pathname, menuItems]);

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
      const currentTab = allTabItems.find((item) => item.key === pathname);
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
      const currentTab = allTabItems.find((item) => item.key === pathname);
      if (currentTab && !openTabs.find((tab) => tab.key === pathname)) {
        setOpenTabs((prev) => [...prev, currentTab]);
      }
    }
    setActiveTab(pathname);
    // 依赖：pathname（路由跳转触发追加）、initialized（首次初始化）、
    // openTabs（关闭后重跑但被 prevPathname 守卫拦下）、allTabItems（菜单就绪后补初始化）。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathname, initialized, openTabs, allTabItems]);

  /**
   * 当前路径命中的分组默认展开（递归，含「本身是路由」的分组）。
   *
   * 旧实现只认 `menu-group-*` 前缀的顶层分组。插件项按 parentPath 挂到
   * `/dashboard/plugin` 这类**本身就是路由**的分组下之后，旧判断永远不命中，
   * 进该页面时分组不展开，子项（文件存储）就看不见——必须去掉前缀限制。
   * 现在按菜单树递归收集所有「子项或孙项命中当前路径」的分组 key，
   * 同时把 key 等于当前路径的分组也算命中（点进父页面即展开其子项）。
   */
  useEffect(() => {
    if (siderCollapsed) return;
    const toOpen = new Set<string>();
    const walk = (items: MenuItemModel[], acc: Set<string>) => {
      for (const item of items) {
        if (!item.children || item.children.length === 0) continue;
        const childHit = item.children.some((c) => c.key === pathname);
        const grandHit = item.children.some((c) => (c.children || []).some((g) => g.key === pathname));
        if (childHit || grandHit || item.key === pathname) acc.add(item.key);
        walk(item.children, acc);
      }
    };
    walk(menuItems, toOpen);
    if (toOpen.size === 0) return;
    setOpenGroupKeys((prev) => {
      const next = new Set(prev);
      let changed = false;
      for (const k of toOpen) {
        if (!next.has(k)) {
          next.add(k);
          changed = true;
        }
      }
      return changed ? next : prev;
    });
  }, [pathname, menuItems, siderCollapsed]);

  /** 加载中显示全屏loading */
  if (loading) {
    return (
      <div
        style={{ height: '100vh' }}
        className="flex items-center justify-center bg-[var(--color-bg-base)]"
      >
        <RotateCw className="size-8 animate-spin text-muted-foreground" />
      </div>
    );
  }

  /** 未登录时不渲染任何内容（由useEffect处理跳转） */
  if (!isLogin) return null;

  /** 菜单点击：导航到对应页面；分组节点由 NavGroup 处理展开；搜索命中后清空关键词 */
  const handleLeafClick = (item: MenuItemModel) => {
    if (item.key.startsWith(GROUP_KEY_PREFIX)) return;
    setMenuKeyword('');
    router.push(item.key);
    if (isMobile) setMobileNavOpen(false);
  };

  /** 分组展开/收起 */
  const toggleGroup = (key: string) => {
    setOpenGroupKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  /** 标签页切换：导航到对应页面 */
  const handleTabChange = (key: string) => {
    router.push(key);
  };

  /** 标签页编辑（关闭）：移除标签并自动切换到相邻页或仪表盘 */
  const handleTabClose = (targetKey: string) => {
    // 首页仪表盘不可关闭；其他页签均可关闭（含当前页）
    if (targetKey === '/dashboard') return;
    const idx = openTabs.findIndex((tab) => tab.key === targetKey);
    const newTabs = openTabs.filter((tab) => tab.key !== targetKey);
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
  };

  /** 关闭其他标签页：只保留当前激活的标签 */
  const handleCloseOtherTabs = () => {
    const currentTab = openTabs.find((tab) => tab.key === activeTab);
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
    const idx = openTabs.findIndex((tab) => tab.key === activeTab);
    if (idx > 0) {
      setOpenTabs(openTabs.slice(idx));
    }
  };

  /** 关闭右侧标签页 */
  const handleCloseRightTabs = () => {
    const idx = openTabs.findIndex((tab) => tab.key === activeTab);
    if (idx >= 0 && idx < openTabs.length - 1) {
      setOpenTabs(openTabs.slice(0, idx + 1));
    }
  };

  /** 当前激活标签页索引 */
  const activeTabIndex = openTabs.findIndex((tab) => tab.key === activeTab);

  /** 标签页操作处理 */
  const handleTabAction = (actionKey: string) => {
    switch (actionKey) {
      case 'refresh':
        router.refresh();
        break;
      case 'closeCurrent':
        handleTabClose(activeTab);
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

  /** 面包屑渲染数据（分组节点不是路由，不做可点击项） */
  const breadcrumbAncestors = breadcrumbTrail.slice(0, -1).filter((item) => !String(item.key).startsWith(GROUP_KEY_PREFIX));
  const breadcrumbLast = breadcrumbTrail[breadcrumbTrail.length - 1];
  const showBreadcrumb = !isMobile && breadcrumbTrail.length > 1;

  return (
    <div className="min-h-screen">
      {isMobile && mobileNavOpen && (
        <div className="nav-mask fixed inset-0 z-[190] bg-black/40" onClick={() => setMobileNavOpen(false)} />
      )}

      {/* ==================== 侧边栏 ==================== */}
      <aside
        className={cn(
          'dashboard-sider flex flex-col border-r border-border bg-[var(--color-bg-sidebar)]',
          isMobile ? 'drawer-open' : '',
        )}
        style={{
          width: siderCollapsed ? 80 : 220,
          position: 'fixed',
          left: 0,
          top: 0,
          bottom: 0,
          zIndex: isMobile ? (mobileNavOpen ? 200 : -1) : 100,
          transform: isMobile ? (mobileNavOpen ? 'translateX(0)' : 'translateX(-100%)') : undefined,
          transition: isMobile ? 'transform 0.24s ease' : undefined,
        }}
      >
        {/* Logo */}
        <div className="sider-logo flex h-16 shrink-0 items-center px-4">
          {siderCollapsed ? (
            <div
              aria-hidden
              className="flex size-8 items-center justify-center rounded-lg bg-primary text-[15px] font-bold tracking-normal text-primary-foreground"
            >
              A
            </div>
          ) : (
            <h1 className="text-xl font-bold tracking-tight text-foreground">
              Astral<span className="text-primary">.</span>
            </h1>
          )}
        </div>

        {/* 搜索 + 菜单 */}
        <div className="sider-menu-wrap flex-1 overflow-y-auto overflow-x-hidden pb-4">
          {!siderCollapsed && (
            <div className="relative mx-3 mb-1.5">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
              <Input
                className="sider-search h-8 pl-8 text-xs"
                placeholder="搜索菜单"
                value={menuKeyword}
                onChange={(e) => setMenuKeyword(e.target.value)}
                aria-label="搜索菜单"
              />
            </div>
          )}
          {menuKeyword.trim() && displayedMenuItems.length === 0 && !siderCollapsed ? (
            <div className="sider-search-empty flex flex-col items-center gap-1.5 px-4 py-6 text-xs text-muted-foreground">
              <Inbox className="size-5" strokeWidth={1.5} aria-hidden />
              <span>未找到匹配的菜单</span>
            </div>
          ) : (
            <nav className="mt-2 space-y-0.5 px-3">
              {displayedMenuItems.map((item) =>
                (item.children && item.children.length > 0) || item.key.startsWith(GROUP_KEY_PREFIX) ? (
                  <NavGroup
                    key={item.key}
                    item={item}
                    open={openGroupKeys.has(item.key)}
                    collapsed={siderCollapsed}
                    activePath={pathname}
                    onToggle={() => {
                      if (siderCollapsed) {
                        // 折叠态点击分组图标 = 展开侧边栏
                        setCollapsed(false);
                        setOpenGroupKeys((prev) => new Set(prev).add(item.key));
                      } else {
                        toggleGroup(item.key);
                      }
                    }}
                    onLeafClick={handleLeafClick}
                  />
                ) : (
                  <NavLeaf
                    key={item.key}
                    item={item}
                    active={item.key === pathname}
                    collapsed={siderCollapsed}
                    onClick={() => handleLeafClick(item)}
                  />
                ),
              )}
            </nav>
          )}
        </div>
      </aside>

      {/* ==================== 主区 ==================== */}
      <div
        style={{ marginLeft: isMobile ? 0 : siderCollapsed ? 80 : 220, transition: 'margin-left 0.2s' }}
      >
        {/* 头部 */}
        <header className="dashboard-header flex h-14 items-center gap-1 border-b border-border bg-[var(--color-bg-white)] px-4">
          {/* 面包屑：左侧（flex-1 占满剩余空间，把右侧操作区顶到最右） */}
          {showBreadcrumb && (
            <Breadcrumb className="ml-1 min-w-0 flex-1">
              <BreadcrumbList>
                <BreadcrumbItem>
                  <a onClick={() => router.push('/dashboard')} className="cursor-pointer hover:text-foreground">
                    首页
                  </a>
                </BreadcrumbItem>
                {breadcrumbAncestors.map((item) => (
                  <Fragment key={item.key}>
                    <BreadcrumbSeparator />
                    <BreadcrumbItem>
                      <a onClick={() => router.push(item.key)} className="cursor-pointer hover:text-foreground">
                        {item.label}
                      </a>
                    </BreadcrumbItem>
                  </Fragment>
                ))}
                <BreadcrumbSeparator />
                <BreadcrumbItem>
                  <BreadcrumbPage>{breadcrumbLast.label}</BreadcrumbPage>
                </BreadcrumbItem>
              </BreadcrumbList>
            </Breadcrumb>
          )}

          {/* 右侧操作区（统一靠右）：收缩菜单栏 / 主题切换 / 通知 / 用户 */}
          <div className={cn('flex items-center gap-1', showBreadcrumb ? '' : 'ml-auto')}>
            <Button
              variant="ghost"
              size="icon"
              onClick={() => (isMobile ? setMobileNavOpen(true) : setCollapsed(!collapsed))}
              title={isMobile ? '打开菜单' : siderCollapsed ? '展开侧边栏' : '收起侧边栏'}
              aria-label={isMobile ? '打开菜单' : siderCollapsed ? '展开侧边栏' : '收起侧边栏'}
            >
              {isMobile ? <MenuIcon /> : siderCollapsed ? <PanelLeftOpen /> : <PanelLeftClose />}
            </Button>
            <Button
              variant="ghost"
              size="icon"
              onClick={toggleTheme}
              title={themeMode === 'dark' ? '切换到浅色模式' : '切换到深色模式'}
              aria-label={themeMode === 'dark' ? '切换到浅色模式' : '切换到深色模式'}
            >
              {themeMode === 'dark' ? <Sun /> : <Moon />}
            </Button>
            <NoticeBell />

            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button type="button" className="header-user flex cursor-pointer items-center gap-2 rounded-md outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50">
                  <span
                    className={cn(
                      'flex items-center justify-center rounded-full bg-primary font-semibold text-primary-foreground',
                      isMobile ? 'size-8' : 'size-9',
                    )}
                  >
                    <User className="size-4" />
                  </span>
                  <span className={cn('username-text font-medium text-foreground', isMobile && 'hidden')}>
                    {user?.username || '管理员'}
                  </span>
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="min-w-36">
                <DropdownMenuItem variant="destructive" onClick={handleLogout}>
                  <LogOut />
                  退出登录
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </header>

        {/* 页签条 */}
        {openTabs.length > 0 && (
          <div
            className="tabs-wrapper sticky top-0 z-10 flex items-center overflow-hidden border-b border-border bg-[var(--color-bg-white)] px-4 pt-2"
          >
            <div className="flex min-w-0 flex-1 overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
              <div className="flex gap-1 whitespace-nowrap">
                {openTabs.map((tab) => {
                  const active = tab.key === activeTab;
                  const closable = tab.key !== '/dashboard';
                  return (
                    <span
                      key={tab.key}
                      role="tab"
                      aria-selected={active}
                      tabIndex={0}
                      onClick={() => handleTabChange(tab.key)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') handleTabChange(tab.key);
                      }}
                      className={cn(
                        'group inline-flex cursor-pointer items-center gap-1.5 rounded-t-lg border border-b-0 px-3 py-2 text-sm transition-colors',
                        active
                          ? 'border-border bg-[var(--color-bg-base)] font-medium text-foreground'
                          : 'border-transparent text-muted-foreground hover:bg-accent hover:text-foreground',
                      )}
                    >
                      <span className="flex size-4 items-center [&_svg]:size-4">{tab.icon}</span>
                      <span>{tab.label}</span>
                      {closable && (
                        <button
                          type="button"
                          aria-label={`关闭 ${tab.label}`}
                          onClick={(e) => {
                            e.stopPropagation();
                            handleTabClose(tab.key);
                          }}
                          className="ml-0.5 rounded-sm p-0.5 opacity-0 transition-opacity hover:bg-accent group-hover:opacity-60 focus:opacity-100 cursor-pointer"
                        >
                          <X className="size-3.5" />
                        </button>
                      )}
                    </span>
                  );
                })}
              </div>
            </div>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon" className="ml-2 shrink-0" title="标签操作" aria-label="标签操作">
                  <MoreHorizontal />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="min-w-32">
                <DropdownMenuItem onClick={() => handleTabAction('refresh')}>
                  <RotateCw />
                  刷新当前
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem disabled={openTabs.length <= 1} onClick={() => handleTabAction('closeCurrent')}>
                  <X />
                  关闭当前
                </DropdownMenuItem>
                <DropdownMenuItem disabled={openTabs.length <= 1} onClick={() => handleTabAction('closeOther')}>
                  <X />
                  关闭其他
                </DropdownMenuItem>
                <DropdownMenuItem disabled={activeTabIndex <= 0} onClick={() => handleTabAction('closeLeft')}>
                  <X />
                  关闭左侧
                </DropdownMenuItem>
                <DropdownMenuItem
                  disabled={activeTabIndex >= openTabs.length - 1 || activeTabIndex < 0}
                  onClick={() => handleTabAction('closeRight')}
                >
                  <X />
                  关闭右侧
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem variant="destructive" onClick={() => handleTabAction('closeAll')}>
                  <XCircle />
                  关闭全部
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        )}

        {/* 内容区 */}
        <main className="dashboard-content fade-in p-4" style={{ minHeight: 'calc(100vh - 105px)' }}>
          {children}
        </main>
      </div>
    </div>
  );
}
