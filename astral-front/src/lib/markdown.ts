/**
 * Markdown 渲染（管理端与博客前台共用同一套 marked 配置与 HTML 结构）。
 *
 * 只做渲染，不做消毒 —— 正文来自后台编辑器，视为受信内容；
 * 公开前台展示的也是同一批数据，因此两端输出必须完全一致。
 */

import { Marked, type Token, type Tokens } from 'marked';

import { highlightCode, normalizeLanguage } from '@/lib/highlight';

/** marked 渲染器里 this.parser 的最小形状（只用到行内渲染） */
interface InlineParser {
  parseInline(tokens: Token[]): string;
}

/** 代码块 HTML：mac 风格标题条（三点 + 语言名 + 复制按钮）+ 高亮后的代码。 */
function renderCodeBlock(code: string, langRaw: string | null): string {
  const language = normalizeLanguage(langRaw);
  const label = (langRaw ?? '').trim() || 'text';
  const highlighted = highlightCode(code, language);
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

/** 图床（如 cdn.canace.cn）开了防盗链，HTML 字符串里也要声明 no-referrer。 */
function renderImage(href: string, title: string | null, text: string): string {
  const alt = text.replace(/"/g, '&quot;');
  const titleAttr = title ? ` title="${title.replace(/"/g, '&quot;')}"` : '';
  return (
    `<img src="${href.replace(/"/g, '&quot;')}" alt="${alt}"${titleAttr}` +
    ` referrerpolicy="no-referrer" loading="lazy" decoding="async">`
  );
}

/**
 * 表格 HTML：外层 .md-table 负责横向滚动。
 * 表格自身保持 display:table 撑满整行，否则 thead 会按内容宽度收缩、列错位。
 */
function renderTable(token: Tokens.Table, parser: InlineParser): string {
  const align = (cell: Tokens.TableCell): string =>
    cell.align ? ` style="text-align:${cell.align}"` : '';
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

const marked = new Marked({ gfm: true, breaks: false });

marked.use({
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

/** 渲染成 HTML 字符串（用于 dangerouslySetInnerHTML 或预览） */
export function renderMarkdown(markdown: string): string {
  if (!markdown) return '';
  const html = marked.parse(markdown, { async: false });
  return typeof html === 'string' ? html : '';
}
