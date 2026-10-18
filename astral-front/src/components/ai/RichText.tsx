/**
 * 轻量富文本渲染。
 *
 * 项目里没有引入 markdown 库（见 `package.json`：只有 react-syntax-highlighter），
 * 而助手回答里稳定出现的只有标题、无序/有序列表、围栏代码块、引用、粗体与行内代码，
 * 为这几类语法写一个小解析器，比引一个全量 markdown 引擎更可控：
 * 不会解析 HTML，也就没有 XSS 面。
 *
 * 不支持的语法一律按纯文本输出，宁可少渲染也不能渲染错。
 */

'use client';

import React from 'react';

/* ==================== 行内解析 ==================== */

/** 行内标记：`code`、**bold**、[text](url) */
const INLINE_PATTERN = /(`[^`\n]+`)|(\*\*[^*\n]+\*\*)|(\[[^\]\n]+\]\([^)\s]+\))/g;

function renderInline(text: string, keyPrefix: string): React.ReactNode[] {
  const nodes: React.ReactNode[] = [];
  let last = 0;
  let index = 0;

  for (const match of text.matchAll(INLINE_PATTERN)) {
    const start = match.index ?? 0;
    if (start > last) nodes.push(text.slice(last, start));
    const token = match[0];
    const key = `${keyPrefix}-i${index++}`;

    if (token.startsWith('`')) {
      nodes.push(
        <code
          key={key}
          style={{
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
            fontSize: '0.92em',
            padding: '1px 5px',
            borderRadius: 4,
            background: 'var(--muted, rgba(127,127,127,0.14))',
          }}
        >
          {token.slice(1, -1)}
        </code>,
      );
    } else if (token.startsWith('**')) {
      nodes.push(
        <strong key={key} style={{ fontWeight: 600 }}>
          {token.slice(2, -2)}
        </strong>,
      );
    } else {
      const close = token.indexOf('](');
      const label = token.slice(1, close);
      const href = token.slice(close + 2, -1);
      // 只放行 http(s)，避免 javascript: 之类被点开
      const safe = /^https?:\/\//i.test(href);
      nodes.push(
        safe ? (
          <a key={key} href={href} target="_blank" rel="noreferrer noopener">
            {label}
          </a>
        ) : (
          <span key={key}>{label}</span>
        ),
      );
    }
    last = start + token.length;
  }

  if (last < text.length) nodes.push(text.slice(last));
  return nodes;
}

/* ==================== 块解析 ==================== */

type Block =
  | { kind: 'heading'; level: number; text: string }
  | { kind: 'paragraph'; text: string }
  | { kind: 'list'; ordered: boolean; items: string[] }
  | { kind: 'code'; lang: string; lines: string[] }
  | { kind: 'quote'; text: string }
  | { kind: 'divider' };

function parseBlocks(source: string): Block[] {
  const lines = source.replace(/\r\n/g, '\n').split('\n');
  const blocks: Block[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    if (!line.trim()) {
      i += 1;
      continue;
    }

    // 围栏代码块
    if (line.trimStart().startsWith('```')) {
      const lang = line.trim().slice(3).trim();
      const body: string[] = [];
      i += 1;
      while (i < lines.length && !lines[i].trimStart().startsWith('```')) {
        body.push(lines[i]);
        i += 1;
      }
      i += 1; // 跳过结束围栏
      blocks.push({ kind: 'code', lang, lines: body });
      continue;
    }

    // 分隔线
    if (/^\s*([-*_])\1{2,}\s*$/.test(line)) {
      blocks.push({ kind: 'divider' });
      i += 1;
      continue;
    }

    // 标题
    const heading = /^(#{1,6})\s+(.*)$/.exec(line);
    if (heading) {
      blocks.push({ kind: 'heading', level: heading[1].length, text: heading[2].trim() });
      i += 1;
      continue;
    }

    // 引用
    if (/^\s*>\s?/.test(line)) {
      const parts: string[] = [];
      while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
        parts.push(lines[i].replace(/^\s*>\s?/, ''));
        i += 1;
      }
      blocks.push({ kind: 'quote', text: parts.join('\n') });
      continue;
    }

    // 列表（连续同类项合并成一个列表）
    const isUnordered = /^\s*[-*+]\s+/.test(line);
    const isOrdered = /^\s*\d+[.)]\s+/.test(line);
    if (isUnordered || isOrdered) {
      const items: string[] = [];
      while (i < lines.length) {
        const current = lines[i];
        const unordered = /^\s*[-*+]\s+/.test(current);
        const ordered = /^\s*\d+[.)]\s+/.test(current);
        if (isUnordered ? !unordered : !ordered) break;
        items.push(current.replace(/^\s*([-*+]|\d+[.)])\s+/, ''));
        i += 1;
      }
      blocks.push({ kind: 'list', ordered: isOrdered, items });
      continue;
    }

    // 普通段落：吃到空行或下一个块起始为止
    const paragraph: string[] = [];
    while (i < lines.length) {
      const current = lines[i];
      if (
        !current.trim() ||
        current.trimStart().startsWith('```') ||
        /^(#{1,6})\s+/.test(current) ||
        /^\s*([-*_])\1{2,}\s*$/.test(current) ||
        /^\s*>\s?/.test(current) ||
        /^\s*[-*+]\s+/.test(current) ||
        /^\s*\d+[.)]\s+/.test(current)
      ) {
        break;
      }
      paragraph.push(current);
      i += 1;
    }
    blocks.push({ kind: 'paragraph', text: paragraph.join('\n') });
  }

  return blocks;
}

/* ==================== 渲染 ==================== */

export interface RichTextProps {
  /** 原始文本（助手回答全文或片段） */
  text?: string | null;
  /** 紧凑模式：用于气泡内小字号场景 */
  compact?: boolean;
}

/**
 * 渲染助手回答正文。
 *
 * 段落保留换行（`white-space: pre-wrap`），这样模型输出的窄表格或对齐文本不会被压扁。
 */
export default function RichText({ text, compact = false }: RichTextProps) {
  const source = (text ?? '').trim();
  if (!source) return null;

  const blocks = parseBlocks(source);
  const fontSize = compact ? 13 : 14;

  return (
    <div style={{ fontSize, lineHeight: 1.75, wordBreak: 'break-word' }}>
      {blocks.map((block, index) => {
        const key = `b${index}`;

        switch (block.kind) {
          case 'heading': {
            const size = [18, 16, 15, 14, 14, 14][Math.min(block.level, 6) - 1];
            return (
              <div
                key={key}
                style={{
                  fontSize: size,
                  fontWeight: 600,
                  margin: index === 0 ? '0 0 8px' : '14px 0 6px',
                  lineHeight: 1.5,
                }}
              >
                {renderInline(block.text, key)}
              </div>
            );
          }
          case 'code':
            return (
              <pre
                key={key}
                style={{
                  margin: '10px 0',
                  padding: '10px 12px',
                  borderRadius: 8,
                  overflowX: 'auto',
                  fontSize: 12.5,
                  lineHeight: 1.6,
                  background: 'var(--muted, rgba(127,127,127,0.12))',
                  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                }}
              >
                <code>{block.lines.join('\n')}</code>
              </pre>
            );
          case 'list': {
            const ListTag = block.ordered ? 'ol' : 'ul';
            return (
              <ListTag key={key} style={{ margin: '6px 0', paddingLeft: 22 }}>
                {block.items.map((item, itemIndex) => (
                  <li key={`${key}-${itemIndex}`} style={{ margin: '2px 0' }}>
                    {renderInline(item, `${key}-${itemIndex}`)}
                  </li>
                ))}
              </ListTag>
            );
          }
          case 'quote':
            return (
              <blockquote
                key={key}
                style={{
                  margin: '10px 0',
                  padding: '6px 12px',
                  borderLeft: '3px solid var(--border, rgba(127,127,127,0.35))',
                  color: 'var(--muted-foreground, inherit)',
                  opacity: 0.9,
                }}
              >
                {renderInline(block.text, key)}
              </blockquote>
            );
          case 'divider':
            return (
              <hr
                key={key}
                style={{
                  margin: '14px 0',
                  border: 0,
                  borderTop: '1px solid var(--border, rgba(127,127,127,0.25))',
                }}
              />
            );
          default:
            return (
              <p key={key} style={{ margin: '6px 0', whiteSpace: 'pre-wrap' }}>
                {renderInline(block.text, key)}
              </p>
            );
        }
      })}
    </div>
  );
}
