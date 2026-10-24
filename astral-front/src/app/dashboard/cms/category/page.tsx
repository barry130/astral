'use client';

import { useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';
import { FolderPlus, MoreHorizontal, Pencil, Plus } from 'lucide-react';

import { cmsApi, CmsCategory } from '@/api/cms';
import { usePerm } from '@/lib/perm';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import {
  Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import {
  TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  CmsTable, collectSelfAndDescendantIds, flattenTree, IndentLabel,
  ListCard, PageHeader, TableStateRow,
} from '../shared';

const NONE = '__none__';

interface CategoryFormState {
  name: string;
  slug: string;
  parentId: string;
  sort: string;
  description: string;
}

const EMPTY_FORM: CategoryFormState = { name: '', slug: '', parentId: NONE, sort: '0', description: '' };

/** 分类管理：树形列表（缩进）+ 新建/编辑弹窗 + 删除（后端拦截时直接展示其错误消息） */
export default function CmsCategoryPage() {
  const canEdit = usePerm()('admin:cms:content:edit');

  const [categories, setCategories] = useState<CmsCategory[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [form, setForm] = useState<CategoryFormState>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await cmsApi.categoryTree();
      setCategories(res.data || []);
    } catch (e) {
      const message = (e as Error).message || '分类加载失败';
      setError(message);
      setCategories([]);
      toast.error(message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const openCreate = (parent?: CmsCategory) => {
    setEditingId(null);
    setForm({ ...EMPTY_FORM, parentId: parent ? String(parent.id) : NONE });
    setDialogOpen(true);
  };

  const openEdit = (node: CmsCategory) => {
    setEditingId(node.id);
    setForm({
      name: node.name || '',
      slug: node.slug || '',
      parentId: node.parentId != null ? String(node.parentId) : NONE,
      sort: String(node.sort ?? 0),
      description: node.description || '',
    });
    setDialogOpen(true);
  };

  const submit = async () => {
    if (!form.name.trim()) { toast.error('请填写分类名称'); return; }
    setSaving(true);
    const payload = {
      name: form.name.trim(),
      slug: form.slug.trim() || undefined,
      parentId: form.parentId !== NONE ? Number(form.parentId) : undefined,
      sort: Number(form.sort) || 0,
      description: form.description.trim() || undefined,
    };
    try {
      if (editingId != null) await cmsApi.updateCategory(editingId, payload);
      else await cmsApi.createCategory(payload);
      toast.success(editingId != null ? '分类已更新' : '分类已创建');
      setDialogOpen(false);
      void load();
    } catch (e) {
      toast.error((e as Error).message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const remove = async (node: CmsCategory) => {
    if (!window.confirm(`确认删除分类「${node.name || node.id}」？`)) return;
    try {
      await cmsApi.deleteCategory(node.id);
      toast.success('已删除');
      void load();
    } catch (e) {
      // 有子分类或仍有已发布文章时后端拦截，直接展示其消息（如「该分类下仍有 N 篇文章…」）
      toast.error((e as Error).message || '删除失败');
    }
  };

  /** 编辑时父分类可选集要排除自身及子孙，避免把树改成环 */
  const tree = flattenTree(categories);
  const editingNode = editingId != null ? tree.find((f) => f.node.id === editingId)?.node : undefined;
  const excluded = editingNode ? collectSelfAndDescendantIds(editingNode) : null;
  const parentOptions = tree.filter((f) => !excluded || !excluded.has(f.node.id));

  return (
    <div className="space-y-4">
      <PageHeader
        title="分类"
        description="文章分类支持多级嵌套，一篇文章可同时归属多个分类；删除前需先移走子分类与已发布文章"
        actions={canEdit && (
          <Button className="gap-1.5" onClick={() => openCreate()}>
            <Plus className="size-4" />新建分类
          </Button>
        )}
      />

      <ListCard
        toolbar={(
          <p className="text-xs text-muted-foreground">
            共 {tree.length} 个分类 · 缩进表示层级，删除会连带清理文章分类关联
          </p>
        )}
      >
        <CmsTable>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead>名称</TableHead>
              <TableHead className="w-40">别名</TableHead>
              <TableHead className="w-16 text-right">排序</TableHead>
              <TableHead className="w-20 text-right">文章数</TableHead>
              <TableHead>描述</TableHead>
              <TableHead className="w-28 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading ? (
              <TableStateRow colSpan={6} loading />
            ) : error ? (
              <TableStateRow colSpan={6} error={error} onRetry={() => void load()} />
            ) : tree.length === 0 ? (
              <TableStateRow
                colSpan={6}
                emptyText="还没有分类"
                emptyHint="先建一个顶级分类，之后可以在它下面继续建子分类"
                emptyAction={canEdit ? (
                  <Button size="sm" className="gap-1.5" onClick={() => openCreate()}>
                    <Plus className="size-4" />新建分类
                  </Button>
                ) : undefined}
              />
            ) : tree.map(({ node, depth }) => (
              <TableRow key={node.id}>
                <TableCell className="max-w-[240px]">
                  <IndentLabel depth={depth} label={node.name} />
                </TableCell>
                <TableCell className="max-w-[160px] truncate text-muted-foreground" title={node.slug || undefined}>
                  {node.slug || '-'}
                </TableCell>
                <TableCell className="text-right text-muted-foreground tabular-nums">{node.sort ?? 0}</TableCell>
                <TableCell className="text-right text-muted-foreground tabular-nums">{node.postCount ?? 0}</TableCell>
                <TableCell className="max-w-[280px] truncate text-muted-foreground"
                           title={node.description || undefined}>
                  {node.description || '-'}
                </TableCell>
                <TableCell>
                  {canEdit && (
                    <div className="flex items-center justify-end gap-0.5">
                      <Button variant="ghost" size="sm" className="h-8 gap-1 px-2" onClick={() => openEdit(node)}>
                        <Pencil className="size-3.5" />编辑
                      </Button>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon" className="size-8 text-muted-foreground"
                                  aria-label="更多操作">
                            <MoreHorizontal className="size-4" />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="w-36">
                          <DropdownMenuItem onSelect={() => openCreate(node)}>新建子分类</DropdownMenuItem>
                          <DropdownMenuSeparator />
                          <DropdownMenuItem variant="destructive" onSelect={() => remove(node)}>删除</DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </div>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </CmsTable>
      </ListCard>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>{editingId != null ? '编辑分类' : '新建分类'}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="cat-name">名称 *</Label>
              <Input id="cat-name" value={form.name} maxLength={64}
                     onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))} placeholder="分类名称" />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="cat-slug">URL 别名</Label>
                <Input id="cat-slug" value={form.slug} maxLength={64}
                       onChange={(e) => setForm((p) => ({ ...p, slug: e.target.value }))} placeholder="可选" />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="cat-sort">排序</Label>
                <Input id="cat-sort" type="number" value={form.sort}
                       onChange={(e) => setForm((p) => ({ ...p, sort: e.target.value }))} />
              </div>
            </div>
            <div className="space-y-1.5">
              <Label>父分类</Label>
              <Select value={form.parentId} onValueChange={(v) => setForm((p) => ({ ...p, parentId: v }))}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value={NONE}>作为顶级分类</SelectItem>
                  {parentOptions.map(({ node, depth }) => (
                    <SelectItem key={node.id} value={String(node.id)}>
                      {'\u00A0'.repeat(depth * 2)}{depth > 0 ? '└ ' : ''}{node.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="cat-desc">描述</Label>
              <Textarea id="cat-desc" rows={2} maxLength={256} value={form.description}
                        onChange={(e) => setForm((p) => ({ ...p, description: e.target.value }))} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setDialogOpen(false)}>取消</Button>
            <Button onClick={submit} disabled={saving} className="gap-1.5">
              <FolderPlus className="size-4" />{saving ? '保存中…' : '保存'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
