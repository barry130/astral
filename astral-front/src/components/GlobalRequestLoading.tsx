'use client';

import React, { useEffect, useState } from 'react';
import { subscribeRequestCount } from '@/lib/requestLoading';

/**
 * 全局接口加载指示
 *
 * 两级反馈，兼顾「即时」与「不打扰」：
 * 1. 顶部 2px 进度条：请求一发出就出现，解决「点下去半天没反应」
 * 2. 「加载中」气泡：持续超过 800ms 才出现，避免快接口闪一下就消失造成的视觉噪音
 *
 * 并发请求由计数驱动，全部结束才消失。
 */

/** 超过这个时长仍在加载，才显示文字气泡 */
const TEXT_DELAY_MS = 800;

export function GlobalRequestLoading() {
  const [active, setActive] = useState(0);
  const [showText, setShowText] = useState(false);

  useEffect(() => subscribeRequestCount(setActive), []);

  useEffect(() => {
    if (active === 0) {
      setShowText(false);
      return;
    }
    const timer = window.setTimeout(() => setShowText(true), TEXT_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [active]);

  if (active === 0) return null;

  return (
    <>
      <div
        className="global-loading-bar"
        role="progressbar"
        aria-label="接口请求中"
        aria-valuetext="加载中"
      >
        <span className="global-loading-bar-inner" />
      </div>
      {showText ? (
        <div className="global-loading-pill" role="status" aria-live="polite">
          <span className="global-loading-spinner" aria-hidden />
          <span>加载中{active > 1 ? `（${active} 个请求）` : ''}...</span>
        </div>
      ) : null}
    </>
  );
}
