import type { Metadata } from 'next';
import Link from 'next/link';
import {
  AndroidOutlined,
  WindowsOutlined,
  CustomerServiceOutlined,
  StarFilled,
  MobileOutlined,
  CloudDownloadOutlined,
} from '@ant-design/icons';

/**
 * 轻听音乐介绍页（路由 /lightlisten）
 *
 * 品牌宣传页：还原「轻听」宣传海报的视觉调性 —— 浅蓝天空、白云、漂浮音符、
 * 双端下载徽章、四大卖点卡片与品牌 slogan。纯静态服务端组件，无 antd 组件
 * （避免跟随后台明暗主题翻转，品牌页保持固定浅色天空视觉）。
 * 素材位于 public/lightlisten/（横版 banner、竖版海报、应用图标）。
 */

export const metadata: Metadata = {
  title: '轻听音乐 - 你的私人音乐台',
  description:
    '轻听 QuietMusic：随时随地，畅享好音乐。海量曲库、个性推荐、多端同步、离线畅听，Android 与 Windows 双端支持。',
};

/** 卖点卡片：文案取自官方宣传物料 */
const FEATURES = [
  {
    icon: <CustomerServiceOutlined />,
    title: '海量曲库',
    desc: '千万正版音乐\n随心听',
  },
  {
    icon: <StarFilled />,
    title: '个性推荐',
    desc: '智能算法\n懂你所爱',
  },
  {
    icon: <MobileOutlined />,
    title: '多端同步',
    desc: '手机 / 电脑\n无缝切换',
  },
  {
    icon: <CloudDownloadOutlined />,
    title: '离线畅听',
    desc: '下载歌曲\n随时随地',
  },
];

/** 均衡器装饰条的延迟序列：错落跳动，营造「音乐正在播放」的氛围 */
const EQ_DELAYS = [0, 0.12, 0.24, 0.06, 0.3, 0.18, 0, 0.24, 0.12, 0.3, 0.06, 0.18, 0.1, 0.22];

/** 双端下载统一跳转：夸克网盘分享页 */
const DOWNLOAD_URL = 'https://pan.quark.cn/s/5901919384f4';

export default function LightListenPage() {
  return (
    <div className="ql-page">
      {/* 背景装饰：云朵 + 漂浮音符（纯装饰，读屏跳过） */}
      <div aria-hidden className="ql-deco">
        <span className="ql-cloud ql-cloud-1" />
        <span className="ql-cloud ql-cloud-2" />
        <span className="ql-cloud ql-cloud-3" />
        <span className="ql-note ql-note-1">♪</span>
        <span className="ql-note ql-note-2">♫</span>
        <span className="ql-note ql-note-3">♩</span>
        <span className="ql-note ql-note-4">♬</span>
      </div>

      {/* 顶部导航 */}
      <header className="ql-nav">
        <div className="ql-brand">
          <img src="/lightlisten/icon.png" alt="" className="ql-brand-icon" draggable={false} />
          <div className="ql-brand-text">
            <strong>轻听</strong>
            <span>你的私人音乐台</span>
          </div>
        </div>
        <Link href="/" className="ql-nav-back">
          ← 返回 Astral 首页
        </Link>
      </header>

      <main className="ql-main">
        {/* Hero：文案 + 竖版海报 */}
        <section className="ql-hero">
          <div className="ql-hero-copy fade-in-up">
            <span className="ql-badge">轻听 · QuietMusic</span>
            <h1 className="ql-title">
              随时随地
              <br />
              畅享好音乐
            </h1>
            <p className="ql-subtitle">海量曲库 · 个性推荐 · 多端同步</p>

            {/* 均衡器装饰（尊重系统减少动态偏好，见 globals.css） */}
            <div className="ql-eq" aria-hidden>
              {EQ_DELAYS.map((d, i) => (
                <span
                  key={i}
                  style={{
                    height: `${16 + ((i * 7) % 18)}px`,
                    animationDelay: `${d}s`,
                  }}
                />
              ))}
            </div>

            {/* 双端下载徽章：Android / Windows 均跳转网盘下载页 */}
            <div className="ql-download">
              <a
                className="ql-pill"
                href={DOWNLOAD_URL}
                target="_blank"
                rel="noopener noreferrer"
                title="手机随时听"
              >
                <AndroidOutlined className="ql-pill-icon" />
                <span className="ql-pill-text">
                  <b>Android 版</b>
                  <i>点击下载</i>
                </span>
              </a>
              <a
                className="ql-pill"
                href={DOWNLOAD_URL}
                target="_blank"
                rel="noopener noreferrer"
                title="大屏更沉浸"
              >
                <WindowsOutlined className="ql-pill-icon" />
                <span className="ql-pill-text">
                  <b>Windows 版</b>
                  <i>点击下载</i>
                </span>
              </a>
            </div>
          </div>
        </section>

        {/* 卖点 */}
        <section className="ql-section">
          <h2 className="ql-section-title">为什么选择轻听</h2>
          <div className="ql-feature-grid">
            {FEATURES.map((f) => (
              <div key={f.title} className="ql-feature-card fade-in-up">
                <div className="ql-feature-icon">{f.icon}</div>
                <h3>{f.title}</h3>
                <p>
                  {f.desc.split('\n').map((line, i) => (
                    <span key={i}>
                      {i > 0 && <br />}
                      {line}
                    </span>
                  ))}
                </p>
              </div>
            ))}
          </div>
        </section>

        {/* 产品一览：横版宣传图 */}
        <section className="ql-section">
          <h2 className="ql-section-title">产品一览</h2>
          <div className="ql-banner fade-in-up">
            <img src="/lightlisten/banner.jpg" alt="轻听音乐产品宣传图" draggable={false} />
          </div>
        </section>

        {/* Slogan */}
        <section className="ql-slogan fade-in-up">
          <p>轻听，让音乐陪伴你的一刻</p>
        </section>
      </main>

      <footer className="ql-footer">
        <Link href="/">Astral 后台管理系统</Link>
        <span>© 轻听 QuietMusic · 让音乐陪伴你的一刻</span>
      </footer>
    </div>
  );
}
