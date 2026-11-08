'use client';

import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import dynamic from 'next/dynamic';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { Download } from 'lucide-react';
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
import { Textarea } from '@/components/ui/textarea';
import { Switch } from '@/components/ui/switch';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { useSession } from '@/components/session-provider';
import { requestJson } from '@/lib/client-request';
import { parseResult, resultMessage } from '@/lib/result';
import { safeImageUrl } from '@/lib/url';
import { cn } from '@/lib/utils';
import {
  flattenCategoryOptions,
  getPageParentOptions,
  parseEditorMeta,
  parseEditorRecord,
  validateContentSave,
  type AdminPageRecord,
  type AdminPostRecord,
  type ContentKind,
  type EditorMeta,
} from '@/lib/editor';

const MarkdownEditor = dynamic(
  () => import('@/components/markdown-editor').then((mod) => mod.MarkdownEditor),
  {
    ssr: false,
    loading: () => (
      <p
        role="status"
        className="border-line bg-surface-muted text-ink-soft border border-dashed p-6 text-sm"
      >
        编辑器加载中…
      </p>
    ),
  },
);
interface Props {
  kind: ContentKind;
  id: number;
  children: React.ReactNode;
}
interface Draft {
  title: string;
  content: string;
  status: string;
  slug: string;
  publishedAt: string | null;
  summary: string;
  cover: string;
  isTop: boolean;
  categoryIds: number[];
  tagIds: number[];
  parentId: number | null;
  sort: number;
}
const EMPTY_META: EditorMeta = { categories: [], tags: [], pages: [], statuses: [] };

/** Navigation gives each content record a distinct lifecycle and draft. */
export function EditableArticle(props: Props) {
  return <ArticleWorkbench key={`${props.kind}:${props.id}`} {...props} />;
}

function ArticleWorkbench({ kind, id, children }: Props) {
  const { canEdit } = useSession();
  const router = useRouter();
  const fieldId = useId();
  const [draft, setDraft] = useState<Draft | null>(null);
  const [baseline, setBaseline] = useState('');
  const [meta, setMeta] = useState<EditorMeta>(EMPTY_META);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [bodyFullscreen, setBodyFullscreen] = useState(false);
  const mounted = useRef(true);
  const generation = useRef(0);
  const loadingLock = useRef(false);
  const savingLock = useRef(false);
  const controller = useRef<AbortController | null>(null);
  const editButton = useRef<HTMLButtonElement>(null);
  const latestDraft = useRef(draft);
  latestDraft.current = draft;
  const editable = useRef(canEdit);
  editable.current = canEdit;
  const endpoint = `/api/admin/${kind}/${id}`;
  const serialized = JSON.stringify(draft);
  const dirty = draft !== null && serialized !== baseline;

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      generation.current += 1;
      controller.current?.abort();
    };
  }, []);
  useEffect(() => {
    if (!dirty && !saving) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty, saving]);

  const load = useCallback(async () => {
    if (loadingLock.current || savingLock.current || !editable.current) return;
    loadingLock.current = true;
    const current = ++generation.current;
    controller.current?.abort();
    controller.current = new AbortController();
    setLoading(true);
    setError(null);
    setMeta(EMPTY_META);
    try {
      const [detail, metadata] = await Promise.all([
        requestJson(endpoint, { signal: controller.current.signal }),
        requestJson('/api/admin/meta', { signal: controller.current.signal }),
      ]);
      if (!mounted.current || current !== generation.current || !editable.current) return;
      const detailBody = parseResult(detail.body);
      const metaBody = parseResult(metadata.body);
      if (!detail.response.ok || detailBody?.code !== 200)
        throw new Error(resultMessage(detailBody, '内容暂时无法读取，请重新登录或重试。'));
      if (!metadata.response.ok || metaBody?.code !== 200)
        throw new Error(resultMessage(metaBody, '编辑基础数据无法读取，请重试。'));
      const record = parseEditorRecord(kind, detailBody.data);
      const nextMeta = parseEditorMeta(metaBody.data);
      if (!record || record.id !== id || !nextMeta)
        throw new Error('内容或编辑基础数据格式异常，请重试。');
      const post = kind === 'post' ? (record as AdminPostRecord) : null;
      const page = kind === 'page' ? (record as AdminPageRecord) : null;
      const categories = post?.categories?.map((category) => category.id) ?? [];
      const next: Draft = {
        title: record.title,
        content: record.content,
        status: record.status,
        slug: record.slug ?? '',
        publishedAt: record.publishedAt,
        summary: post?.summary ?? '',
        cover: post?.cover ?? '',
        isTop: post?.isTop ?? false,
        categoryIds: categories.length ? categories : post?.categoryId ? [post.categoryId] : [],
        tagIds: post?.tags?.map((tag) => tag.id) ?? [],
        parentId: page?.parentId ?? null,
        sort: page?.sort ?? 0,
      };
      setMeta(nextMeta);
      setDraft(next);
      setBaseline(JSON.stringify(next));
    } catch (failure) {
      if (mounted.current && current === generation.current)
        setError(failure instanceof Error ? failure.message : '网络异常，无法读取编辑内容。');
    } finally {
      loadingLock.current = false;
      if (mounted.current && current === generation.current) setLoading(false);
    }
  }, [endpoint, kind, id]);

  const close = useCallback(() => {
    if (savingLock.current) return;
    generation.current += 1;
    controller.current?.abort();
    setDraft(null);
    setBodyFullscreen(false);
    setBaseline('');
    setConfirmDiscard(false);
    setError(null);
  }, []);
  const cancel = useCallback(() => {
    if (savingLock.current) return;
    if (dirty) setConfirmDiscard(true);
    else close();
  }, [dirty, close]);
  const categories = useMemo(() => flattenCategoryOptions(meta.categories), [meta.categories]);
  const parents = useMemo(() => getPageParentOptions(meta.pages, id), [meta.pages, id]);

  const save = useCallback(async () => {
    if (savingLock.current || !draft || !editable.current || confirmDiscard) return;
    if (
      kind === 'page' &&
      draft.parentId != null &&
      !parents.some((option) => option.id === draft.parentId)
    ) {
      setError('父页面无效或已不在目录中，请重新选择；当前草稿已保留。');
      return;
    }
    const payload = validateContentSave(kind, draft, id);
    if (!payload.ok) {
      setError(payload.error);
      return;
    }
    savingLock.current = true;
    const current = generation.current;
    const submitted = JSON.stringify(draft);
    controller.current = new AbortController();
    setSaving(true);
    setError(null);
    try {
      const { response, body: raw } = await requestJson(endpoint, {
        method: 'PUT',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(payload.data),
        signal: controller.current.signal,
      });
      if (!mounted.current || current !== generation.current) return;
      const body = parseResult(raw);
      if (!response.ok || body?.code !== 200)
        throw new Error(resultMessage(body, '保存结果无法确认，请核对后再重试；草稿已保留。'));
      if (!editable.current) {
        setError('会话已变化，请重新登录后核对保存结果；草稿已保留。');
        return;
      }
      if (JSON.stringify(latestDraft.current) !== submitted) {
        setBaseline(submitted);
        setError('提交版本已保存，当前的新修改尚未保存。');
      } else {
        setDraft(null);
        setBodyFullscreen(false);
        setBaseline('');
        toast.success('已保存');
      }
      router.refresh();
    } catch (failure) {
      if (mounted.current && current === generation.current)
        setError(
          failure instanceof Error
            ? failure.message
            : '保存结果无法确认，请核对后再重试；草稿已保留。',
        );
    } finally {
      savingLock.current = false;
      if (mounted.current && current === generation.current) setSaving(false);
    }
  }, [draft, kind, id, parents, endpoint, router, confirmDiscard]);

  useEffect(() => {
    if (!draft) return;
    const shortcut = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault();
        const layer =
          event.target instanceof Element
            ? event.target.closest('[data-slot="dialog-content"]')
            : null;
        if (!event.isComposing && layer?.hasAttribute('data-article-editor')) void save();
      }
    };
    window.addEventListener('keydown', shortcut);
    return () => window.removeEventListener('keydown', shortcut);
  }, [draft, save]);

  const update = <K extends keyof Draft>(key: K, value: Draft[K]) => {
    if (!savingLock.current)
      setDraft((previous) => (previous ? { ...previous, [key]: value } : null));
  };
  const toggle = (key: 'categoryIds' | 'tagIds', value: number) => {
    if (!draft) return;
    update(
      key,
      draft[key].includes(value)
        ? draft[key].filter((item) => item !== value)
        : [...draft[key], value],
    );
  };
  const statuses =
    draft && !meta.statuses.some((option) => option.value === draft.status)
      ? [...meta.statuses, { value: draft.status, label: draft.status }]
      : meta.statuses;
  const coverUrl = safeImageUrl(draft?.cover);

  return (
    <div className="relative">
      {!draft && canEdit ? (
        <div className="mb-3 flex justify-end gap-2">
          <a
            href={`/api/admin/export/${kind}/${id}`}
            className="text-ink-soft hover:text-brand inline-flex min-h-9 items-center gap-1 rounded px-2 text-sm"
          >
            <Download className="size-4" aria-hidden />
            导出
          </a>
          <Button
            ref={editButton}
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => void load()}
            disabled={loading}
          >
            {loading ? '读取中…' : '编辑'}
          </Button>
        </div>
      ) : null}
      {!draft && error ? (
        <p role="alert" className="text-destructive mb-4 text-sm">
          {error}
        </p>
      ) : null}
      {children}
      <Dialog
        open={draft !== null}
        onOpenChange={(open) => {
          if (!open) cancel();
        }}
      >
        {draft ? (
          <DialogContent
            data-article-editor
            showCloseButton={false}
            className="safe-bottom bg-canvas fixed inset-0 flex h-dvh max-h-none w-full max-w-none translate-x-0 translate-y-0 flex-col gap-0 overflow-hidden rounded-none border-0 p-0 sm:max-w-none"
            onEscapeKeyDown={(event) => {
              // Radix owns a document-level Escape listener before textarea bubbling.
              // Body mode must exit first, never close/discard the article behind it.
              if (savingLock.current || bodyFullscreen) event.preventDefault();
            }}
            onInteractOutside={(event) => event.preventDefault()}
            onCloseAutoFocus={(event) => {
              event.preventDefault();
              editButton.current?.focus();
            }}
          >
            <header
              hidden={bodyFullscreen}
              inert={bodyFullscreen}
              className={
                bodyFullscreen
                  ? 'hidden'
                  : 'safe-top border-line bg-surface flex shrink-0 flex-wrap items-center gap-3 border-b px-4 py-3 sm:px-6'
              }
            >
              <DialogTitle className="text-sm font-medium">
                {kind === 'post' ? '编辑文章' : '编辑页面'}
              </DialogTitle>
              <DialogDescription className="sr-only">
                Markdown 正文与发布属性工作台。保存期间不能关闭或修改字段；取消修改需要确认。
              </DialogDescription>
              {dirty ? <span className="text-ink-soft text-xs">未保存</span> : null}
              <div className="ml-auto flex items-center gap-2">
                <span className="text-ink-faint hidden text-xs sm:inline">Ctrl/Cmd + S 保存</span>
                <Button type="button" variant="ghost" size="sm" disabled={saving} onClick={cancel}>
                  取消
                </Button>
                <Button
                  type="button"
                  size="sm"
                  disabled={saving || !canEdit}
                  onClick={() => void save()}
                >
                  {saving ? '保存中…' : '保存'}
                </Button>
              </div>
            </header>
            {!canEdit ? (
              <p
                role="alert"
                className="border-line bg-surface text-destructive border-b px-4 py-2 text-sm"
              >
                会话暂时不可编辑；当前草稿保留，请复制或重新登录后核对。
              </p>
            ) : null}
            {error ? (
              <p role="alert" className="border-line text-destructive border-b px-4 py-2 text-sm">
                {error}
              </p>
            ) : null}
            <div
              aria-busy={saving}
              className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4 sm:p-5 lg:grid lg:grid-cols-[minmax(0,1fr)_320px] lg:overflow-hidden"
            >
              <section className="flex shrink-0 flex-col gap-3 lg:min-h-0" aria-label="正文编辑">
                <div hidden={bodyFullscreen} inert={bodyFullscreen}>
                  <Field label="标题" htmlFor={`${fieldId}-title`}>
                    <Input
                      id={`${fieldId}-title`}
                      aria-label="标题"
                      autoFocus
                      maxLength={256}
                      disabled={saving}
                      value={draft.title}
                      onChange={(event) => update('title', event.target.value)}
                      className="h-11 text-base font-semibold"
                    />
                  </Field>
                </div>
                <div className="min-h-[55vh] flex-1 lg:min-h-0">
                  <MarkdownEditor
                    fill
                    onFullscreenChange={setBodyFullscreen}
                    disabled={saving}
                    value={draft.content}
                    onChange={(value) => update('content', value)}
                    placeholder="支持 Markdown：标题、列表、代码块、表格、图片、引用……"
                  />
                </div>
              </section>
              <aside
                hidden={bodyFullscreen}
                inert={bodyFullscreen}
                className="space-y-4 lg:min-h-0 lg:overflow-y-auto lg:pr-1"
                aria-label="发布属性"
              >
                <Panel title="发布">
                  <Field label="状态" htmlFor={`${fieldId}-status`}>
                    <Select
                      value={draft.status}
                      disabled={saving}
                      onValueChange={(value) => update('status', value)}
                    >
                      <SelectTrigger id={`${fieldId}-status`}>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {statuses.map((option) => (
                          <SelectItem key={option.value} value={option.value}>
                            {option.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    {meta.statuses.length === 0 ? (
                      <p className="text-ink-faint text-xs">状态字典未配置，仅保留当前原值。</p>
                    ) : null}
                  </Field>
                  {kind === 'post' ? (
                    <div className="flex items-center justify-between gap-3">
                      <Label htmlFor={`${fieldId}-top`}>置顶文章</Label>
                      <Switch
                        id={`${fieldId}-top`}
                        disabled={saving}
                        checked={draft.isTop}
                        onCheckedChange={(value) => update('isTop', value)}
                      />
                    </div>
                  ) : (
                    <>
                      <Field label="父页面" htmlFor={`${fieldId}-parent`}>
                        <Select
                          value={draft.parentId == null ? '__root__' : String(draft.parentId)}
                          disabled={saving}
                          onValueChange={(value) =>
                            update('parentId', value === '__root__' ? null : Number(value))
                          }
                        >
                          <SelectTrigger id={`${fieldId}-parent`}>
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectItem value="__root__">根页面</SelectItem>
                            {parents.map((option) => (
                              <SelectItem key={option.id} value={String(option.id)}>
                                {option.label}
                              </SelectItem>
                            ))}
                            {draft.parentId != null &&
                            !parents.some((option) => option.id === draft.parentId) ? (
                              <SelectItem value={String(draft.parentId)} disabled>
                                #{draft.parentId}（不可选）
                              </SelectItem>
                            ) : null}
                          </SelectContent>
                        </Select>
                      </Field>
                      <Field label="排序（整数，越小越靠前）" htmlFor={`${fieldId}-sort`}>
                        <Input
                          id={`${fieldId}-sort`}
                          type="number"
                          step={1}
                          min={-2147483648}
                          max={2147483647}
                          disabled={saving}
                          value={Number.isFinite(draft.sort) ? draft.sort : ''}
                          onChange={(event) =>
                            update(
                              'sort',
                              event.target.value === '' ? NaN : Number(event.target.value),
                            )
                          }
                        />
                      </Field>
                    </>
                  )}
                  <Field label="URL 别名" htmlFor={`${fieldId}-slug`}>
                    <Input
                      id={`${fieldId}-slug`}
                      maxLength={128}
                      disabled={saving}
                      value={draft.slug}
                      onChange={(event) => update('slug', event.target.value)}
                      placeholder="留空则按 ID 访问"
                    />
                  </Field>
                  <p className="text-ink-faint text-xs">
                    发布时间：{draft.publishedAt || '未发布'}（原值保留）
                  </p>
                </Panel>
                {kind === 'post' ? (
                  <>
                    <Panel title="摘要与封面">
                      <Field label="摘要" htmlFor={`${fieldId}-summary`}>
                        <Textarea
                          id={`${fieldId}-summary`}
                          maxLength={512}
                          rows={3}
                          disabled={saving}
                          value={draft.summary}
                          onChange={(event) => update('summary', event.target.value)}
                        />
                      </Field>
                      <Field label="封面图片 URL" htmlFor={`${fieldId}-cover`}>
                        <Input
                          id={`${fieldId}-cover`}
                          maxLength={512}
                          disabled={saving}
                          value={draft.cover}
                          onChange={(event) => update('cover', event.target.value)}
                          placeholder="https://…"
                        />
                      </Field>
                      {coverUrl ? (
                        <img
                          src={coverUrl}
                          alt="封面预览"
                          referrerPolicy="no-referrer"
                          className="max-h-32 w-full rounded object-cover"
                        />
                      ) : null}
                      {draft.cover.trim() && !coverUrl ? (
                        <p className="text-destructive text-xs">封面 URL 无效，不加载预览。</p>
                      ) : null}
                    </Panel>
                    <Panel title="分类">
                      <p className="text-ink-faint text-xs">
                        首个选中项为主分类。取消再勾选可调整顺序。
                      </p>
                      {draft.categoryIds.length ? (
                        <p className="text-ink-soft text-xs break-words">
                          已选：
                          {draft.categoryIds
                            .map(
                              (value) =>
                                categories.find((option) => option.id === value)?.label ??
                                `#${value}`,
                            )
                            .join(' · ')}
                        </p>
                      ) : null}
                      {categories.length ? (
                        <div className="max-h-56 space-y-1 overflow-y-auto">
                          {categories.map((option) => (
                            <label
                              key={option.id}
                              className="flex min-h-9 cursor-pointer items-center gap-2 text-sm"
                              style={{ paddingLeft: option.depth * 12 }}
                            >
                              <input
                                type="checkbox"
                                disabled={saving}
                                checked={draft.categoryIds.includes(option.id)}
                                onChange={() => toggle('categoryIds', option.id)}
                              />
                              <span>{option.label}</span>
                            </label>
                          ))}
                        </div>
                      ) : (
                        <p className="text-ink-faint text-sm">
                          暂无可选分类；已有选择不会自动清空。
                        </p>
                      )}
                    </Panel>
                    <Panel title="标签">
                      <div className="flex flex-wrap gap-2">
                        {meta.tags.map((tag) => (
                          <Button
                            key={tag.id}
                            type="button"
                            variant={draft.tagIds.includes(tag.id) ? 'secondary' : 'outline'}
                            size="sm"
                            aria-pressed={draft.tagIds.includes(tag.id)}
                            disabled={saving}
                            onClick={() => toggle('tagIds', tag.id)}
                          >
                            {tag.name}
                          </Button>
                        ))}
                      </div>
                      {draft.tagIds
                        .filter((value) => !meta.tags.some((tag) => tag.id === value))
                        .map((value) => (
                          <p key={value} className="text-ink-faint text-xs">
                            保留标签 #{value}
                          </p>
                        ))}
                      {!meta.tags.length ? (
                        <p className="text-ink-faint text-sm">
                          暂无可选标签；已有选择不会自动清空。
                        </p>
                      ) : null}
                    </Panel>
                  </>
                ) : null}
              </aside>
            </div>
          </DialogContent>
        ) : null}
      </Dialog>
      <Dialog open={confirmDiscard} onOpenChange={setConfirmDiscard}>
        <DialogContent className="sm:max-w-[380px]">
          <DialogHeader>
            <DialogTitle>放弃未保存的修改？</DialogTitle>
            <DialogDescription>
              关闭工作台后，当前草稿会丢失。保存中的请求不能在这里取消。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => setConfirmDiscard(false)}>
              继续编辑
            </Button>
            <Button type="button" variant="destructive" disabled={saving} onClick={close}>
              放弃修改
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function Field({
  label,
  htmlFor,
  children,
}: {
  label: string;
  htmlFor: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={htmlFor}>{label}</Label>
      {children}
    </div>
  );
}
function Panel({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className={cn('border-line bg-surface space-y-3 rounded-md border p-4')}>
      <h2 className="border-line-soft text-ink border-b pb-2 text-sm font-medium">{title}</h2>
      {children}
    </section>
  );
}
