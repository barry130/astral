'use client';

import { useEffect, useState } from 'react';

/**
 * 正文 Markdown 的客户端增强。
 *
 * 正文 HTML 是服务端渲染的字符串，事件挂不到 React 树上，
 * 所以这里在容器上做一次性事件委托：点「复制」按钮时取同级 <pre> 的文本。
 */
export function MarkdownEnhance() {
  const [announcement, setAnnouncement] = useState('');

  useEffect(() => {
    // 每个按钮只保留一个还原计时器：连点同一个按钮时先取消上一个，
    // 否则第一次点击排的 1600ms 计时器会把第二次点击的「已复制」提前还原。
    const restoreTimers = new Map<HTMLButtonElement, number>();
    let active = true;
    let clearTimer = 0;
    let latestCopy = 0;
    const pending = new WeakSet<HTMLButtonElement>();

    async function onClick(event: MouseEvent) {
      const target = event.target;
      if (!(target instanceof Element)) return;
      const button = target.closest('.md-code-copy');
      if (!(button instanceof HTMLButtonElement)) return;

      const block = button.closest('.md-code');
      const code = block?.querySelector('pre code');
      const text = code?.textContent ?? '';
      if (!text || pending.has(button)) return;
      pending.add(button);
      const generation = ++latestCopy;

      let copied = false;
      try {
        await navigator.clipboard.writeText(text);
        copied = true;
      } catch {
        if (!active || !button.isConnected || generation !== latestCopy) {
          pending.delete(button);
          return;
        }
        // Keep temporary focus inside the nearest modal; preserve the user's document selection.
        const focused = document.activeElement;
        const selection = document.getSelection();
        const ranges: Range[] = [];
        if (selection)
          for (let index = 0; index < selection.rangeCount; index++)
            ranges.push(selection.getRangeAt(index).cloneRange());
        const container = button.closest('[data-slot="dialog-content"]') ?? document.body;
        const textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.setAttribute('readonly', '');
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        container.appendChild(textarea);
        textarea.focus({ preventScroll: true });
        textarea.select();
        try {
          copied = document.execCommand('copy');
        } catch {
          copied = false;
        }
        textarea.remove();
        if (focused instanceof HTMLElement && focused.isConnected)
          focused.focus({ preventScroll: true });
        if (active && button.isConnected && generation === latestCopy && selection) {
          selection.removeAllRanges();
          for (const range of ranges)
            if (range.startContainer.isConnected && range.endContainer.isConnected)
              selection.addRange(range);
        }
      }
      pending.delete(button);

      if (!active || !button.isConnected || generation !== latestCopy) return;
      button.textContent = copied ? '已复制' : '复制失败';
      if (copied) button.dataset.copied = 'true';
      else delete button.dataset.copied;

      const restoreTimer = restoreTimers.get(button);
      if (restoreTimer !== undefined) window.clearTimeout(restoreTimer);
      if (clearTimer !== 0) window.clearTimeout(clearTimer);

      // 先清空再写入：读屏器只播报「文本变化」，同样的文案连按一次不会重读
      setAnnouncement('');
      clearTimer = window.setTimeout(() => {
        setAnnouncement(copied ? '代码已复制' : '复制失败，请手动选择代码');
      }, 30);
      restoreTimers.set(
        button,
        window.setTimeout(() => {
          button.textContent = '复制';
          delete button.dataset.copied;
          restoreTimers.delete(button);
        }, 1600),
      );
    }

    document.addEventListener('click', onClick);
    return () => {
      active = false;
      document.removeEventListener('click', onClick);
      for (const timer of restoreTimers.values()) window.clearTimeout(timer);
      restoreTimers.clear();
      if (clearTimer !== 0) window.clearTimeout(clearTimer);
    };
  }, []);

  return (
    <div role="status" aria-live="polite" className="sr-only">
      {announcement}
    </div>
  );
}
