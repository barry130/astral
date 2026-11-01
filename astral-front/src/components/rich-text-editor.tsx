'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Bold,
  Code2,
  Italic,
  Link2,
  List,
  ListOrdered,
  Quote,
  Strikethrough,
  Heading2,
  Heading3,
  Pilcrow,
  Underline,
  Unlink,
  Eye,
} from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { RichText, looksLikeHtml, sanitizeRichText } from '@/components/rich-text';
import { cn } from '@/lib/utils';

/**
 * 富文本编辑器（公告 / 通知正文）。
 *
 * <p>正文口径是 HTML 片段（后端 `sys_notice.content`，客户端按标签白名单渲染），
 * 所以这里给一个所见即所得的可编辑区 + 一排工具栏，另外保留「源码」模式：
 * 运营常常是从别处整段粘 HTML 过来的，直接改源码比在可视化区里摸索更快。</p>
 *
 * <p>实现刻意不引入富文本库（Tiptap / Slate 都要几十 KB）：可编辑区就是
 * `contentEditable`，工具栏走 `document.execCommand`——它虽被标记废弃，但三大浏览器内核
 * 仍然完整支持，产出的是最朴素的 `<b>/<h3>/<ul>` 标签，正好落在客户端白名单里。</p>
 *
 * <p>写值前统一过一遍 `sanitizeRichText`：粘贴进来的 `<script>`、事件属性、外链样式
 * 在落库前就被剔掉，保存的内容与渲染结果始终一致。</p>
 */

export interface RichTextEditorProps {
  value?: string;
  onChange?: (html: string) => void;
  placeholder?: string;
  /** 可编辑区最小高度（px），默认 160 */
  minHeight?: number;
  disabled?: boolean;
  className?: string;
}

type Mode = 'visual' | 'source';

interface ToolItem {
  key: string;
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  /** 执行命令；不传则走自定义处理 */
  command?: string;
  commandValue?: string;
  run?: () => void;
}

export function RichTextEditor({
  value = '',
  onChange,
  placeholder = '输入正文，支持加粗、标题、列表、链接与图片…',
  minHeight = 160,
  disabled = false,
  className,
}: RichTextEditorProps) {
  const editorRef = useRef<HTMLDivElement>(null);
  const [mode, setMode] = useState<Mode>('visual');
  /** 内部最后一次写进 DOM 的 HTML，用来判断「这次 value 变化是不是自己引起的」 */
  const lastHtmlRef = useRef<string>('');
  const [source, setSource] = useState(value ?? '');
  const [linkOpen, setLinkOpen] = useState(false);
  const [linkUrl, setLinkUrl] = useState('https://');
  const [linkError, setLinkError] = useState('');

  /** value → DOM（外部改值、切模式时同步；光标在编辑区内且值未变时不重写，避免跳光标） */
  useEffect(() => {
    const next = value ?? '';
    setSource(next);
    const el = editorRef.current;
    if (!el || mode !== 'visual') return;
    if (lastHtmlRef.current === next) return;
    const html = sanitizeRichText(next);
    if (html === lastHtmlRef.current) return;
    el.innerHTML = html;
    lastHtmlRef.current = html;
  }, [value, mode]);

  const emit = useCallback(
    (html: string) => {
      lastHtmlRef.current = html;
      onChange?.(html);
    },
    [onChange],
  );

  /** 可编辑区内容变化 → 上抛（只做消毒，不重新赋值，避免打断输入法） */
  const handleInput = useCallback(() => {
    const el = editorRef.current;
    if (!el) return;
    emit(el.innerHTML);
  }, [emit]);

  const exec = useCallback(
    (command: string, commandValue?: string) => {
      if (disabled) return;
      editorRef.current?.focus();
      document.execCommand(command, false, commandValue);
      handleInput();
    },
    [disabled, handleInput],
  );

  const openLinkDialog = useCallback(() => {
    if (disabled) return;
    setLinkUrl('https://');
    setLinkOpen(true);
  }, [disabled]);

  const confirmLink = useCallback(() => {
    const url = linkUrl.trim();
    if (!/^https?:\/\//i.test(url)) {
      setLinkError('只支持 http(s):// 开头的链接');
      return;
    }
    setLinkOpen(false);
    setLinkError('');
    exec('createLink', url);
  }, [exec, linkUrl]);

  const tools = useMemo<ToolItem[]>(
    () => [
      { key: 'bold', icon: Bold, title: '加粗 (Ctrl+B)', command: 'bold' },
      { key: 'italic', icon: Italic, title: '斜体 (Ctrl+I)', command: 'italic' },
      { key: 'underline', icon: Underline, title: '下划线 (Ctrl+U)', command: 'underline' },
      { key: 'strike', icon: Strikethrough, title: '删除线', command: 'strikeThrough' },
      { key: 'h2', icon: Heading2, title: '小标题', command: 'formatBlock', commandValue: 'h2' },
      { key: 'h3', icon: Heading3, title: '次级标题', command: 'formatBlock', commandValue: 'h3' },
      { key: 'p', icon: Pilcrow, title: '正文段落', command: 'formatBlock', commandValue: 'p' },
      { key: 'ul', icon: List, title: '无序列表', command: 'insertUnorderedList' },
      { key: 'ol', icon: ListOrdered, title: '有序列表', command: 'insertOrderedList' },
      {
        key: 'quote',
        icon: Quote,
        title: '引用',
        command: 'formatBlock',
        commandValue: 'blockquote',
      },
      { key: 'link', icon: Link2, title: '插入链接', run: openLinkDialog },
      { key: 'unlink', icon: Unlink, title: '移除链接', command: 'unlink' },
    ],
    [openLinkDialog],
  );

  const changeMode = useCallback(
    (next: Mode) => {
      if (next === mode) return;
      if (next === 'source') {
        const html = sanitizeRichText(value ?? '');
        setSource(html);
        setMode('source');
        return;
      }
      // 源码 → 可视化：先把源码消毒再写进可编辑区
      const html = sanitizeRichText(source);
      setMode('visual');
      lastHtmlRef.current = '';
      // useEffect 会在 mode 变化后写入；这里兜底处理「源码没变、effect 因引用相同跳过的场景」
      requestAnimationFrame(() => {
        const el = editorRef.current;
        if (el && el.innerHTML !== html) {
          el.innerHTML = html;
          lastHtmlRef.current = html;
        }
      });
      emit(html);
    },
    [emit, mode, source, value],
  );

  const isEmpty = !looksLikeHtml(value ?? '') && !(value ?? '').trim();

  return (
    <div className={cn('rounded-xl border border-[color:var(--color-border)]', className)}>
      {/* 工具栏 */}
      <div className="flex flex-wrap items-center gap-0.5 border-b border-[color:var(--color-border-light)] px-1.5 py-1">
        {mode === 'visual' &&
          tools.map((tool) => {
            const Icon = tool.icon;
            return (
              <Button
                key={tool.key}
                type="button"
                variant="ghost"
                size="sm"
                title={tool.title}
                aria-label={tool.title}
                disabled={disabled}
                className="size-8 rounded-lg p-0"
                onMouseDown={(e) => e.preventDefault() /* 保住可编辑区选区 */}
                onClick={() => (tool.run ? tool.run() : exec(tool.command!, tool.commandValue))}
              >
                <Icon className="size-4" />
              </Button>
            );
          })}
        <div className="ml-auto flex items-center gap-0.5">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="h-8 gap-1 rounded-lg px-2 text-xs"
            onClick={() => changeMode(mode === 'visual' ? 'source' : 'visual')}
          >
            {mode === 'visual' ? (
              <>
                <Code2 className="size-3.5" /> 源码
              </>
            ) : (
              <>
                <Eye className="size-3.5" /> 可视化
              </>
            )}
          </Button>
        </div>
      </div>

      {/* 可编辑区 / 源码 */}
      {mode === 'visual' ? (
        <div className="relative">
          {isEmpty && (
            <span className="text-muted-foreground pointer-events-none absolute top-3 left-3.5 text-sm">
              {placeholder}
            </span>
          )}
          <div
            ref={editorRef}
            role="textbox"
            aria-multiline="true"
            aria-label="正文"
            contentEditable={!disabled}
            suppressContentEditableWarning
            onInput={handleInput}
            onBlur={() => {
              const el = editorRef.current;
              if (!el) return;
              const clean = sanitizeRichText(el.innerHTML);
              if (clean !== el.innerHTML) {
                el.innerHTML = clean;
                lastHtmlRef.current = clean;
                emit(clean);
              }
            }}
            style={{ minHeight }}
            className={cn(
              'markdown px-3.5 py-3 text-sm leading-relaxed break-words outline-none',
              disabled && 'cursor-not-allowed opacity-60',
            )}
          />
        </div>
      ) : (
        <div className="space-y-2 p-2">
          <textarea
            value={source}
            onChange={(e) => {
              setSource(e.target.value);
              lastHtmlRef.current = sanitizeRichText(e.target.value);
              onChange?.(e.target.value);
            }}
            rows={10}
            spellCheck={false}
            placeholder="<p>正文 HTML…</p>"
            className="h-auto w-full resize-y rounded-lg border border-[color:var(--color-border)] bg-[var(--color-bg-tint)] p-3 font-mono text-xs leading-relaxed outline-none"
          />
          <div className="rounded-lg border border-dashed border-[color:var(--color-border)] px-3 py-2">
            <div className="text-muted-foreground mb-1 text-[11px]">预览（与客户端渲染一致）</div>
            <RichText text={source} />
          </div>
        </div>
      )}

      <Dialog open={linkOpen} onOpenChange={(v) => !v && setLinkOpen(false)}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>插入链接</DialogTitle>
            <DialogDescription>链接会以新窗口打开，仅支持 http(s) 地址。</DialogDescription>
          </DialogHeader>
          <Input
            value={linkUrl}
            autoFocus
            placeholder="https://example.com"
            onChange={(e) => {
              setLinkUrl(e.target.value);
              setLinkError('');
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                confirmLink();
              }
            }}
          />
          {linkError && <p className="text-destructive text-xs">{linkError}</p>}
          <DialogFooter>
            <Button variant="outline" onClick={() => setLinkOpen(false)}>
              取消
            </Button>
            <Button onClick={confirmLink}>插入</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
