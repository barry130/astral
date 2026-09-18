'use client';

import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  Button,
  Select,
  Upload,
  message,
  Spin,
  Empty,
  Image,
  Popconfirm,
  Switch,
  Pagination,
  Tag,
} from 'antd';
import {
  InboxOutlined,
  CopyOutlined,
  DeleteOutlined,
  LinkOutlined,
  HomeOutlined,
  PictureOutlined,
} from '@ant-design/icons';
import { useAuth } from '@/context/AuthContext';
import { storageApi, MyStorageFolder, StorageFile } from '@/api/storage';

/**
 * 图床（用户侧，路由 /imgbed）
 * 浏览授权给自己的文件夹、直传图片到存储 Provider、复制外链。
 * 文件正文不经过 Astral：先换取上传凭证，再直传 Worker / 对象存储，最后回执登记。
 */

const PERM_ALL = 'ALL';

/** 判断权限集合是否包含指定权限（ALL 表示所有者，拥有全部权限） */
function hasPerm(myPermissions: string | undefined, perm: string): boolean {
  if (!myPermissions) return false;
  if (myPermissions === PERM_ALL) return true;
  return myPermissions.split(',').map((p) => p.trim().toUpperCase()).includes(perm);
}

/** 单个图片卡片：按需换取访问 URL（PUBLIC 走永久链接，私有走短时签名链接） */
function ImageCard({
  file,
  canUpdate,
  canDelete,
  onChanged,
}: {
  file: StorageFile;
  canUpdate: boolean;
  canDelete: boolean;
  onChanged: () => void;
}) {
  const [url, setUrl] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const loadUrl = useCallback(async () => {
    if (!file.publicId) return;
    setLoading(true);
    try {
      const res = file.visibility === 'PUBLIC'
        ? await storageApi.permanentUrl(file.publicId)
        : await storageApi.downloadUrl(file.publicId);
      if (res.code === 200) {
        setUrl(res.data?.url || '');
      }
    } catch {
      setUrl('');
    } finally {
      setLoading(false);
    }
  }, [file.publicId, file.visibility]);

  useEffect(() => {
    loadUrl();
  }, [loadUrl]);

  const copy = async (text: string, tip: string) => {
    try {
      await navigator.clipboard.writeText(text);
      message.success(tip);
    } catch {
      message.error('复制失败，请手动复制');
    }
  };

  const toggleVisibility = async (checked: boolean) => {
    if (!file.publicId) return;
    setBusy(true);
    try {
      const res = await storageApi.changeFileVisibility(file.publicId, checked ? 'PUBLIC' : 'PRIVATE');
      if (res.code !== 200) throw new Error(res.message);
      message.success(checked ? '已设为公开' : '已设为私有');
      onChanged();
    } catch (e: any) {
      message.error(e.message || '修改可见性失败');
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!file.publicId) return;
    setBusy(true);
    try {
      const res = await storageApi.deleteMyFile(file.publicId);
      if (res.code !== 200) throw new Error(res.message);
      message.success('已删除');
      onChanged();
    } catch (e: any) {
      message.error(e.message || '删除失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="imgbed-card">
      <div className="imgbed-thumb">
        {loading ? (
          <Spin />
        ) : url ? (
          <Image src={url} alt={file.originalName} style={{ objectFit: 'cover', width: '100%', height: '100%' }} />
        ) : (
          <PictureOutlined style={{ fontSize: 28, color: 'var(--color-text-tertiary)' }} />
        )}
      </div>
      <div className="imgbed-meta">
        <div className="imgbed-name" title={file.originalName}>{file.originalName || '未命名'}</div>
        <div className="imgbed-sub">
          {file.visibility === 'PUBLIC' ? <Tag color="green">公开</Tag> : <Tag>私有</Tag>}
          <span>{((file.sizeBytes || 0) / 1024).toFixed(1)} KB</span>
        </div>
      </div>
      <div className="imgbed-actions">
        <Button
          size="small"
          icon={<LinkOutlined />}
          disabled={!url}
          onClick={() => copy(url, '链接已复制')}
        >
          复制链接
        </Button>
        <Button
          size="small"
          icon={<CopyOutlined />}
          disabled={!url}
          onClick={() => copy(`![${file.originalName || 'image'}](${url})`, 'Markdown 已复制')}
        >
          Markdown
        </Button>
        {canUpdate && (
          <Switch
            size="small"
            checked={file.visibility === 'PUBLIC'}
            loading={busy}
            checkedChildren="公开"
            unCheckedChildren="私有"
            onChange={toggleVisibility}
          />
        )}
        {canDelete && (
          <Popconfirm title="确定删除这张图片？" onConfirm={remove} okText="删除" cancelText="取消">
            <Button size="small" danger icon={<DeleteOutlined />} loading={busy} />
          </Popconfirm>
        )}
      </div>
    </div>
  );
}

export default function ImageBedPage() {
  const { isLogin, loading: authLoading, user } = useAuth();
  const router = useRouter();

  const [folders, setFolders] = useState<MyStorageFolder[]>([]);
  const [folderId, setFolderId] = useState<number | undefined>();
  const [foldersLoading, setFoldersLoading] = useState(true);
  const [files, setFiles] = useState<StorageFile[]>([]);
  const [filesLoading, setFilesLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [uploading, setUploading] = useState(false);

  /** 未登录跳转登录页 */
  useEffect(() => {
    if (!authLoading && !isLogin) {
      router.replace('/login');
    }
  }, [authLoading, isLogin, router]);

  const current = folders.find((f) => f.id === folderId);
  const canUpload = hasPerm(current?.myPermissions, 'UPLOAD');
  const canUpdate = hasPerm(current?.myPermissions, 'UPDATE');
  const canDelete = hasPerm(current?.myPermissions, 'DELETE');

  /** 加载授权给我的文件夹，默认选中第一个 */
  useEffect(() => {
    if (authLoading || !isLogin) return;
    (async () => {
      setFoldersLoading(true);
      try {
        const res = await storageApi.listMyFolders();
        if (res.code === 200) {
          const list: MyStorageFolder[] = res.data || [];
          setFolders(list);
          if (list.length > 0) {
            setFolderId((prev) => prev ?? list[0].id);
          }
        } else {
          message.error(res.message || '加载文件夹失败');
        }
      } catch (e: any) {
        message.error(e.message || '加载文件夹失败');
      } finally {
        setFoldersLoading(false);
      }
    })();
  }, [authLoading, isLogin]);

  const loadFiles = useCallback(async (fid: number, p: number) => {
    setFilesLoading(true);
    try {
      const res = await storageApi.myFolderFiles(fid, { current: p, size: 20 });
      if (res.code === 200) {
        setFiles(res.data?.records || []);
        setTotal(res.data?.total || 0);
      } else {
        setFiles([]);
        setTotal(0);
      }
    } catch (e: any) {
      setFiles([]);
      setTotal(0);
      message.error(e.message || '加载文件失败');
    } finally {
      setFilesLoading(false);
    }
  }, []);

  useEffect(() => {
    if (folderId != null) {
      loadFiles(folderId, page);
    }
  }, [folderId, page, loadFiles]);

  /** 统一直传：PUT 预签名 / UPYUN 表单 / TELEGRAM Worker 三种通道 */
  const uploadFileDirect = async (targetFolderId: number, file: File): Promise<string> => {
    const ticketRes = await storageApi.uploadTicket({
      folderId: targetFolderId,
      fileName: file.name,
      contentType: file.type || 'application/octet-stream',
      sizeBytes: file.size,
    });
    if (ticketRes.code !== 200) throw new Error(ticketRes.message);
    const ticket = ticketRes.data;
    if (ticket.method === 'PUT') {
      const putResp = await fetch(ticket.uploadUrl, { method: 'PUT', body: file });
      if (!putResp.ok) throw new Error(`对象存储返回 ${putResp.status}`);
      const regRes = await storageApi.registerUpload(ticket.uploadId);
      if (regRes.code !== 200) throw new Error(regRes.message);
      return regRes.data.publicId;
    }
    if (ticket.form) {
      const form = new FormData();
      form.append('policy', ticket.form.policy);
      form.append('authorization', ticket.form.authorization);
      form.append(ticket.formField || 'file', file);
      const resp = await fetch(ticket.uploadUrl, { method: 'POST', body: form });
      if (!resp.ok) throw new Error(`又拍云返回 ${resp.status}`);
      const regRes = await storageApi.registerUpload(ticket.uploadId);
      if (regRes.code !== 200) throw new Error(regRes.message);
      return regRes.data.publicId;
    }
    const form = new FormData();
    form.append(ticket.formField || 'file', file);
    const resp = await fetch(ticket.uploadUrl, { method: ticket.method, body: form });
    const json = await resp.json().catch(() => ({}));
    if (!resp.ok || !json.ok) {
      throw new Error(json.message || `Worker 返回 ${resp.status}`);
    }
    return json.publicId;
  };

  const customUploadRequest = async (options: any) => {
    if (folderId == null) {
      message.warning('请先选择文件夹');
      options.onError?.(new Error('no folder'));
      return;
    }
    setUploading(true);
    try {
      await uploadFileDirect(folderId, options.file);
      message.success(`上传成功：${options.file.name}`);
      options.onSuccess?.({});
      loadFiles(folderId, 1);
      setPage(1);
    } catch (e: any) {
      message.error(e.message || '上传失败');
      options.onError?.(e);
    } finally {
      setUploading(false);
    }
  };

  if (authLoading || !isLogin) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div className="imgbed-container">
      <header className="imgbed-header">
        <div className="imgbed-header-inner">
          <div className="imgbed-title">
            <PictureOutlined />
            <span>图床</span>
          </div>
          <div className="imgbed-header-actions">
            <span className="imgbed-user">{user?.nickname || user?.username}</span>
            <Button type="text" icon={<HomeOutlined />} onClick={() => router.push('/')}>
              返回首页
            </Button>
            <Button type="text" onClick={() => router.push('/dashboard')}>
              控制台
            </Button>
          </div>
        </div>
      </header>

      <main className="imgbed-main">
        {foldersLoading ? (
          <div style={{ textAlign: 'center', padding: 80 }}>
            <Spin size="large" />
          </div>
        ) : folders.length === 0 ? (
          <Empty
            description="暂无可用文件夹。请联系管理员在「插件管理 → 文件存储」中创建文件夹并授权给你。"
            style={{ padding: 80 }}
          />
        ) : (
          <>
            <div className="imgbed-toolbar">
              <div className="imgbed-toolbar-left">
                <span className="imgbed-label">上传到</span>
                <Select
                  style={{ minWidth: 240 }}
                  value={folderId}
                  onChange={(v) => {
                    setFolderId(v);
                    setPage(1);
                  }}
                  options={folders.map((f) => ({
                    value: f.id,
                    label: `${f.folderName}${hasPerm(f.myPermissions, 'UPLOAD') ? '' : '（只读）'}`,
                  }))}
                />
                {current?.visibility === 'PUBLIC' && <Tag color="green">公开文件夹</Tag>}
              </div>
              <div className="imgbed-toolbar-right">
                <span className="imgbed-hint">
                  {canUpload ? '支持拖拽 / 点击上传图片' : '当前文件夹无上传权限'}
                </span>
              </div>
            </div>

            <Upload.Dragger
              multiple
              accept="image/*"
              showUploadList={false}
              disabled={!canUpload || uploading}
              customRequest={customUploadRequest}
              className="imgbed-dragger"
            >
              <p className="ant-upload-drag-icon">
                <InboxOutlined />
              </p>
              <p className="ant-upload-text">
                {uploading ? '上传中…' : '点击或拖拽图片到此区域上传'}
              </p>
              <p className="ant-upload-hint">文件直传存储服务，不经过应用服务器</p>
            </Upload.Dragger>

            <div className="imgbed-list-head">
              <span>共 {total} 张图片</span>
              <Button size="small" onClick={() => folderId != null && loadFiles(folderId, page)}>
                刷新
              </Button>
            </div>

            {filesLoading ? (
              <div style={{ textAlign: 'center', padding: 60 }}>
                <Spin />
              </div>
            ) : files.length === 0 ? (
              <Empty description="该文件夹还没有图片" style={{ padding: 60 }} />
            ) : (
              <div className="imgbed-grid">
                {files.map((f) => (
                  <ImageCard
                    key={f.publicId || f.id}
                    file={f}
                    canUpdate={canUpdate}
                    canDelete={canDelete}
                    onChanged={() => folderId != null && loadFiles(folderId, page)}
                  />
                ))}
              </div>
            )}

            {total > 20 && (
              <div style={{ textAlign: 'center', marginTop: 24 }}>
                <Pagination
                  current={page}
                  total={total}
                  pageSize={20}
                  showSizeChanger={false}
                  onChange={(p) => setPage(p)}
                />
              </div>
            )}
          </>
        )}
      </main>
    </div>
  );
}
