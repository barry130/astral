'use client';

import { useCallback, useDeferredValue, useEffect, useId, useMemo, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { MARKDOWN_EMOJIS, MARKDOWN_SNIPPETS } from '@/lib/markdown-tools';
import { renderMarkdown } from '@/lib/markdown';
import { formatMarkdownLink } from '@/lib/markdown-insert';
import { estimateReadingMinutes } from '@/lib/format';

interface MarkdownEditorProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  fill?: boolean;
  disabled?: boolean;
  onFullscreenChange?: (fullscreen: boolean) => void;
}
type ViewMode = 'edit' | 'split' | 'preview';
type Insertion = 'link' | 'image' | null;

/** Native textarea editing, explicit Tab navigation release, and the production renderer. */
export function MarkdownEditor({
  value,
  onChange,
  placeholder,
  fill,
  disabled = false,
  onFullscreenChange,
}: MarkdownEditorProps) {
  const id = useId();
  const editor = useRef<HTMLDivElement>(null);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const preview = useRef<HTMLElement>(null);
  const selectionFrame = useRef<number | null>(null);
  const selectionVersion = useRef(0);
  const cancelSelection = useCallback(() => {
    selectionVersion.current++;
    if (selectionFrame.current !== null) cancelAnimationFrame(selectionFrame.current);
    selectionFrame.current = null;
  }, []);
  const [emojiOpen, setEmojiOpen] = useState(false);
  const selection = useRef({ start: 0, end: 0 });
  const releaseTab = useRef(false);
  const history = useRef<{ past: string[]; future: string[] }>({ past: [], future: [] });
  const [mode, setMode] = useState<ViewMode>('edit');
  const [fullscreen, setFullscreen] = useState(false);
  const composing = useRef(false);
  const stretch = fill || fullscreen;
  const changeFullscreen = (next: boolean) => {
    if (composing.current) return;
    setFullscreen(next);
    onFullscreenChange?.(next);
  };
  const toggleFullscreen = () => changeFullscreen(!fullscreen);
  const [insertion, setInsertion] = useState<Insertion>(null);
  const [label, setLabel] = useState('');
  const [url, setUrl] = useState('');
  const deferred = useDeferredValue(value);
  const html = useMemo(() => (mode === 'edit' ? '' : renderMarkdown(deferred)), [deferred, mode]);
  const minutes = useMemo(() => estimateReadingMinutes(deferred), [deferred]);
  useEffect(() => {
    if (disabled) {
      setInsertion(null);
      setEmojiOpen(false);
    }
  }, [disabled]);
  const syncScroll = useCallback(
    (source: 'editor' | 'preview') => {
      if (mode !== 'split') return;
      const from = source === 'editor' ? textarea.current : preview.current;
      const to = source === 'editor' ? preview.current : textarea.current;
      if (!from || !to) return;
      const fromRange = from.scrollHeight - from.clientHeight,
        toRange = to.scrollHeight - to.clientHeight;
      if (fromRange <= 0 || toRange <= 0) return;
      const target = Math.max(0, Math.min(1, from.scrollTop / fromRange)) * toRange;
      // Ignore quantization-sized feedback instead of recursively bouncing between panes.
      if (Math.abs(to.scrollTop - target) >= 1) to.scrollTop = target;
    },
    [mode],
  );
  useEffect(() => {
    if (mode === 'split') syncScroll('editor');
  }, [mode, syncScroll]);
  useEffect(() => {
    cancelSelection();
    return cancelSelection;
  }, [cancelSelection, disabled, mode]);

  // Bound snapshot memory as well as the number of undo actions.
  const bound = (stack: string[]) => {
    let chars = stack.reduce((sum, entry) => sum + entry.length, 0);
    while (stack.length > 50 || chars > 4_000_000) chars -= stack.shift()?.length ?? 0;
  };
  const commit = useCallback(
    (next: string) => {
      cancelSelection();
      if (disabled || next === value || next.length > 1_000_000) return;
      history.current.past.push(value);
      bound(history.current.past);
      history.current.future = [];
      onChange(next);
    },
    [cancelSelection, disabled, onChange, value],
  );
  const apply = useCallback(
    (next: string, start: number, end = start) => {
      if (disabled || next.length > 1_000_000) return;
      commit(next);
      const version = selectionVersion.current;
      const node = textarea.current;
      const owner = document.activeElement;
      selectionFrame.current = requestAnimationFrame(() => {
        if (version !== selectionVersion.current) return;
        selectionFrame.current = null;
        if (!node?.isConnected || node !== textarea.current || node.disabled || node.value !== next)
          return;
        const active = document.activeElement;
        // A removed insertion/menu owner may return to its trigger. A newer field
        // owns focus instead; never steal it to finish an obsolete formatting action.
        const returnedFromPopup =
          !owner?.isConnected &&
          (active === document.body ||
            (editor.current?.contains(active) &&
              !(active instanceof HTMLInputElement) &&
              !(active instanceof HTMLTextAreaElement)));
        if (active !== owner && active !== node && !returnedFromPopup) return;
        node.focus();
        node.setSelectionRange(start, end);
      });
    },
    [commit, disabled],
  );
  const undo = () => {
    cancelSelection();
    if (disabled || !history.current.past.length) return;
    history.current.future.push(value);
    bound(history.current.future);
    onChange(history.current.past.pop() as string);
  };
  const redo = () => {
    cancelSelection();
    if (disabled || !history.current.future.length) return;
    history.current.past.push(value);
    bound(history.current.past);
    onChange(history.current.future.pop() as string);
  };
  const wrap = (before: string, after = before, fallback = '文字') => {
    const node = textarea.current;
    if (!node || disabled) return;
    const start = node.selectionStart;
    const end = node.selectionEnd;
    const selected = value.slice(start, end) || fallback;
    apply(
      value.slice(0, start) + before + selected + after + value.slice(end),
      start + before.length,
      start + before.length + selected.length,
    );
  };
  const prefix = (text: string) => {
    const node = textarea.current;
    if (!node || disabled) return;
    const start = value.lastIndexOf('\n', node.selectionStart - 1) + 1;
    const found = value.indexOf('\n', node.selectionEnd);
    const end = found < 0 ? value.length : found;
    const lines = value.slice(start, end).split('\n');
    const remove = lines.every((line) => line.startsWith(text));
    const block = lines.map((line) => (remove ? line.slice(text.length) : text + line)).join('\n');
    apply(value.slice(0, start) + block + value.slice(end), start, start + block.length);
  };
  const block = (text: string) => {
    const node = textarea.current;
    if (!node || disabled) return;
    const start = node.selectionStart;
    const snippet = `${start ? '\n\n' : ''}${text}\n\n`;
    apply(value.slice(0, start) + snippet + value.slice(node.selectionEnd), start + snippet.length);
  };
  const insertEmoji = (emoji: string) => {
    const node = textarea.current;
    if (!node || disabled) return;
    const start = node.selectionStart,
      end = node.selectionEnd;
    apply(value.slice(0, start) + emoji + value.slice(end), start + emoji.length);
    setEmojiOpen(false);
  };
  const openInsertion = (kind: Exclude<Insertion, null>) => {
    if (disabled) return;
    const node = textarea.current;
    selection.current = {
      start: node?.selectionStart ?? value.length,
      end: node?.selectionEnd ?? value.length,
    };
    setLabel(kind === 'link' ? value.slice(selection.current.start, selection.current.end) : '');
    setUrl('https://');
    setInsertion(kind);
  };
  const snippet = insertion
    ? formatMarkdownLink(
        label.trim() || (insertion === 'image' ? '图片' : url.trim()),
        url,
        insertion === 'image',
      )
    : null;
  const insert = () => {
    if (disabled || !snippet) return;
    const { start, end } = selection.current;
    apply(value.slice(0, start) + snippet + value.slice(end), start + snippet.length);
    setInsertion(null);
  };
  const tools: Array<[string, () => void]> = [
    ['撤销', undo],
    ['重做', redo],
    ['加粗', () => wrap('**')],
    ['斜体', () => wrap('*')],
    ['删除线', () => wrap('~~')],
    ['行内代码', () => wrap('`', '`', '代码')],
    ['二级标题', () => prefix('## ')],
    ['引用', () => prefix('> ')],
    ['无序列表', () => prefix('- ')],
    ['有序列表', () => prefix('1. ')],
    ['任务列表', () => prefix('- [ ] ')],
    ['链接', () => openInsertion('link')],
    ['图片', () => openInsertion('image')],
    ['代码块', () => block('```\n代码\n```')],
    ['分割线', () => block('---')],
    ['表格', () => block('| 列一 | 列二 |\n| --- | --- |\n| 内容 | 内容 |')],
  ];
  const keyboard = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    cancelSelection();
    if (disabled || composing.current || event.nativeEvent.isComposing) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      if (fullscreen) changeFullscreen(false);
      releaseTab.current = true;
      return;
    }
    if (event.key === 'Tab' && releaseTab.current) {
      releaseTab.current = false;
      return;
    }
    releaseTab.current = false;
    const mod = event.ctrlKey || event.metaKey;
    if (mod) {
      const key = event.key.toLowerCase();
      if (key === 'b') {
        event.preventDefault();
        wrap('**');
      } else if (key === 'i') {
        event.preventDefault();
        wrap('*');
      } else if (key === 'k') {
        event.preventDefault();
        openInsertion('link');
      } else if (key === 'z') {
        event.preventDefault();
        if (event.shiftKey) redo();
        else undo();
      } else if (key === 'y') {
        event.preventDefault();
        redo();
      } else if (event.key === ']' || event.key === '[') {
        event.preventDefault();
        const node = event.currentTarget;
        const start = value.lastIndexOf('\n', node.selectionStart - 1) + 1;
        const found = value.indexOf('\n', node.selectionEnd);
        const end = found < 0 ? value.length : found;
        const text = value
          .slice(start, end)
          .split('\n')
          .map((line) => (event.key === ']' ? '  ' + line : line.replace(/^(?: {1,2}|\t)/, '')))
          .join('\n');
        apply(value.slice(0, start) + text + value.slice(end), start, start + text.length);
      }
      return;
    }
    if (event.key === 'Tab') {
      event.preventDefault();
      const node = event.currentTarget;
      if (!event.shiftKey && node.selectionStart === node.selectionEnd) {
        const caret = node.selectionStart;
        apply(value.slice(0, caret) + '  ' + value.slice(caret), caret + 2);
      } else {
        const start = value.lastIndexOf('\n', node.selectionStart - 1) + 1;
        const found = value.indexOf('\n', node.selectionEnd);
        const end = found < 0 ? value.length : found;
        const text = value
          .slice(start, end)
          .split('\n')
          .map((line) => (event.shiftKey ? line.replace(/^(?: {1,2}|\t)/, '') : '  ' + line))
          .join('\n');
        apply(value.slice(0, start) + text + value.slice(end), start, start + text.length);
      }
      return;
    }
    // Enter continues a nonempty list item.
    if (
      event.key !== 'Enter' ||
      event.shiftKey ||
      event.currentTarget.selectionStart !== event.currentTarget.selectionEnd
    )
      return;
    const node = event.currentTarget;
    const caret = node.selectionStart;
    const start = value.lastIndexOf('\n', caret - 1) + 1;
    const line = value.slice(start, caret);
    const match = /^(\s*)(?:(\d+)\. |([-+*]) )(\[[ xX]\] )?(.*)$/.exec(line);
    if (
      !match ||
      (match[2] &&
        (!Number.isSafeInteger(Number(match[2])) || Number(match[2]) >= Number.MAX_SAFE_INTEGER))
    )
      return;
    event.preventDefault();
    if (!match[5].trim()) {
      apply(value.slice(0, start) + '\n' + value.slice(caret), start + 1);
      return;
    }
    const marker = match[2] ? `${Number(match[2]) + 1}. ` : `${match[3]} `;
    const continuation = '\n' + match[1] + marker + (match[4] ? '[ ] ' : '');
    apply(value.slice(0, caret) + continuation + value.slice(caret), caret + continuation.length);
  };
  return (
    <div
      ref={editor}
      role="region"
      aria-label={fullscreen ? '正文全屏编辑' : 'Markdown 编辑器'}
      onKeyDown={(event) => {
        if (
          fullscreen &&
          event.key === 'Escape' &&
          event.currentTarget.contains(event.target as Node) &&
          event.target !== textarea.current &&
          !event.nativeEvent.isComposing &&
          !composing.current
        ) {
          event.preventDefault();
          changeFullscreen(false);
        }
      }}
      className={`border-line bg-surface focus-within:ring-brand/25 border focus-within:ring-2 ${fullscreen ? 'fixed inset-0 z-50 flex h-dvh min-h-0 flex-col overflow-hidden' : `rounded-lg ${fill ? 'flex h-full min-h-0 flex-col overflow-hidden' : ''}`}`}
    >
      <fieldset
        disabled={disabled}
        className="border-line-soft min-w-0 shrink-0 border-b p-2"
        aria-label="Markdown 工具栏"
      >
        <div className="flex flex-wrap gap-1">
          {tools.map(([text, action]) => (
            <Button
              key={text}
              type="button"
              variant="ghost"
              size="sm"
              className="h-9 px-2 text-xs max-sm:min-h-11"
              onMouseDown={(event) => event.preventDefault()}
              onClick={action}
            >
              {text}
            </Button>
          ))}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={disabled}
                onMouseDown={(event) => event.preventDefault()}
                aria-label="标题级别"
              >
                标题
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent>
              {[1, 2, 3, 4, 5, 6].map((level) => (
                <DropdownMenuItem
                  key={level}
                  disabled={disabled}
                  onSelect={() => prefix(`${'#'.repeat(level)} `)}
                >
                  标题 {level}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
          <Popover open={emojiOpen} onOpenChange={setEmojiOpen}>
            <PopoverTrigger asChild>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={disabled}
                aria-label="插入表情"
                onMouseDown={(event) => event.preventDefault()}
              >
                表情
              </Button>
            </PopoverTrigger>
            <PopoverContent
              align="start"
              aria-label="表情选择"
              className="w-[300px] max-w-[calc(100vw-2rem)] p-2"
            >
              <div className="grid max-h-[264px] grid-cols-6 gap-0.5 overflow-auto">
                {MARKDOWN_EMOJIS.map((emoji) => (
                  <button
                    key={emoji}
                    type="button"
                    disabled={disabled}
                    aria-label={`插入 ${emoji}`}
                    className="hover:bg-brand-wash min-h-11 min-w-11 rounded text-xl"
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => insertEmoji(emoji)}
                  >
                    {emoji}
                  </button>
                ))}
              </div>
            </PopoverContent>
          </Popover>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={disabled}
                aria-label="Markdown 语法速查"
                onMouseDown={(event) => event.preventDefault()}
              >
                语法速查
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="max-h-[380px] overflow-auto">
              {MARKDOWN_SNIPPETS.map((item) => (
                <DropdownMenuItem
                  key={item.label}
                  disabled={disabled}
                  data-snippet-label={item.label}
                  onSelect={() => block(item.snippet)}
                >
                  {item.label}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </fieldset>
      <div
        className="border-line-soft flex flex-wrap items-center gap-2 border-b px-3 py-2"
        role="group"
        aria-label="编辑器视图"
      >
        {(['edit', 'split', 'preview'] as const).map((view) => (
          <Button
            key={view}
            type="button"
            size="sm"
            variant={mode === view ? 'secondary' : 'ghost'}
            aria-pressed={mode === view}
            onClick={() => setMode(view)}
          >
            {view === 'edit' ? '编辑' : view === 'split' ? '分栏预览' : '预览'}
          </Button>
        ))}
        <Button
          type="button"
          size="sm"
          variant="ghost"
          aria-pressed={fullscreen}
          onMouseDown={(event) => event.preventDefault()}
          onClick={toggleFullscreen}
        >
          {fullscreen ? '退出全屏' : '全屏编辑'}
        </Button>
      </div>
      <div
        className={`${stretch ? 'min-h-0 flex-1 overflow-auto' : ''} ${mode === 'split' ? 'sm:divide-line grid grid-cols-1 sm:grid-cols-2 sm:divide-x' : ''}`}
      >
        {mode !== 'preview' ? (
          <div className={`min-w-0 ${stretch ? 'flex h-full min-h-[240px] flex-col' : ''}`}>
            <Label htmlFor={`${id}-body`} className="sr-only">
              Markdown 正文
            </Label>
            <textarea
              id={`${id}-body`}
              name="markdown"
              autoComplete="off"
              ref={textarea}
              value={value}
              onChange={(event) => commit(event.target.value)}
              onKeyDown={keyboard}
              onPointerDown={cancelSelection}
              onBlur={() => {
                releaseTab.current = false;
              }}
              onCompositionStart={() => {
                cancelSelection();
                composing.current = true;
              }}
              onCompositionEnd={() => {
                composing.current = false;
              }}
              onScroll={() => syncScroll('editor')}
              disabled={disabled}
              maxLength={1_000_000}
              aria-describedby={`${id}-help`}
              spellCheck={false}
              placeholder={placeholder ?? '支持 Markdown：标题、列表、代码块、表格、图片…'}
              className={`text-ink-body placeholder:text-ink-faint w-full resize-y bg-transparent px-4 py-3 font-mono text-sm leading-7 outline-none disabled:opacity-60 ${stretch ? 'min-h-[240px] flex-1 resize-none' : 'min-h-[440px]'}`}
            />
          </div>
        ) : null}
        {mode !== 'edit' ? (
          <section
            ref={preview}
            onScroll={() => syncScroll('preview')}
            aria-label="Markdown 预览"
            aria-busy={deferred !== value}
            className={`min-w-0 overflow-auto px-5 py-4 ${stretch ? 'h-full min-h-[240px]' : 'max-h-[70vh] min-h-[440px]'}`}
          >
            {html ? (
              <div className="markdown" dangerouslySetInnerHTML={{ __html: html }} />
            ) : (
              <p className="text-ink-soft text-sm">预览将在此显示。</p>
            )}
          </section>
        ) : null}
      </div>
      <p
        id={`${id}-help`}
        className="border-line-soft text-ink-soft shrink-0 border-t px-3 py-2 text-xs leading-6"
      >
        Tab 插入两空格 / 块缩进 · Shift+Tab 反缩进 · Esc 后 Tab / Shift+Tab 切换焦点 ·
        Ctrl/Cmd+B/I/K/Z · Ctrl/Cmd+] / [ 缩进 · {value.length} 字符 · 约 {minutes} 分钟
      </p>
      <Dialog
        open={insertion !== null}
        onOpenChange={(open) => {
          if (!open) setInsertion(null);
        }}
      >
        <DialogContent className="sm:max-w-[420px]">
          <DialogHeader>
            <DialogTitle>{insertion === 'image' ? '插入图片' : '插入链接'}</DialogTitle>
            <DialogDescription>
              只接受安全 URL；图片只接受 HTTP、HTTPS 或站内相对地址。链接文字会转义 Markdown
              分隔符。
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor={`${id}-label`}>
                {insertion === 'image' ? '图片说明' : '链接文字'}
              </Label>
              <Input
                id={`${id}-label`}
                maxLength={512}
                value={label}
                onChange={(event) => setLabel(event.target.value)}
                disabled={disabled}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor={`${id}-url`}>URL</Label>
              <Input
                id={`${id}-url`}
                maxLength={2048}
                value={url}
                onChange={(event) => setUrl(event.target.value)}
                disabled={disabled}
                autoComplete="off"
                inputMode="url"
              />
            </div>
            {url.trim() && !snippet ? (
              <p role="alert" className="text-destructive text-sm">
                地址无效或协议不允许。
              </p>
            ) : null}
          </div>
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => setInsertion(null)}>
              取消
            </Button>
            <Button type="button" onClick={insert} disabled={disabled || !snippet}>
              插入
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
