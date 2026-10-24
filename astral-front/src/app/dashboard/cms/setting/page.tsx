'use client';

import { useEffect, useState } from 'react';
import { toast } from 'sonner';
import { FileArchive, FileText, Save } from 'lucide-react';

import { cmsApi, downloadCmsExport, CmsExportKind } from '@/api/cms';
import { usePerm } from '@/lib/perm';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { FormCard, PageHeader } from '../shared';

/** 设置键（与后端 CmsSettingService 对齐，未知键会被后端拒绝） */
const KEY_TITLE = 'site_title';
const KEY_DESCRIPTION = 'site_description';
const KEY_BLOG_URL = 'blog_url';
const KEY_FOOTER = 'footer_text';
const KEY_COMMENT_ENABLED = 'comment_enabled';
const KEY_NEED_REVIEW = 'comment_need_review';

/** 单个开关行：左侧标签 + 说明，右侧 Switch，上下用发丝线分隔 */
function SwitchRow({ id, label, hint, checked, disabled, onCheckedChange }: {
  id: string;
  label: string;
  hint: string;
  checked: boolean;
  disabled?: boolean;
  onCheckedChange: (value: boolean) => void;
}) {
  return (
    <div className="flex items-start justify-between gap-6 py-1">
      <div className="min-w-0 space-y-1">
        <Label htmlFor={id} className="cursor-pointer">{label}</Label>
        <p className="text-xs leading-relaxed text-muted-foreground">{hint}</p>
      </div>
      <Switch id={id} checked={checked} disabled={disabled} onCheckedChange={onCheckedChange} />
    </div>
  );
}

/** 站点设置：按「站点信息 / 评论策略」分组，长表单不再是一张通栏卡片 */
export default function CmsSettingPage() {
  const canEdit = usePerm()('admin:cms:setting:edit');

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [blogUrl, setBlogUrl] = useState('');
  const [footer, setFooter] = useState('');
  const [commentEnabled, setCommentEnabled] = useState(true);
  const [needReview, setNeedReview] = useState(true);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  /** 正在下载的导出类型（用于按钮禁用与文案，避免连点重复下载） */
  const [exporting, setExporting] = useState<CmsExportKind | null>(null);

  useEffect(() => {
    cmsApi.settingList()
      .then((res) => {
        const data = res.data || {};
        setTitle(data[KEY_TITLE] || '');
        setDescription(data[KEY_DESCRIPTION] || '');
        setBlogUrl(data[KEY_BLOG_URL] || '');
        setFooter(data[KEY_FOOTER] || '');
        setCommentEnabled(data[KEY_COMMENT_ENABLED] !== 'false'); // 缺省视为开启
        setNeedReview(data[KEY_NEED_REVIEW] !== 'false');
      })
      .catch((e: Error) => toast.error(e.message || '设置加载失败'))
      .finally(() => setLoading(false));
  }, []);

  const submit = async () => {
    setSaving(true);
    try {
      await cmsApi.saveSettings({
        [KEY_TITLE]: title.trim(),
        [KEY_DESCRIPTION]: description.trim(),
        [KEY_BLOG_URL]: blogUrl.trim(),
        [KEY_FOOTER]: footer.trim(),
        [KEY_COMMENT_ENABLED]: String(commentEnabled),
        [KEY_NEED_REVIEW]: String(needReview),
      });
      toast.success('设置已保存');
    } catch (e) {
      toast.error((e as Error).message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const disabled = !canEdit || loading;

  /** 内容导出：文件流由后端直接返回，前端只负责触发下载与失败提示 */
  const doExport = async (kind: CmsExportKind) => {
    setExporting(kind);
    try {
      const res = await downloadCmsExport(kind);
      if (res.ok) toast.success('已开始下载');
      else toast.error(res.error || '导出失败');
    } finally {
      setExporting(null);
    }
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title="站点设置"
        description="CMS 插件的站点信息与评论策略，保存后立即生效"
        actions={canEdit && (
          <Button onClick={submit} disabled={saving || loading} className="gap-1.5">
            <Save className="size-4" />{saving ? '保存中…' : '保存设置'}
          </Button>
        )}
      />

      {!canEdit && (
        <p className="rounded-md border border-border/60 bg-muted/40 px-4 py-2.5 text-xs text-muted-foreground">
          当前账号没有设置编辑权限，以下内容为只读。
        </p>
      )}

      {loading ? (
        <div role="status" aria-busy="true" aria-label="设置加载中" className="space-y-4">
          {[0, 1].map((card) => (
            <div key={card} className="rounded-xl border border-border/60 p-5">
              <Skeleton className="h-4 w-24" />
              <div className="mt-5 space-y-4">
                <Skeleton className="h-9 w-full" />
                <Skeleton className="h-9 w-[70%]" />
                <Skeleton className="h-9 w-[45%]" />
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="space-y-4">
          <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)] lg:items-start">
            <FormCard
              title="站点信息"
              description="展示在前台的站点标题、描述与页脚，同时影响 SEO 元信息"
            >
              <div className="space-y-1.5">
                <Label htmlFor="set-title">站点标题</Label>
                <Input id="set-title" value={title} disabled={disabled} maxLength={128}
                       onChange={(e) => setTitle(e.target.value)} placeholder="如：Astral 知识库" />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="set-desc">站点描述</Label>
                <Textarea id="set-desc" rows={3} value={description} disabled={disabled} maxLength={256}
                          onChange={(e) => setDescription(e.target.value)}
                          placeholder="用于前台 SEO 与站点介绍" />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="set-footer">页脚文字</Label>
                <Textarea id="set-footer" rows={2} value={footer} disabled={disabled}
                          onChange={(e) => setFooter(e.target.value)}
                          placeholder="如：© 2026 Astral" />
              </div>
            </FormCard>

            <div className="space-y-4">
              <FormCard
                title="博客地址"
                description="管理端「查看博客」入口指向该地址"
              >
                <div className="space-y-1.5">
                  <Label htmlFor="set-blog-url">前台地址</Label>
                  <Input id="set-blog-url" value={blogUrl} disabled={disabled} maxLength={256}
                         onChange={(e) => setBlogUrl(e.target.value)}
                         placeholder="留空则使用本地默认 http://localhost:3001" />
                  <p className="text-xs leading-relaxed text-muted-foreground">
                    部署到 EdgeOne Pages / Cloudflare Pages 后填绑定域名
                  </p>
                </div>
              </FormCard>

              <FormCard
                title="评论策略"
                description="评论开关只影响前台展示与新评论提交，历史评论不会被删除"
              >
                <div className="divide-y divide-border/60">
                  <SwitchRow
                    id="set-comment-enabled"
                    label="启用评论"
                    hint="关闭后前台隐藏评论框且不再接受新评论"
                    checked={commentEnabled}
                    disabled={disabled}
                    onCheckedChange={setCommentEnabled}
                  />
                  <div className="pt-4">
                    <SwitchRow
                      id="set-need-review"
                      label="评论需审核"
                      hint="新评论先进入待审核队列，通过后才对外可见"
                      checked={needReview}
                      disabled={disabled}
                      onCheckedChange={setNeedReview}
                    />
                  </div>
                </div>
              </FormCard>
            </div>
          </div>

          {/* 导出与站点设置无关，单独一张通栏卡片放底部，避免混进「保存设置」的语义 */}
          <FormCard
            title="内容导出"
            description="把全部内容导出成文件留存或迁移；导出当前全部数据，不受列表筛选影响"
          >
            <div className="flex flex-wrap items-center gap-2">
              <Button variant="outline" size="sm" className="gap-1.5"
                      disabled={exporting !== null}
                      onClick={() => void doExport('zip')}>
                <FileArchive className="size-4" />
                {exporting === 'zip' ? '打包中…' : '导出全站 ZIP'}
              </Button>
              <Button variant="outline" size="sm" className="gap-1.5"
                      disabled={exporting !== null}
                      onClick={() => void doExport('markdown')}>
                <FileText className="size-4" />
                {exporting === 'markdown' ? '生成中…' : '导出全部文章 Markdown'}
              </Button>
            </div>
            <p className="text-xs leading-relaxed text-muted-foreground">
              ZIP 包含文章、页面与图片目录结构；Markdown 合集把所有文章按发布时间合并成一个文件。
              单篇文章请在文章列表的「更多」菜单里导出。
            </p>
          </FormCard>
        </div>
      )}

    </div>
  );
}
