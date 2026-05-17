'use client';

import { useEffect, useState, ReactNode } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { Layout, Menu, Button, Spin, Avatar, Dropdown, Tabs, Space } from 'antd';
import {
  DashboardOutlined,
  ToolOutlined,
  ApiOutlined,
  ClusterOutlined,
  LogoutOutlined,
  FileTextOutlined,
  UserOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  CloseOutlined,
  ReloadOutlined,
  SettingOutlined,
  TeamOutlined,
  BookOutlined,
  SafetyOutlined,
  KeyOutlined,
  TableOutlined,
} from '@ant-design/icons';
import { useAuth } from '@/context/AuthContext';
import { TabContext } from '@/context/TabContext';

const { Header, Sider, Content } = Layout;

/** 标签页项接口 */
interface TabItem {
  /** 标签页唯一标识（路由路径） */
  key: string;
  /** 标签页显示名称 */
  label: string;
  /** 标签页图标 */
  icon: React.ReactNode;
}

/** 侧边栏菜单配置：定义所有可访问的页面及其图标和名称 */
const menuConfig: { key: string; icon: React.ReactNode; label: string }[] = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '仪表盘' },
  { key: '/dashboard/sequence', icon: <ApiOutlined />, label: '序列管理' },
  { key: '/dashboard/cluster', icon: <ClusterOutlined />, label: '集群管理' },
  { key: '/dashboard/system/user', icon: <TeamOutlined />, label: '用户管理' },
  { key: '/dashboard/system/role', icon: <SafetyOutlined />, label: '角色权限' },
  { key: '/dashboard/system/dict', icon: <BookOutlined />, label: '数据字典' },
  { key: '/dashboard/system/config', icon: <SettingOutlined />, label: '系统配置' },
  { key: '/dashboard/system/token', icon: <KeyOutlined />, label: 'Token管理' },
  { key: '/dashboard/system/table-schema', icon: <TableOutlined />, label: '表结构管理' },
  { key: '/dashboard/log', icon: <FileTextOutlined />, label: '日志管理' },
];

/**
 * 仪表盘布局组件
 * 包含侧边栏菜单、顶部导航栏、标签页栏和内容区域
 */
export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  /** 从认证上下文获取登录状态和用户信息 */
  const { isLogin, logout, loading, user } = useAuth();
  const router = useRouter();
  /** 当前路由路径 */
  const pathname = usePathname();
  /** 侧边栏折叠状态 */
  const [collapsed, setCollapsed] = useState(false);
  /** 已打开的标签页列表 */
  const [openTabs, setOpenTabs] = useState<TabItem[]>([]);
  /** 当前激活的标签页 */
  const [activeTab, setActiveTab] = useState(pathname);
  /** 标签页是否已初始化（避免首次加载重复添加） */
  const [initialized, setInitialized] = useState(false);

  /** 未登录时重定向到首页 */
  useEffect(() => {
    if (!loading && !isLogin) {
      router.push('/');
    }
  }, [isLogin, loading, router]);

  /** 路由变化时自动管理标签页：新增或切换 */
  useEffect(() => {
    if (!initialized) {
      // 首次加载：只添加当前页签
      const currentTab = menuConfig.find(item => item.key === pathname);
      if (currentTab) {
        setOpenTabs([currentTab]);
        setInitialized(true);
      }
    } else {
      // 后续导航：若标签不存在则添加，并切换激活状态
      const currentTab = menuConfig.find(item => item.key === pathname);
      if (currentTab && !openTabs.find(tab => tab.key === pathname)) {
        setOpenTabs(prev => [...prev, currentTab]);
      }
      setActiveTab(pathname);
    }
  }, [pathname, initialized, openTabs]);

  /** 加载中显示全屏loading */
  if (loading) {
    return (
      <div style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#fafbfc' }}>
        <Spin size="large" />
      </div>
    );
  }

  /** 未登录时不渲染任何内容（由useEffect处理跳转） */
  if (!isLogin) return null;

  /** 菜单点击：导航到对应页面 */
  const handleMenuClick = ({ key }: { key: string }) => {
    router.push(key);
  };

  /** 标签页切换：导航到对应页面 */
  const handleTabChange = (key: string) => {
    router.push(key);
  };

  /** 标签页编辑（关闭）：移除标签并自动切换到最后一个或仪表盘 */
  const handleTabEdit = (targetKey: any, action: 'remove' | 'add') => {
    if (action === 'remove' && typeof targetKey === 'string') {
      const newTabs = openTabs.filter(tab => tab.key !== targetKey);
      setOpenTabs(newTabs);
      if (activeTab === targetKey && newTabs.length > 0) {
        router.push(newTabs[newTabs.length - 1].key);
      } else if (newTabs.length === 0) {
        router.push('/dashboard');
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

  /** 将菜单配置转换为Ant Design Menu组件所需格式 */
  const menuItems = menuConfig.map(item => ({
    key: item.key,
    icon: item.icon,
    label: item.label,
  }));

  /** 将标签页数据转换为Ant Design Tabs组件所需格式 */
  const tabItems = openTabs.map(tab => ({
    key: tab.key,
    label: (
      <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        {tab.icon}
        <span>{tab.label}</span>
      </span>
    ),
  }));

  /** 标签页上下文值：提供给子组件使用 */
  const tabContextValue = {
    activeTab,
    openTabs,
    setActiveTab,
    addTab: () => {},
    removeTab: (key: string) => {
      const newTabs = openTabs.filter(tab => tab.key !== key);
      setOpenTabs(newTabs);
      if (activeTab === key && newTabs.length > 0) {
        router.push(newTabs[newTabs.length - 1].key);
      }
    },
  };

  return (
    <TabContext.Provider value={tabContextValue}>
      <Layout className="dashboard-layout" style={{ minHeight: '100vh' }}>
        <Sider 
          width={220} 
          collapsedWidth={80}
          collapsible 
          collapsed={collapsed}
          onCollapse={setCollapsed}
          className="dashboard-sider"
          trigger={null}
          style={{ position: 'fixed', left: 0, top: 0, bottom: 0, zIndex: 100 }}
        >
          <div className="sider-logo">
            {collapsed ? (
              <h1 style={{ color: '#fff', fontSize: 24, fontWeight: 700, margin: 0 }}>X</h1>
            ) : (
              <h1>Astral<span>.</span></h1>
            )}
          </div>
          <Menu
            mode="inline"
            selectedKeys={[pathname]}
            items={menuItems}
            onClick={handleMenuClick}
            theme="dark"
            inlineCollapsed={collapsed}
            style={{ 
              borderRight: 'none',
              marginTop: 8,
            }}
          />
        </Sider>
        <Layout style={{ marginLeft: collapsed ? 80 : 220, transition: 'margin-left 0.2s' }}>
          <Header className="dashboard-header">
            <Button
              type="text"
              icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => setCollapsed(!collapsed)}
              style={{ fontSize: 16, marginRight: 16 }}
            />
            <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
              <div className="header-user" style={{ cursor: 'pointer' }}>
                <Avatar 
                  size={36} 
                  style={{ 
                    background: 'linear-gradient(135deg, #4a90d9 0%, #3a7bc8 100%)',
                    fontWeight: 600 
                  }}
                  icon={<UserOutlined />}
                />
                <span style={{ fontWeight: 500, color: '#303133' }}>
                  {user?.username || '管理员'}
                </span>
              </div>
            </Dropdown>
          </Header>
          <div className="tabs-wrapper" style={{ 
            background: '#fff', 
            padding: '8px 16px 0', 
            borderBottom: '1px solid #ebeef5',
            position: 'sticky',
            top: 64,
            zIndex: 10,
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center'
          }}>
            <Tabs
              activeKey={activeTab}
              onChange={handleTabChange}
              type="editable-card"
              onEdit={handleTabEdit}
              hideAdd
              items={tabItems}
              style={{ marginBottom: -1, flex: 1 }}
            />
            {openTabs.length > 1 && (
              <Space size="small" style={{ marginLeft: 8 }}>
                <Button 
                  type="text" 
                  size="small" 
                  icon={<CloseOutlined />} 
                  onClick={handleCloseOtherTabs}
                  title="关闭其他"
                />
                <Button 
                  type="text" 
                  size="small" 
                  icon={<ReloadOutlined />} 
                  onClick={handleCloseAllTabs}
                  title="关闭全部"
                />
              </Space>
            )}
          </div>
          <Content className="dashboard-content fade-in" style={{ minHeight: 'calc(100vh - 130px)' }}>
            {children}
          </Content>
        </Layout>
      </Layout>
    </TabContext.Provider>
  );
}