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
  Images,
  LayoutDashboard,
  MessageSquare,
  Moon,
  PlugZap,
  ShieldCheck,
  Sun,
  Users,
} from 'lucide-react';

import { useAuth } from '@/context/AuthContext';
import { BrandLockup } from '@/components/brand';
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

const WORKFLOWS = [
  {
    icon: ShieldCheck,
    title: '身份与权限',
    description: '从登录、角色到会话，访问边界清晰可见。',
  },
  {
    icon: ChartNoAxesCombined,
    title: '监控与响应',
    description: '从资源、接口到操作日志，运行状态持续可追踪。',
  },
  {
    icon: PlugZap,
    title: '业务与扩展',
    description: '以统一数据与插件体系承载不同应用模块。',
  },
] as const;

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

      <header className="sticky top-0 z-20 border-b border-[color:var(--color-nav-border)] bg-[var(--color-nav-bg)] text-[color:var(--color-nav-text)]">
        <div className="mx-auto flex min-h-[72px] max-w-7xl items-center justify-between gap-3 px-5 sm:px-8">
          <Link
            href="/"
            aria-label="Astral 首页"
            className={`${styles.focusLink} flex shrink-0 rounded-lg`}
          >
            <BrandLockup inverted subtitle={null} size="sm" />
          </Link>

          <nav
            aria-label="首页导航"
            className="hidden items-center gap-7 text-[13px] text-[color:var(--color-nav-muted)] md:flex"
          >
            <a
              href="#applications"
              className={`${styles.focusLink} rounded-sm transition-colors hover:text-[color:var(--color-nav-text)]`}
            >
              应用入口
            </a>
            <a
              href="#platform"
              className={`${styles.focusLink} rounded-sm transition-colors hover:text-[color:var(--color-nav-text)]`}
            >
              平台能力
            </a>
            <a
              href={QUIET_MUSIC_URL}
              className={`${styles.focusLink} inline-flex items-center gap-1 rounded-sm transition-colors hover:text-[color:var(--color-nav-text)]`}
            >
              轻听音乐 <ArrowUpRight className="size-3.5" aria-hidden="true" />
            </a>
          </nav>

          <div className="flex items-center gap-2">
            <Button
              variant="ghost"
              size="icon"
              onClick={toggle}
              title="切换明暗主题"
              aria-label="切换明暗主题"
              className="size-9 text-[color:var(--color-nav-muted)] hover:bg-[var(--color-nav-surface)] hover:text-[color:var(--color-nav-text)]"
            >
              <Moon className="size-4 dark:hidden" aria-hidden="true" />
              <Sun className="hidden size-4 dark:block" aria-hidden="true" />
            </Button>
            <Button asChild size="sm" className="h-9 px-3 sm:px-4">
              <Link
                href={consoleHref}
                prefetch={false}
                className={`${styles.focusLink} rounded-lg`}
              >
                {isLogin ? '进入控制台' : '登录控制台'}
                <ArrowRight className="size-4" aria-hidden="true" />
              </Link>
            </Button>
          </div>
        </div>
      </header>

      <main id="main-content" tabIndex={-1} className="focus:outline-none">
        <section className="border-b border-[color:var(--color-nav-border)] bg-[var(--color-nav-bg)] text-[color:var(--color-nav-text)]">
          <div className="mx-auto grid max-w-7xl gap-14 px-5 py-16 sm:px-8 sm:py-20 lg:grid-cols-[minmax(0,1.25fr)_minmax(340px,0.75fr)] lg:items-center lg:py-24">
            <div>
              <p className="flex items-center gap-2 text-sm text-[color:var(--color-nav-muted)]">
                <span className="size-2 rounded-full bg-[var(--color-nav-accent)]" aria-hidden />
                应用、数据与运维的统一入口
              </p>
              <h1 className="mt-7 text-[36px] leading-[1.15] font-semibold tracking-[-0.05em] sm:text-[56px] lg:text-[64px]">
                <span className="block">把应用与服务，</span>
                <span className="block">收进清晰的工作台。</span>
              </h1>
              <p className="mt-7 max-w-xl text-[15px] leading-8 text-[color:var(--color-nav-muted)]">
                Astral 连接轻听音乐、图床与管理后台，并将用户权限、服务监控和业务数据
                组织在同一套工作流中。
              </p>
              <div className="mt-9 flex flex-wrap items-center gap-x-7 gap-y-4">
                <Button asChild size="lg" className="h-11 px-5">
                  <Link
                    href={consoleHref}
                    prefetch={false}
                    className={`${styles.focusLink} rounded-[10px]`}
                  >
                    {isLogin ? '进入控制台' : '登录管理后台'}
                    <ArrowRight className="size-4" aria-hidden="true" />
                  </Link>
                </Button>
                <a
                  href="#applications"
                  className={`${styles.focusLink} inline-flex items-center gap-2 rounded-lg text-sm text-[color:var(--color-nav-text)]`}
                >
                  浏览应用
                  <ArrowDown
                    className="size-4 text-[color:var(--color-nav-accent)]"
                    aria-hidden="true"
                  />
                </a>
              </div>
            </div>

            <aside aria-label="平台工作流" className="min-w-0">
              <p className="mb-4 text-xs text-[color:var(--color-nav-muted)]">
                一个控制台，覆盖完整管理工作流
              </p>
              <div className="border-y border-[color:var(--color-nav-border)]">
                {WORKFLOWS.map(({ icon: Icon, title, description }) => (
                  <div
                    key={title}
                    className="flex gap-4 border-b border-[color:var(--color-nav-border)] py-5 last:border-b-0"
                  >
                    <Icon
                      className="mt-0.5 size-5 shrink-0 text-[color:var(--color-nav-accent)]"
                      strokeWidth={1.7}
                      aria-hidden="true"
                    />
                    <div>
                      <h2 className="text-sm font-medium">{title}</h2>
                      <p className="mt-2 text-xs leading-6 text-[color:var(--color-nav-muted)]">
                        {description}
                      </p>
                    </div>
                  </div>
                ))}
              </div>
            </aside>
          </div>
        </section>

        <section
          id="applications"
          aria-labelledby="applications-title"
          className="scroll-mt-24 bg-[var(--color-bg-base)] py-16 sm:py-20"
        >
          <div className="mx-auto max-w-7xl px-5 sm:px-8">
            <div className="mb-8 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
              <div>
                <p className="text-sm font-medium text-[color:var(--color-brand-text)]">应用入口</p>
                <h2
                  id="applications-title"
                  className="mt-3 text-[32px] font-semibold tracking-[-0.035em] sm:text-[36px]"
                >
                  从这里，进入具体工作。
                </h2>
                <p className="text-muted-foreground mt-3 max-w-2xl text-sm leading-7">
                  内容产品与管理能力各有独立入口，身份状态由当前浏览器会话保持。
                </p>
              </div>
              <Link
                href={consoleHref}
                prefetch={false}
                className={`${styles.focusLink} inline-flex shrink-0 items-center gap-2 rounded-lg text-sm font-medium text-[color:var(--color-brand-text)]`}
              >
                {isLogin ? '返回管理控制台' : '管理员登录'}
                <ArrowRight className="size-4" aria-hidden="true" />
              </Link>
            </div>

            <div className="bg-card overflow-hidden rounded-[14px] border border-[color:var(--color-border)] shadow-[var(--shadow-sm)]">
              <Link
                href={QUIET_MUSIC_URL}
                prefetch={false}
                className={`${styles.appCard} group bg-card grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-4 border-b border-[color:var(--color-border-light)] p-5 sm:gap-6 sm:px-7 sm:py-6`}
              >
                <span className="flex size-12 items-center justify-center overflow-hidden rounded-xl border border-[color:var(--color-border-light)] bg-[var(--color-bg-tint)] sm:size-14">
                  <Image
                    src="/lightlisten/icon.png"
                    alt="轻听音乐"
                    width={56}
                    height={56}
                    className="size-full object-cover dark:brightness-[0.88] dark:contrast-[1.06]"
                  />
                </span>
                <span className="min-w-0">
                  <span className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                    <span className="text-lg font-semibold tracking-[-0.02em]">轻听音乐</span>
                    <span className="text-muted-foreground text-[11px]">独立官网</span>
                  </span>
                  <span className="text-muted-foreground mt-2 block text-[13px] leading-6">
                    打开 Quiet Music，继续你的歌单、收藏与聆听体验。
                  </span>
                </span>
                <span className="text-muted-foreground hidden items-center gap-3 text-xs sm:flex">
                  访问官网
                  <ArrowUpRight className={`${styles.appArrow} size-5`} aria-hidden="true" />
                </span>
                <ArrowUpRight
                  className={`${styles.appArrow} size-5 sm:hidden`}
                  aria-hidden="true"
                />
              </Link>

              <Link
                href="/lightlisten/imgbed"
                prefetch={false}
                className={`${styles.appCard} group bg-card grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-4 border-b border-[color:var(--color-border-light)] p-5 sm:gap-6 sm:px-7 sm:py-6`}
              >
                <span className="flex size-12 items-center justify-center rounded-xl border border-[color:var(--color-border-light)] bg-[var(--color-bg-tint)] text-[color:var(--color-brand-text)] sm:size-14">
                  <Images className="size-6" strokeWidth={1.7} aria-hidden="true" />
                </span>
                <span className="min-w-0">
                  <span className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                    <span className="text-lg font-semibold tracking-[-0.02em]">轻图</span>
                    <span className="text-muted-foreground text-[11px]">图片工作区</span>
                  </span>
                  <span className="text-muted-foreground mt-2 block text-[13px] leading-6">
                    集中上传、整理和取用图片，让素材管理保持简单。
                  </span>
                </span>
                <span className="text-muted-foreground hidden items-center gap-3 text-xs sm:flex">
                  打开图床
                  <ArrowUpRight className={`${styles.appArrow} size-5`} aria-hidden="true" />
                </span>
                <ArrowUpRight
                  className={`${styles.appArrow} size-5 sm:hidden`}
                  aria-hidden="true"
                />
              </Link>

              <Link
                href={consoleHref}
                prefetch={false}
                className={`${styles.appCard} group bg-card grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-4 p-5 sm:gap-6 sm:px-7 sm:py-6`}
              >
                <span className="flex size-12 items-center justify-center rounded-xl border border-[color:var(--color-border-light)] bg-[var(--color-bg-tint)] text-[color:var(--color-brand-text)] sm:size-14">
                  <LayoutDashboard className="size-6" strokeWidth={1.7} aria-hidden="true" />
                </span>
                <span className="min-w-0">
                  <span className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                    <span className="text-lg font-semibold tracking-[-0.02em]">管理控制台</span>
                    <span className="text-muted-foreground text-[11px]">
                      {isLogin ? '当前已登录' : '账号入口'}
                    </span>
                  </span>
                  <span className="text-muted-foreground mt-2 block text-[13px] leading-6">
                    {isLogin
                      ? '返回工作台，继续处理权限、监控、配置与业务管理。'
                      : '登录后进入工作台，使用权限、监控、配置与业务管理能力。'}
                  </span>
                </span>
                <span className="text-muted-foreground hidden items-center gap-3 text-xs sm:flex">
                  {isLogin ? '进入工作台' : '登录'}
                  <ArrowRight className={`${styles.appArrow} size-5`} aria-hidden="true" />
                </span>
                <ArrowRight className={`${styles.appArrow} size-5 sm:hidden`} aria-hidden="true" />
              </Link>
            </div>
          </div>
        </section>

        <section
          id="platform"
          aria-labelledby="platform-title"
          className="bg-background scroll-mt-24 border-t border-[color:var(--color-border-light)] py-16 sm:py-20"
        >
          <div className="mx-auto max-w-7xl px-5 sm:px-8">
            <div className="max-w-2xl">
              <p className="text-sm font-medium text-[color:var(--color-brand-text)]">平台能力</p>
              <h2
                id="platform-title"
                className="mt-3 text-[32px] font-semibold tracking-[-0.035em] sm:text-[36px]"
              >
                复杂留在系统里，清晰留给使用者。
              </h2>
              <p className="text-muted-foreground mt-4 text-sm leading-7">
                围绕日常管理的高频工作组织功能，减少在页面与工具之间反复切换。
              </p>
            </div>

            <div className="mt-9 grid gap-px overflow-hidden rounded-[14px] border border-[color:var(--color-border)] bg-[var(--color-border)] sm:grid-cols-2 lg:grid-cols-3">
              {FEATURES.map(({ icon: Icon, title, desc }) => (
                <article key={title} className="bg-card p-6 sm:p-7">
                  <Icon
                    className="size-5 text-[color:var(--color-brand-text)]"
                    strokeWidth={1.7}
                    aria-hidden="true"
                  />
                  <h3 className="mt-5 text-[15px] font-semibold">{title}</h3>
                  <p className="text-muted-foreground mt-2 text-[13px] leading-6">{desc}</p>
                </article>
              ))}
            </div>
          </div>
        </section>

        <section
          aria-labelledby="tech-title"
          className="border-t border-[color:var(--color-border-light)] bg-[var(--color-bg-base)] py-16 sm:py-20"
        >
          <div className="mx-auto grid max-w-7xl gap-10 px-5 sm:px-8 lg:grid-cols-[minmax(260px,0.7fr)_minmax(0,1.3fr)] lg:gap-20">
            <div>
              <div className="flex items-center gap-2.5">
                <AudioLines
                  className="size-5 text-[color:var(--color-brand-text)]"
                  aria-hidden="true"
                />
                <p className="text-sm font-medium text-[color:var(--color-brand-text)]">技术基础</p>
              </div>
              <h2 id="tech-title" className="mt-4 text-[30px] font-semibold tracking-[-0.035em]">
                为长期维护与扩展而构建。
              </h2>
              <p className="text-muted-foreground mt-4 max-w-md text-sm leading-7">
                成熟的应用框架、明确的类型边界与可扩展的数据支持，让每次迭代都有稳定起点。
              </p>
            </div>

            <dl>
              {TECH_STACK.map((group) => (
                <div
                  key={group.title}
                  className="grid gap-3 border-t border-[color:var(--color-border)] py-5 last:border-b sm:grid-cols-[120px_minmax(0,1fr)] sm:gap-6"
                >
                  <dt className="text-sm font-medium">{group.title}</dt>
                  <dd className="text-muted-foreground flex flex-wrap gap-x-3 gap-y-2 text-[13px] leading-6">
                    {group.items.map((item, index) => (
                      <span key={item} className="inline-flex items-center gap-3">
                        {index > 0 && (
                          <span className="bg-border size-1 rounded-full" aria-hidden />
                        )}
                        {item}
                      </span>
                    ))}
                  </dd>
                </div>
              ))}
            </dl>
          </div>
        </section>
      </main>

      <footer className="border-t border-[color:var(--color-nav-border)] bg-[var(--color-nav-bg)] text-[color:var(--color-nav-text)]">
        <div className="mx-auto flex max-w-7xl flex-col gap-7 px-5 py-9 sm:px-8 md:flex-row md:items-center md:justify-between">
          <div className="flex items-center gap-4">
            <BrandLockup inverted subtitle={null} size="sm" />
            <span className="hidden h-8 w-px bg-[var(--color-nav-border)] sm:block" aria-hidden />
            <p className="text-xs text-[color:var(--color-nav-muted)]">连接应用与服务</p>
          </div>
          <nav
            aria-label="页脚导航"
            className="flex flex-wrap items-center gap-x-5 gap-y-3 text-xs text-[color:var(--color-nav-muted)]"
          >
            <a
              href={QUIET_MUSIC_URL}
              className={`${styles.focusLink} rounded-sm hover:text-[color:var(--color-nav-text)]`}
            >
              轻听音乐官网 ↗
            </a>
            <Link
              href={storageHref}
              prefetch={false}
              className={`${styles.focusLink} rounded-sm hover:text-[color:var(--color-nav-text)]`}
            >
              文件管理
            </Link>
            <Link
              href={consoleHref}
              prefetch={false}
              className={`${styles.focusLink} rounded-sm hover:text-[color:var(--color-nav-text)]`}
            >
              {isLogin ? '管理控制台' : '管理登录'}
            </Link>
          </nav>
        </div>
      </footer>
    </div>
  );
}
