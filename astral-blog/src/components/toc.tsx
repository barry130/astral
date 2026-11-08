'use client';

import { useEffect, useMemo, useState } from 'react';
import type { TocItem } from '@/lib/markdown';

/**
 * 文章标题目录（正文右侧附注栏）。
 *
 * 交互刻意做得轻：
 * - 不做「滚动到哪个标题就高亮」的滚动监听（长文里 ScrollY 计算易抖动），
 *   改成点击后立即高亮，用户始终知道自己点了哪一节；
 * - 标题层级用左缩进 + 竖线表达，不额外加图标和背景色。
 */

interface TocProps {
  items: TocItem[];
}

interface FlatEntry {
  id: string;
  text: string;
  depth: number;
}

function flatten(items: TocItem[], out: FlatEntry[] = []): FlatEntry[] {
  for (const item of items) {
    out.push({ id: item.id, text: item.text, depth: item.depth });
    if (item.children.length > 0) flatten(item.children, out);
  }
  return out;
}

export function Toc({ items }: TocProps) {
  const entries = useMemo(() => flatten(items), [items]);
  const [activeId, setActiveId] = useState<string | null>(null);

  // 换文章时清掉上一页的高亮
  useEffect(() => {
    setActiveId(null);
  }, [items]);

  if (entries.length === 0) {
    return <p className="text-ink-faint text-[12px] leading-relaxed">这篇文章没有小标题。</p>;
  }

  // 目录深度归一：h2 为 0 级，h3 为 1 级……（h1 不进目录）
  const minDepth = entries.reduce((min, item) => Math.min(min, item.depth), 6);

  const handleJump = (event: React.MouseEvent<HTMLAnchorElement>, id: string) => {
    // 保留浏览器原生行为：新标签/新窗口打开（Ctrl/Cmd/Shift/Alt + 点击）不应被拦截。
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    const target = document.getElementById(id);
    if (!target) return;
    setActiveId(id);
    // 顶栏 sticky 高约 68px，再留 20px 呼吸位；scrollIntoView 不支持 offset。
    const top = target.getBoundingClientRect().top + window.scrollY - 88;
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    window.scrollTo({ top: Math.max(0, top), behavior: reduceMotion ? 'auto' : 'smooth' });
    window.history.replaceState(window.history.state, '', `#${encodeURIComponent(id)}`);
    // 便于键盘用户从跳转点继续阅读
    target.setAttribute('tabindex', '-1');
    target.focus({ preventScroll: true });
  };

  return (
    <nav aria-label="文章目录">
      <ul className="border-line-soft space-y-[3px] border-l">
        {entries.map((entry) => {
          const level = Math.max(0, entry.depth - minDepth);
          const active = activeId === entry.id;
          return (
            <li key={entry.id}>
              <a
                href={`#${entry.id}`}
                onClick={(event) => handleJump(event, entry.id)}
                style={{ paddingLeft: 12 + level * 12 }}
                className={`-ml-px block border-l py-3 pr-2 text-[clamp(13.5px,0.28vw+9.5px,15.5px)] leading-[1.65] transition-colors sm:py-[5px] ${
                  active
                    ? 'border-brand text-brand font-medium'
                    : 'text-ink-soft hover:border-line-strong hover:text-ink border-transparent'
                }`}
              >
                {entry.text}
              </a>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
