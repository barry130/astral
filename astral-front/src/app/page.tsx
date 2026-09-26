'use client';

import { useRouter } from 'next/navigation';
import { Button } from 'antd';
import {
  LoginOutlined,
  DashboardOutlined,
  SafetyOutlined,
  TeamOutlined,
  AppstoreOutlined,
  AudioOutlined,
  CustomerServiceOutlined,
  CommentOutlined,
  DatabaseOutlined,
  LineChartOutlined,
  ApiOutlined,
  CloudServerOutlined,
  RocketOutlined,
  PictureOutlined,
} from '@ant-design/icons';
import { useAuth } from '@/context/AuthContext';

/**
 * 项目介绍首页（根路由 /）
 * 静态展示项目定位、核心功能、技术栈与插件体系；
 * 顶部按钮根据登录状态指向控制台或登录页，不再自动跳转。
 */

const FEATURES = [
  {
    icon: <TeamOutlined />,
    title: '用户与权限',
    desc: '用户管理、角色权限、RBAC 模型、权限树，Sa-Token 认证与会话管理',
  },
  {
    icon: <SafetyOutlined />,
    title: '安全审计',
    desc: '操作日志、登录日志 AOP 异步记录，Token 在线管理与吊销',
  },
  {
    icon: <DatabaseOutlined />,
    title: '数据管理',
    desc: '数据字典、系统配置、表结构管理、代码生成、SQL 生成与导出',
  },
  {
    icon: <CloudServerOutlined />,
    title: '基础设施',
    desc: '邮件服务、序列生成（Snowflake / 号段 / Redis 等 5 种算法）、系统监控',
  },
  {
    icon: <LineChartOutlined />,
    title: '全端统计',
    desc: '客户端上报采集、访问量 / 设备 / 接口指标、错误日志聚合',
  },
  {
    icon: <AppstoreOutlined />,
    title: '插件体系',
    desc: 'SPI 扩展 + 插件注册中心 + 前端导航注入，管理页一键启停',
  },
];

interface PluginCard {
  icon: React.ReactNode;
  title: string;
  desc: string;
  /** 有用户侧页面可进入的插件提供跳转路径 */
  href?: string;
  action?: string;
  /** 公开页：未登录也可直接进入（如轻听音乐介绍页） */
  public?: boolean;
}

const PLUGINS: PluginCard[] = [
  {
    icon: <AudioOutlined />,
    title: '轻听音乐（qt）',
    desc: 'App 用户体系、公告、版本更新、打卡、收藏，提供 /api/v1/app/** 开放接口',
    href: '/lightlisten',
    action: '了解轻听',
    public: true,
  },
  {
    icon: <CommentOutlined />,
    title: '反馈（feedback）',
    desc: '用户反馈、管理员回复、站内消息通知',
  },
  {
    icon: <ApiOutlined />,
    title: '文件存储（storage）',
    desc: 'Telegram + Cloudflare Worker 图床，文件夹授权与分享边界',
    href: '/imgbed',
    action: '进入图床',
  },
];

const TECH_STACK = [
  'Spring Boot 3.2',
  'Java 21',
  'Sa-Token',
  'MyBatis-Plus',
  'PostgreSQL',
  'Redis',
  'Next.js 14',
  'React 18',
  'TypeScript',
  'Ant Design 5',
];

export default function HomePage() {
  const { isLogin } = useAuth();
  const router = useRouter();

  return (
    <div className="landing-container">
      {/* 顶部导航 */}
      <header className="landing-header">
        <div className="landing-header-inner">
          <div className="landing-logo">
            <div className="landing-logo-mark">
              <RocketOutlined />
            </div>
            <span className="landing-logo-name">Astral</span>
          </div>
          <div className="landing-header-actions">
            <Button
              icon={<CustomerServiceOutlined />}
              onClick={() => router.push('/lightlisten')}
            >
              轻听音乐
            </Button>
            <Button
              icon={<PictureOutlined />}
              onClick={() => router.push(isLogin ? '/imgbed' : '/login')}
            >
              图床
            </Button>
            <Button
              type="text"
              onClick={() => router.push(isLogin ? '/dashboard' : '/login')}
            >
              {isLogin ? '返回控制台' : '后台登录'}
            </Button>
            <Button
              type="primary"
              icon={isLogin ? <DashboardOutlined /> : <LoginOutlined />}
              onClick={() => router.push(isLogin ? '/dashboard' : '/login')}
            >
              {isLogin ? '进入控制台' : '登录系统'}
            </Button>
          </div>
        </div>
      </header>

      {/* Hero */}
      <section className="landing-hero">
        <h1 className="landing-hero-title fade-in-up">
          Astral 后台管理系统
        </h1>
        <p className="landing-hero-subtitle fade-in-up stagger-1">
          基于 Spring Boot 3 + Next.js 14 的全栈管理平台
        </p>
        <p className="landing-hero-desc fade-in-up stagger-2">
          前后端分离架构，内置 RBAC 权限模型、操作日志审计、Sa-Token 认证
          与可扩展插件体系，开箱即用。
        </p>
        <div className="landing-hero-actions fade-in-up stagger-3">
          <Button
            type="primary"
            size="large"
            icon={isLogin ? <DashboardOutlined /> : <LoginOutlined />}
            onClick={() => router.push(isLogin ? '/dashboard' : '/login')}
          >
            {isLogin ? '进入控制台' : '登录体验'}
          </Button>
        </div>
      </section>

      {/* 核心功能 */}
      <section className="landing-section">
        <h2 className="landing-section-title">核心功能</h2>
        <div className="landing-grid landing-grid-3">
          {FEATURES.map((f) => (
            <div key={f.title} className="landing-card">
              <div className="landing-card-icon">{f.icon}</div>
              <h3 className="landing-card-title">{f.title}</h3>
              <p className="landing-card-desc">{f.desc}</p>
            </div>
          ))}
        </div>
      </section>

      {/* 业务插件 */}
      <section className="landing-section">
        <h2 className="landing-section-title">业务插件</h2>
        <p className="landing-section-desc">
          插件实现统一收敛在 astral-plugin 模块，通过 SPI 注册中心自动发现，后台可启停。
        </p>
        <div className="landing-grid landing-grid-3">
          {PLUGINS.map((p) => (
            <div
              key={p.title}
              className={`landing-card${p.href ? ' landing-card-link' : ''}`}
              onClick={p.href ? () => router.push(p.public || isLogin ? p.href! : '/login') : undefined}
            >
              <div className="landing-card-icon">{p.icon}</div>
              <h3 className="landing-card-title">{p.title}</h3>
              <p className="landing-card-desc">{p.desc}</p>
              {p.href && <span className="landing-card-action">{p.action} →</span>}
            </div>
          ))}
        </div>
      </section>

      {/* 技术栈 */}
      <section className="landing-section">
        <h2 className="landing-section-title">技术栈</h2>
        <div className="landing-tags">
          {TECH_STACK.map((t) => (
            <span key={t} className="landing-tag">{t}</span>
          ))}
        </div>
      </section>

      {/* 页脚 */}
      <footer className="landing-footer">
        <p>Astral Management System · Spring Boot 3 + Next.js 14</p>
        <p className="landing-footer-secondary">
          默认账号 admin / admin · 详见项目 README
        </p>
      </footer>
    </div>
  );
}
