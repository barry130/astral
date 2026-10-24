import { Marked } from 'marked';
import type { Token, Tokens } from 'marked';

import { highlightCode, normalizeLanguage } from '@/lib/highlight';

/** marked 渲染器里 this.parser 的最小形状（只用到行内渲染） */
interface InlineParser {
  parseInline(tokens: Token[]): string;
}

/**
 * 两个渲染器共用的钩子：代码块高亮 + 图片防盗链绕过。
 * 每次 parse 前都调用，因为 marked 的 renderer 挂在实例上、不能跨实例复用。
 */
function applyCommonRenderers(parser: Marked): void {
  parser.use({
    renderer: {
      code({ text, lang }) {
        return renderCodeBlock(text, lang ?? null);
      },
      image({ href, title, text }) {
        return renderImage(href, title ?? null, text);
      },
      table(token) {
        return renderTable(token, this.parser as unknown as InlineParser);
      },
    },
  });
}

// 不含目录钩子的渲染器（评论等处如需 Markdown 可复用）。
// 这个实例只读配置、不受并发影响：每次 parse 都是独立调用。
const plain = new Marked({ gfm: true, breaks: false });

applyCommonRenderers(plain);

/**
 * 代码块 HTML：mac 风格标题条（三个圆点 + 语言名 + 复制按钮）+ 高亮后的代码。
 * 复制按钮本身不含逻辑，由客户端组件 <MarkdownEnhance /> 做事件委托。
 */
function renderCodeBlock(code: string, langRaw: string | null): string {
  const language = normalizeLanguage(langRaw);
  const label = (langRaw ?? '').trim() || 'text';
  const highlighted = highlightCode(code, language);
  // hljs 输出的 class 用于着色，没有对应语言时保持原文
  const className = language ? `hljs language-${language}` : 'hljs';
  return (
    `<div class="md-code" data-lang="${label.replace(/"/g, '')}">` +
    `<div class="md-code-bar">` +
    `<span class="md-code-dots" aria-hidden="true"><i></i><i></i><i></i></span>` +
    `<span class="md-code-lang">${label.replace(/</g, '&lt;')}</span>` +
    `<button class="md-code-copy" type="button" aria-label="复制代码">复制</button>` +
    `</div>` +
    `<pre><code class="${className}">${highlighted}</code></pre>` +
    `</div>\n`
  );
}

/**
 * 给正文里的图片补 referrerPolicy：图床（如 cdn.canace.cn）开了防盗链，
 * 带 Referer 的请求会被 403，前端 <img> 能逐个声明，HTML 字符串只能在这里加。
 */
function renderImage(href: string, title: string | null, text: string): string {
  const alt = text.replace(/"/g, '&quot;');
  const titleAttr = title ? ` title="${title.replace(/"/g, '&quot;')}"` : '';
  return (
    `<img src="${href.replace(/"/g, '&quot;')}" alt="${alt}"${titleAttr}` +
    ` referrerpolicy="no-referrer" loading="lazy" decoding="async">`
  );
}

/**
 * 表格 HTML：外面包一层可横向滚动的容器。
 *
 * 直接给 <table> 设 display:block + overflow-x 能让宽表格滚动，
 * 但 thead 会按内容宽度收缩、右侧留白且列错位；
 * 因此表格保持 display:table 撑满整行，滚动交给外层 .md-table。
 */
function renderTable(token: Tokens.Table, parser: InlineParser): string {
  const align = (cell: Tokens.TableCell): string => {
    if (!cell.align) return '';
    return ` style="text-align:${cell.align}"`;
  };
  const head =
    `<thead><tr>` +
    token.header.map((cell) => `<th${align(cell)}>${parser.parseInline(cell.tokens)}</th>`).join('') +
    `</tr></thead>`;
  const body = token.rows
    .map(
      (row) =>
        `<tr>` +
        row.map((cell) => `<td${align(cell)}>${parser.parseInline(cell.tokens)}</td>`).join('') +
        `</tr>`,
    )
    .join('');
  const bodyPart = body ? `<tbody>${body}</tbody>` : '';
  return `<div class="md-table"><table>${head}${bodyPart}</table></div>\n`;
}

/** 目录收录的最深标题层级（h2 / h3） */
const TOC_MAX_DEPTH = 3;

/**
 * 目录节点。children 用于表达标题层级嵌套（h2 下挂 h3 等），
 * 深度差大于 1 时也能正确挂到最近的祖先上。
 */
export interface TocItem {
  id: string;
  text: string;
  depth: number;
  children: TocItem[];
}

export interface RenderedMarkdown {
  html: string;
  toc: TocItem[];
}

/**
 * 由标题纯文本生成稳定的锚点 id。
 * 保留中英文与数字（HTML5 允许），其余符号压成连字符；
 * 同名标题追加 -2 / -3 保证全页唯一。
 */
function createAnchors() {
  const used = new Map<string, number>();
  return (rawText: string): string => {
    const base =
      rawText
        .toLowerCase()
        .replace(/[^\p{L}\p{N}\s-]/gu, ' ')
        .trim()
        .replace(/\s+/g, '-')
        .slice(0, 64)
        .replace(/^-+|-+$/g, '') || 'section';
    const seen = used.get(base) ?? 0;
    used.set(base, seen + 1);
    return seen === 0 ? base : `${base}-${seen + 1}`;
  };
}

/** 把标题里的行内 HTML（<code>/<em> 等）还原成纯文本，供目录显示 */
function toPlainText(html: string): string {
  return html
    .replace(/<[^>]*>/g, '')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ')
    .trim();
}

/** 比较标题是否等同时用的归一化：去空白、去标点、转小写 */
function normalizeHeadingText(text: string): string {
  return text
    .toLowerCase()
    .replace(/[\s\u3000]+/g, '')
    .replace(/[^\p{L}\p{N}]/gu, '');
}

/** 按 depth 把扁平标题压成树；跳级（h2 → h4）挂到最近的更浅祖先下 */
function buildToc(flat: TocItem[]): TocItem[] {
  const roots: TocItem[] = [];
  const stack: TocItem[] = [];
  for (const item of flat) {
    while (stack.length > 0 && stack[stack.length - 1].depth >= item.depth) {
      stack.pop();
    }
    if (stack.length === 0) {
      roots.push(item);
    } else {
      stack[stack.length - 1].children.push(item);
    }
    stack.push(item);
  }
  return roots;
}

export interface TocOptions {
  /**
   * 文章标题。正文第一个 h1 若与之相同，说明是「标题也写进了正文」的老习惯，
   * 跳过它以免目录和页面标题重复；不同则是正文里的真实章节，照常收录。
   */
  articleTitle?: string | null;
}

/**
 * 服务端把文章 / Wiki 页面的 Markdown 正文渲染为 HTML，并同时抽取标题目录。
 *
 * - 标题带上锚点 id（详情页左侧 TOC 点击跳转用）
 * - 内容由后台管理员（受信方）撰写，故不做额外消毒
 * - 每次调用使用独立 Marked 实例，避免 heading 计数器在并发请求间串味
 */
export function renderMarkdownWithToc(
  markdown: string | null | undefined,
  options: TocOptions = {},
): RenderedMarkdown {
  if (!markdown) return { html: '', toc: [] };

  const titleKey = normalizeHeadingText(options.articleTitle ?? '');
  const nextAnchor = createAnchors();
  const flat: TocItem[] = [];
  let seenHeading = false;
  const parser = new Marked({ gfm: true, breaks: false });
  applyCommonRenderers(parser);

  parser.use({
    renderer: {
      heading({ tokens, depth }) {
        const inner = this.parser.parseInline(tokens);
        const text = toPlainText(inner);
        const id = nextAnchor(text);
        // 目录收录 h1~h3。h4 及更深多为文内小点，一篇文章能列到几十条，
        // 收进来反而失去导航价值。
        const isFirstHeading = !seenHeading;
        seenHeading = true;
        const repeatsArticleTitle =
          depth === 1 &&
          isFirstHeading &&
          titleKey.length > 0 &&
          normalizeHeadingText(text) === titleKey;
        if (depth <= TOC_MAX_DEPTH && !repeatsArticleTitle) {
          flat.push({ id, text, depth, children: [] });
        }
        return `<h${depth} id="${id}">${inner}</h${depth}>\n`;
      },
    },
  });

  const html = parser.parse(markdown, { async: false });
  return {
    html: typeof html === 'string' ? html : '',
    toc: buildToc(flat),
  };
}

/**
 * 服务端把文章 / Wiki 页面的 Markdown 正文渲染为 HTML。
 * 内容由后台管理员（受信方）撰写，故不做额外消毒；
 * 公开评论不经过此函数（纯文本渲染）。
 */
export function renderMarkdown(markdown: string | null | undefined): string {
  if (!markdown) return '';
  const html = plain.parse(markdown, { async: false });
  return typeof html === 'string' ? html : '';
}
