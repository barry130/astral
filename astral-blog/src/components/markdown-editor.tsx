'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Bold,
  Braces,
  Code,
  Columns2,
  Eye,
  Heading2,
  Image as ImageIcon,
  Italic,
  Keyboard,
  Link2,
  List,
  ListChecks,
  ListOrdered,
  Maximize2,
  Minimize2,
  Minus,
  Pencil,
  Quote,
  Redo2,
  Smile,
  SquareCode,
  Strikethrough,
  Table,
  Undo2,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { renderMarkdown } from '@/lib/markdown';

/**
 * Markdown 编辑器（工具栏 + 编辑 / 分栏 / 预览三种视图）。
 *
 * 手感对齐原站用的 editor.md：一整排标点按钮，点一下就套到选区上，
 * 不引入 CodeMirror / Monaco（体积和依赖都不划算，正文本来就是纯 Markdown，
 * 后台用的也是纯文本域）。
 *
 * 交互约定：
 * - 有选区时套语法或加前缀；无选区时插入占位文字并选中，可直接改写；
 * - 行前缀类（标题 / 列表 / 引用）再点一次取消，按整行判定；
 * - 回车自动延续列表（有序列表递增、任务项复制未勾选状态），空项回车退出；
 * - Tab 缩进两格，Shift+Tab 反缩进，选中多行时整块缩进；
 * - Ctrl/Cmd + B / I / K 直达加粗、斜体、链接。
 */

interface MarkdownEditorProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  /**
   * 充满父容器高度（父级需有确定高度）。
   * 用于「全屏编辑工作台」：正文区吃掉剩余空间，工具栏与状态条固定不动；
   * 默认模式下编辑器按内容撑高，只保证 440px 下限。
   */
  fill?: boolean;
}

type ViewMode = 'edit' | 'split' | 'preview';

type ToolAction = {
  kind: 'action';
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  shortcut?: string;
  run: () => void;
};
type ToolDivider = { kind: 'divider' };
type Tool = ToolAction | ToolDivider;

export function MarkdownEditor({ value, onChange, placeholder, fill }: MarkdownEditorProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const previewRef = useRef<HTMLDivElement>(null);
  const [mode, setMode] = useState<ViewMode>('edit');
  const [expanded, setExpanded] = useState(false);
  const [linkOpen, setLinkOpen] = useState(false);
  const [imageOpen, setImageOpen] = useState(false);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);

  // 撤销栈自己维护：受控组件里原生 undo 会被 React 重渲染冲掉
  const history = useRef<{ past: string[]; future: string[] }>({ past: [], future: [] });

  const commit = useCallback(
    (next: string) => {
      if (next === value) return;
      history.current.past.push(value);
      history.current.future = [];
      if (history.current.past.length > 200) history.current.past.shift();
      onChange(next);
    },
    [onChange, value],
  );

  /** 写回文本并恢复选区（受控组件重渲染后再设，否则光标跳到末尾） */
  const apply = useCallback(
    (next: string, selStart: number, selEnd: number) => {
      commit(next);
      requestAnimationFrame(() => {
        const el = textareaRef.current;
        if (!el) return;
        el.focus();
        el.setSelectionRange(selStart, selEnd);
      });
    },
    [commit],
  );

  /** 用 before/after 包住选区；无选区则插入占位文字并选中 */
  const wrapSelection = useCallback(
    (before: string, after: string = before, placeholderText = '文字') => {
      const el = textareaRef.current;
      if (!el) return;
      const start = el.selectionStart;
      const end = el.selectionEnd;
      const selected = value.slice(start, end) || placeholderText;
      const next = value.slice(0, start) + before + selected + after + value.slice(end);
      apply(next, start + before.length, start + before.length + selected.length);
    },
    [apply, value],
  );

  /** 行前缀：标题、列表、引用、任务项。整段都已带前缀时再点则取消 */
  const toggleLinePrefix = useCallback(
    (prefix: string) => {
      const el = textareaRef.current;
      if (!el) return;
      const start = el.selectionStart;
      const end = el.selectionEnd;
      const lineStart = value.lastIndexOf('\n', start - 1) + 1;
      let lineEnd = value.indexOf('\n', end);
      if (lineEnd === -1) lineEnd = value.length;

      const block = value.slice(lineStart, lineEnd);
      const lines = block.split('\n');
      const allPrefixed = lines.every(
        (line) => line.startsWith(prefix) || line.startsWith(prefix.trimEnd()),
      );

      const nextLines = allPrefixed
        ? lines.map((line) =>
            line.startsWith(prefix)
              ? line.slice(prefix.length)
              : line.replace(prefix.trimEnd(), ''),
          )
        : lines.map((line) => (line.trim().length === 0 ? prefix.trimEnd() : prefix + line));

      const nextBlock = nextLines.join('\n');
      const next = value.slice(0, lineStart) + nextBlock + value.slice(lineEnd);
      // 无选区时把光标放到行尾，接着写就行
      const caret = start === end ? lineStart + nextBlock.length : lineStart + nextBlock.length;
      apply(next, caret, caret);
    },
    [apply, value],
  );

  /** 插入独立块（代码块、表格、分割线）：前后各留一个空行 */
  const insertBlock = useCallback(
    (block: string, selectFrom?: number, selectTo?: number) => {
      const el = textareaRef.current;
      if (!el) return;
      const start = el.selectionStart;
      const before = value.slice(0, start);
      const lead =
        before.length === 0 || before.endsWith('\n\n')
          ? ''
          : before.endsWith('\n')
            ? '\n'
            : '\n\n';
      const rest = value.slice(el.selectionEnd);
      const tail = rest.startsWith('\n') || rest.length === 0 ? '\n' : '\n\n';
      const next = before + lead + block + tail + rest;
      const base = before.length + lead.length;
      apply(
        next,
        base + (selectFrom ?? block.length),
        base + (selectTo ?? block.length),
      );
    },
    [apply, value],
  );

  /** 在当前行后面另起一行插入内容（列表续行用） */
  const insertAtCursor = useCallback(
    (text: string, caretOffset?: number) => {
      const el = textareaRef.current;
      if (!el) return;
      const start = el.selectionStart;
      const end = el.selectionEnd;
      const next = value.slice(0, start) + text + value.slice(end);
      const caret = start + (caretOffset ?? text.length);
      apply(next, caret, caret);
    },
    [apply, value],
  );

  const undo = useCallback(() => {
    const past = history.current.past;
    if (past.length === 0) return;
    history.current.future.push(value);
    onChange(past.pop() as string);
  }, [onChange, value]);

  const redo = useCallback(() => {
    const future = history.current.future;
    if (future.length === 0) return;
    history.current.past.push(value);
    onChange(future.pop() as string);
  }, [onChange, value]);

  const insertLink = useCallback(
    (text: string, url: string) => {
      const el = textareaRef.current;
      if (!el) return;
      const start = el.selectionStart;
      const end = el.selectionEnd;
      const selected = value.slice(start, end);
      const label = text || selected || url;
      const snippet = `[${label}](${url})`;
      const next = value.slice(0, start) + snippet + value.slice(end);
      apply(next, start + snippet.length, start + snippet.length);
    },
    [apply, value],
  );

  const insertImage = useCallback(
    (alt: string, url: string) => {
      const el = textareaRef.current;
      if (!el) return;
      const start = el.selectionStart;
      const snippet = `![${alt || '图片'}](${url})`;
      const next = value.slice(0, start) + snippet + value.slice(el.selectionEnd);
      apply(next, start + snippet.length, start + snippet.length);
    },
    [apply, value],
  );

  /** 键盘：快捷键、列表续行、Tab 缩进 */
  const onKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
      const el = event.currentTarget;
      const start = el.selectionStart;
      const end = el.selectionEnd;
      const mod = event.ctrlKey || event.metaKey;

      if (mod) {
        const key = event.key.toLowerCase();
        if (key === 'b') {
          event.preventDefault();
          wrapSelection('**');
          return;
        }
        if (key === 'i') {
          event.preventDefault();
          wrapSelection('*');
          return;
        }
        if (key === 'k') {
          event.preventDefault();
          setLinkOpen(true);
          return;
        }
        if (key === 's') {
          // 交回外层表单处理（部分浏览器默认另存为，这里只挡 Ctrl+S）
          event.preventDefault();
          return;
        }
        if (key === 'z' && !event.shiftKey) {
          event.preventDefault();
          undo();
          return;
        }
        if ((key === 'z' && event.shiftKey) || key === 'y') {
          event.preventDefault();
          redo();
          return;
        }
      }

      // Tab / Shift+Tab：整块缩进或反缩进
      if (event.key === 'Tab') {
        event.preventDefault();
        const lineStart = value.lastIndexOf('\n', start - 1) + 1;
        let lineEnd = value.indexOf('\n', end);
        if (lineEnd === -1) lineEnd = value.length;
        const block = value.slice(lineStart, lineEnd);

        if (!event.shiftKey && start === end) {
          const next = value.slice(0, start) + INDENT + value.slice(end);
          apply(next, start + INDENT.length, start + INDENT.length);
          return;
        }

        const lines = block.split('\n');
        const nextLines = event.shiftKey
          ? lines.map((line) => (line.startsWith(INDENT) ? line.slice(INDENT.length) : line.replace(/^\s{1,2}/, '')))
          : lines.map((line) => INDENT + line);
        const nextBlock = nextLines.join('\n');
        const next = value.slice(0, lineStart) + nextBlock + value.slice(lineEnd);
        apply(next, lineStart, lineStart + nextBlock.length);
        return;
      }

      // 回车：延续列表
      if (event.key === 'Enter' && !event.shiftKey && start === end) {
        const lineStart = value.lastIndexOf('\n', start - 1) + 1;
        const line = value.slice(lineStart, start);
        const matched = /^(\s*)(?:([-*+])|(\d+)\.)\s+(\[[ xX]\]\s+)?/.exec(line);
        if (matched) {
          // 空列表项再回车 → 退出列表
          if (line.trim() === matched[0].trim()) {
            event.preventDefault();
            const next = value.slice(0, lineStart) + value.slice(start);
            apply(next, lineStart, lineStart);
            return;
          }
          event.preventDefault();
          const indent = matched[1];
          const bullet = matched[2] ? matched[2] : `${Number(matched[3]) + 1}.`;
          const task = matched[4] ? '[ ] ' : '';
          const snippet = `\n${indent}${bullet} ${task}`;
          const next = value.slice(0, start) + snippet + value.slice(end);
          apply(next, start + snippet.length, start + snippet.length);
        }
      }
    },
    [apply, redo, undo, value, wrapSelection],
  );

  const html = useMemo(() => renderMarkdown(value), [value]);

  const tools: Tool[] = [
    { kind: 'action', icon: Undo2, label: '撤销', shortcut: 'Ctrl+Z', run: undo },
    { kind: 'action', icon: Redo2, label: '重做', shortcut: 'Ctrl+Shift+Z', run: redo },
    { kind: 'divider' },
    { kind: 'action', icon: Bold, label: '加粗', shortcut: 'Ctrl+B', run: () => wrapSelection('**') },
    { kind: 'action', icon: Italic, label: '斜体', shortcut: 'Ctrl+I', run: () => wrapSelection('*') },
    { kind: 'action', icon: Strikethrough, label: '删除线', run: () => wrapSelection('~~') },
    { kind: 'action', icon: Code, label: '行内代码', run: () => wrapSelection('`', '`', '代码') },
    { kind: 'divider' },
    { kind: 'action', icon: Quote, label: '引用', run: () => toggleLinePrefix('> ') },
    { kind: 'action', icon: Minus, label: '分割线', run: () => insertBlock('---') },
    { kind: 'divider' },
    { kind: 'action', icon: List, label: '无序列表', run: () => toggleLinePrefix('- ') },
    { kind: 'action', icon: ListOrdered, label: '有序列表', run: () => toggleLinePrefix('1. ') },
    { kind: 'action', icon: ListChecks, label: '任务列表', run: () => toggleLinePrefix('- [ ] ') },
    { kind: 'divider' },
    { kind: 'action', icon: Link2, label: '链接', shortcut: 'Ctrl+K', run: () => setLinkOpen(true) },
    { kind: 'action', icon: ImageIcon, label: '图片', run: () => setImageOpen(true) },
    { kind: 'action', icon: Table, label: '表格', run: () => insertBlock(TABLE_SNIPPET, 2, 5) },
    {
      kind: 'action',
      icon: SquareCode,
      label: '代码块',
      run: () => insertBlock('```\n' + '在这里写代码\n' + '```', 4, 10),
    },
  ];

  const lineCount = value.length === 0 ? 0 : value.split('\n').length;
  const charCount = value.length;
  const readingMinutes = useMemo(() => estimateReadingMinutes(value), [value]);
  const showEditor = mode !== 'preview';
  const showPreview = mode !== 'edit';
  // 全屏或 fill 模式下正文区吃掉剩余高度，工具栏/状态条固定
  const stretch = expanded || Boolean(fill);

  // —— 分栏滚动联动 ——
  // 编辑器是 textarea、右侧是渲染后的 HTML，两边行高完全不同，逐像素对齐没意义；
  // 按「可滚动高度」的比例对齐是唯一稳定的做法（也是 editor.md 的做法）。
  // syncLock 记录「哪一侧是程序滚动」，否则两侧 scroll 事件互相触发会抖成筛子。
  const syncLock = useRef<'editor' | 'preview' | null>(null);

  const syncScroll = useCallback(
    (source: 'editor' | 'preview') => {
      if (mode !== 'split') return;
      // 这一侧的 scroll 是我们自己写 scrollTop 引起的，直接忽略
      if (syncLock.current === source) return;

      const from = source === 'editor' ? textareaRef.current : previewRef.current;
      const to = source === 'editor' ? previewRef.current : textareaRef.current;
      if (!from || !to) return;

      const fromRange = from.scrollHeight - from.clientHeight;
      const toRange = to.scrollHeight - to.clientHeight;
      // 任一侧内容不够高（没有滚动条）就不同步，否则会把它硬拽到底
      if (fromRange <= 0 || toRange <= 0) return;

      const target = (from.scrollTop / fromRange) * toRange;
      if (Math.abs(to.scrollTop - target) < 1) return;

      syncLock.current = source === 'editor' ? 'preview' : 'editor';
      to.scrollTop = target;
      // 锁在下一帧释放：被同步方在同一帧内还会补发一次 scroll 事件
      requestAnimationFrame(() => {
        syncLock.current = null;
      });
    },
    [mode],
  );

  // 切进分栏视图时先按编辑器当前位置对齐一次，避免右侧停在顶部
  useEffect(() => {
    if (mode === 'split') syncScroll('editor');
  }, [mode, syncScroll]);

  return (
    <div
      className={
        // textarea 自己 outline-none（避免和圆角边框打架），焦点反馈改由外层容器承担：
        // 键盘 Tab 进来时整块出现品牌色描边，鼠标点进来也同样可见，不会出现「不知道焦点在哪」
        'transition-[border-color,box-shadow] focus-within:border-brand-line focus-within:ring-2 focus-within:ring-brand/15 ' +
        (expanded
          ? 'fixed inset-0 z-50 flex flex-col bg-canvas px-4 py-3 sm:px-6'
          : fill
            ? 'flex h-full min-h-0 flex-col overflow-hidden rounded-lg border border-line bg-surface'
            : 'overflow-hidden rounded-lg border border-line bg-surface')
      }
    >
      {expanded ? (
        <div className="mb-2 flex items-center justify-between">
          <span className="text-[12px] text-ink-soft">正文（Markdown）</span>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => setExpanded(false)}
            className="h-7 gap-1.5 px-2 text-[12px] text-ink-soft hover:text-brand"
          >
            <Minimize2 className="h-3.5 w-3.5" />
            退出全屏
          </Button>
        </div>
      ) : null}

      {/* 工具栏：所有按钮摊平后自动换行，形成 editor.md 那种两三排的手感 */}
      <div className="flex shrink-0 flex-wrap items-center gap-0.5 border-b border-line-soft px-2 py-1.5">
        {tools.map((tool, index) =>
          tool.kind === 'divider' ? (
            <span key={`d-${index}`} aria-hidden className="mx-1 h-4 w-px bg-line" />
          ) : (
            <ToolButton
              key={tool.label}
              icon={tool.icon}
              label={tool.label}
              shortcut={tool.shortcut}
              run={tool.run}
            />
          ),
        )}

        <span aria-hidden className="mx-1 h-4 w-px bg-line" />

        {/* 标题级别收进下拉，省掉 6 个按钮 */}
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              title="标题级别"
              aria-label="标题级别"
              className="inline-flex h-7 items-center gap-1 rounded-md px-1.5 text-ink-soft transition-colors hover:bg-brand-wash hover:text-brand"
            >
              <Heading2 className="h-3.5 w-3.5" />
              <span className="text-[10.5px] tabular-nums">H</span>
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="min-w-[132px]">
            <DropdownMenuLabel className="text-[11px] font-normal text-ink-faint">
              标题级别
            </DropdownMenuLabel>
            {[1, 2, 3, 4, 5].map((level) => (
              <DropdownMenuItem
                key={level}
                onSelect={() => toggleLinePrefix(`${'#'.repeat(level)} `)}
                className="cursor-pointer text-[12.5px]"
              >
                标题 {level}
                <span className="ml-auto pl-4 font-mono text-[11px] text-ink-faint">
                  {'#'.repeat(level)}
                </span>
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>

        {/* 表情 */}
        <Popover>
          <PopoverTrigger asChild>
            <button
              type="button"
              title="插入表情"
              aria-label="插入表情"
              className="inline-flex h-7 w-7 items-center justify-center rounded-md text-ink-soft transition-colors hover:bg-brand-wash hover:text-brand"
            >
              <Smile className="h-3.5 w-3.5" />
            </button>
          </PopoverTrigger>
          <PopoverContent align="start" className="w-[286px] p-2">
            <div className="max-h-[184px] overflow-y-auto">
              <div className="grid grid-cols-10 gap-0.5">
                {EMOJIS.map((emoji) => (
                  <button
                    key={emoji}
                    type="button"
                    onClick={() => insertAtCursor(emoji)}
                    className="flex h-6 w-6 items-center justify-center rounded text-[15px] leading-none transition-colors hover:bg-brand-wash"
                  >
                    {emoji}
                  </button>
                ))}
              </div>
            </div>
          </PopoverContent>
        </Popover>

        {/* 语法速查 */}
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              title="Markdown 语法速查"
              aria-label="Markdown 语法速查"
              className="inline-flex h-7 w-7 items-center justify-center rounded-md text-ink-soft transition-colors hover:bg-brand-wash hover:text-brand"
            >
              <Braces className="h-3.5 w-3.5" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="max-h-[380px] w-[330px] overflow-y-auto">
            <DropdownMenuLabel className="text-[11px] font-normal text-ink-faint">
              点任意一行即可插入
            </DropdownMenuLabel>
            {CHEATSHEET.map((item) => (
              <DropdownMenuItem
                key={item.label}
                onSelect={() => insertAtCursor(item.snippet, item.snippet.length)}
                className="flex cursor-pointer items-center gap-3 text-[12px]"
              >
                <span className="w-[86px] shrink-0 text-ink-soft">{item.label}</span>
                <code className="truncate font-mono text-[11px] text-ink-faint">{item.example}</code>
              </DropdownMenuItem>
            ))}
            <DropdownMenuSeparator />
            <DropdownMenuItem
              onSelect={() => setShortcutsOpen(true)}
              className="cursor-pointer text-[12px] text-ink-soft"
            >
              <Keyboard className="mr-2 h-3.5 w-3.5" />
              快捷键一览
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>

        <div className="ml-auto flex items-center gap-0.5">
          {/* 视图切换：编辑 / 分栏 / 预览 */}
          <div className="flex items-center rounded-md bg-surface-muted p-0.5">
            <ViewButton
              active={mode === 'edit'}
              icon={Pencil}
              label="仅编辑"
              onClick={() => setMode('edit')}
            />
            <ViewButton
              active={mode === 'split'}
              icon={Columns2}
              label="分栏预览"
              onClick={() => setMode('split')}
              className="hidden sm:inline-flex"
            />
            <ViewButton
              active={mode === 'preview'}
              icon={Eye}
              label="仅预览"
              onClick={() => setMode('preview')}
            />
          </div>
          <button
            type="button"
            onClick={() => setExpanded((v) => !v)}
            title={expanded ? '退出全屏' : '全屏编辑'}
            aria-label={expanded ? '退出全屏' : '全屏编辑'}
            className="ml-0.5 inline-flex h-7 w-7 items-center justify-center rounded-md text-ink-soft transition-colors hover:bg-brand-wash hover:text-brand"
          >
            {expanded ? (
              <Minimize2 className="h-3.5 w-3.5" />
            ) : (
              <Maximize2 className="h-3.5 w-3.5" />
            )}
          </button>
        </div>
      </div>

      {/* 编辑 / 预览 区域 */}
      <div
        className={`flex ${stretch ? 'min-h-0 flex-1' : ''} ${
          mode === 'split' ? 'divide-x divide-line-soft' : ''
        }`}
      >
        {showEditor ? (
          <textarea
            ref={textareaRef}
            value={value}
            onChange={(event) => commit(event.target.value)}
            onKeyDown={onKeyDown}
            onScroll={() => syncScroll('editor')}
            placeholder={placeholder ?? '支持 Markdown：标题、列表、代码块、表格、图片……'}
            spellCheck={false}
            className={`w-full resize-y bg-transparent px-4 py-3 font-mono text-[12.5px] leading-[1.8] text-ink-body outline-none placeholder:text-ink-faint ${
              mode === 'split' ? 'min-w-0 flex-1' : ''
            } ${
              stretch
                ? 'min-h-0 flex-1 resize-none'
                : mode === 'split'
                  ? 'min-h-[440px]'
                  : 'min-h-[440px]'
            }`}
          />
        ) : null}

        {showPreview ? (
          <div
            ref={previewRef}
            onScroll={() => syncScroll('preview')}
            className={`overflow-y-auto px-5 py-4 ${
              mode === 'split' ? 'min-w-0 flex-1' : 'w-full'
            } ${stretch ? 'min-h-0 flex-1' : 'max-h-[70vh] min-h-[440px]'}`}
          >
            <div
              className="markdown"
              // 预览的是正在写的正文，与详情页同一套渲染管线
              dangerouslySetInnerHTML={{ __html: html || EMPTY_PREVIEW }}
            />
          </div>
        ) : null}
      </div>

      <div className="flex items-center justify-between gap-3 border-t border-line-soft px-3 py-1.5 text-[11px] text-ink-faint">
        <button
          type="button"
          onClick={() => setShortcutsOpen(true)}
          className="inline-flex items-center gap-1 transition-colors hover:text-brand"
        >
          <Keyboard className="h-3 w-3" />
          Markdown
        </button>
        <span className="tabular-nums">
          {lineCount} 行 · {charCount} 字 · 约 {readingMinutes} 分钟
        </span>
      </div>

      <LinkDialog
        open={linkOpen}
        onOpenChange={setLinkOpen}
        selection={selectionOf(textareaRef.current)}
        onSubmit={(text, url) => {
          insertLink(text, url);
          setLinkOpen(false);
        }}
      />
      <ImageDialog
        open={imageOpen}
        onOpenChange={setImageOpen}
        onSubmit={(alt, url) => {
          insertImage(alt, url);
          setImageOpen(false);
        }}
      />
      <ShortcutsDialog open={shortcutsOpen} onOpenChange={setShortcutsOpen} />
    </div>
  );
}

function ToolButton({
  icon: Icon,
  label,
  shortcut,
  run,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  shortcut?: string;
  run: () => void;
}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          type="button"
          aria-label={label}
          // preventDefault：避免点击按钮时文本域失焦，选区丢了会插到错误位置
          onMouseDown={(event) => event.preventDefault()}
          onClick={run}
          className="inline-flex h-7 w-7 items-center justify-center rounded-md text-ink-soft transition-colors hover:bg-brand-wash hover:text-brand"
        >
          <Icon className="h-3.5 w-3.5" />
        </button>
      </TooltipTrigger>
      <TooltipContent side="bottom" className="text-[11px]">
        {label}
        {shortcut ? <span className="ml-1.5 text-ink-faint">{shortcut}</span> : null}
      </TooltipContent>
    </Tooltip>
  );
}

function ViewButton({
  active,
  icon: Icon,
  label,
  onClick,
  className,
}: {
  active: boolean;
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  onClick: () => void;
  className?: string;
}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          type="button"
          aria-label={label}
          aria-pressed={active}
          onClick={onClick}
          className={`inline-flex h-6 w-7 items-center justify-center rounded transition-colors ${className ?? ''} ${
            active ? 'bg-surface text-brand shadow-sm' : 'text-ink-faint hover:text-ink-soft'
          }`}
        >
          <Icon className="h-3.5 w-3.5" />
        </button>
      </TooltipTrigger>
      <TooltipContent side="bottom" className="text-[11px]">
        {label}
      </TooltipContent>
    </Tooltip>
  );
}

function LinkDialog({
  open,
  onOpenChange,
  selection,
  onSubmit,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  selection: string;
  onSubmit: (text: string, url: string) => void;
}) {
  const [text, setText] = useState('');
  const [url, setUrl] = useState('https://');

  useEffect(() => {
    if (!open) return;
    setText(selection);
    setUrl('https://');
  }, [open, selection]);

  const valid = url.trim().length > 'https://'.length;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-[420px]">
        <DialogHeader>
          <DialogTitle className="text-[15px]">插入链接</DialogTitle>
          <DialogDescription className="text-[12px]">
            链接文字留空时用地址本身。
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-3 py-1">
          <div className="space-y-1.5">
            <Label htmlFor="md-link-text" className="text-[12px] text-ink-soft">
              链接文字
            </Label>
            <Input
              id="md-link-text"
              value={text}
              onChange={(event) => setText(event.target.value)}
              placeholder="例如：Typecho 官方文档"
              className="h-8 text-[12.5px]"
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="md-link-url" className="text-[12px] text-ink-soft">
              链接地址
            </Label>
            <Input
              id="md-link-url"
              value={url}
              onChange={(event) => setUrl(event.target.value)}
              placeholder="https://"
              className="h-8 font-mono text-[12px]"
            />
          </div>
        </div>
        <DialogFooter>
          <Button type="button" variant="ghost" size="sm" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button
            type="button"
            size="sm"
            disabled={!valid}
            onClick={() => onSubmit(text.trim(), url.trim())}
          >
            插入
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ImageDialog({
  open,
  onOpenChange,
  onSubmit,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (alt: string, url: string) => void;
}) {
  const [alt, setAlt] = useState('');
  const [url, setUrl] = useState('https://');

  useEffect(() => {
    if (!open) return;
    setAlt('');
    setUrl('https://');
  }, [open]);

  const valid = url.trim().length > 'https://'.length;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-[420px]">
        <DialogHeader>
          <DialogTitle className="text-[15px]">插入图片</DialogTitle>
          <DialogDescription className="text-[12px]">
            粘贴图床地址即可；图片说明会作为 alt 文本显示。
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-3 py-1">
          <div className="space-y-1.5">
            <Label htmlFor="md-image-alt" className="text-[12px] text-ink-soft">
              图片说明
            </Label>
            <Input
              id="md-image-alt"
              value={alt}
              onChange={(event) => setAlt(event.target.value)}
              placeholder="例如：架构图"
              className="h-8 text-[12.5px]"
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="md-image-url" className="text-[12px] text-ink-soft">
              图片地址
            </Label>
            <Input
              id="md-image-url"
              value={url}
              onChange={(event) => setUrl(event.target.value)}
              placeholder="https://cdn.example.com/a.png"
              className="h-8 font-mono text-[12px]"
            />
          </div>
          {valid ? (
            <div className="overflow-hidden rounded-md border border-line-soft bg-surface-muted p-2">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={url.trim()}
                alt={alt || '预览'}
                referrerPolicy="no-referrer"
                className="mx-auto max-h-[160px] w-auto rounded"
              />
            </div>
          ) : null}
        </div>
        <DialogFooter>
          <Button type="button" variant="ghost" size="sm" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button
            type="button"
            size="sm"
            disabled={!valid}
            onClick={() => onSubmit(alt.trim(), url.trim())}
          >
            插入
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ShortcutsDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-[460px]">
        <DialogHeader>
          <DialogTitle className="text-[15px]">Markdown 快捷键</DialogTitle>
          <DialogDescription className="text-[12px]">
            在编辑区生效；Mac 用 ⌘ 代替 Ctrl。
          </DialogDescription>
        </DialogHeader>
        <div className="divide-y divide-line-soft py-1">
          {SHORTCUTS.map((item) => (
            <div key={item.keys} className="flex items-center justify-between py-1.5">
              <span className="text-[12.5px] text-ink-body">{item.action}</span>
              <kbd className="rounded border border-line bg-surface-muted px-1.5 py-0.5 font-mono text-[11px] text-ink-soft">
                {item.keys}
              </kbd>
            </div>
          ))}
        </div>
        <DialogFooter>
          <Button type="button" size="sm" onClick={() => onOpenChange(false)}>
            知道了
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function selectionOf(el: HTMLTextAreaElement | null): string {
  if (!el) return '';
  const text = el.value.slice(el.selectionStart, el.selectionEnd);
  // 选区太长就不预填到链接文字里
  return text.length > 60 ? '' : text.trim();
}

function estimateReadingMinutes(markdown: string): number {
  const text = markdown
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`[^`]*`/g, ' ')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/[#>*_~|-]/g, ' ');
  const chinese = (text.match(/[\u4e00-\u9fa5]/g) ?? []).length;
  const words = text
    .replace(/[\u4e00-\u9fa5]/g, ' ')
    .split(/\s+/)
    .filter(Boolean).length;
  return Math.max(1, Math.round(chinese / 350 + words / 200));
}

const INDENT = '  ';

const TABLE_SNIPPET = ['| 列 1 | 列 2 | 列 3 |', '| --- | --- | --- |', '| 内容 | 内容 | 内容 |'].join(
  '\n',
);

const EMPTY_PREVIEW = '<p style="color:#8a8a92">还没有内容。</p>';

const EMOJIS = [
  '😀', '😁', '😂', '🤣', '😃', '😄', '😅', '😆', '😉', '😊',
  '😋', '😎', '😍', '😘', '🙂', '😐', '😑', '😶', '🙁', '😣',
  '😥', '😮', '🤐', '😯', '😪', '😫', '😴', '😌', '😛', '😜',
  '😝', '🤤', '😒', '😓', '😔', '😕', '🙃', '🤑', '😲', '🙄',
  '😤', '😭', '😨', '😩', '🤯', '😬', '😰', '😱', '🥵', '🥶',
  '😳', '🤪', '😵', '😡', '😠', '🤬', '😷', '🤒', '🤕', '🤢',
  '🤮', '🤧', '😇', '🤠', '🤡', '🤥', '🤫', '🤭', '🧐', '🤓',
  '😈', '👻', '💀', '☠️', '👽', '🤖', '💩', '😺', '😸', '😹',
  '👍', '👎', '👌', '✌️', '🤞', '🤟', '🤘', '🤙', '👈', '👉',
  '👆', '👇', '☝️', '✋', '🤚', '👋', '🤝', '🙏', '💪', '🎉',
  '🎊', '🔥', '✨', '💯', '⭐', '🌟', '❤️', '💔', '💖', '💙',
  '💚', '💛', '🧡', '💜', '🖤', '🤍', '✅', '❌', '⚠️', '❓',
];

const CHEATSHEET = [
  { label: '一级标题', snippet: '# 标题', example: '# 标题' },
  { label: '二级标题', snippet: '## 标题', example: '## 标题' },
  { label: '加粗', snippet: '**加粗文字**', example: '**加粗**' },
  { label: '斜体', snippet: '*斜体文字*', example: '*斜体*' },
  { label: '删除线', snippet: '~~删除文字~~', example: '~~删除~~' },
  { label: '行内代码', snippet: '`code`', example: '`code`' },
  { label: '代码块', snippet: '```java\n代码\n```', example: '```java' },
  { label: '引用', snippet: '> 引用内容', example: '> 引用' },
  { label: '无序列表', snippet: '- 列表项', example: '- 项' },
  { label: '有序列表', snippet: '1. 列表项', example: '1. 项' },
  { label: '任务列表', snippet: '- [ ] 待办', example: '- [ ] 待办' },
  { label: '链接', snippet: '[文字](https://)', example: '[文字](url)' },
  { label: '图片', snippet: '![说明](https://)', example: '![alt](url)' },
  { label: '表格', snippet: TABLE_SNIPPET, example: '| a | b |' },
  { label: '分割线', snippet: '---', example: '---' },
  { label: '折叠块', snippet: '<details>\n<summary>展开</summary>\n\n内容\n\n</details>', example: '<details>' },
  { label: '脚注', snippet: '[^1]\n\n[^1]: 说明', example: '[^1]' },
];

const SHORTCUTS = [
  { action: '加粗', keys: 'Ctrl+B' },
  { action: '斜体', keys: 'Ctrl+I' },
  { action: '插入链接', keys: 'Ctrl+K' },
  { action: '撤销', keys: 'Ctrl+Z' },
  { action: '重做', keys: 'Ctrl+Shift+Z' },
  { action: '缩进 / 反缩进', keys: 'Tab / Shift+Tab' },
  { action: '列表续行', keys: 'Enter' },
  { action: '退出列表', keys: '空项处 Enter' },
];
