'use client';

import { useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';
import { Pencil, Plus, Tags, Trash2 } from 'lucide-react';

import { cmsApi, CmsTag } from '@/api/cms';
import { usePerm } from '@/lib/perm';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import {
  TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table';
import { CmsTable, ListCard, PageHeader, TableStateRow } from '../shared';

interface TagFormState { name: string; slug: string }
const EMPTY_FORM: TagFormState = { name: '', slug: '' };

/** 标签管理：扁平列表 + 新建/编辑弹窗 + 删除（后端级联清理文章关联） */
export default function CmsTagPage() {
  const canEdit = usePerm()('admin:cms:content:edit');

  const [tags, setTags] = useState<CmsTag[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [form, setForm] = useState<TagFormState>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await cmsApi.tagList();
      setTags(res.data || []);
    } catch (e) {
      const message = (e as Error).message || '标签加载失败';
      setError(message);
      setTags([]);
      toast.error(message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const openCreate = () => {
    setEditingId(null);
    setForm(EMPTY_FORM);
    setDialogOpen(true);
  };

  const openEdit = (tag: CmsTag) => {
    setEditingId(tag.id);
    setForm({ name: tag.name || '', slug: tag.slug || '' });
    setDialogOpen(true);
  };

  const submit = async () => {
    if (!form.name.trim()) { toast.error('请填写标签名称'); return; }
    setSaving(true);
    const payload = { name: form.name.trim(), slug: form.slug.trim() || undefined };
    try {
      if (editingId != null) await cmsApi.updateTag(editingId, payload);
      else await cmsApi.createTag(payload);
      toast.success(editingId != null ? '标签已更新' : '标签已创建');
      setDialogOpen(false);
      void load();
    } catch (e) {
      // 名称唯一约束冲突等后端校验消息直接展示
      toast.error((e as Error).message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const remove = async (tag: CmsTag) => {
    if (!window.confirm(`确认删除标签「${tag.name || tag.id}」？其与文章的关联会一并移除。`)) return;
    try {
      await cmsApi.deleteTag(tag.id);
      toast.success('已删除');
      void load();
    } catch (e) {
      toast.error((e as Error).message || '删除失败');
    }
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title="标签"
        description="文章的轻量归类方式，一篇文章可打多个标签；删除标签不会影响文章本身"
        actions={canEdit && (
          <Button className="gap-1.5" onClick={openCreate}>
            <Plus className="size-4" />新建标签
          </Button>
        )}
      />

      <ListCard
        toolbar={(
          <p className="text-xs text-muted-foreground">共 {tags.length} 个标签</p>
        )}
      >
        <CmsTable>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead>名称</TableHead>
              <TableHead className="w-48">别名</TableHead>
              <TableHead className="w-24 text-right">文章数</TableHead>
              <TableHead className="w-28 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading ? (
              <TableStateRow colSpan={4} loading />
            ) : error ? (
              <TableStateRow colSpan={4} error={error} onRetry={() => void load()} />
            ) : tags.length === 0 ? (
              <TableStateRow
                colSpan={4}
                emptyText="还没有标签"
                emptyHint="标签是文章之外的轻量归类方式，可在文章表单里多选"
                emptyAction={canEdit ? (
                  <Button size="sm" className="gap-1.5" onClick={openCreate}>
                    <Plus className="size-4" />新建标签
                  </Button>
                ) : undefined}
              />
            ) : tags.map((tag) => (
              <TableRow key={tag.id}>
                <TableCell className="font-medium">{tag.name || '-'}</TableCell>
                <TableCell className="text-muted-foreground">{tag.slug || '-'}</TableCell>
                <TableCell className="text-right text-muted-foreground tabular-nums">
                  {tag.postCount ?? 0}
                </TableCell>
                <TableCell>
                  {canEdit && (
                    <div className="flex items-center justify-end gap-0.5">
                      <Button variant="ghost" size="sm" className="h-8 gap-1 px-2" onClick={() => openEdit(tag)}>
                        <Pencil className="size-3.5" />编辑
                      </Button>
                      <Button variant="ghost" size="icon"
                              className="size-8 text-muted-foreground hover:text-destructive"
                              aria-label={`删除标签 ${tag.name || tag.id}`}
                              onClick={() => remove(tag)}>
                        <Trash2 className="size-4" />
                      </Button>
                    </div>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </CmsTable>
      </ListCard>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>{editingId != null ? '编辑标签' : '新建标签'}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="tag-name">名称 *</Label>
              <Input id="tag-name" value={form.name} maxLength={64}
                     onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))} placeholder="标签名称" />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="tag-slug">URL 别名</Label>
              <Input id="tag-slug" value={form.slug} maxLength={64}
                     onChange={(e) => setForm((p) => ({ ...p, slug: e.target.value }))} placeholder="可选" />
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setDialogOpen(false)}>取消</Button>
            <Button onClick={submit} disabled={saving} className="gap-1.5">
              <Tags className="size-4" />{saving ? '保存中…' : '保存'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
