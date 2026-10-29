'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { toast } from 'sonner';
import { ArrowLeft, Check, Save, X } from 'lucide-react';

import { cmsApi, cmsDictOptions, loadCmsDicts, CmsCategory, CmsPost, CmsTag } from '@/api/cms';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { MarkdownEditor } from '@/components/markdown-editor';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { cn } from '@/lib/utils';
import { flattenCategoryOptions, FormCard, PageHeader } from '../shared';
import SharePanel from '../SharePanel';

const CONTENT_STATUS = 'cms_content_status';

/** 状态说明：仅对需要额外解释的状态给一句提示（其余状态语义已足够直白） */
const STATUS_HINT: Record<string, string> = {
  private: '私密：仅登录的管理员在前台可见，可用分享链接临时开放给他人',
};

interface PostFormState {
  title: string;
  slug: string;
  summary: string;
  cover: string;
  /** 多分类；第一项为主分类 */
  categoryIds: number[];
  status: string;
  isTop: boolean;
  content: string;
  tagIds: number[];
}

const EMPTY_FORM: PostFormState = {
  title: '',
  slug: '',
  summary: '',
  cover: '',
  categoryIds: [],
  status: 'draft',
  isTop: false,
  content: '',
  tagIds: [],
};

/**
 * 文章新建/编辑共用表单。正文用 MarkdownEditor（工具栏 + 分栏预览，编辑器不引 CodeMirror，
 * 底层仍是纯文本域），分类/标签为可点击的 chip 列表（分类来自分类树、带层级缩进，可多选，
 * 第一项为主分类），留空 slug 前台按 id 访问。
 */
export default function PostForm({ postId }: { postId?: number }) {
  const router = useRouter();
  const [form, setForm] = useState<PostFormState>(EMPTY_FORM);
  const [tags, setTags] = useState<CmsTag[]>([]);
  const [categories, setCategories] = useState<CmsCategory[]>([]);
  const [saving, setSaving] = useState(false);
  const editing = typeof postId === 'number' && Number.isFinite(postId);
  // 编辑态首帧即显示骨架，避免表单先闪一下空值
  const [detailLoading, setDetailLoading] = useState(
    typeof postId === 'number' && Number.isFinite(postId),
  );

  const setField = <K extends keyof PostFormState>(key: K, value: PostFormState[K]) => {
    setForm((prev) => ({ ...prev, [key]: value }));
  };

  useEffect(() => {
    void loadCmsDicts().catch(() => {
      /* 字典拉取失败时回退展示原始值 */
    });
    cmsApi
      .tagList()
      .then((res) => setTags(res.data || []))
      .catch(() => {});
    cmsApi
      .categoryTree()
      .then((res) => setCategories(res.data || []))
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (!editing) return;
    setDetailLoading(true);
    cmsApi
      .postDetail(postId!)
      .then((res) => {
        const post: CmsPost = res.data || {};
        // 回填多分类：优先新字段 categories，缺失时回退旧的单分类 categoryId
        const categoryIds = (post.categories || []).map((c) => c.id);
        setForm({
          title: post.title || '',
          slug: post.slug || '',
          summary: post.summary || '',
          cover: post.cover || '',
          categoryIds:
            categoryIds.length > 0 ? categoryIds : post.categoryId != null ? [post.categoryId] : [],
          status: post.status || 'draft',
          isTop: Boolean(post.isTop),
          content: post.content || '',
          tagIds: (post.tags || []).map((t) => t.id),
        });
      })
      .catch((e: Error) => {
        toast.error((e as Error).message || '文章加载失败');
        router.replace('/dashboard/cms/post');
      })
      .finally(() => setDetailLoading(false));
  }, [editing, postId, router]);

  const categoryOptions = useMemo(() => flattenCategoryOptions(categories), [categories]);

  /** 已选分类对应的展示名（按选择顺序，第一项是主分类） */
  const categoryFullName = useCallback(
    (node: CmsCategory, path: string[]) => [...path, node.name || `#${node.id}`].join(' / '),
    [],
  );

  const selectedCategoryNames = useMemo(() => {
    const nameById = new Map<number, string>();
    for (const { node, path } of categoryOptions) {
      nameById.set(node.id, categoryFullName(node, path));
    }
    return form.categoryIds.map((id) => nameById.get(id) || `#${id}`);
  }, [categoryFullName, categoryOptions, form.categoryIds]);

  /** 多选分类：保持点击顺序，取消再选会回到末尾（主分类随之变化） */
  const toggleCategory = (id: number) => {
    setForm((prev) => ({
      ...prev,
      categoryIds: prev.categoryIds.includes(id)
        ? prev.categoryIds.filter((c) => c !== id)
        : [...prev.categoryIds, id],
    }));
  };

  const clearCategories = () => setField('categoryIds', []);

  const toggleTag = (id: number) => {
    setForm((prev) => ({
      ...prev,
      tagIds: prev.tagIds.includes(id) ? prev.tagIds.filter((t) => t !== id) : [...prev.tagIds, id],
    }));
  };

  const submit = useCallback(async () => {
    if (!form.title.trim()) {
      toast.error('请填写标题');
      return;
    }
    if (!form.content.trim()) {
      toast.error('请填写正文');
      return;
    }
    setSaving(true);
    const payload = {
      title: form.title.trim(),
      slug: form.slug.trim() || undefined,
      summary: form.summary.trim() || undefined,
      cover: form.cover.trim() || undefined,
      // 多分类为唯一写入源；categoryId 冗余成主分类，供尚未升级的读取方兼容
      categoryIds: form.categoryIds,
      categoryId: form.categoryIds[0] ?? null,
      status: form.status,
      isTop: form.isTop,
      content: form.content,
      tagIds: form.tagIds,
    };
    try {
      if (editing) await cmsApi.updatePost(postId!, payload);
      else await cmsApi.createPost(payload);
      toast.success(editing ? '文章已更新' : '文章已创建');
      router.push('/dashboard/cms/post');
    } catch (e) {
      toast.error((e as Error).message || '保存失败');
    } finally {
      setSaving(false);
    }
  }, [editing, form, postId, router]);

  const backToList = () => router.push('/dashboard/cms/post');

  const header = (
    <PageHeader
      title={editing ? '编辑文章' : '新建文章'}
      description="正文使用 Markdown 语法；一篇文章可归属多个分类，第一项作为主分类"
      actions={
        <Button variant="ghost" size="sm" asChild className="text-muted-foreground gap-1">
          <Link href="/dashboard/cms/post">
            <ArrowLeft className="size-3.5" />
            返回列表
          </Link>
        </Button>
      }
    />
  );

  // 详情加载中：按最终排版给骨架，避免表单先闪一下空值
  if (detailLoading) {
    return (
      <div className="space-y-4">
        {header}
        <div role="status" aria-busy="true" aria-label="文章加载中" className="space-y-4">
          <div className="border-border/60 rounded-xl border p-5">
            <Skeleton className="h-4 w-24" />
            <div className="mt-5 space-y-4">
              <Skeleton className="h-9 w-full" />
              <Skeleton className="h-9 w-[60%]" />
            </div>
          </div>
          <div className="border-border/60 rounded-xl border p-5">
            <Skeleton className="h-4 w-16" />
            <Skeleton className="mt-5 h-64 w-full" />
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {header}

      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_340px]">
        <div className="space-y-4">
          <FormCard title="基本信息" description="标题必填；URL 别名留空时前台按文章 ID 访问">
            <div className="space-y-1.5">
              <Label htmlFor="post-title">标题 *</Label>
              <Input
                id="post-title"
                value={form.title}
                maxLength={256}
                onChange={(e) => setField('title', e.target.value)}
                placeholder="文章标题"
              />
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="post-slug">URL 别名</Label>
                <Input
                  id="post-slug"
                  value={form.slug}
                  maxLength={128}
                  onChange={(e) => setField('slug', e.target.value)}
                  placeholder="如 hello-world"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="post-cover">封面图 URL</Label>
                <Input
                  id="post-cover"
                  value={form.cover}
                  maxLength={512}
                  onChange={(e) => setField('cover', e.target.value)}
                  placeholder="https://…"
                />
              </div>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="post-summary">摘要</Label>
              <Textarea
                id="post-summary"
                rows={2}
                maxLength={512}
                value={form.summary}
                onChange={(e) => setField('summary', e.target.value)}
                placeholder="留空时前台可展示正文开头"
              />
            </div>
          </FormCard>

          <FormCard
            title="内容"
            description="Markdown 语法，工具栏可直接插入常用语法，保存后由前台渲染"
          >
            <div className="space-y-1.5">
              <Label>正文（Markdown）*</Label>
              <MarkdownEditor
                value={form.content}
                onChange={(v) => setField('content', v)}
                placeholder={'# 标题\n\n正文内容…'}
              />
            </div>
          </FormCard>

          {/* 分享面板只在编辑态出现：新建时还没有文章 ID，链接无从生成 */}
          {editing && <SharePanel targetType="post" targetId={postId} />}
        </div>

        <div className="space-y-4 lg:sticky lg:top-4">
          <FormCard
            title="发布设置"
            description="状态决定前台是否可见：草稿仅后台可见，私密仅登录管理员可见"
            footer={
              <div className="flex items-center gap-2">
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={backToList}
                  className="text-muted-foreground"
                >
                  取消
                </Button>
                <Button size="sm" onClick={submit} disabled={saving} className="ml-auto gap-1.5">
                  <Save className="size-4" />
                  {saving ? '保存中…' : editing ? '保存修改' : '创建文章'}
                </Button>
              </div>
            }
          >
            <div className="space-y-1.5">
              <Label>状态</Label>
              <Select value={form.status} onValueChange={(v) => setField('status', v)}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {cmsDictOptions(CONTENT_STATUS).map((o) => (
                    <SelectItem key={o.value} value={o.value}>
                      {o.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {/* 私密状态的语义不直观，选中时补一句说明（字典只给标签，解释留在前端） */}
              {STATUS_HINT[form.status] && (
                <p className="text-muted-foreground text-xs leading-relaxed">
                  {STATUS_HINT[form.status]}
                </p>
              )}
            </div>
            <div className="flex items-center justify-between gap-3">
              <div className="space-y-0.5">
                <Label htmlFor="post-top" className="cursor-pointer">
                  置顶
                </Label>
                <p className="text-muted-foreground text-xs">开启后排在文章列表前面</p>
              </div>
              <Switch
                id="post-top"
                checked={form.isTop}
                onCheckedChange={(checked) => setField('isTop', checked)}
              />
            </div>
          </FormCard>

          <FormCard title="分类" description="可多选；第一个选中的作为主分类">
            <div className="flex items-center justify-between">
              <Label>已选</Label>
              <div className="flex items-center gap-2">
                <span className="text-muted-foreground text-xs tabular-nums">
                  {form.categoryIds.length > 0 ? `${form.categoryIds.length} 个` : '未分类'}
                </span>
                {form.categoryIds.length > 0 && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="text-muted-foreground h-6 px-1.5 text-xs"
                    onClick={clearCategories}
                  >
                    清空
                  </Button>
                )}
              </div>
            </div>

            {/* 已选分类：按选择顺序展示，第一项标「主」；点 × 直接移除 */}
            {form.categoryIds.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                {form.categoryIds.map((id, index) => (
                  <span
                    key={id}
                    className={cn(
                      'flex max-w-full items-center gap-1 rounded-md border py-0.5 pr-1 pl-2 text-xs',
                      index === 0
                        ? 'border-border bg-secondary text-secondary-foreground'
                        : 'border-border/60 text-muted-foreground',
                    )}
                  >
                    <span className="truncate" title={selectedCategoryNames[index]}>
                      {selectedCategoryNames[index]}
                    </span>
                    {index === 0 && (
                      <span className="text-muted-foreground shrink-0 text-[10px]">主</span>
                    )}
                    <button
                      type="button"
                      aria-label={`移除分类 ${selectedCategoryNames[index]}`}
                      className="hover:bg-background/80 hover:text-foreground shrink-0 rounded p-0.5 transition-colors"
                      onClick={() => toggleCategory(id)}
                    >
                      <X className="size-3" />
                    </button>
                  </span>
                ))}
              </div>
            )}

            <div className="border-border/60 bg-background -mt-2 max-h-64 space-y-0.5 overflow-y-auto rounded-lg border p-1.5">
              {categoryOptions.length === 0 ? (
                <p className="text-muted-foreground px-2 py-6 text-center text-xs">
                  暂无分类，可先到「分类」页创建
                </p>
              ) : (
                categoryOptions.map(({ node, depth, path }) => {
                  const selected = form.categoryIds.includes(node.id);
                  const primary = form.categoryIds[0] === node.id;
                  const fullName = categoryFullName(node, path);
                  return (
                    <button
                      key={node.id}
                      type="button"
                      role="checkbox"
                      aria-checked={selected}
                      title={fullName}
                      onClick={() => toggleCategory(node.id)}
                      style={{ paddingLeft: `${depth * 14 + 8}px` }}
                      className={cn(
                        'flex w-full items-center gap-2 rounded-md py-1.5 pr-2 text-left text-sm transition-colors',
                        selected
                          ? 'bg-secondary text-secondary-foreground'
                          : 'text-muted-foreground hover:bg-muted hover:text-foreground',
                      )}
                    >
                      <span
                        className={cn(
                          'flex size-3.5 shrink-0 items-center justify-center rounded-[4px] border transition-colors',
                          selected
                            ? 'border-primary bg-primary text-primary-foreground'
                            : 'border-border',
                        )}
                      >
                        {selected && <Check className="size-3" strokeWidth={3} />}
                      </span>
                      <span className="truncate">{node.name || `#${node.id}`}</span>
                      {primary && (
                        <span className="text-muted-foreground ml-auto shrink-0 text-[10px]">
                          主
                        </span>
                      )}
                    </button>
                  );
                })
              )}
            </div>
          </FormCard>

          <FormCard title="标签" description="可多选，留空表示不打标签">
            {tags.length === 0 ? (
              <p className="text-muted-foreground text-sm">暂无可选标签，可先到「标签」页创建</p>
            ) : (
              <div className="flex flex-wrap gap-1.5">
                {tags.map((tag) => {
                  const selected = form.tagIds.includes(tag.id);
                  return (
                    <Button
                      key={tag.id}
                      type="button"
                      variant={selected ? 'secondary' : 'outline'}
                      size="sm"
                      className={cn(
                        'h-7 px-2.5 font-normal',
                        !selected && 'text-muted-foreground',
                        selected && 'text-secondary-foreground',
                      )}
                      onClick={() => toggleTag(tag.id)}
                    >
                      {tag.name}
                    </Button>
                  );
                })}
              </div>
            )}
          </FormCard>
        </div>
      </div>
    </div>
  );
}
