'use client';

import Image from 'next/image';
import Link from 'next/link';
import {
  ArrowDown,
  ArrowRight,
  ArrowUpRight,
  AudioLines,
  ChartNoAxesCombined,
  Database,
  FolderOpen,
  Headphones,
  Image as ImageIcon,
  LayoutDashboard,
  MessageSquare,
  Moon,
  Orbit,
  PlugZap,
  ShieldCheck,
  Sun,
  Users,
} from 'lucide-react';

import { useAuth } from '@/context/AuthContext';
import { Button } from '@/components/ui/button';
import { useTheme } from './providers';
import styles from './page.module.css';

/** 首页的所有轻听入口统一直达独立官网，不经过旧的 /lightlisten 介绍页。 */
const QUIET_MUSIC_URL = 'https://quietmusic.canace.cn/';

const FEATURES = [
  {
    icon: Users,
    title: '用户与权限',
    desc: '统一管理用户、角色与访问权限，让每个人各司其职。',
  },
  {
    icon: ShieldCheck,
    title: '日志与审计',
    desc: '记录登录与操作轨迹，支持会话管理和问题追踪。',
  },
  {
    icon: Database,
    title: '数据与配置',
    desc: '集中维护数据字典、系统配置与表结构，减少重复工作。',
  },
  {
    icon: MessageSquare,
    title: '消息与反馈',
    desc: '连接用户反馈、回复与通知，让沟通有回应。',
  },
  {
    icon: ChartNoAxesCombined,
    title: '监控与统计',
    desc: '汇集访问、设备和接口指标，了解服务的运行情况。',
  },
  {
    icon: PlugZap,
    title: '插件与扩展',
    desc: '按需启用业务模块，让平台随实际需求逐步生长。',
  },
];

const TECH_STACK = [
  { title: '后端服务', items: ['Java 25', 'Spring Boot 4.1', 'Sa-Token', 'MyBatis-Plus'] },
  { title: '前端体验', items: ['Next.js 16', 'React 19', 'TypeScript', 'shadcn/ui'] },
  { title: '数据支持', items: ['PostgreSQL', 'Redis'] },
];

/** 面向访客的服务首页；登录后仍停留在首页，由用户自主进入控制台。 */
export default function HomePage() {
  const { isLogin } = useAuth();
  const { toggle } = useTheme();
  const consoleHref = isLogin ? '/dashboard' : '/login';
  const storageHref = isLogin ? '/files' : '/login';

  return (
    <div className="bg-background text-foreground min-h-dvh">
      <a
        href="#main-content"
        className={`${styles.skipLink} bg-primary text-primary-foreground rounded-lg px-4 py-3`}
      >
        跳至主要内容
      </a>

      <header className="border-border bg-background sticky top-0 z-20 border-b">
        <div className="mx-auto flex min-h-18 max-w-6xl items-center justify-between gap-3 px-5 sm:px-8">
          <Link
            href="/"
            aria-label="Astral 首页"
            className={`${styles.focusLink} flex shrink-0 items-center gap-2.5 rounded-lg`}
          >
            <span className="bg-primary text-primary-foreground flex size-9 items-center justify-center rounded-xl">
              <Orbit className="size-5" aria-hidden="true" />
            </span>
            <span className="text-xl font-semibold tracking-tight">
              Astral<span className="text-primary">.</span>
            </span>
          </Link>

          <nav
            aria-label="首页导航"
            className="text-muted-foreground hidden items-center gap-7 text-sm md:flex"
          >
            <a
              href="#applications"
              className={`${styles.focusLink} hover:text-foreground rounded-sm transition-colors`}
            >
              应用入口
            </a>
            <a
              href="#platform"
              className={`${styles.focusLink} hover:text-foreground rounded-sm transition-colors`}
            >
              平台能力
            </a>
            <a
              href={QUIET_MUSIC_URL}
              className={`${styles.focusLink} hover:text-foreground inline-flex items-center gap-1 rounded-sm transition-colors`}
            >
              轻听音乐 <ArrowUpRight className="size-3.5" aria-hidden="true" />
            </a>
          </nav>

          <div className="flex shrink-0 items-center gap-2">
            <Button
              variant="ghost"
              size="icon"
              onClick={toggle}
              aria-label="切换明暗主题"
              title="切换明暗主题"
            >
              <Sun className="hidden dark:block" aria-hidden="true" />
              <Moon className="dark:hidden" aria-hidden="true" />
            </Button>
            <Button asChild variant="outline" className="rounded-full px-4">
              <Link href={consoleHref} prefetch={false}>
                <LayoutDashboard aria-hidden="true" />
                {isLogin ? '进入控制台' : '管理登录'}
              </Link>
            </Button>
          </div>
        </div>
      </header>

      <main id="main-content" className="mx-auto max-w-6xl px-5 sm:px-8">
        <section
          aria-labelledby="hero-title"
          className="grid items-center gap-8 pt-14 pb-12 sm:pt-20 sm:pb-16 lg:grid-cols-[1.3fr_1fr] lg:gap-16 lg:pt-24 lg:pb-20"
        >
          <div>
            <p className="text-primary mb-5 flex items-center gap-2 text-sm font-medium">
              <span className="bg-primary h-px w-6" aria-hidden="true" />
              ASTRAL · 应用与服务
            </p>
            <h1
              id="hero-title"
              className="text-[clamp(2.5rem,5vw,4rem)] leading-[1.2] font-semibold tracking-tight"
            >
              日常所需，
              <br />
              <span className="text-primary">触手可及。</span>
            </h1>
            <p className="text-muted-foreground mt-6 max-w-md text-base leading-8">
              听音乐、管理文件，或进入管理控制台。
              <br className="hidden sm:block" />
              从这里，找到你需要的应用与服务。
            </p>
            <div className="mt-8 flex flex-wrap items-center gap-3">
              <Button asChild size="lg" className="h-11 rounded-full px-6">
                <a href="#applications">
                  探索应用 <ArrowDown aria-hidden="true" />
                </a>
              </Button>
              <Button asChild variant="ghost" size="lg" className="h-11 rounded-full">
                <a href="#platform">
                  了解平台 <ArrowRight aria-hidden="true" />
                </a>
              </Button>
            </div>
          </div>

          <aside
            aria-label="服务导航"
            className="border-border bg-muted/40 rounded-2xl border p-5 sm:p-7"
          >
            <p className="text-muted-foreground mb-5 text-xs font-medium tracking-[0.18em]">
              从喜欢的事开始
            </p>
            <a
              href={QUIET_MUSIC_URL}
              className={`${styles.focusLink} group flex items-center gap-4 rounded-xl py-3`}
            >
              <span className="border-border bg-background text-primary flex size-11 shrink-0 items-center justify-center rounded-xl border">
                <Headphones className="size-5" aria-hidden="true" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-medium">给日常一点旋律</span>
                <span className="text-muted-foreground mt-1 block text-xs">打开轻听音乐官网</span>
              </span>
              <ArrowUpRight
                className="text-muted-foreground group-hover:text-primary size-4 shrink-0 transition-colors"
                aria-hidden="true"
              />
            </a>
            <div className="border-border my-2 border-t" />
            <Link
              href={storageHref}
              prefetch={false}
              className={`${styles.focusLink} group flex items-center gap-4 rounded-xl py-3`}
            >
              <span className="border-border bg-background text-primary flex size-11 shrink-0 items-center justify-center rounded-xl border">
                <FolderOpen className="size-5" aria-hidden="true" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-medium">让文件井井有条</span>
                <span className="text-muted-foreground mt-1 block text-xs">
                  {isLogin ? '进入我的文件' : '登录后管理与分享文件'}
                </span>
              </span>
              <ArrowRight
                className="text-muted-foreground group-hover:text-primary size-4 shrink-0 transition-colors"
                aria-hidden="true"
              />
            </Link>
          </aside>
        </section>

        <section
          id="applications"
          aria-labelledby="applications-title"
          className="scroll-mt-24 pb-16 sm:pb-20"
        >
          <div className="mb-7 flex flex-wrap items-end justify-between gap-3">
            <div>
              <p className="text-primary mb-2 text-xs font-medium tracking-[0.14em]">EXPLORE</p>
              <h2
                id="applications-title"
                className="text-2xl font-semibold tracking-tight sm:text-3xl"
              >
                选一个，开始使用
              </h2>
            </div>
            <p className="text-muted-foreground text-sm">专注日常，让每次打开都更简单。</p>
          </div>

          <div className="grid gap-5 md:grid-cols-2">
            <a
              href={QUIET_MUSIC_URL}
              className={`${styles.appCard} group border-border bg-card relative flex flex-col overflow-hidden rounded-2xl border p-6 sm:p-8`}
            >
              <div className="relative z-10 flex items-start justify-between gap-4">
                <Image
                  src="/lightlisten/icon.png"
                  alt=""
                  width={56}
                  height={56}
                  className="rounded-2xl"
                />
                <span className="border-border bg-background text-muted-foreground rounded-full border px-3 py-1 text-xs">
                  音乐应用
                </span>
              </div>
              <div className="relative z-10 mt-7 mb-9">
                <h3 className="text-2xl font-semibold tracking-tight">轻听音乐</h3>
                <p className="text-muted-foreground mt-1 text-xs tracking-[0.12em]">QUIET MUSIC</p>
                <p className="text-muted-foreground mt-4 max-w-sm text-sm leading-7">
                  发现喜欢的音乐，收藏常听的歌。
                  <br />
                  在手机与电脑上，享受自己的聆听时光。
                </p>
              </div>
              <div className="border-border relative z-10 mt-auto flex items-center justify-between gap-3 border-t pt-5">
                <span className="text-primary text-sm font-medium">前往轻听官网</span>
                <ArrowUpRight
                  className="text-primary size-5 transition-transform group-hover:translate-x-0.5 group-hover:-translate-y-0.5"
                  aria-hidden="true"
                />
              </div>
              <AudioLines
                className="text-primary/[0.04] pointer-events-none absolute top-20 -right-4 size-44"
                strokeWidth={1}
                aria-hidden="true"
              />
            </a>

            <Link
              href={storageHref}
              prefetch={false}
              className={`${styles.appCard} group border-border bg-card relative flex flex-col overflow-hidden rounded-2xl border p-6 sm:p-8`}
            >
              <div className="relative z-10 flex items-start justify-between gap-4">
                <span className="bg-muted text-primary flex size-14 items-center justify-center rounded-2xl">
                  <ImageIcon className="size-7" strokeWidth={1.5} aria-hidden="true" />
                </span>
                <span className="border-border bg-background text-muted-foreground rounded-full border px-3 py-1 text-xs">
                  文件工具
                </span>
              </div>
              <div className="relative z-10 mt-7 mb-9">
                <h3 className="text-2xl font-semibold tracking-tight">文件管理</h3>
                <p className="text-muted-foreground mt-1 text-xs tracking-[0.12em]">FILE MANAGER</p>
                <p className="text-muted-foreground mt-4 max-w-sm text-sm leading-7">
                  集中存放文件，按文件夹整理内容。
                  <br />
                  从上传、管理到分享，让文件随用随取。
                </p>
              </div>
              <div className="border-border relative z-10 mt-auto flex items-center justify-between gap-3 border-t pt-5">
                <span className="text-primary text-sm font-medium">
                  {isLogin ? '进入我的文件' : '登录后使用'}
                </span>
                <ArrowRight
                  className="text-primary size-5 transition-transform group-hover:translate-x-0.5"
                  aria-hidden="true"
                />
              </div>
              <FolderOpen
                className="text-primary/[0.04] pointer-events-none absolute top-20 -right-4 size-44"
                strokeWidth={1}
                aria-hidden="true"
              />
            </Link>
          </div>
        </section>

        <section
          id="platform"
          aria-labelledby="platform-title"
          className="border-border scroll-mt-24 border-t py-12 sm:py-16"
        >
          <div className="flex flex-col justify-between gap-6 sm:flex-row sm:items-center">
            <div>
              <p className="text-primary mb-2 text-xs font-medium tracking-[0.14em]">
                BUILT ON ASTRAL
              </p>
              <h2 id="platform-title" className="text-2xl font-semibold tracking-tight sm:text-3xl">
                应用背后，服务有序运转
              </h2>
              <p className="text-muted-foreground mt-3 text-sm leading-7">
                统一的管理平台，连接用户、数据与业务扩展。
              </p>
            </div>
            <Button
              asChild
              variant="outline"
              className="h-11 self-start rounded-full px-5 sm:shrink-0"
            >
              <Link href={consoleHref} prefetch={false}>
                {isLogin ? '进入管理控制台' : '登录管理控制台'}
                <ArrowRight aria-hidden="true" />
              </Link>
            </Button>
          </div>

          <div className="mt-9 grid gap-x-10 gap-y-8 sm:grid-cols-2 lg:grid-cols-3">
            {FEATURES.map(({ icon: Icon, title, desc }) => (
              <article key={title} className="flex items-start gap-3.5">
                <Icon
                  className="text-primary mt-1 size-5 shrink-0"
                  strokeWidth={1.6}
                  aria-hidden="true"
                />
                <div>
                  <h3 className="text-sm font-semibold">{title}</h3>
                  <p className="text-muted-foreground mt-2 text-sm leading-7">{desc}</p>
                </div>
              </article>
            ))}
          </div>

          <div className="border-border bg-muted/40 mt-12 grid gap-6 rounded-2xl border p-5 sm:p-6 lg:grid-cols-[1.3fr_1.3fr_1fr]">
            {TECH_STACK.map(({ title, items }) => (
              <div key={title}>
                <h3 className="text-muted-foreground mb-3 text-xs font-medium">{title}</h3>
                <ul className="flex flex-wrap gap-x-3 gap-y-2">
                  {items.map((item) => (
                    <li key={item} className="text-xs leading-5">
                      {item}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </section>
      </main>

      <footer className="border-border bg-muted/30 border-t">
        <div className="text-muted-foreground mx-auto flex max-w-6xl flex-col justify-between gap-5 px-5 py-7 text-xs sm:flex-row sm:items-center sm:px-8">
          <p>
            <span className="text-foreground font-semibold">Astral</span>
            <span className="mx-2" aria-hidden="true">
              /
            </span>
            连接应用与服务
          </p>
          <nav aria-label="页脚导航" className="flex flex-wrap items-center gap-x-5 gap-y-3">
            <a
              href={QUIET_MUSIC_URL}
              className={`${styles.focusLink} hover:text-foreground rounded-sm`}
            >
              轻听音乐官网 ↗
            </a>
            <Link
              href={storageHref}
              prefetch={false}
              className={`${styles.focusLink} hover:text-foreground rounded-sm`}
            >
              文件管理
            </Link>
            <Link
              href={consoleHref}
              prefetch={false}
              className={`${styles.focusLink} hover:text-foreground rounded-sm`}
            >
              {isLogin ? '管理控制台' : '管理登录'}
            </Link>
          </nav>
        </div>
      </footer>
    </div>
  );
}
