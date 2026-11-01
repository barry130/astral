'use client';

import type { CSSProperties, ReactNode } from 'react';

/**
 * 富文本（HTML 片段）渲染 —— 公告 / 通知正文用。
 *
 * <p>后端 `sys_notice.content` 的口径是「可承载富文本」：运营在后台写 `<h3>` / `<p>` /
 * `<ul>` 这类标签，客户端（qt-pc 的 `lib/richText`、qt-uniappx 的富文本组件）按标签白名单
 * 解析后渲染，**不走 `innerHTML`**。管理端此前把这个字段当纯文本直接输出，于是公告详情里
 * 看到的是一整屏 `<div style="…">` 源码。</p>
 *
 * <p>这里复用客户端的做法：先把 HTML 解析成节点树，再用 React 元素渲染（React 天然转义文本），
 * 配合标签 / 属性白名单（脚本、事件属性、非 http(s) 链接一律丢弃），注入面为零。
 * 与客户端唯一的差别是**行内样式放宽**——运营常常粘一段带 `border` / `border-radius` /
 * `padding` 的提示框，客户端白名单只保留颜色与对齐，管理端保留这些排版属性，
 * 但会剔除 `url()` / `expression()` / `@import` / `position` 这类可执行或可越界的声明。</p>
 *
 * <p>同时兼容纯文本正文：整段不含标签时按纯文本渲染（保留换行 + 链接可点）。</p>
 */

interface ElementNode {
  tag: string;
  attrs: Record<string, string>;
  children: Node[];
}
interface TextNode {
  text: string;
}
type Node = ElementNode | TextNode;

/** 空标签（无闭合） */
const VOID_TAGS = new Set(['br', 'hr', 'img']);
/** 换行时补一个空行的块级标签（纯文本摘要用） */
const BLOCK_BREAK_TAGS = new Set([
  'p',
  'div',
  'section',
  'article',
  'figure',
  'figcaption',
  'br',
  'hr',
  'h1',
  'h2',
  'h3',
  'h4',
  'h5',
  'h6',
  'ul',
  'ol',
  'li',
  'blockquote',
  'pre',
  'table',
  'tr',
]);

/** 渲染成 React 元素时接受的标签（其余按容器展开，只保留文本） */
export const RICH_TEXT_TAGS = new Set([
  'p',
  'div',
  'section',
  'article',
  'figure',
  'figcaption',
  'br',
  'hr',
  'h1',
  'h2',
  'h3',
  'h4',
  'h5',
  'h6',
  'strong',
  'b',
  'em',
  'i',
  'u',
  'ins',
  's',
  'del',
  'sub',
  'sup',
  'small',
  'mark',
  'span',
  'font',
  'ul',
  'ol',
  'li',
  'blockquote',
  'pre',
  'code',
  'a',
  'img',
  'table',
  'thead',
  'tbody',
  'tr',
  'th',
  'td',
  'style',
  'script',
  'iframe',
  'object',
  'embed',
  'noscript',
]);

/** 是否像 HTML（有 `<tag` 形态）；纯文本里的 `1 < 2` 不会误判 */
export function looksLikeHtml(html: string): boolean {
  return /<\/?[a-zA-Z][^>]*>/.test(html);
}

function decodeEntities(s: string): string {
  return s
    .replace(/&nbsp;/gi, ' ')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#0*39;/g, "'")
    .replace(/&#x27;/gi, "'")
    .replace(/&amp;/gi, '&');
}

function parseAttrs(raw: string): Record<string, string> {
  const attrs: Record<string, string> = {};
  const re = /([\w-:]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(raw)) !== null) {
    attrs[m[1].toLowerCase()] = decodeEntities(m[2] ?? m[3] ?? m[4] ?? '');
  }
  return attrs;
}

/** 预清洗：注释、script/style 等整段剔除（否则其内容会当正文显示出来） */
function stripDangerous(html: string): string {
  return html
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<(script|style|iframe|object|embed|noscript)[\s\S]*?<\/\1\s*>/gi, '')
    .replace(/<\/?(script|style|iframe|object|embed|noscript)\b[^>]*>/gi, '');
}

/** HTML → 节点树。未闭合标签容错，未知标签保留（渲染时当容器展开） */
export function parseRichText(html: string): Node[] {
  const root: Node[] = [];
  const stack: ElementNode[] = [];
  const tokenRe = /<[^>]+>|[^<]+/g;
  const closeRe = /^<\s*\/\s*([a-zA-Z][\w-]*)/;
  const openRe = /^<\s*([a-zA-Z][\w-]*)([\s\S]*?)\/?>$/;

  const append = (node: Node): void => {
    const parent = stack.length > 0 ? stack[stack.length - 1] : null;
    if (parent) parent.children.push(node);
    else root.push(node);
  };
  const inPre = (): boolean => stack.some((n) => n.tag === 'pre' || n.tag === 'code');

  let m: RegExpExecArray | null;
  while ((m = tokenRe.exec(html)) !== null) {
    const token = m[0];
    if (token.charAt(0) !== '<') {
      // 非 pre 内按 HTML 语义折叠空白，并丢掉标签之间的纯缩进文本
      const text = inPre() ? decodeEntities(token) : decodeEntities(token).replace(/\s+/g, ' ');
      if (text.trim().length === 0 && !inPre()) continue;
      append({ text });
      continue;
    }
    const close = closeRe.exec(token);
    if (close) {
      const tag = close[1].toLowerCase();
      for (let i = stack.length - 1; i >= 0; i -= 1) {
        if (stack[i].tag === tag) {
          stack.length = i;
          break;
        }
      }
      continue;
    }
    const open = openRe.exec(token);
    if (!open) continue; // 注释 / DOCTYPE 等
    const tag = open[1].toLowerCase();
    const node: ElementNode = { tag, attrs: parseAttrs(open[2]), children: [] };
    append(node);
    if (!VOID_TAGS.has(tag) && !/\/\s*>$/.test(token)) stack.push(node);
  }
  return root;
}

/** 纯文本摘要：去掉标签、块级标签之间补换行 */
export function htmlToText(html: string): string {
  if (!html) return '';
  if (!looksLikeHtml(html)) return html;
  const out: string[] = [];
  const walk = (list: Node[]): void => {
    for (const n of list) {
      if ('text' in n) {
        out.push(n.text);
        continue;
      }
      const block = BLOCK_BREAK_TAGS.has(n.tag);
      if (block) out.push('\n');
      if (n.tag === 'img' && n.attrs.alt) out.push(n.attrs.alt);
      walk(n.children);
      if (block) out.push('\n');
    }
  };
  walk(parseRichText(stripDangerous(html)));
  return out
    .join('')
    .replace(/[ \t]+/g, ' ')
    .replace(/\n\s*\n+/g, '\n')
    .trim();
}

// ---------------------------------------------------------------------------
// 消毒（写库前调用）：把运营/客户端粘进来的 HTML 收敛到白名单
// ---------------------------------------------------------------------------

/** 允许落库的标签（其余标签拆壳保留文本） */
const ALLOWED_TAGS = new Set([
  'p',
  'div',
  'section',
  'article',
  'figure',
  'figcaption',
  'br',
  'hr',
  'h1',
  'h2',
  'h3',
  'h4',
  'h5',
  'h6',
  'strong',
  'b',
  'em',
  'i',
  'u',
  'ins',
  's',
  'del',
  'sub',
  'sup',
  'small',
  'mark',
  'span',
  'font',
  'ul',
  'ol',
  'li',
  'blockquote',
  'pre',
  'code',
  'a',
  'img',
  'table',
  'thead',
  'tbody',
  'tr',
  'th',
  'td',
]);

const ALLOWED_ATTRS: Record<string, string[]> = {
  a: ['href', 'title'],
  img: ['src', 'alt', 'title', 'width', 'height'],
  ol: ['start'],
  td: ['colspan', 'rowspan'],
  th: ['colspan', 'rowspan'],
  font: ['color'],
};

/** 行内样式白名单：排版属性放行，能触发请求或越界的属性一律丢弃 */
const ALLOWED_STYLE_PREFIXES = [
  'color',
  'background',
  'border',
  'padding',
  'margin',
  'text-align',
  'text-decoration',
  'font-size',
  'font-weight',
  'font-style',
  'font-family',
  'line-height',
  'letter-spacing',
  'width',
  'max-width',
  'min-width',
  'height',
  'max-height',
  'display',
  'vertical-align',
  'white-space',
  'border-radius',
];

/** 危险值特征：外链请求、表达式、绝对定位 */
const UNSAFE_STYLE_VALUE = /(url\s*\(|expression\s*\(|@import|javascript:|position\s*:)/i;

function safeStyle(raw: string | undefined): string {
  const decls: string[] = [];
  for (const decl of (raw ?? '').split(';')) {
    const i = decl.indexOf(':');
    if (i < 0) continue;
    const key = decl.slice(0, i).trim().toLowerCase();
    const value = decl.slice(i + 1).trim();
    if (!key || !value) continue;
    if (!ALLOWED_STYLE_PREFIXES.some((p) => key === p || key.startsWith(`${p}-`))) continue;
    if (UNSAFE_STYLE_VALUE.test(value)) continue;
    decls.push(`${key}:${value}`);
  }
  return decls.join(';');
}

function safeUrl(raw: string | undefined): string | null {
  const url = (raw ?? '').trim();
  if (!url) return null;
  if (/^https?:\/\//i.test(url)) return url;
  // 站内 astral:// 协议允许（通知里的跳转链接）
  if (/^astral:\/\//i.test(url)) return url;
  return null;
}

/**
 * 把 HTML 收敛成白名单结构（返回可直接落库 / 塞进 contenteditable 的字符串）。
 *
 * 纯文本原样返回（不包 `<p>`，保存前后一致最不容易惊到运营）。
 */
export function sanitizeRichText(html: string): string {
  if (!html) return '';
  if (!looksLikeHtml(html)) return html;
  if (typeof window === 'undefined' || typeof DOMParser === 'undefined') return html;

  const doc = new DOMParser().parseFromString(stripDangerous(html), 'text/html');
  const body = doc.body;

  const walk = (parent: Element): void => {
    const children = Array.from(parent.children);
    for (const el of children) {
      const tag = el.tagName.toLowerCase();
      if (!ALLOWED_TAGS.has(tag)) {
        // 未知标签：拆壳，把子节点提到父级
        walk(el);
        while (el.firstChild) parent.insertBefore(el.firstChild, el);
        parent.removeChild(el);
        continue;
      }
      const attrs = ALLOWED_ATTRS[tag] ?? [];
      for (const attr of Array.from(el.attributes)) {
        const name = attr.name.toLowerCase();
        if (name === 'style') {
          const style = safeStyle(attr.value);
          if (style) el.setAttribute('style', style);
          else el.removeAttribute('style');
          continue;
        }
        if (!attrs.includes(name)) {
          el.removeAttribute(name);
          continue;
        }
        if ((name === 'href' || name === 'src') && !safeUrl(attr.value)) {
          el.removeAttribute(name);
        }
      }
      // 外链统一新窗口打开，并切断 opener
      if (tag === 'a' && el.getAttribute('href')) {
        el.setAttribute('target', '_blank');
        el.setAttribute('rel', 'noopener noreferrer');
      }
      walk(el);
    }
  };
  walk(body);

  return body.innerHTML.trim();
}

// ---------------------------------------------------------------------------
// 渲染
// ---------------------------------------------------------------------------

/** 只保留安全的行内样式（排版白名单，剔除可执行 / 可外链的声明） */
function inlineStyle(attrs: Record<string, string>): CSSProperties | undefined {
  const raw = safeStyle(attrs.style);
  if (!raw) {
    const color = attrs.color;
    return color && !UNSAFE_STYLE_VALUE.test(color) ? { color } : undefined;
  }
  const style: Record<string, string> = {};
  for (const decl of raw.split(';')) {
    const i = decl.indexOf(':');
    if (i < 0) continue;
    const key = decl.slice(0, i).trim();
    const value = decl.slice(i + 1).trim();
    if (!key || !value) continue;
    style[key] = value;
  }
  return Object.keys(style).length > 0 ? (style as CSSProperties) : undefined;
}

function renderNodes(nodes: Node[], keyPrefix: string): ReactNode[] {
  return nodes.map((node, i) =>
    'text' in node ? node.text : renderElement(node, `${keyPrefix}-${i}`),
  );
}

function renderElement(node: ElementNode, key: string): ReactNode {
  const { tag, attrs } = node;
  const inner = renderNodes(node.children, key);
  const style = inlineStyle(attrs);

  switch (tag) {
    case 'p':
      return (
        <p key={key} style={style} className="my-1 first:mt-0 last:mb-0">
          {inner}
        </p>
      );
    // 容器标签必须渲染成 <div>：`<p>` 里塞 `<h3>` / `<ul>` 会被浏览器解析器就地闭合，
    // 服务端 HTML 与客户端 React 树对不上 → hydration mismatch（公告正文常见这种嵌套）
    case 'div':
    case 'section':
    case 'article':
    case 'figure':
    case 'figcaption':
      return (
        <div key={key} style={style} className="my-1 first:mt-0 last:mb-0">
          {inner}
        </div>
      );
    case 'br':
      return <br key={key} />;
    case 'hr':
      return <hr key={key} className="border-border my-3" />;
    case 'h1':
      return (
        <h1 key={key} style={style} className="mt-3 mb-1 text-lg font-semibold first:mt-0">
          {inner}
        </h1>
      );
    case 'h2':
      return (
        <h2 key={key} style={style} className="mt-3 mb-1 text-base font-semibold first:mt-0">
          {inner}
        </h2>
      );
    case 'h3':
    case 'h4':
    case 'h5':
    case 'h6':
      return (
        <h3 key={key} style={style} className="mt-2 mb-1 text-sm font-semibold first:mt-0">
          {inner}
        </h3>
      );
    case 'strong':
    case 'b':
      return (
        <strong key={key} style={style} className="font-semibold">
          {inner}
        </strong>
      );
    case 'em':
    case 'i':
      return (
        <em key={key} style={style} className="italic">
          {inner}
        </em>
      );
    case 'u':
    case 'ins':
      return (
        <span key={key} style={style} className="underline underline-offset-2">
          {inner}
        </span>
      );
    case 's':
    case 'del':
      return (
        <s key={key} style={style} className="line-through">
          {inner}
        </s>
      );
    case 'sub':
      return (
        <sub key={key} style={style}>
          {inner}
        </sub>
      );
    case 'sup':
      return (
        <sup key={key} style={style}>
          {inner}
        </sup>
      );
    case 'small':
      return (
        <small key={key} style={style} className="text-muted-foreground text-xs">
          {inner}
        </small>
      );
    case 'mark':
      return (
        <mark key={key} style={style} className="bg-primary/20 text-foreground rounded px-0.5">
          {inner}
        </mark>
      );
    case 'ul':
      return (
        <ul key={key} style={style} className="my-1 list-disc space-y-0.5 pl-5">
          {inner}
        </ul>
      );
    case 'ol':
      return (
        <ol key={key} style={style} className="my-1 list-decimal space-y-0.5 pl-5">
          {inner}
        </ol>
      );
    case 'li':
      return (
        <li key={key} style={style}>
          {inner}
        </li>
      );
    case 'blockquote':
      return (
        <blockquote
          key={key}
          style={style}
          className="border-border text-muted-foreground my-2 border-l-2 pl-3"
        >
          {inner}
        </blockquote>
      );
    case 'pre':
      return (
        <pre
          key={key}
          style={style}
          className="bg-secondary/60 my-2 overflow-x-auto rounded p-2 text-xs whitespace-pre-wrap"
        >
          {inner}
        </pre>
      );
    case 'code':
      return (
        <code key={key} style={style} className="bg-secondary rounded px-1 py-0.5 text-xs">
          {inner}
        </code>
      );
    case 'a': {
      const href = safeUrl(attrs.href);
      if (!href) {
        return (
          <span key={key} style={style}>
            {inner}
          </span>
        );
      }
      const external = /^https?:\/\//i.test(href);
      return (
        <a
          key={key}
          href={href}
          style={style}
          className="text-primary break-all underline underline-offset-2"
          {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
        >
          {inner}
        </a>
      );
    }
    case 'img': {
      const src = safeUrl(attrs.src);
      if (!src) return null;
      return (
        <img
          key={key}
          src={src}
          alt={attrs.alt ?? ''}
          loading="lazy"
          decoding="async"
          referrerPolicy="no-referrer"
          style={style}
          className="my-2 h-auto max-w-full rounded"
        />
      );
    }
    case 'table':
      return (
        <table key={key} style={style} className="my-2 w-full border-collapse text-xs">
          {inner}
        </table>
      );
    case 'thead':
      return <thead key={key}>{inner}</thead>;
    case 'tbody':
      return <tbody key={key}>{inner}</tbody>;
    case 'tr':
      return <tr key={key}>{inner}</tr>;
    case 'th':
      return (
        <th
          key={key}
          style={style}
          className="border-border border px-2 py-1 text-left font-medium"
        >
          {inner}
        </th>
      );
    case 'td':
      return (
        <td key={key} style={style} className="border-border border px-2 py-1 align-top">
          {inner}
        </td>
      );
    default:
      // span / font / 未知标签：当纯容器展开，保留行内样式
      return (
        <span key={key} style={style}>
          {inner}
        </span>
      );
  }
}

/** 纯文本正文：按换行分段，段内 URL 可点 */
function PlainText({ text }: { text: string }): ReactNode {
  const paragraphs = text
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
  if (paragraphs.length === 0) {
    return <p className="text-muted-foreground text-sm">（这条内容没有正文）</p>;
  }
  return (
    <div className="space-y-3">
      {paragraphs.map((p, i) => (
        <p key={i} className="text-sm leading-relaxed break-words whitespace-pre-wrap">
          {splitLinks(p).map((part, j) =>
            part.type === 'link' ? (
              <a
                key={j}
                href={part.value}
                target="_blank"
                rel="noopener noreferrer"
                className="text-primary break-all underline underline-offset-2"
              >
                {part.value}
              </a>
            ) : (
              <span key={j}>{part.value}</span>
            ),
          )}
        </p>
      ))}
    </div>
  );
}

/** 纯文本里挑出 http(s) 链接 */
export function splitLinks(text: string): { type: 'text' | 'link'; value: string }[] {
  const parts: { type: 'text' | 'link'; value: string }[] = [];
  const re = /(https?:\/\/[^\s，。）)、】]+)/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) parts.push({ type: 'text', value: text.slice(last, m.index) });
    parts.push({ type: 'link', value: m[1] });
    last = m.index + m[1].length;
  }
  if (last < text.length) parts.push({ type: 'text', value: text.slice(last) });
  return parts;
}

export interface RichTextProps {
  /** 正文：HTML 片段或纯文本 */
  text?: string | null;
  className?: string;
}

/**
 * 富文本渲染：HTML 走白名单节点树，纯文本按段落渲染。
 *
 * 不调用 `dangerouslySetInnerHTML`——所有文本节点都经 React 转义。
 */
export function RichText({ text, className }: RichTextProps): ReactNode {
  const value = text ?? '';
  if (!value.trim()) {
    return <p className="text-muted-foreground text-sm">（这条内容没有正文）</p>;
  }
  if (!looksLikeHtml(value)) {
    return (
      <div className={className}>
        <PlainText text={value} />
      </div>
    );
  }
  const nodes = parseRichText(stripDangerous(value));
  if (nodes.length === 0) {
    return <p className="text-muted-foreground text-sm">（这条内容没有正文）</p>;
  }
  return (
    <div className={className ?? 'text-sm leading-relaxed break-words'}>
      {renderNodes(nodes, 'r')}
    </div>
  );
}
