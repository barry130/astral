'use client';

import { useEffect, useMemo, useState } from 'react';
import type { TocItem } from '@/lib/markdown';

/**
 * 文章标题目录（左侧栏）。
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
    return (
      <p className="text-[12px] leading-relaxed text-ink-faint">
        这篇文章没有小标题。
      </p>
    );
  }

  // 目录深度归一：h2 为 0 级，h3 为 1 级……（h1 不进目录）
  const minDepth = entries.reduce((min, item) => Math.min(min, item.depth), 6);

  const handleJump = (event: React.MouseEvent<HTMLAnchorElement>, id: string) => {
    event.preventDefault();
    const target = document.getElementById(id);
    if (!target) return;
    setActiveId(id);
    target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    // 顶栏 sticky 高 64px，滚动后补偏移，避免标题被盖住
    window.history.replaceState(null, '', `#${id}`);
  };

  return (
    <nav aria-label="文章目录">
      <ul className="space-y-[3px] border-l border-line-soft">
        {entries.map((entry) => {
          const level = Math.max(0, entry.depth - minDepth);
          const active = activeId === entry.id;
          return (
            <li key={entry.id}>
              <a
                href={`#${entry.id}`}
                onClick={(event) => handleJump(event, entry.id)}
                style={{ paddingLeft: 12 + level * 12 }}
                className={`-ml-px block border-l py-1.5 pr-2 text-[clamp(13.5px,0.28vw+9.5px,15.5px)] leading-[1.65] transition-colors sm:py-[5px] ${
                  active
                    ? 'border-brand font-medium text-brand'
                    : 'border-transparent text-ink-soft hover:border-line-strong hover:text-ink'
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
