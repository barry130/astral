'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { Check, Download as IconDownload, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
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
import { MarkdownEditor } from '@/components/markdown-editor';
import { cn } from '@/lib/utils';

/**
 * 文章 / Wiki 页面的「就地编辑工作台」。
 *
 * 为什么不塞在正文卡片里：
 * - 正文卡片是 760px 的阅读宽度，长文改起来要一直横向滚动；
 * - 目录栏在编辑时是干扰，全屏工作台天然把它盖住；
 * - 后台的分类/标签/封面/发布设置原本要另开一个页面，来回切换容易丢改动。
 *
 * 所以编辑态切换成覆盖视口的工作台：左侧标题 + 大号正文编辑器（吃满剩余高度），
 * 右侧是后台那套结构化字段。保存仍走本站 Route Handler 转发到后端 Admin 接口，
 * 权限由后端 admin:cms:content:edit 二次校验。
 */

type Kind = 'post' | 'page';

interface ResultEnvelope<T> {
  code?: number;
  message?: string | null;
  data?: T | null;
}

interface AdminPostRecord {
  id: number;
  title: string;
  slug: string | null;
  summary: string | null;
  content: string;
  cover: string | null;
  status: string;
  isTop: boolean | null;
  publishedAt: string | null;
  categoryId: number | null;
  categories?: Array<{ id: number; name: string }> | null;
  tags?: Array<{ id: number; name: string }> | null;
}

interface AdminPageRecord {
  id: number;
  title: string;
  slug: string | null;
  content: string;
  parentId: number | null;
  sort: number | null;
  status: string;
  publishedAt: string | null;
}

interface CategoryNode {
  id: number;
  name: string;
  parentId?: number | null;
  children?: CategoryNode[] | null;
}

interface TagItem {
  id: number;
  name: string;
}

interface PageNode {
  id: number;
  title: string;
  parentId?: number | null;
  children?: PageNode[] | null;
}

interface MetaPayload {
  categories: CategoryNode[];
  tags: TagItem[];
  pages: PageNode[];
}

interface FlatOption {
  id: number;
  label: string;
  depth: number;
}

const STATUS_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'published', label: '已发布' },
  { value: 'private', label: '私密' },
  { value: 'draft', label: '草稿' },
  { value: 'hidden', label: '隐藏' },
];

/** 分类树摊平成带缩进的选项，label 是完整路径（子分类可能重名） */
function flattenCategories(
  nodes: CategoryNode[],
  depth = 0,
  path: string[] = [],
  out: FlatOption[] = [],
): FlatOption[] {
  for (const node of nodes) {
    const nextPath = [...path, node.name || `#${node.id}`];
    out.push({ id: node.id, label: nextPath.join(' / '), depth });
    if (node.children && node.children.length > 0) {
      flattenCategories(node.children, depth + 1, nextPath, out);
    }
  }
  return out;
}

/** 页面树摊平，用于选择父页面 */
function flattenPages(
  nodes: PageNode[],
  depth = 0,
  path: string[] = [],
  out: FlatOption[] = [],
): FlatOption[] {
  for (const node of nodes) {
    const nextPath = [...path, node.title || `#${node.id}`];
    out.push({ id: node.id, label: nextPath.join(' / '), depth });
    if (node.children && node.children.length > 0) {
      flattenPages(node.children, depth + 1, nextPath, out);
    }
  }
  return out;
}

/**
 * 收集某个页面自身及全部子孙 id：父页面下拉里必须排除，否则会成环。
 * 返回值只表示「本层子树里是否命中目标」，不能借用 acc 是否为空判断——
 * 那样先遍历到的兄弟分支命中后，后面无关分支也会被误加进来。
 */
function collectPageIds(nodes: PageNode[], targetId: number, acc: Set<number>): boolean {
  let found = false;
  for (const node of nodes) {
    const childHit = node.children ? collectPageIds(node.children, targetId, acc) : false;
    if (node.id === targetId || childHit) {
      acc.add(node.id);
      found = true;
    }
  }
  return found;
}

interface EditableArticleProps {
  kind: Kind;
  id: number;
  /** 服务端渲染的正文区（阅读态原样展示） */
  children: React.ReactNode;
}

export function EditableArticle({ kind, id, children }: EditableArticleProps) {
  // 登录态统一由 layout 里的 SessionProvider 提供（服务端已经读 Cookie 算好了）。
  // 这里不能再套一层 Provider：内层用默认值 false 会把外层登录态盖掉，
  // 导致明明已登录却看不到「编辑」按钮。
  return (
    <EditableArticleInner kind={kind} id={id}>
      {children}
    </EditableArticleInner>
  );
}

function EditableArticleInner({ kind, id, children }: EditableArticleProps) {
  const { canEdit } = useSession();
  const router = useRouter();
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  // —— 表单字段 ——
  const [title, setTitle] = useState('');
  const [summary, setSummary] = useState('');
  const [content, setContent] = useState('');
  const [status, setStatus] = useState('published');
  const [slug, setSlug] = useState('');
  const [cover, setCover] = useState('');
  const [isTop, setIsTop] = useState(false);
  const [categoryIds, setCategoryIds] = useState<number[]>([]);
  const [tagIds, setTagIds] = useState<number[]>([]);
  const [parentId, setParentId] = useState<number | null>(null);
  const [sort, setSort] = useState(0);

  // 未编辑的字段原样带回，避免保存时把发布时间等字段清空
  const [snapshot, setSnapshot] = useState<AdminPostRecord | AdminPageRecord | null>(null);
  // 用于判断「有没有未保存的改动」
  const [baseline, setBaseline] = useState<string>('');

  const [meta, setMeta] = useState<MetaPayload>({ categories: [], tags: [], pages: [] });

  const endpoint = kind === 'post' ? `/api/admin/post/${id}` : `/api/admin/page/${id}`;

  const applyRecord = useCallback((data: AdminPostRecord | AdminPageRecord) => {
    const isPost = kind === 'post';
    const post = data as AdminPostRecord;
    const page = data as AdminPageRecord;
    const nextCategories = isPost
      ? (post.categories?.map((item) => item.id) ??
        (post.categoryId != null ? [post.categoryId] : []))
      : [];
    const nextTags = isPost ? (post.tags?.map((item) => item.id) ?? []) : [];

    setTitle(data.title ?? '');
    setContent(data.content ?? '');
    setStatus(data.status ?? 'published');
    setSlug(data.slug ?? '');
    setSummary(isPost ? (post.summary ?? '') : '');
    setCover(isPost ? (post.cover ?? '') : '');
    setIsTop(isPost ? Boolean(post.isTop) : false);
    setCategoryIds(nextCategories);
    setTagIds(nextTags);
    setParentId(isPost ? null : page.parentId ?? null);
    setSort(isPost ? 0 : page.sort ?? 0);

    setBaseline(
      JSON.stringify({
        title: data.title ?? '',
        content: data.content ?? '',
        status: data.status ?? 'published',
        slug: data.slug ?? '',
        summary: isPost ? (post.summary ?? '') : '',
        cover: isPost ? (post.cover ?? '') : '',
        isTop: isPost ? Boolean(post.isTop) : false,
        categoryIds: nextCategories,
        tagIds: nextTags,
        parentId: isPost ? null : page.parentId ?? null,
        sort: isPost ? 0 : page.sort ?? 0,
      }),
    );
  }, [kind]);

  const startEditing = useCallback(async () => {
    setLoading(true);
    try {
      // 详情与基础数据一起拉：分类/标签/页面树只在打开工作台时用一次
      const [detailRes, metaRes] = await Promise.all([
        fetch(endpoint, { cache: 'no-store' }),
        fetch('/api/admin/meta', { cache: 'no-store' }).catch(() => null),
      ]);

      const body = (await detailRes.json().catch(() => null)) as ResultEnvelope<
        AdminPostRecord | AdminPageRecord
      > | null;
      const data = body?.data;
      if (!detailRes.ok || !data) {
        toast.error(body?.message || '无法读取文章内容，请重新登录后再试。');
        return;
      }

      if (metaRes && metaRes.ok) {
        const metaBody = (await metaRes.json().catch(() => null)) as ResultEnvelope<MetaPayload> | null;
        if (metaBody?.data) {
          setMeta({
            categories: metaBody.data.categories ?? [],
            tags: metaBody.data.tags ?? [],
            pages: metaBody.data.pages ?? [],
          });
        }
      }

      setSnapshot(data);
      applyRecord(data);
      setEditing(true);
    } catch {
      toast.error('网络异常，无法读取文章内容。');
    } finally {
      setLoading(false);
    }
  }, [endpoint, applyRecord]);

  // 分类：带完整路径的扁平选项（子分类可重名，靠 path 区分）
  const categoryOptions = useMemo(
    () => flattenCategories(meta.categories),
    [meta.categories],
  );

  // 页面：父页面下拉必须排除自身及全部子孙，否则会成环（后端也会拦，但别让用户先踩坑）
  const pageOptions = useMemo(() => {
    if (kind !== 'page') return [];
    const excluded = new Set<number>();
    collectPageIds(meta.pages, id, excluded);
    return flattenPages(meta.pages).filter((option) => !excluded.has(option.id));
  }, [kind, meta.pages, id]);

  const currentState = useMemo(
    () =>
      JSON.stringify({
        title,
        content,
        status,
        slug,
        summary,
        cover,
        isTop,
        categoryIds,
        tagIds,
        parentId,
        sort,
      }),
    [title, content, status, slug, summary, cover, isTop, categoryIds, tagIds, parentId, sort],
  );
  const dirty = editing && baseline !== '' && currentState !== baseline;

  const cancel = useCallback(() => {
    if (dirty && !window.confirm('有未保存的改动，确定放弃吗？')) {
      return;
    }
    setEditing(false);
    setSnapshot(null);
    setBaseline('');
  }, [dirty]);

  const save = useCallback(async () => {
    if (!snapshot || saving) return;
    if (!title.trim() || !content.trim()) {
      toast.error('标题和正文不能为空。');
      return;
    }

    // 后端要求提交完整 DTO：未在表单里的字段必须回传原值
    const payload =
      kind === 'post'
        ? {
            title: title.trim(),
            slug: slug.trim() || null,
            summary: summary.trim() || null,
            content,
            cover: cover.trim() || null,
            status,
            isTop,
            publishedAt: (snapshot as AdminPostRecord).publishedAt,
            // 多分类为唯一写入源；categoryId 冗余成主分类，兼容旧读取方
            categoryIds,
            categoryId: categoryIds[0] ?? null,
            tagIds,
          }
        : {
            title: title.trim(),
            slug: slug.trim() || null,
            content,
            parentId,
            sort,
            status,
            publishedAt: (snapshot as AdminPageRecord).publishedAt,
          };

    setSaving(true);
    try {
      const res = await fetch(endpoint, {
        method: 'PUT',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(payload),
      });
      const body = (await res.json().catch(() => null)) as ResultEnvelope<unknown> | null;
      if (!res.ok || body?.code !== 200) {
        toast.error(body?.message || '保存失败，请稍后重试。');
        return;
      }
      toast.success('已保存');
      setEditing(false);
      setSnapshot(null);
      setBaseline('');
      // 重新拉服务端数据：正文 HTML 与目录都需要重新生成
      router.refresh();
    } catch {
      toast.error('网络异常，保存失败。');
    } finally {
      setSaving(false);
    }
  }, [
    snapshot, saving, title, content, summary, status, kind, endpoint, router,
    slug, cover, isTop, categoryIds, tagIds, parentId, sort,
  ]);

  // 工作台快捷键：Ctrl/Cmd+S 保存、Esc 关闭
  useEffect(() => {
    if (!editing) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault();
        void save();
      } else if (event.key === 'Escape') {
        // 弹层（下拉/对话框）自己会先吃掉 Esc，这里只在没有弹层时生效
        if (document.querySelector('[data-state="open"][role="dialog"], [data-radix-popper-content-wrapper]')) {
          return;
        }
        cancel();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [editing, save, cancel]);

  if (!canEdit) {
    return <div className="relative">{children}</div>;
  }

  return (
    <div className="relative">
      {editing ? null : (
        <div className="absolute right-0 top-0 z-10 flex items-center gap-0.5">
          {/* 阅读态也能导出：只要求 canEdit，进了工作台就不用先点「编辑」 */}
          <a
            href={`/api/admin/export/${kind}/${id}`}
            className="inline-flex h-7 items-center gap-1 rounded-md px-2 text-[12px] text-ink-faint transition-colors hover:text-brand"
            title="导出为 Markdown 文件"
          >
            <IconDownload className="size-3.5" />
            导出
          </a>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => {
              void startEditing();
            }}
            disabled={loading}
            className="h-7 px-2 text-[12px] text-ink-faint hover:text-brand"
          >
            {loading ? '读取中…' : '编辑'}
          </Button>
        </div>
      )}

      {children}

      {editing ? (
        <div className="fixed inset-0 z-50 flex flex-col bg-canvas">
          {/* 顶栏：标题 + 脏标记 + 取消/保存 */}
          <header className="safe-top flex shrink-0 items-center gap-3 border-b border-line bg-surface px-4 py-2.5 sm:px-6">
            <span className="shrink-0 text-[12px] font-medium uppercase tracking-[0.12em] text-ink-faint">
              {kind === 'post' ? '编辑文章' : '编辑页面'}
            </span>
            <span className="min-w-0 truncate text-[13px] text-ink-soft">
              {title || '未命名'}
            </span>
            {dirty ? (
              <span className="shrink-0 rounded-full border border-line-strong bg-surface-muted px-2 py-0.5 text-[10.5px] text-ink-soft">
                未保存
              </span>
            ) : null}
            <div className="ml-auto flex shrink-0 items-center gap-2">
              <span className="hidden text-[11px] text-ink-faint sm:inline">
                Ctrl/Cmd + S 保存 · Esc 关闭
              </span>
              {/* 导出当前内容为 Markdown：走本站转发，携带登录 Cookie */}
              <a
                href={`/api/admin/export/${kind}/${id}`}
                className="inline-flex h-8 items-center gap-1 rounded-md px-2 text-[12px] text-ink-soft transition-colors hover:bg-surface-muted hover:text-ink"
                title="导出为 Markdown 文件"
              >
                <IconDownload className="size-3.5" />
                导出
              </a>
              <Button type="button" variant="ghost" size="sm" onClick={cancel} disabled={saving}>
                取消
              </Button>
              <Button type="button" size="sm" onClick={() => void save()} disabled={saving}>
                {saving ? '保存中…' : '保存'}
              </Button>
            </div>
          </header>

          {/* 主体：左正文（吃满高度）+ 右字段面板 */}
          <div className="safe-bottom flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4 sm:p-5 lg:grid lg:grid-cols-[minmax(0,1fr)_320px] lg:overflow-hidden">
            <div className="flex min-h-0 flex-1 flex-col gap-3">
              <input
                value={title}
                onChange={(event) => setTitle(event.target.value)}
                placeholder="标题"
                aria-label="标题"
                className="w-full shrink-0 rounded-lg border border-line bg-surface px-3.5 py-2.5 text-[17px] font-semibold tracking-[-0.01em] text-ink outline-none transition-colors placeholder:font-normal placeholder:text-ink-faint focus:border-brand-line"
              />
              <div className="min-h-[55vh] flex-1 lg:min-h-0">
                <MarkdownEditor
                  fill
                  value={content}
                  onChange={setContent}
                  placeholder="支持 Markdown：标题、列表、代码块、表格、图片、引用……"
                />
              </div>
            </div>

            <aside className="flex shrink-0 flex-col gap-3 lg:min-h-0 lg:overflow-y-auto lg:pr-0.5">
              <PanelCard title="发布">
                <div className="space-y-1.5">
                  <Label className="text-[11.5px] text-ink-soft">状态</Label>
                  <Select value={status} onValueChange={setStatus}>
                    <SelectTrigger className="h-8 w-full text-[12.5px]">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {STATUS_OPTIONS.map((option) => (
                        <SelectItem
                          key={option.value}
                          value={option.value}
                          className="text-[12.5px]"
                        >
                          {option.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>

                {kind === 'post' ? (
                  <div className="flex items-center justify-between gap-3">
                    <div className="space-y-0.5">
                      <Label htmlFor="edit-top" className="cursor-pointer text-[12px] text-ink-body">
                        置顶
                      </Label>
                      <p className="text-[11px] text-ink-faint">排在文章列表前面</p>
                    </div>
                    <Switch id="edit-top" checked={isTop} onCheckedChange={setIsTop} />
                  </div>
                ) : (
                  <>
                    <div className="space-y-1.5">
                      <Label className="text-[11.5px] text-ink-soft">父页面</Label>
                      <Select
                        value={parentId == null ? '__root__' : String(parentId)}
                        onValueChange={(value) =>
                          setParentId(value === '__root__' ? null : Number(value))
                        }
                      >
                        <SelectTrigger className="h-8 w-full text-[12.5px]">
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value="__root__" className="text-[12.5px]">
                            根页面
                          </SelectItem>
                          {pageOptions.map((option) => (
                            <SelectItem
                              key={option.id}
                              value={String(option.id)}
                              className="text-[12.5px]"
                            >
                              {option.label}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="space-y-1.5">
                      <Label htmlFor="edit-sort" className="text-[11.5px] text-ink-soft">
                        排序（越小越靠前）
                      </Label>
                      <Input
                        id="edit-sort"
                        type="number"
                        value={sort}
                        onChange={(event) => setSort(Number(event.target.value) || 0)}
                        className="h-8 text-[12.5px]"
                      />
                    </div>
                  </>
                )}

                <div className="space-y-1.5">
                  <Label htmlFor="edit-slug" className="text-[11.5px] text-ink-soft">
                    URL 别名
                  </Label>
                  <Input
                    id="edit-slug"
                    value={slug}
                    onChange={(event) => setSlug(event.target.value)}
                    placeholder="留空则按 ID 访问"
                    className="h-8 text-[12.5px]"
                  />
                </div>
              </PanelCard>

              {kind === 'post' ? (
                <>
                  <PanelCard title="摘要">
                    <Textarea
                      value={summary}
                      onChange={(event) => setSummary(event.target.value)}
                      rows={3}
                      placeholder="留空则由后端按正文生成"
                      className="text-[12px] leading-relaxed"
                    />
                  </PanelCard>

                  <PanelCard title="封面图">
                    <Input
                      value={cover}
                      onChange={(event) => setCover(event.target.value)}
                      placeholder="https://…"
                      className="h-8 text-[12px]"
                    />
                    {cover.trim() ? (
                      <div className="overflow-hidden rounded-md border border-line-soft bg-surface-muted">
                        {/* eslint-disable-next-line @next/next/no-img-element -- 封面来自任意图床，不进 next/image 白名单 */}
                        <img
                          src={cover}
                          alt="封面预览"
                          referrerPolicy="no-referrer"
                          className="block max-h-28 w-full object-cover"
                        />
                      </div>
                    ) : null}
                  </PanelCard>

                  <PanelCard
                    title="分类"
                    hint={categoryIds.length > 0 ? `${categoryIds.length} 个` : '未分类'}
                  >
                    {categoryOptions.length === 0 ? (
                      <p className="text-[11.5px] text-ink-faint">暂无分类</p>
                    ) : (
                      <>
                        {categoryIds.length > 0 ? (
                          <div className="flex flex-wrap gap-1">
                            {categoryIds.map((cid, index) => (
                              <span
                                key={cid}
                                className={cn(
                                  'flex max-w-full items-center gap-1 rounded border py-0.5 pl-1.5 pr-1 text-[11px]',
                                  index === 0
                                    ? 'border-brand-line bg-brand-wash text-brand'
                                    : 'border-line text-ink-soft',
                                )}
                              >
                                <span className="truncate">
                                  {categoryLabelOf(cid)}
                                </span>
                                {index === 0 ? (
                                  <span className="shrink-0 text-[10px] text-ink-faint">主</span>
                                ) : null}
                                <button
                                  type="button"
                                  aria-label={`移除分类 ${cid}`}
                                  className="shrink-0 rounded p-0.5 transition-colors hover:bg-surface hover:text-ink"
                                  onClick={() => toggleCategory(cid)}
                                >
                                  <X className="h-2.5 w-2.5" />
                                </button>
                              </span>
                            ))}
                          </div>
                        ) : null}
                        <div className="max-h-52 space-y-0.5 overflow-y-auto rounded-md border border-line-soft bg-canvas p-1">
                          {categoryOptions.map((option) => {
                            const selected = categoryIds.includes(option.id);
                            return (
                              <button
                                key={option.id}
                                type="button"
                                role="checkbox"
                                aria-checked={selected}
                                title={option.label}
                                onClick={() => toggleCategory(option.id)}
                                style={{ paddingLeft: `${option.depth * 12 + 6}px` }}
                                className={cn(
                                  'flex w-full items-center gap-1.5 rounded py-1 pr-1.5 text-left text-[12px] transition-colors',
                                  selected
                                    ? 'bg-brand-wash text-brand'
                                    : 'text-ink-soft hover:bg-surface-muted hover:text-ink',
                                )}
                              >
                                <span
                                  className={cn(
                                    'flex size-3 shrink-0 items-center justify-center rounded-[3px] border',
                                    selected
                                      ? 'border-brand bg-brand text-white'
                                      : 'border-line-strong',
                                  )}
                                >
                                  {selected ? <Check className="h-2 w-2" strokeWidth={3} /> : null}
                                </span>
                                <span className="truncate">
                                  {option.label.split(' / ').pop()}
                                </span>
                                {categoryIds[0] === option.id ? (
                                  <span className="ml-auto shrink-0 text-[10px] text-ink-faint">
                                    主
                                  </span>
                                ) : null}
                              </button>
                            );
                          })}
                        </div>
                      </>
                    )}
                  </PanelCard>

                  <PanelCard
                    title="标签"
                    hint={tagIds.length > 0 ? `${tagIds.length} 个` : '无'}
                  >
                    {meta.tags.length === 0 ? (
                      <p className="text-[11.5px] text-ink-faint">暂无可选标签</p>
                    ) : (
                      <div className="flex flex-wrap gap-1">
                        {meta.tags.map((tag) => {
                          const selected = tagIds.includes(tag.id);
                          return (
                            <button
                              key={tag.id}
                              type="button"
                              onClick={() =>
                                setTagIds((prev) =>
                                  prev.includes(tag.id)
                                    ? prev.filter((item) => item !== tag.id)
                                    : [...prev, tag.id],
                                )
                              }
                              className={cn(
                                'rounded-full border px-2 py-0.5 text-[11.5px] transition-colors',
                                selected
                                  ? 'border-brand-line bg-brand-wash text-brand'
                                  : 'border-line text-ink-soft hover:border-line-strong hover:text-ink',
                              )}
                            >
                              {tag.name}
                            </button>
                          );
                        })}
                      </div>
                    )}
                  </PanelCard>
                </>
              ) : null}
            </aside>
          </div>
        </div>
      ) : null}
    </div>
  );

  function toggleCategory(cid: number) {
    setCategoryIds((prev) =>
      prev.includes(cid) ? prev.filter((item) => item !== cid) : [...prev, cid],
    );
  }

  function categoryLabelOf(cid: number) {
    return categoryOptions.find((option) => option.id === cid)?.label ?? `#${cid}`;
  }
}

/** 右侧字段面板的单张卡片 */
function PanelCard({
  title,
  hint,
  children,
}: {
  title: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <section className="space-y-2.5 rounded-lg border border-line bg-surface p-3.5">
      <div className="flex items-baseline justify-between gap-2 border-b border-line-soft pb-1.5">
        <h2 className="text-[11.5px] font-medium uppercase tracking-[0.1em] text-ink-faint">
          {title}
        </h2>
        {hint ? <span className="text-[10.5px] tabular-nums text-ink-faint">{hint}</span> : null}
      </div>
      {children}
    </section>
  );
}
