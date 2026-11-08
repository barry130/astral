import { Marked, type Token, type Tokens } from 'marked';

import { highlightCode, normalizeLanguage } from '@/lib/highlight';
import { escapeHtml, safeImageUrl, safeUrl } from '@/lib/url';

/** marked 渲染器里 this.parser 的最小形状（只用到行内渲染） */
interface InlineParser {
  parseInline(tokens: Token[]): string;
}

/**
 * 正文里允许原样输出的 HTML 标签，**只允许不带任何属性**。
 *
 * 为什么要有这层白名单：CMS 的写权限 `admin:cms:content:edit` 是可下放的（后端 AGENTS.md
 * 「写接口的粒度取舍」明确把它列为可下放码），也就是内容编辑者不是超管；而正文 HTML 是
 * 直接 innerHTML 进公开页面的。一旦放行任意 HTML，一个内容编辑就能在公开页塞
 * `<script>` / `<img onerror>`，对所有读者构成存储型 XSS，还能用读者里的管理员会话反打
 * 管理接口（同源 /api/admin/**）。
 *
 * 这里只放行「纯排版、无属性」的标签：没有属性就没有 on* 事件、没有 src/href、没有 style，
 * 剩下的标签自身不制造可执行上下文。script/style/iframe/object/embed/svg/math/form/a/img
 * 一律不在表内 —— 链接与图片必须走 Markdown 语法，由上面的 renderer 过 safeUrl 协议白名单。
 */
const RAW_HTML_TAGS = new Set([
  'abbr',
  'b',
  'blockquote',
  'br',
  'cite',
  'code',
  'dd',
  'del',
  'details',
  'div',
  'dl',
  'dt',
  'em',
  'figcaption',
  'figure',
  'hr',
  'i',
  'ins',
  'kbd',
  'li',
  'mark',
  'ol',
  'p',
  'q',
  's',
  'small',
  'span',
  'strong',
  'sub',
  'summary',
  'sup',
  'time',
  'u',
  'ul',
  'wbr',
]);

/** 只匹配「开标签 / 闭标签，且没有属性、没有自闭合斜杠之外的杂音」 */
const RAW_TAG_PATTERN = /^<\s*(\/?)\s*([a-zA-Z][a-zA-Z0-9]*)\s*>$/;

/**
 * 把一段原样 HTML 收敛成「白名单标签 + 其余一律转义」。
 *
 * 只逐段检查标签本身，标签之间的文字保持原样（纯文本没有可执行能力）。
 * 不在白名单内的 `<` 只转义这一个字符、游标前进一位，避免把一个 `<` 当成整段标签吞掉，
 * 导致后面的合法标签也跟着失效。
 */
export function sanitizeRawHtml(raw: string): string {
  if (!raw) return '';
  let out = '';
  let index = 0;
  while (index < raw.length) {
    const open = raw.indexOf('<', index);
    if (open < 0) {
      out += raw.slice(index);
      break;
    }
    out += raw.slice(index, open);
    const close = raw.indexOf('>', open);
    if (close < 0) {
      out += escapeHtml(raw.slice(open));
      break;
    }
    const match = RAW_TAG_PATTERN.exec(raw.slice(open, close + 1));
    const tag = match ? match[2].toLowerCase() : '';
    if (match && RAW_HTML_TAGS.has(tag)) {
      out += `<${match[1]}${tag}>`;
      index = close + 1;
    } else {
      out += escapeHtml('<');
      index = open + 1;
    }
  }
  return out;
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
      link({ href, title, tokens }) {
        const inner = this.parser.parseInline(tokens);
        const url = safeUrl(href);
        if (!url) {
          // 不安全协议不生成可点击链接，保留原文字避免内容消失
          return `<span class="md-unsafe-link">${inner}</span>`;
        }
        const titleAttr = title ? ` title="${escapeHtml(title)}"` : '';
        return `<a href="${escapeHtml(url)}"${titleAttr}>${inner}</a>`;
      },
      // 原样 HTML 走白名单：落笔即消毒，评论区之类不经此渲染器的入口另走纯文本。
      html({ text }) {
        return sanitizeRawHtml(text);
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
    `<div class="md-code" data-lang="${escapeHtml(label)}">` +
    `<div class="md-code-bar">` +
    `<span class="md-code-dots" aria-hidden="true"><i></i><i></i><i></i></span>` +
    `<span class="md-code-lang">${escapeHtml(label)}</span>` +
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
  const url = safeImageUrl(href);
  if (!url) return `<span class="md-unsafe-image">${escapeHtml(text)}</span>`;
  const alt = escapeHtml(text);
  const titleAttr = title ? ` title="${escapeHtml(title)}"` : '';
  return (
    `<img src="${escapeHtml(url)}" alt="${alt}"${titleAttr}` +
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
    token.header
      .map((cell) => `<th${align(cell)}>${parser.parseInline(cell.tokens)}</th>`)
      .join('') +
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
  const used = new Set<string>();
  return (rawText: string): string => {
    const base =
      rawText
        .toLowerCase()
        .replace(/[^\p{L}\p{N}\s-]/gu, ' ')
        .trim()
        .replace(/\s+/g, '-')
        .slice(0, 64)
        .replace(/^-+|-+$/g, '') || 'section';
    let candidate = base;
    let suffix = 2;
    while (used.has(candidate)) candidate = `${base}-${suffix++}`;
    used.add(candidate);
    return candidate;
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
 * - 与编辑预览共用 HTML / URL 白名单，不把可下放的内容编辑权限当作可信 HTML
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
 *
 * 消毒边界（不要退回「作者可信所以不消毒」）：正文确实由后台撰写，但写权限
 * `admin:cms:content:edit` 可下放给非超管内容编辑者，而产物是公开页面的 innerHTML。
 * 因此 Markdown 语法内的链接/图片过 safeUrl 协议白名单，正文里的原样 HTML 过
 * sanitizeRawHtml 标签白名单（无属性），两条路径都不允许注入可执行内容。
 * 公开评论不走此函数（comment-list 按纯文本渲染）。
 */
export function renderMarkdown(markdown: string | null | undefined): string {
  if (!markdown) return '';
  const html = plain.parse(markdown, { async: false });
  return typeof html === 'string' ? html : '';
}
