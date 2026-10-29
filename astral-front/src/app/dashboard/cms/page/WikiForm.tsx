'use client';

import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { toast } from 'sonner';
import { ArrowLeft, Save } from 'lucide-react';

import { cmsApi, cmsDictOptions, loadCmsDicts, CmsWikiPage } from '@/api/cms';
import { usePerm } from '@/lib/perm';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { MarkdownEditor } from '@/components/markdown-editor';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { collectSelfAndDescendantIds, flattenTree, FormCard, PageHeader } from '../shared';
import SharePanel from '../SharePanel';

const CONTENT_STATUS = 'cms_content_status';
const NONE = '__none__';

/** 状态说明：仅对需要额外解释的状态给一句提示（其余状态语义已足够直白） */
const STATUS_HINT: Record<string, string> = {
  private: '私密：仅登录的管理员在前台可见，可用分享链接临时开放给他人',
};

interface WikiFormState {
  title: string;
  slug: string;
  parentId: string;
  sort: string;
  status: string;
  content: string;
}

const EMPTY_FORM: WikiFormState = {
  title: '',
  slug: '',
  parentId: NONE,
  sort: '0',
  status: 'draft',
  content: '',
};

/**
 * Wiki 页面新建/编辑共用表单，可选父页面构成树。
 * defaultParentId 用于「新建子页面」入口预填父页面。
 * 排版与 PostForm 一致：左栏「基本信息 / 内容」，右栏「发布设置」。
 */
export default function WikiForm({
  pageId,
  defaultParentId,
}: {
  pageId?: number;
  defaultParentId?: string;
}) {
  const router = useRouter();
  const canEdit = usePerm()('admin:cms:content:edit');
  const [form, setForm] = useState<WikiFormState>(EMPTY_FORM);
  const [pages, setPages] = useState<CmsWikiPage[]>([]);
  const [saving, setSaving] = useState(false);
  const editing = typeof pageId === 'number' && Number.isFinite(pageId);
  // 编辑态首帧即显示骨架，避免表单先闪一下空值
  const [loading, setLoading] = useState(editing);

  useEffect(() => {
    void loadCmsDicts().catch(() => {
      /* 字典拉取失败时回退展示原始值 */
    });
    cmsApi
      .pageTree()
      .then((res) => setPages(res.data || []))
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (editing) {
      setLoading(true);
      cmsApi
        .pageDetail(pageId!)
        .then((res) => {
          const page: CmsWikiPage = res.data || {};
          setForm({
            title: page.title || '',
            slug: page.slug || '',
            parentId: page.parentId != null ? String(page.parentId) : NONE,
            sort: String(page.sort ?? 0),
            status: page.status || 'draft',
            content: page.content || '',
          });
        })
        .catch((e: Error) => {
          toast.error((e as Error).message || '页面加载失败');
          router.replace('/dashboard/cms/page');
        })
        .finally(() => setLoading(false));
    } else if (defaultParentId) {
      setForm((prev) => ({ ...prev, parentId: defaultParentId }));
    }
  }, [editing, pageId, defaultParentId, router]);

  const setField = <K extends keyof WikiFormState>(key: K, value: WikiFormState[K]) => {
    setForm((prev) => ({ ...prev, [key]: value }));
  };

  const submit = useCallback(async () => {
    if (!form.title.trim()) {
      toast.error('请填写标题');
      return;
    }
    if (!form.content.trim()) {
      toast.error('请填写内容');
      return;
    }
    setSaving(true);
    const payload = {
      title: form.title.trim(),
      slug: form.slug.trim() || undefined,
      parentId: form.parentId !== NONE ? Number(form.parentId) : undefined,
      sort: Number(form.sort) || 0,
      status: form.status,
      content: form.content,
    };
    try {
      if (editing) await cmsApi.updatePage(pageId!, payload);
      else await cmsApi.createPage(payload);
      toast.success(editing ? '页面已更新' : '页面已创建');
      router.push('/dashboard/cms/page');
    } catch (e) {
      toast.error((e as Error).message || '保存失败');
    } finally {
      setSaving(false);
    }
  }, [editing, form, pageId, router]);

  /** 编辑时父页面可选集排除自身及子孙，避免成环 */
  const tree = flattenTree(pages);
  const editingNode = editing ? tree.find((f) => f.node.id === pageId)?.node : undefined;
  const excluded = editingNode ? collectSelfAndDescendantIds(editingNode) : null;
  const parentOptions = tree.filter((f) => !excluded || !excluded.has(f.node.id));

  return (
    <div className="space-y-4">
      <PageHeader
        title={editing ? '编辑 Wiki 页面' : '新建 Wiki 页面'}
        description="Wiki 页面按树形组织，适合知识库与文档；正文使用 Markdown"
        actions={
          <Button variant="ghost" size="sm" asChild className="text-muted-foreground gap-1">
            <Link href="/dashboard/cms/page">
              <ArrowLeft className="size-3.5" />
              返回列表
            </Link>
          </Button>
        }
      />

      {loading ? (
        <div role="status" aria-busy="true" aria-label="页面加载中" className="space-y-4">
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
      ) : (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_340px] lg:items-start">
          <div className="space-y-4">
            <FormCard
              title="基本信息"
              description="标题会出现在导航与面包屑中；URL 别名留空时按标题或 ID 访问"
            >
              <div className="space-y-1.5">
                <Label htmlFor="wiki-title">
                  标题 <span className="text-destructive">*</span>
                </Label>
                <Input
                  id="wiki-title"
                  value={form.title}
                  disabled={!canEdit}
                  onChange={(e) => setField('title', e.target.value)}
                  placeholder="页面标题"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="wiki-slug">URL 别名</Label>
                <Input
                  id="wiki-slug"
                  value={form.slug}
                  disabled={!canEdit}
                  onChange={(e) => setField('slug', e.target.value)}
                  placeholder="可选，如 getting-started"
                />
              </div>
            </FormCard>

            <FormCard
              title="内容"
              description="Markdown 语法，工具栏可直接插入常用语法，保存后前台实时渲染"
            >
              <div className="space-y-1.5">
                <Label className="sr-only">内容</Label>
                <MarkdownEditor
                  value={form.content}
                  onChange={(v) => setField('content', v)}
                  readOnly={!canEdit}
                  placeholder={'# 标题\n\n页面内容…'}
                />
              </div>
            </FormCard>

            {/* 分享面板只在编辑态出现：新建时还没有页面 ID，链接无从生成 */}
            {editing && <SharePanel targetType="page" targetId={pageId} />}
          </div>

          <div className="space-y-4 lg:sticky lg:top-4">
            <FormCard title="发布设置" description="层级、排序与可见性">
              <div className="space-y-1.5">
                <Label>父页面</Label>
                <Select
                  value={form.parentId}
                  onValueChange={(v) => setField('parentId', v)}
                  disabled={!canEdit}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={NONE}>作为顶级页面</SelectItem>
                    {parentOptions.map(({ node, depth }) => (
                      <SelectItem key={node.id} value={String(node.id)}>
                        {'\u00A0'.repeat(depth * 2)}
                        {depth > 0 ? '└ ' : ''}
                        {node.title}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p className="text-muted-foreground text-xs leading-relaxed">
                  父页面的子页面不可选自身，避免层级成环
                </p>
              </div>
              <div className="space-y-1.5">
                <Label>状态</Label>
                <Select
                  value={form.status}
                  onValueChange={(v) => setField('status', v)}
                  disabled={!canEdit}
                >
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
              <div className="space-y-1.5">
                <Label htmlFor="wiki-sort">排序</Label>
                <Input
                  id="wiki-sort"
                  type="number"
                  value={form.sort}
                  disabled={!canEdit}
                  onChange={(e) => setField('sort', e.target.value)}
                />
                <p className="text-muted-foreground text-xs leading-relaxed">
                  同级内升序，数字小的靠前
                </p>
              </div>
            </FormCard>

            <div className="flex items-center justify-end gap-2">
              <Button variant="ghost" onClick={() => router.push('/dashboard/cms/page')}>
                取消
              </Button>
              <Button onClick={submit} disabled={saving || !canEdit} className="gap-1.5">
                <Save className="size-4" />
                {saving ? '保存中…' : '保存'}
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
