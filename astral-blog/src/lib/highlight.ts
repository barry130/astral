/**
 * 代码块语法高亮。
 *
 * 用 highlight.js 的 core 版本按需注册语言，只打包实际用到的语法定义，
 * 避免整包（约 1MB）进服务端产物。未注册的语言退回纯文本。
 */

import hljs from 'highlight.js/lib/core';

import bash from 'highlight.js/lib/languages/bash';
import c from 'highlight.js/lib/languages/c';
import cpp from 'highlight.js/lib/languages/cpp';
import csharp from 'highlight.js/lib/languages/csharp';
import css from 'highlight.js/lib/languages/css';
import dockerfile from 'highlight.js/lib/languages/dockerfile';
import go from 'highlight.js/lib/languages/go';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import json from 'highlight.js/lib/languages/json';
import kotlin from 'highlight.js/lib/languages/kotlin';
import less from 'highlight.js/lib/languages/less';
import markdown from 'highlight.js/lib/languages/markdown';
import nginx from 'highlight.js/lib/languages/nginx';
import php from 'highlight.js/lib/languages/php';
import plaintext from 'highlight.js/lib/languages/plaintext';
import powershell from 'highlight.js/lib/languages/powershell';
import python from 'highlight.js/lib/languages/python';
import rust from 'highlight.js/lib/languages/rust';
import scss from 'highlight.js/lib/languages/scss';
import shell from 'highlight.js/lib/languages/shell';
import sql from 'highlight.js/lib/languages/sql';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import yaml from 'highlight.js/lib/languages/yaml';

/** 语言别名 → 规范名，覆盖常见写法（js/jsx/ts/tsx/sh/html/vue 等） */
const ALIASES: Record<string, string> = {
  js: 'javascript',
  jsx: 'javascript',
  mjs: 'javascript',
  cjs: 'javascript',
  node: 'javascript',
  ts: 'typescript',
  tsx: 'typescript',
  sh: 'bash',
  zsh: 'bash',
  console: 'bash',
  shel: 'shell',
  htm: 'xml',
  html: 'xml',
  vue: 'xml',
  svg: 'xml',
  yml: 'yaml',
  py: 'python',
  'c++': 'cpp',
  cs: 'csharp',
  'c#': 'csharp',
  golang: 'go',
  ps1: 'powershell',
  conf: 'nginx',
  text: 'plaintext',
  txt: 'plaintext',
  md: 'markdown',
};

const LANGUAGES = {
  bash,
  c,
  cpp,
  csharp,
  css,
  dockerfile,
  go,
  java,
  javascript,
  json,
  kotlin,
  less,
  markdown,
  nginx,
  php,
  plaintext,
  powershell,
  python,
  rust,
  scss,
  shell,
  sql,
  typescript,
  xml,
  yaml,
};

let registered = false;

/** 注册语言（幂等）。服务端每个请求都可能走到这里，故加标记避免重复注册。 */
function ensureRegistered(): void {
  if (registered) return;
  for (const [name, definition] of Object.entries(LANGUAGES)) {
    hljs.registerLanguage(name, definition);
  }
  registered = true;
}

/** 语言名归一化；返回 null 表示不认识，调用方应退回纯文本 */
export function normalizeLanguage(raw: string | undefined | null): string | null {
  const name = (raw ?? '').trim().toLowerCase().split(/\s+/)[0];
  if (!name) return null;
  ensureRegistered();
  const resolved = ALIASES[name] ?? name;
  return hljs.getLanguage(resolved) ? resolved : null;
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** 高亮后的 HTML；语言未知时返回转义后的原文 */
export function highlightCode(code: string, language: string | null): string {
  if (!language) return escapeHtml(code);
  try {
    return hljs.highlight(code, { language, ignoreIllegals: true }).value;
  } catch {
    return escapeHtml(code);
  }
}
