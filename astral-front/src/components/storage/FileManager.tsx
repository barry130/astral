'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Check,
  ChevronLeft,
  ChevronRight,
  Copy,
  Download,
  Eye,
  File,
  FileArchive,
  FileAudio,
  FileImage,
  FileText,
  FileVideo,
  Folder,
  FolderOpen,
  FolderPlus,
  Grid2X2,
  HardDrive,
  List,
  Loader2,
  MoreHorizontal,
  Pencil,
  RefreshCw,
  Search,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import { toast } from 'sonner';
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
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  storageApi,
  FileBatchRequest,
  MyStorageFolder,
  StorageFile,
  StorageFolder,
} from '@/api/storage';
import { DictOption, fetchDictOptions } from '@/api/dict';
import { uploadStorageFile } from '@/lib/storage-upload';
import { cn } from '@/lib/utils';

export function hasFolderPermission(folder: MyStorageFolder | undefined, permission: string) {
  return folder?.myPermissions === 'ALL' || !!folder?.myPermissions.split(',').includes(permission);
}

function errorText(error: unknown) {
  return error instanceof Error ? error.message : '操作失败，请稍后重试';
}
function fileSize(bytes = 0) {
  if (bytes < 1024) return `${bytes} B`;
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), 4);
  return `${(bytes / 1024 ** i).toFixed(i > 1 ? 1 : 0)} ${['B', 'KB', 'MB', 'GB', 'TB'][i]}`;
}
function fileDate(value?: string) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
}
function FileIcon({ type, className }: { type?: string; className?: string }) {
  const Icon = type?.startsWith('image/')
    ? FileImage
    : type?.startsWith('audio/')
      ? FileAudio
      : type?.startsWith('video/')
        ? FileVideo
        : type?.includes('zip') || type?.includes('compressed')
          ? FileArchive
          : type?.startsWith('text/') || type?.includes('pdf') || type?.includes('document')
            ? FileText
            : File;
  return <Icon className={className} />;
}

const selectClass =
  'h-9 min-w-0 rounded-md border border-input bg-background px-2 text-sm text-foreground outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50';
const PAGE_SIZE = 24;
type QueueEntry = {
  id: string;
  file: File;
  folderId: number;
  folderName: string;
  state: 'wait' | 'run' | 'ok' | 'fail';
  error?: string;
};
type Editor = {
  type: 'new-folder' | 'folder-name' | 'file-name' | 'move' | 'delete-files' | 'delete-folder';
  folder?: MyStorageFolder;
  file?: StorageFile;
  ids?: string[];
};

/** 用户页和后台共享文件整理功能；后台请求使用独立的声明式权限接口。 */
export default function FileManager({
  admin = false,
  canEdit = true,
}: {
  admin?: boolean;
  canEdit?: boolean;
}) {
  const [folders, setFolders] = useState<MyStorageFolder[]>([]);
  const [foldersLoading, setFoldersLoading] = useState(true);
  const [folderId, setFolderId] = useState<number>();
  const [folderSearch, setFolderSearch] = useState('');
  const [files, setFiles] = useState<StorageFile[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [category, setCategory] = useState('');
  const [visibility, setVisibility] = useState('');
  const [sort, setSort] = useState('newest');
  const [view, setView] = useState<'grid' | 'list'>('list');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [dict, setDict] = useState<Record<string, DictOption[]>>({});
  const [refresh, setRefresh] = useState(0);
  const [busy, setBusy] = useState(false);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [name, setName] = useState('');
  const [targetId, setTargetId] = useState('');
  const [operationError, setOperationError] = useState('');
  const [failures, setFailures] = useState<{ publicId: string; message: string }[]>([]);
  const [preview, setPreview] = useState<{
    file: StorageFile;
    url?: string;
    error?: string;
  } | null>(null);
  const [queue, setQueue] = useState<QueueEntry[]>([]);
  const [uploading, setUploading] = useState(false);
  const [dragging, setDragging] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const requestVersion = useRef(0);
  const previewVersion = useRef(0);
  const uploadLock = useRef(false);

  const current = folders.find((f) => f.id === folderId);
  const folderMap = useMemo(() => new Map(folders.map((f) => [f.id, f])), [folders]);
  const permission = useCallback(
    (f: MyStorageFolder | undefined, p: string) =>
      admin ? p === 'READ' || canEdit : hasFolderPermission(f, p),
    [admin, canEdit],
  );
  const canRead = folderId === undefined || permission(current, 'READ');
  const canUpload = !!current && current.status === 'ENABLED' && permission(current, 'UPLOAD');
  const canManage = folderId === undefined ? canEdit : permission(current, 'MANAGE');
  const label = (code: string, value?: string) =>
    dict[code]?.find((d) => d.value === value)?.label || value || '—';
  const filePermission = (file: StorageFile, p: string) =>
    permission(folderMap.get(file.folderId || 0), p);
  const picked = files.filter((f) => f.publicId && selected.has(f.publicId));
  const selectable = files.filter(
    (f) =>
      f.publicId &&
      f.status === 'AVAILABLE' &&
      (filePermission(f, 'UPDATE') || filePermission(f, 'DELETE')),
  );
  const moveIds = editor?.ids || [];
  const moveFiles = files.filter((f) => f.publicId && moveIds.includes(f.publicId));
  const targetFolders = folders.filter(
    (f) =>
      f.status === 'ENABLED' &&
      permission(f, 'UPLOAD') &&
      moveFiles.every((file) => file.storageConfigId === f.storageConfigId),
  );

  const loadFolders = useCallback(async () => {
    try {
      const result = admin ? await storageApi.listFolders() : await storageApi.listMyFolders();
      if (result.code !== 200) throw new Error(result.msg);
      const rows: MyStorageFolder[] = admin
        ? (result.data as StorageFolder[])
            .filter((f) => f.id != null)
            .map((f) => ({
              id: f.id!,
              parentId: f.parentId,
              folderName: f.folderName || '',
              folderPath: f.folderPath || '',
              storageConfigId: f.storageConfigId!,
              visibility: f.visibility || '',
              status: f.status || '',
              myPermissions: 'ALL',
            }))
        : result.data || [];
      rows.sort((a, b) => a.folderPath.localeCompare(b.folderPath, 'zh-CN'));
      setFolders(rows);
      setFolderId((id) => (id != null && !rows.some((f) => f.id === id) ? undefined : id));
    } catch (error) {
      toast.error(errorText(error));
    } finally {
      setFoldersLoading(false);
    }
  }, [admin]);

  useEffect(() => {
    void loadFolders();
  }, [loadFolders]);
  useEffect(() => {
    let alive = true;
    fetchDictOptions([
      'storage_visibility',
      'storage_file_status',
      'storage_file_category',
      'storage_file_sort',
    ])
      .then((d) => {
        if (alive) setDict(d);
      })
      .catch(() => {
        if (alive) toast.error('筛选字典加载失败，请刷新页面');
      });
    return () => {
      alive = false;
    };
  }, []);
  useEffect(() => {
    const timer = setTimeout(() => {
      setKeyword(searchInput.trim());
      setPage(1);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchInput]);
  useEffect(() => {
    setSelected(new Set());
    setFailures([]);
  }, [folderId, keyword, category, visibility, sort, page]);
  useEffect(() => {
    if (foldersLoading) return;
    const version = ++requestVersion.current;
    if (!canRead) {
      setFiles([]);
      setTotal(0);
      setLoading(false);
      return;
    }
    setLoading(true);
    setLoadError('');
    storageApi
      .managerFiles(
        { folderId, keyword, category, visibility, sort, current: page, size: PAGE_SIZE },
        admin,
      )
      .then((result) => {
        if (requestVersion.current !== version) return;
        if (result.code !== 200) throw new Error(result.msg);
        setFiles(result.data.records || []);
        setTotal(result.data.total || 0);
        const last = Math.max(1, Math.ceil(result.data.total / PAGE_SIZE));
        if (page > last) setPage(last);
      })
      .catch((error) => {
        if (requestVersion.current === version) {
          setLoadError(errorText(error));
          setFiles([]);
          setTotal(0);
        }
      })
      .finally(() => {
        if (requestVersion.current === version) setLoading(false);
      });
    return () => {
      requestVersion.current++;
    };
  }, [
    admin,
    foldersLoading,
    folderId,
    canRead,
    keyword,
    category,
    visibility,
    sort,
    page,
    refresh,
  ]);

  const children = folders.filter((f) =>
    folderId == null ? f.parentId == null || !folderMap.has(f.parentId) : f.parentId === folderId,
  );
  const breadcrumbs: MyStorageFolder[] = [];
  let ancestor = current;
  const visited = new Set<number>();
  while (ancestor && !visited.has(ancestor.id)) {
    breadcrumbs.unshift(ancestor);
    visited.add(ancestor.id);
    ancestor = ancestor.parentId == null ? undefined : folderMap.get(ancestor.parentId);
  }
  const navigate = (id?: number) => {
    setFolderId(id);
    setPage(1);
    setSelected(new Set());
  };
  const reload = () => {
    setRefresh((v) => v + 1);
    void loadFolders();
  };
  const openEditor = (next: Editor) => {
    setOperationError('');
    setName(next.file?.originalName || next.folder?.folderName || '');
    setTargetId('');
    setEditor(next);
  };
  const toggle = (id: string) =>
    setSelected((previous) => {
      const next = new Set(previous);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const runBatch = async (request: FileBatchRequest) => {
    const result = await storageApi.managerBatch(request, admin);
    if (result.code !== 200) throw new Error(result.msg);
    setFailures(result.data.failed);
    setSelected(new Set(result.data.failed.map((f) => f.publicId)));
    if (result.data.succeeded.length)
      toast.success(`已处理 ${result.data.succeeded.length} 个文件`);
    if (result.data.failed.length)
      toast.error(`${result.data.failed.length} 个文件未完成，请查看失败明细`);
    setRefresh((v) => v + 1);
  };
  const saveEditor = async () => {
    if (!editor || busy) return;
    setBusy(true);
    setOperationError('');
    try {
      if (editor.type === 'new-folder') {
        const result = admin
          ? await storageApi.createFolder({
              parentId: folderId,
              folderName: name.trim(),
              configId: current?.storageConfigId,
            })
          : await storageApi.createMyFolder({ parentId: folderId, folderName: name.trim() });
        if (result.code !== 200) throw new Error(result.msg);
        toast.success('文件夹已创建');
      } else if (editor.type === 'folder-name') {
        const result = admin
          ? await storageApi.updateFolder(editor.folder!.id, { folderName: name.trim() })
          : await storageApi.renameMyFolder(editor.folder!.id, name.trim());
        if (result.code !== 200) throw new Error(result.msg);
        toast.success('文件夹已重命名');
      } else if (editor.type === 'delete-folder') {
        const result = admin
          ? await storageApi.deleteFolder(editor.folder!.id)
          : await storageApi.deleteMyFolder(editor.folder!.id);
        if (result.code !== 200) throw new Error(result.msg);
        toast.success('文件夹已删除');
      } else if (editor.type === 'file-name') {
        const result = await storageApi.managerUpdate(
          editor.file!.publicId!,
          { originalName: name.trim() },
          admin,
        );
        if (result.code !== 200) throw new Error(result.msg);
        toast.success('文件已重命名');
      } else if (editor.type === 'move') {
        if (!targetId) throw new Error('请选择目标文件夹');
        await runBatch({ publicIds: editor.ids!, action: 'move', folderId: Number(targetId) });
      } else await runBatch({ publicIds: editor.ids!, action: 'delete' });
      setEditor(null);
      reload();
    } catch (error) {
      setOperationError(errorText(error));
    } finally {
      setBusy(false);
    }
  };
  const changeVisibility = async (value: string) => {
    setBusy(true);
    try {
      await runBatch({ publicIds: [...selected], action: 'visibility', visibility: value });
    } catch (error) {
      toast.error(errorText(error));
    } finally {
      setBusy(false);
    }
  };
  const openPreview = async (file: StorageFile) => {
    const version = ++previewVersion.current;
    setPreview({ file });
    try {
      // PUBLIC 文件优先用永久链接：无过期签名，Worker 边缘长缓存可直接命中（省一次换链 POST + 更高缓存命中率）。
      // 失败（如配置未填 Worker 地址）或 PRIVATE 文件仍走签名链接。
      if (file.visibility === 'PUBLIC') {
        try {
          const permanent = await storageApi.managerUrl(file.publicId!, true, admin);
          if (version !== previewVersion.current) return;
          if (permanent.code === 200 && permanent.data?.url) {
            setPreview({ file, url: permanent.data.url });
            return;
          }
        } catch {
          /* 回退签名链接 */
        }
      }
      const result = await storageApi.managerUrl(file.publicId!, false, admin);
      if (version !== previewVersion.current) return;
      if (result.code !== 200) throw new Error(result.msg);
      setPreview({ file, url: result.data.url });
    } catch (error) {
      if (version === previewVersion.current) setPreview({ file, error: errorText(error) });
    }
  };
  const getLink = async (file: StorageFile, action: 'copy' | 'permanent' | 'download') => {
    try {
      const result = await storageApi.managerUrl(file.publicId!, action === 'permanent', admin);
      if (result.code !== 200) throw new Error(result.msg);
      if (action === 'download') {
        const a = document.createElement('a');
        a.href = result.data.url;
        a.download = file.originalName || 'download';
        a.target = '_blank';
        a.rel = 'noopener noreferrer';
        document.body.appendChild(a);
        a.click();
        a.remove();
      } else {
        await navigator.clipboard.writeText(result.data.url);
        toast.success(
          action === 'permanent' ? '永久链接已复制' : '临时链接已复制，过期后需重新获取',
        );
      }
    } catch (error) {
      toast.error(errorText(error));
    }
  };

  const uploadEntries = async (entries: QueueEntry[]) => {
    if (uploadLock.current) return;
    uploadLock.current = true;
    setUploading(true);
    const pending = [...entries];
    const patch = (id: string, change: Partial<QueueEntry>) =>
      setQueue((rows) => rows.map((row) => (row.id === id ? { ...row, ...change } : row)));
    const worker = async () => {
      for (let entry = pending.shift(); entry; entry = pending.shift()) {
        patch(entry.id, { state: 'run', error: undefined });
        try {
          await uploadStorageFile(entry.folderId, entry.file, admin);
          patch(entry.id, { state: 'ok' });
        } catch (error) {
          patch(entry.id, { state: 'fail', error: errorText(error) });
        }
      }
    };
    try {
      await Promise.all([worker(), worker(), worker()]);
    } finally {
      uploadLock.current = false;
      setUploading(false);
      setRefresh((v) => v + 1);
    }
  };
  const addUploads = (incoming: File[]) => {
    if (!canUpload || folderId == null || uploadLock.current || !incoming.length) return;
    if (incoming.length > 100) {
      toast.error('每次最多上传 100 个文件');
      return;
    }
    const entries: QueueEntry[] = incoming.map((file) => ({
      id: crypto.randomUUID(),
      file,
      folderId,
      folderName: current!.folderPath,
      state: 'wait',
    }));
    setQueue((previous) => [...previous.filter((e) => e.state !== 'ok'), ...entries]);
    void uploadEntries(entries);
  };

  const fileActions = (file: StorageFile) => (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label={`操作 ${file.originalName}`}>
          <MoreHorizontal className="size-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem
          disabled={file.status !== 'AVAILABLE'}
          onSelect={() => void openPreview(file)}
        >
          <Eye />
          预览与详情
        </DropdownMenuItem>
        <DropdownMenuItem
          disabled={file.status !== 'AVAILABLE'}
          onSelect={() => void getLink(file, 'download')}
        >
          <Download />
          下载文件
        </DropdownMenuItem>
        <DropdownMenuItem
          disabled={file.status !== 'AVAILABLE'}
          onSelect={() => void getLink(file, 'copy')}
        >
          <Copy />
          复制临时链接
        </DropdownMenuItem>
        {file.visibility === 'PUBLIC' && (
          <DropdownMenuItem
            disabled={file.status !== 'AVAILABLE'}
            onSelect={() => void getLink(file, 'permanent')}
          >
            <Copy />
            复制永久链接
          </DropdownMenuItem>
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem
          disabled={busy || file.status !== 'AVAILABLE' || !filePermission(file, 'UPDATE')}
          onSelect={() => openEditor({ type: 'file-name', file })}
        >
          <Pencil />
          重命名
        </DropdownMenuItem>
        <DropdownMenuItem
          disabled={busy || file.status !== 'AVAILABLE' || !filePermission(file, 'UPDATE')}
          onSelect={() => openEditor({ type: 'move', ids: [file.publicId!] })}
        >
          <FolderOpen />
          移动到
        </DropdownMenuItem>
        <DropdownMenuItem
          className="text-destructive"
          disabled={busy || !filePermission(file, 'DELETE') || file.status === 'DELETING'}
          onSelect={() => openEditor({ type: 'delete-files', ids: [file.publicId!] })}
        >
          <Trash2 />
          删除
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
  const folderActions = (folder: MyStorageFolder) =>
    permission(folder, 'MANAGE') && (
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon" aria-label={`管理 ${folder.folderName}`}>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem onSelect={() => openEditor({ type: 'folder-name', folder })}>
            <Pencil />
            重命名
          </DropdownMenuItem>
          <DropdownMenuItem
            className="text-destructive"
            onSelect={() => openEditor({ type: 'delete-folder', folder })}
          >
            <Trash2 />
            删除空文件夹
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    );

  return (
    <div className="border-border bg-card text-card-foreground overflow-hidden rounded-xl border">
      <div className="flex min-h-[640px] flex-col md:flex-row">
        <aside className="border-border bg-muted/20 shrink-0 border-b p-3 md:w-56 md:border-r md:border-b-0">
          <div className="mb-3 flex items-center gap-2 px-2 py-1 text-sm font-semibold">
            <HardDrive className="text-primary size-4" />
            文件空间
          </div>
          <Button
            variant={folderId == null ? 'secondary' : 'ghost'}
            className="w-full justify-start"
            onClick={() => navigate()}
          >
            <FolderOpen className="size-4" />
            全部文件
          </Button>
          <Input
            className="my-3 h-8"
            aria-label="查找文件夹"
            placeholder="查找文件夹…"
            value={folderSearch}
            onChange={(e) => setFolderSearch(e.target.value)}
          />
          <nav
            aria-label="文件夹"
            className="flex max-h-48 flex-col gap-1 overflow-y-auto md:max-h-[560px]"
          >
            {foldersLoading ? (
              <Loader2 className="m-3 size-4 animate-spin" />
            ) : (
              folders
                .filter((f) => f.folderPath.toLowerCase().includes(folderSearch.toLowerCase()))
                .map((f) => (
                  <button
                    key={f.id}
                    title={f.folderPath}
                    onClick={() => navigate(f.id)}
                    className={cn(
                      'hover:bg-accent focus-visible:ring-ring flex min-w-0 items-center gap-2 rounded-md px-2 py-2 text-left text-sm transition-colors focus-visible:ring-2 focus-visible:outline-none',
                      folderId === f.id
                        ? 'bg-accent text-accent-foreground font-medium'
                        : 'text-muted-foreground',
                    )}
                  >
                    <Folder className="size-4 shrink-0" />
                    <span className="truncate">{f.folderPath}</span>
                  </button>
                ))
            )}
            {!foldersLoading && folders.length === 0 && (
              <p className="text-muted-foreground px-2 py-3 text-xs">新建文件夹后即可上传。</p>
            )}
          </nav>
        </aside>

        <section
          className="min-w-0 flex-1 p-4 sm:p-5"
          onDragOver={(event) => {
            if (event.dataTransfer.types.includes('Files')) {
              event.preventDefault();
              if (canUpload && !uploading) setDragging(true);
            }
          }}
          onDragLeave={(event) => {
            if (!event.currentTarget.contains(event.relatedTarget as Node)) setDragging(false);
          }}
          onDrop={(event) => {
            event.preventDefault();
            setDragging(false);
            addUploads(Array.from(event.dataTransfer.files));
          }}
        >
          <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
            <nav
              aria-label="当前位置"
              className="flex min-w-0 flex-wrap items-center gap-1 text-sm"
            >
              <button
                className="text-muted-foreground hover:text-foreground rounded px-1 py-1"
                onClick={() => navigate()}
              >
                全部文件
              </button>
              {breadcrumbs.map((f) => (
                <span className="flex min-w-0 items-center gap-1" key={f.id}>
                  <ChevronRight className="text-muted-foreground size-3 shrink-0" />
                  <button
                    className="hover:text-primary max-w-40 truncate rounded px-1 py-1"
                    onClick={() => navigate(f.id)}
                    title={f.folderName}
                  >
                    {f.folderName}
                  </button>
                </span>
              ))}
              {current && folderActions(current)}
            </nav>
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={!canManage || busy}
                onClick={() => openEditor({ type: 'new-folder' })}
              >
                <FolderPlus className="size-4" />
                新建文件夹
              </Button>
              <Button
                size="sm"
                disabled={!canUpload || uploading}
                onClick={() => inputRef.current?.click()}
              >
                {uploading ? (
                  <Loader2 className="size-4 animate-spin" />
                ) : (
                  <Upload className="size-4" />
                )}
                上传文件
              </Button>
              <input
                ref={inputRef}
                type="file"
                multiple
                className="hidden"
                onChange={(event) => {
                  addUploads(Array.from(event.target.files || []));
                  event.target.value = '';
                }}
              />
            </div>
          </div>
          <div className="mb-4 flex flex-wrap items-center gap-2">
            <div className="relative min-w-44 flex-1">
              <Search className="text-muted-foreground pointer-events-none absolute top-2.5 left-3 size-4" />
              <Input
                aria-label="搜索文件名"
                placeholder="搜索文件名…"
                maxLength={255}
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                className="h-9 pl-9"
              />
            </div>
            <select
              aria-label="文件类型"
              className={selectClass}
              value={category}
              onChange={(e) => {
                setCategory(e.target.value);
                setPage(1);
              }}
            >
              <option value="">全部类型</option>
              {dict.storage_file_category?.map((d) => (
                <option key={d.value} value={d.value}>
                  {d.label}
                </option>
              ))}
            </select>
            <select
              aria-label="可见性"
              className={selectClass}
              value={visibility}
              onChange={(e) => {
                setVisibility(e.target.value);
                setPage(1);
              }}
            >
              <option value="">全部可见性</option>
              {dict.storage_visibility?.map((d) => (
                <option key={d.value} value={d.value}>
                  {d.label}
                </option>
              ))}
            </select>
            <select
              aria-label="排序"
              className={selectClass}
              value={sort}
              onChange={(e) => {
                setSort(e.target.value);
                setPage(1);
              }}
            >
              {!dict.storage_file_sort?.length && <option value="newest">newest</option>}
              {dict.storage_file_sort?.map((d) => (
                <option key={d.value} value={d.value}>
                  {d.label}
                </option>
              ))}
            </select>
            <div className="border-border flex rounded-md border">
              <Button
                variant={view === 'list' ? 'secondary' : 'ghost'}
                size="icon"
                className="size-9 rounded-r-none"
                aria-label="列表视图"
                aria-pressed={view === 'list'}
                onClick={() => setView('list')}
              >
                <List className="size-4" />
              </Button>
              <Button
                variant={view === 'grid' ? 'secondary' : 'ghost'}
                size="icon"
                className="size-9 rounded-l-none"
                aria-label="网格视图"
                aria-pressed={view === 'grid'}
                onClick={() => setView('grid')}
              >
                <Grid2X2 className="size-4" />
              </Button>
            </div>
            <Button
              variant="ghost"
              size="icon"
              className="size-9"
              aria-label="刷新文件"
              disabled={loading}
              onClick={reload}
            >
              <RefreshCw className={cn('size-4', loading && 'animate-spin')} />
            </Button>
          </div>
          {dragging && (
            <div className="border-primary bg-primary/5 text-primary mb-4 rounded-lg border-2 border-dashed p-8 text-center text-sm">
              释放文件，上传到 {current?.folderName}
            </div>
          )}
          <div className="text-muted-foreground mb-4 flex flex-wrap items-center justify-between gap-2 text-xs">
            <span>
              {total} 个文件{current ? ` · ${current.folderPath}` : ' · 所有可读取的文件夹'}
            </span>
            <span>
              {canUpload
                ? '可拖拽文件到此处上传'
                : current
                  ? '当前目录无上传权限或已停用'
                  : '进入文件夹后上传'}
            </span>
          </div>
          {selected.size > 0 && (
            <div className="border-border bg-muted/40 mb-4 flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2 text-sm">
              <span className="mr-auto">已选 {selected.size} 项</span>
              <Button
                variant="outline"
                size="sm"
                disabled={
                  busy ||
                  picked.length !== selected.size ||
                  !picked.every((f) => filePermission(f, 'UPDATE'))
                }
                onClick={() => openEditor({ type: 'move', ids: [...selected] })}
              >
                <FolderOpen className="size-4" />
                移动
              </Button>
              <select
                aria-label="批量修改可见性"
                className={selectClass}
                value=""
                disabled={
                  busy ||
                  picked.length !== selected.size ||
                  !picked.every((f) => filePermission(f, 'UPDATE'))
                }
                onChange={(e) => {
                  if (e.target.value) void changeVisibility(e.target.value);
                }}
              >
                <option value="">设置可见性</option>
                {dict.storage_visibility?.map((d) => (
                  <option key={d.value} value={d.value}>
                    {d.label}
                  </option>
                ))}
              </select>
              <Button
                variant="outline"
                size="sm"
                className="text-destructive"
                disabled={
                  busy ||
                  picked.length !== selected.size ||
                  !picked.every((f) => filePermission(f, 'DELETE'))
                }
                onClick={() => openEditor({ type: 'delete-files', ids: [...selected] })}
              >
                <Trash2 className="size-4" />
                删除
              </Button>
              <Button
                variant="ghost"
                size="icon"
                className="size-8"
                aria-label="清空选择"
                onClick={() => setSelected(new Set())}
              >
                <X className="size-4" />
              </Button>
            </div>
          )}
          {failures.length > 0 && (
            <div
              role="alert"
              className="border-destructive/30 bg-destructive/5 mb-4 rounded-lg border p-3 text-sm"
            >
              <div className="mb-2 flex justify-between font-medium">
                部分文件处理失败
                <Button
                  variant="ghost"
                  size="icon"
                  className="size-5"
                  aria-label="关闭失败明细"
                  onClick={() => setFailures([])}
                >
                  <X className="size-3" />
                </Button>
              </div>
              {failures.map((f) => (
                <p key={f.publicId} className="text-muted-foreground py-1 break-words">
                  {files.find((file) => file.publicId === f.publicId)?.originalName || f.publicId}：
                  {f.message}
                </p>
              ))}
            </div>
          )}
          {children.length > 0 && !keyword && !category && !visibility && (
            <div className="mb-5 grid grid-cols-1 gap-2 sm:grid-cols-2 xl:grid-cols-3">
              {children.map((folder) => (
                <div
                  key={folder.id}
                  className="border-border hover:bg-muted/40 flex min-w-0 items-center rounded-lg border px-3 py-2 transition-colors"
                >
                  <button
                    onClick={() => navigate(folder.id)}
                    className="flex min-w-0 flex-1 items-center gap-3 py-1 text-left"
                  >
                    <Folder className="text-primary/70 size-7 shrink-0" />
                    <span className="truncate text-sm font-medium">{folder.folderName}</span>
                  </button>
                  {folderActions(folder)}
                </div>
              ))}
            </div>
          )}
          {loading ? (
            <div className="text-muted-foreground flex min-h-60 items-center justify-center gap-2 text-sm">
              <Loader2 className="size-5 animate-spin" />
              加载文件…
            </div>
          ) : loadError ? (
            <div role="alert" className="py-16 text-center text-sm">
              <p className="text-destructive mb-3">{loadError}</p>
              <Button variant="outline" onClick={reload}>
                重新加载
              </Button>
            </div>
          ) : !files.length ? (
            <div className="text-muted-foreground flex min-h-60 flex-col items-center justify-center gap-3 text-center">
              <FolderOpen className="size-10 opacity-40" />
              <p className="text-sm">
                {!canRead
                  ? '当前文件夹未授予读取权限'
                  : keyword || category || visibility
                    ? '没有符合筛选条件的文件'
                    : '这里还没有文件'}
              </p>
              {keyword || category || visibility ? (
                <Button
                  variant="ghost"
                  onClick={() => {
                    setSearchInput('');
                    setKeyword('');
                    setCategory('');
                    setVisibility('');
                    setPage(1);
                  }}
                >
                  清除筛选
                </Button>
              ) : canUpload ? (
                <Button variant="outline" onClick={() => inputRef.current?.click()}>
                  上传第一个文件
                </Button>
              ) : (
                folders.length === 0 && (
                  <p className="max-w-xs text-xs">
                    先创建文件夹；若尚未配置存储，请在后台文件存储中设置默认存储。
                  </p>
                )
              )}
            </div>
          ) : (
            <>
              <label className="text-muted-foreground mb-3 flex w-fit items-center gap-2 text-xs">
                <input
                  type="checkbox"
                  aria-label="选择本页可操作文件"
                  disabled={!selectable.length}
                  checked={
                    selectable.length > 0 && selectable.every((f) => selected.has(f.publicId!))
                  }
                  onChange={(e) =>
                    setSelected(
                      e.target.checked ? new Set(selectable.map((f) => f.publicId!)) : new Set(),
                    )
                  }
                  className="accent-primary size-4"
                />
                选择本页
              </label>
              {view === 'list' ? (
                <div className="border-border overflow-x-auto rounded-lg border">
                  <table className="w-full text-left text-sm">
                    <thead className="border-border bg-muted/30 text-muted-foreground border-b text-xs">
                      <tr>
                        <th className="w-10 px-3 py-3">
                          <span className="sr-only">选择</span>
                        </th>
                        <th className="min-w-48 py-3 font-medium">文件名</th>
                        <th className="hidden px-3 font-medium lg:table-cell">位置</th>
                        <th className="px-3 font-medium whitespace-nowrap">大小</th>
                        <th className="px-3 font-medium">可见性</th>
                        <th className="hidden px-3 font-medium whitespace-nowrap xl:table-cell">
                          上传时间
                        </th>
                        <th className="w-12">
                          <span className="sr-only">操作</span>
                        </th>
                      </tr>
                    </thead>
                    <tbody className="divide-border divide-y">
                      {files.map((file) => (
                        <tr
                          key={file.publicId}
                          className={cn(
                            'hover:bg-muted/30',
                            selected.has(file.publicId!) && 'bg-primary/5',
                          )}
                        >
                          <td className="px-3">
                            <input
                              type="checkbox"
                              aria-label={`选择 ${file.originalName}`}
                              checked={selected.has(file.publicId!)}
                              disabled={!selectable.includes(file) || busy}
                              onChange={() => toggle(file.publicId!)}
                              className="accent-primary size-4"
                            />
                          </td>
                          <td className="max-w-80 py-3">
                            <button
                              className="flex w-full min-w-0 items-center gap-3 text-left disabled:cursor-default"
                              disabled={file.status !== 'AVAILABLE'}
                              onClick={() => void openPreview(file)}
                            >
                              <FileIcon
                                type={file.contentType}
                                className="text-muted-foreground size-7 shrink-0"
                              />
                              <span className="min-w-0">
                                <span
                                  className="block truncate font-medium"
                                  title={file.originalName}
                                >
                                  {file.originalName}
                                </span>
                                <span className="text-muted-foreground mt-0.5 block truncate text-xs">
                                  {file.status !== 'AVAILABLE'
                                    ? label('storage_file_status', file.status)
                                    : file.contentType}
                                </span>
                              </span>
                            </button>
                          </td>
                          <td
                            className="text-muted-foreground hidden max-w-40 truncate px-3 text-xs lg:table-cell"
                            title={folderMap.get(file.folderId!)?.folderPath}
                          >
                            {folderMap.get(file.folderId!)?.folderPath || '—'}
                          </td>
                          <td className="text-muted-foreground px-3 text-xs whitespace-nowrap">
                            {fileSize(file.sizeBytes)}
                          </td>
                          <td className="px-3 text-xs whitespace-nowrap">
                            {label('storage_visibility', file.visibility)}
                          </td>
                          <td className="text-muted-foreground hidden px-3 text-xs whitespace-nowrap xl:table-cell">
                            {fileDate(file.createTime)}
                          </td>
                          <td className="px-1">{fileActions(file)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
                  {files.map((file) => (
                    <div
                      key={file.publicId}
                      className={cn(
                        'border-border overflow-hidden rounded-lg border',
                        selected.has(file.publicId!) && 'border-primary/50 bg-primary/5',
                      )}
                    >
                      <div className="flex items-center justify-between px-3 pt-2">
                        <input
                          type="checkbox"
                          aria-label={`选择 ${file.originalName}`}
                          checked={selected.has(file.publicId!)}
                          disabled={!selectable.includes(file) || busy}
                          onChange={() => toggle(file.publicId!)}
                          className="accent-primary size-4"
                        />
                        {fileActions(file)}
                      </div>
                      <button
                        className="bg-muted/20 flex h-28 w-full items-center justify-center"
                        disabled={file.status !== 'AVAILABLE'}
                        aria-label={`预览 ${file.originalName}`}
                        onClick={() => void openPreview(file)}
                      >
                        <FileIcon
                          type={file.contentType}
                          className="text-muted-foreground/60 size-12"
                        />
                      </button>
                      <div className="p-3">
                        <p className="truncate text-sm font-medium" title={file.originalName}>
                          {file.originalName}
                        </p>
                        <div className="text-muted-foreground mt-2 flex justify-between gap-2 text-xs">
                          <span>{fileSize(file.sizeBytes)}</span>
                          <span>{label('storage_visibility', file.visibility)}</span>
                        </div>
                        <p className="text-muted-foreground mt-2 truncate text-xs">
                          {file.status !== 'AVAILABLE'
                            ? label('storage_file_status', file.status)
                            : folderMap.get(file.folderId!)?.folderPath}
                        </p>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
          {total > 0 && (
            <div className="text-muted-foreground mt-5 flex items-center justify-between text-xs">
              <span>
                共 {total} 项 · 每页 {PAGE_SIZE} 项
              </span>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="icon"
                  className="size-8"
                  aria-label="上一页"
                  disabled={page <= 1 || loading}
                  onClick={() => setPage((p) => p - 1)}
                >
                  <ChevronLeft className="size-4" />
                </Button>
                <span>
                  {page} / {Math.max(1, Math.ceil(total / PAGE_SIZE))}
                </span>
                <Button
                  variant="outline"
                  size="icon"
                  className="size-8"
                  aria-label="下一页"
                  disabled={page * PAGE_SIZE >= total || loading}
                  onClick={() => setPage((p) => p + 1)}
                >
                  <ChevronRight className="size-4" />
                </Button>
              </div>
            </div>
          )}
        </section>
      </div>

      {queue.length > 0 && (
        <div className="border-border bg-muted/20 border-t p-4">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2 text-sm">
            <span className="font-medium">
              上传队列 · {queue.filter((q) => q.state === 'ok').length}/{queue.length} 已完成
            </span>
            <div className="flex gap-2">
              {queue.some((q) => q.state === 'fail') && (
                <Button
                  variant="outline"
                  size="sm"
                  disabled={uploading}
                  onClick={() => void uploadEntries(queue.filter((q) => q.state === 'fail'))}
                >
                  重试失败项
                </Button>
              )}
              <Button variant="ghost" size="sm" disabled={uploading} onClick={() => setQueue([])}>
                清空记录
              </Button>
            </div>
          </div>
          <div className="max-h-52 space-y-2 overflow-y-auto" aria-live="polite">
            {queue.map((entry) => (
              <div className="flex items-start gap-2 text-xs" key={entry.id}>
                {entry.state === 'run' ? (
                  <Loader2 className="text-primary size-4 shrink-0 animate-spin" />
                ) : entry.state === 'ok' ? (
                  <Check className="text-primary size-4 shrink-0" />
                ) : entry.state === 'fail' ? (
                  <X className="text-destructive size-4 shrink-0" />
                ) : (
                  <File className="text-muted-foreground size-4 shrink-0" />
                )}
                <div className="min-w-0 flex-1">
                  <span className="block truncate">
                    {entry.file.name}{' '}
                    <span className="text-muted-foreground">
                      · {fileSize(entry.file.size)} · {entry.folderName}
                    </span>
                  </span>
                  {entry.error && (
                    <p className="text-destructive mt-1 break-words">{entry.error}</p>
                  )}
                </div>
                <span className="text-muted-foreground shrink-0">
                  {entry.state === 'wait'
                    ? '等待中'
                    : entry.state === 'run'
                      ? '上传中'
                      : entry.state === 'ok'
                        ? '已完成'
                        : '失败'}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      <Dialog
        open={!!editor}
        onOpenChange={(open) => {
          if (!open && !busy) setEditor(null);
        }}
      >
        <DialogContent
          onInteractOutside={(event) => {
            if (busy) event.preventDefault();
          }}
        >
          <DialogHeader>
            <DialogTitle>
              {editor?.type === 'new-folder'
                ? '新建文件夹'
                : editor?.type === 'folder-name' || editor?.type === 'file-name'
                  ? '重命名'
                  : editor?.type === 'move'
                    ? '移动文件'
                    : '确认删除'}
            </DialogTitle>
            <DialogDescription>
              {editor?.type === 'delete-files'
                ? `将删除 ${editor.ids?.length} 个文件及远端对象，无法撤销。Telegram 文件会进入异步删除流程。`
                : editor?.type === 'delete-folder'
                  ? `删除「${editor.folder?.folderName}」。文件夹内必须没有文件和子文件夹。`
                  : editor?.type === 'move'
                    ? '选择相同存储配置且有上传权限的目标文件夹。移动后现有文件链接保持有效。'
                    : editor?.type === 'new-folder'
                      ? `创建位置：${current?.folderPath || '根目录'}。根目录文件夹使用默认存储。`
                      : '更新显示名称，已有文件链接保持有效。'}
            </DialogDescription>
          </DialogHeader>
          {(editor?.type === 'new-folder' ||
            editor?.type === 'folder-name' ||
            editor?.type === 'file-name') && (
            <form
              id="storage-name-form"
              onSubmit={(event) => {
                event.preventDefault();
                void saveEditor();
              }}
            >
              <label className="mb-2 block text-sm" htmlFor="storage-name">
                名称
              </label>
              <Input
                id="storage-name"
                autoFocus
                value={name}
                maxLength={editor.type === 'file-name' ? 255 : 128}
                onChange={(e) => setName(e.target.value)}
                disabled={busy}
                required
              />
            </form>
          )}
          {editor?.type === 'move' && (
            <div>
              <label className="mb-2 block text-sm" htmlFor="storage-target">
                目标文件夹
              </label>
              <select
                id="storage-target"
                className={cn(selectClass, 'w-full')}
                value={targetId}
                onChange={(e) => setTargetId(e.target.value)}
                disabled={busy}
              >
                <option value="">请选择文件夹</option>
                {targetFolders.map((folder) => (
                  <option key={folder.id} value={folder.id}>
                    {folder.folderPath}
                  </option>
                ))}
              </select>
              {!targetFolders.length && (
                <p className="text-muted-foreground mt-2 text-xs">
                  没有可用目标；不同存储配置的文件请分别选择后移动。
                </p>
              )}
            </div>
          )}
          {operationError && (
            <p role="alert" className="text-destructive text-sm">
              {operationError}
            </p>
          )}
          <DialogFooter>
            <Button variant="outline" disabled={busy} onClick={() => setEditor(null)}>
              取消
            </Button>
            <Button
              variant={editor?.type.startsWith('delete') ? 'destructive' : 'default'}
              disabled={
                busy ||
                (editor?.type === 'move'
                  ? !targetId
                  : editor?.type.startsWith('delete')
                    ? false
                    : !name.trim())
              }
              onClick={() => void saveEditor()}
            >
              {busy && <Loader2 className="size-4 animate-spin" />}
              {editor?.type.startsWith('delete') ? '确认删除' : '保存'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog
        open={!!preview}
        onOpenChange={(open) => {
          if (!open) {
            previewVersion.current++;
            setPreview(null);
          }
        }}
      >
        <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle className="pr-5 break-all">
              {preview?.file.originalName || '文件详情'}
            </DialogTitle>
            <DialogDescription>
              {fileSize(preview?.file.sizeBytes)} · {preview?.file.contentType} ·{' '}
              {label('storage_visibility', preview?.file.visibility)}
            </DialogDescription>
          </DialogHeader>
          {preview?.error ? (
            <p role="alert" className="text-destructive py-12 text-center text-sm">
              {preview.error}
            </p>
          ) : !preview?.url ? (
            <div className="flex h-40 items-center justify-center">
              <Loader2 className="size-6 animate-spin" />
            </div>
          ) : preview.file.contentType?.startsWith('image/') ? (
            <img
              src={preview.url}
              alt={preview.file.originalName || ''}
              referrerPolicy="no-referrer"
              className="max-h-[55dvh] w-full rounded-md object-contain"
            />
          ) : preview.file.contentType?.startsWith('video/') ? (
            <video
              controls
              preload="metadata"
              src={preview.url}
              className="max-h-[55dvh] w-full rounded-md"
            />
          ) : preview.file.contentType?.startsWith('audio/') ? (
            <audio controls preload="metadata" src={preview.url} className="my-8 w-full" />
          ) : preview.file.contentType === 'application/pdf' ? (
            <iframe
              title={preview.file.originalName}
              src={preview.url}
              sandbox=""
              referrerPolicy="no-referrer"
              className="border-border h-[50dvh] w-full rounded-md border"
            />
          ) : (
            <div className="text-muted-foreground flex flex-col items-center gap-3 py-12">
              <FileIcon type={preview.file.contentType} className="size-14" />
              <p className="text-sm">此类型暂不支持在线预览，可下载查看。</p>
            </div>
          )}
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-xs">
            <dt className="text-muted-foreground">所在文件夹</dt>
            <dd className="break-all">
              {folderMap.get(preview?.file.folderId || 0)?.folderPath || '—'}
            </dd>
            <dt className="text-muted-foreground">上传时间</dt>
            <dd>{fileDate(preview?.file.createTime)}</dd>
            <dt className="text-muted-foreground">文件标识</dt>
            <dd className="font-mono break-all">{preview?.file.publicId}</dd>
            <dt className="text-muted-foreground">状态</dt>
            <dd>{label('storage_file_status', preview?.file.status)}</dd>
          </dl>
          <DialogFooter>
            <Button
              variant="outline"
              disabled={!preview?.url}
              onClick={() => preview && void getLink(preview.file, 'copy')}
            >
              <Copy className="size-4" />
              复制临时链接
            </Button>
            <Button
              disabled={!preview?.url}
              onClick={() => preview && void getLink(preview.file, 'download')}
            >
              <Download className="size-4" />
              下载文件
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
