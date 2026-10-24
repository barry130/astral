'use client';

import { useEffect } from 'react';

/**
 * 正文 Markdown 的客户端增强。
 *
 * 正文 HTML 是服务端渲染的字符串，事件挂不到 React 树上，
 * 所以这里在容器上做一次性事件委托：点「复制」按钮时取同级 <pre> 的文本。
 */
export function MarkdownEnhance() {
  useEffect(() => {
    async function onClick(event: MouseEvent) {
      const target = event.target;
      if (!(target instanceof Element)) return;
      const button = target.closest('.md-code-copy');
      if (!(button instanceof HTMLButtonElement)) return;

      const block = button.closest('.md-code');
      const code = block?.querySelector('pre code');
      const text = code?.textContent ?? '';
      if (!text) return;

      try {
        await navigator.clipboard.writeText(text);
      } catch {
        // 非安全上下文（http）下 clipboard API 不可用，退回 execCommand
        const textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.setAttribute('readonly', '');
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        document.body.removeChild(textarea);
      }

      const original = button.textContent;
      button.textContent = '已复制';
      button.dataset.copied = 'true';
      window.setTimeout(() => {
        button.textContent = original ?? '复制';
        delete button.dataset.copied;
      }, 1600);
    }

    document.addEventListener('click', onClick);
    return () => document.removeEventListener('click', onClick);
  }, []);

  return null;
}
