'use client';

import { ChangeEvent, useEffect, useRef, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  FileImage,
  Loader2,
  RefreshCw,
  UploadCloud,
  XCircle,
} from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Progress } from '@/components/ui/progress';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { storageApi, StorageFolder, UploadOperationStatus } from '@/api/storage';
import { DictOption, dictLabel, fetchDictOptions } from '@/api/dict';

/** 正文 PUT 期间的轮询间隔与上限（服务端异步读取 + 上游发送，正常 10s 内终结） */
const POLL_INTERVAL_MS = 1500;
const MAX_POLLS = 120;
const ACCEPTED_MIME = 'image/png,image/jpeg';

type Stage =
  'idle' | 'hashing' | 'creating' | 'uploading' | 'polling' | 'done' | 'failed' | 'unknown';

function errorText(error: unknown) {
  return error instanceof Error ? error.message : '操作失败，请稍后重试';
}

function errorCodeOf(error: unknown) {
  if (error instanceof Error && 'errorCode' in error) {
    const code = (error as Error & { errorCode?: unknown }).errorCode;
    return typeof code === 'string' ? code : undefined;
  }
  return undefined;
}

function fileSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

function delay(ms: number) {
  return new Promise<void>((resolve) => {
    setTimeout(resolve, ms);
  });
}

/** 二进制摘要：上传正文前必须对“最终上传的字节”算 SHA-256 */
async function sha256Hex(buffer: ArrayBuffer) {
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

function newIdempotencyKey() {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes)
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

/**
 * 两步上传（服务端中转）：选文件 → 计算 SHA-256 → 创建意图 → PUT 正文 → 查询到终态。
 *
 * <p>关键语义（设计 v1.1 §7.3/§7.4/§13.3）：POST/PUT 返回 200 不代表上传成功；
 * 只有 state=COMMITTED 才算完成。进度条只反映第一段正文上传，不代表服务端已落库。</p>
 */
export default function ServerSideUpload({ canUpload = true }: { canUpload?: boolean }) {
  const [folders, setFolders] = useState<StorageFolder[]>([]);
  const [folderId, setFolderId] = useState<string>('');
  const [dict, setDict] = useState<Record<string, DictOption[]>>({});
  const [stage, setStage] = useState<Stage>('idle');
  const [file, setFile] = useState<File | null>(null);
  const [sha256, setSha256] = useState('');
  const [progress, setProgress] = useState(0);
  const [uploadId, setUploadId] = useState('');
  const [opStatus, setOpStatus] = useState<UploadOperationStatus | null>(null);
  const [error, setError] = useState('');
  const inputRef = useRef<HTMLInputElement | null>(null);
  /** 每次流程自增；卸载/重新开始时让旧流程的异步回调全部失效 */
  const runIdRef = useRef(0);
  /** 幂等键按文件指纹复用：网络超时重试同一文件绝不能换键 */
  const keyRef = useRef<{ signature: string; key: string } | null>(null);

  useEffect(() => {
    let alive = true;
    storageApi
      .listFolders()
      .then((result) => {
        if (!alive) return;
        if (result.code !== 200) throw new Error(result.msg);
        const list = (result.data || []).filter((item) => item.status === 'ENABLED');
        setFolders(list);
        if (list.length === 1 && list[0].id != null) setFolderId(String(list[0].id));
      })
      .catch((error) => {
        if (alive) toast.error(`上传目录加载失败：${errorText(error)}`);
      });
    fetchDictOptions(['storage_upload_state'])
      .then((map) => {
        if (alive) setDict(map);
      })
      .catch((error) => {
        if (alive) toast.warning(`上传状态字典加载失败，状态将显示原始值：${errorText(error)}`);
      });
    return () => {
      alive = false;
      runIdRef.current += 1;
    };
  }, []);

  const busy =
    stage === 'hashing' || stage === 'creating' || stage === 'uploading' || stage === 'polling';
  const currentFolder = folders.find((item) => String(item.id) === folderId);

  /** 收敛终态：返回 true 表示已到终态、停止轮询 */
  const settle = (status: UploadOperationStatus) => {
    setOpStatus(status);
    if (status.state === 'COMMITTED') {
      setStage('done');
      setProgress(100);
      toast.success('上传完成');
      return true;
    }
    if (status.state === 'FAILED' || status.state === 'EXPIRED') {
      setStage('failed');
      setError(
        status.state === 'EXPIRED'
          ? '上传意图已过期，服务端未接收正文。请重新选择文件开始新的上传。'
          : `上传失败，服务端未完成登记${status.safeFailureCode ? `（${status.safeFailureCode}）` : ''}。请按失败原因处理后重新开始。`,
      );
      return true;
    }
    if (status.state === 'UNKNOWN_OUTCOME') {
      setStage('unknown');
      setError('结果未知，请查询原操作。请求可能已被服务端受理，不要用新上传替代。');
      return true;
    }
    return false;
  };

  const pollOperation = async (id: string, runId: number) => {
    for (let attempt = 0; attempt < MAX_POLLS; attempt += 1) {
      const result = await storageApi.getUploadOperation(id, true);
      if (runId !== runIdRef.current) return;
      if (result.code !== 200) throw new Error(result.msg);
      if (settle(result.data)) return;
      await delay(POLL_INTERVAL_MS);
      if (runId !== runIdRef.current) return;
    }
    setStage('unknown');
    setError('状态查询超时，请稍后点「查询状态」继续确认；不要重复上传同一份文件。');
  };

  const handleFailure = (
    caught: unknown,
    knownUploadId: string,
    runId: number,
    phase: 'create' | 'send' | 'query',
  ) => {
    if (runId !== runIdRef.current) return;
    const code = errorCodeOf(caught);
    const message = errorText(caught);
    // 创建/发送请求超时或断网时，服务端可能已经受理；只有查询阶段且已知操作 ID 才可安全判为未知。
    const networkUnknown =
      message.includes('无法连接到服务器') ||
      message.includes('服务暂时不可用') ||
      message.includes('timeout') ||
      message.includes('超时');
    const mayHaveReachedServer = networkUnknown && (phase !== 'query' || !!knownUploadId);
    if (code === 'STORAGE042' || message.includes('并发')) {
      setStage('failed');
      setError('上传并发已满（服务端返回 429），请稍后重试。为避免重复占用配额，请不要自动重试。');
      return;
    }
    if (code === 'STORAGE032') {
      setStage('failed');
      setError('今日上传次数已达上限，请明天再试，或联系管理员调整目录每日配额。');
      return;
    }
    if (code === 'STORAGE043') {
      setStage('failed');
      setError('同一幂等键的操作元数据不一致，请重新选择文件后再上传。');
      return;
    }
    if (code === 'STORAGE044') {
      setStage('failed');
      setError('上传意图已过期或不再可发送，请重新选择文件开始新的上传。');
      return;
    }
    if (code === 'STORAGE045') {
      setStage('failed');
      setError('上传正文与声明不一致（大小或 SHA-256 不匹配），请重新选择文件。');
      return;
    }
    if (code === 'STORAGE046' || code === 'STORAGE049' || mayHaveReachedServer) {
      setStage('unknown');
      setError(
        knownUploadId
          ? '结果未知，请查询原操作。请求可能已被服务端受理，不要用新上传替代。'
          : '结果未知，服务端可能已受理。请重新选择同一文件（系统会复用同一幂等键），不要换文件或用新键重传。',
      );
      return;
    }
    setStage('failed');
    setError(message);
  };

  const startUpload = async (picked: File) => {
    if (!canUpload || busy) return;
    const targetFolderId = Number(folderId);
    if (!Number.isFinite(targetFolderId) || targetFolderId <= 0) {
      toast.error('请先选择上传目录');
      return;
    }
    const contentType = picked.type;
    if (!contentType) {
      toast.error('无法识别该文件的类型，请换一张图片');
      return;
    }
    const runId = runIdRef.current + 1;
    runIdRef.current = runId;
    setFile(picked);
    setProgress(0);
    setUploadId('');
    setOpStatus(null);
    setError('');
    setSha256('');
    setStage('hashing');
    let operationId = '';
    let phase: 'create' | 'send' = 'create';
    try {
      // 摘要必须基于最终上传的字节；body 直接用同一个 File，避免二次拷贝
      const buffer = await picked.arrayBuffer();
      const digest = await sha256Hex(buffer);
      if (runId !== runIdRef.current) return;
      setSha256(digest);

      const signature = `${picked.name}|${picked.size}|${picked.lastModified}`;
      if (!keyRef.current || keyRef.current.signature !== signature) {
        keyRef.current = { signature, key: newIdempotencyKey() };
      }
      const idempotencyKey = keyRef.current.key;

      setStage('creating');
      const created = await storageApi.createUploadOperation(
        targetFolderId,
        picked.name,
        contentType,
        picked.size,
        digest,
        idempotencyKey,
      );
      if (runId !== runIdRef.current) return;
      if (created.code !== 200) throw new Error(created.msg);
      const operation = created.data;
      operationId = operation.uploadId;
      setUploadId(operation.uploadId);
      if (picked.size > operation.maxBytes) {
        setStage('failed');
        setError(`文件大小超过该目录允许的 ${fileSize(operation.maxBytes)}，请压缩后重试。`);
        return;
      }
      if (!operation.canSendContent) {
        // 重复 POST 命中已存在的操作：不再发送正文，直接查询原操作
        setStage('polling');
        await pollOperation(operation.uploadId, runId);
        return;
      }

      setStage('uploading');
      phase = 'send';
      const sent = await storageApi.putUploadContent(
        operation.uploadId,
        picked,
        contentType,
        (percent) => {
          if (runId === runIdRef.current) setProgress(percent);
        },
      );
      if (runId !== runIdRef.current) return;
      setProgress(100);
      if (!sent.data.contentAccepted) {
        // 重复 PUT 返回原状态且未读取正文：按契约转为 GET 查询
        toast.info('该操作已接收过正文，已改为查询原操作状态');
      }
      if (settle(sent.data.operation)) return;
      setStage('polling');
      await pollOperation(operation.uploadId, runId);
    } catch (caught) {
      handleFailure(caught, operationId, runId, phase);
    }
  };

  const queryStatus = async () => {
    if (!uploadId || busy) return;
    const runId = runIdRef.current + 1;
    runIdRef.current = runId;
    setError('');
    setStage('polling');
    try {
      await pollOperation(uploadId, runId);
    } catch (caught) {
      handleFailure(caught, uploadId, runId, 'query');
    }
  };

  const openFile = async () => {
    const publicId = opStatus?.publicId;
    if (!publicId) return;
    try {
      const result = await storageApi.managerUrl(publicId, false, true);
      if (result.code !== 200) throw new Error(result.msg);
      const url = result.data?.url;
      if (!url) {
        toast.error('服务端未返回下载地址');
        return;
      }
      window.open(url, '_blank', 'noopener');
    } catch (caught) {
      toast.error(errorText(caught));
    }
  };

  const reset = () => {
    runIdRef.current += 1;
    keyRef.current = null;
    setFile(null);
    setSha256('');
    setProgress(0);
    setUploadId('');
    setOpStatus(null);
    setError('');
    setStage('idle');
    if (inputRef.current) inputRef.current.value = '';
  };

  const onPick = (event: ChangeEvent<HTMLInputElement>) => {
    const picked = event.target.files?.[0];
    // 清空 value，允许结果未知时重新选择“同一个文件”以复用原幂等键
    event.target.value = '';
    if (picked) void startUpload(picked);
  };

  const stateLabel = opStatus
    ? dictLabel(dict.storage_upload_state, opStatus.state)
    : dictLabel(dict.storage_upload_state, 'CREATED');

  return (
    <div className="border-border bg-card text-card-foreground rounded-xl border p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="flex items-center gap-2 text-sm font-medium">
            <UploadCloud className="size-4" />
            两步上传（服务端中转）
          </h3>
          <p className="text-muted-foreground mt-1 text-xs leading-relaxed">
            先创建上传意图，再把原始字节 PUT 给服务端；进度条只代表正文传输，
            <span className="text-foreground font-medium">只有状态变为「已完成」才算上传成功</span>
            。适合无法直传的对象存储与内网渠道。
          </p>
        </div>
        <div className="flex w-full items-center gap-2 sm:w-auto">
          <Select value={folderId} disabled={busy} onValueChange={setFolderId}>
            <SelectTrigger aria-label="上传目录" className="h-9 w-full sm:w-56">
              <SelectValue placeholder="选择上传目录" />
            </SelectTrigger>
            <SelectContent>
              {folders.map((folder) => (
                <SelectItem key={folder.id} value={String(folder.id)}>
                  {folder.folderPath || folder.folderName}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button
            size="sm"
            disabled={!canUpload || busy || !folderId}
            onClick={() => inputRef.current?.click()}
          >
            {busy ? (
              <Loader2 className="size-4 animate-spin" />
            ) : (
              <UploadCloud className="size-4" />
            )}
            {busy ? '上传中…' : '选择图片'}
          </Button>
          <input
            ref={inputRef}
            type="file"
            accept={ACCEPTED_MIME}
            className="hidden"
            onChange={onPick}
          />
        </div>
      </div>

      {folders.length === 0 && (
        <p className="text-muted-foreground mt-3 text-xs">
          还没有启用的目录。请先到「文件夹」页签创建目录并绑定存储渠道/存储组。
        </p>
      )}
      {currentFolder && (
        <p className="text-muted-foreground mt-2 text-xs">
          上传目标：{currentFolder.folderPath || currentFolder.folderName}
        </p>
      )}

      {file && (
        <div className="border-border mt-3 rounded-lg border p-3">
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <FileImage className="text-muted-foreground size-4 shrink-0" />
            <span className="min-w-0 flex-1 truncate">{file.name}</span>
            <span className="text-muted-foreground text-xs">{fileSize(file.size)}</span>
          </div>
          <div className="text-muted-foreground mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
            <span>阶段：{stageText(stage)}</span>
            {uploadId && <span className="break-all">操作 ID：{uploadId}</span>}
            {sha256 && <span className="break-all">SHA-256：{sha256}</span>}
          </div>

          {stage === 'uploading' && (
            <div className="mt-3 space-y-1">
              <Progress value={progress} aria-label="正文上传进度" />
              <p className="text-muted-foreground text-xs">
                正文传输 {progress}%（尚未完成，请勿关闭页面）
              </p>
            </div>
          )}
          {stage === 'polling' && (
            <p
              className="text-muted-foreground mt-3 flex items-center gap-2 text-xs"
              aria-live="polite"
            >
              <Loader2 className="size-3.5 animate-spin" />
              服务端处理中（当前状态：{stateLabel}）…
            </p>
          )}

          <div className="mt-3" aria-live="polite">
            {stage === 'done' && (
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <CheckCircle2 className="size-4 text-emerald-600" />
                <span>上传完成（state={opStatus?.state || 'COMMITTED'}）</span>
                {opStatus?.publicId && (
                  <>
                    <span className="text-muted-foreground text-xs break-all">
                      文件 publicId：{opStatus.publicId}
                    </span>
                    <Button
                      variant="link"
                      size="sm"
                      className="h-auto p-0"
                      onClick={() => void openFile()}
                    >
                      获取下载地址
                    </Button>
                  </>
                )}
              </div>
            )}
            {stage === 'failed' && (
              <p role="alert" className="text-destructive flex items-start gap-2 text-sm">
                <XCircle className="mt-0.5 size-4 shrink-0" />
                <span>{error}</span>
              </p>
            )}
            {stage === 'unknown' && (
              <div role="alert" className="space-y-1 text-sm">
                <p className="flex items-start gap-2 font-medium">
                  <AlertTriangle className="mt-0.5 size-4 shrink-0 text-amber-600" />
                  结果未知，请查询原操作
                </p>
                <p className="text-muted-foreground text-xs">
                  {error ||
                    '服务端可能已受理该次上传，请用上方操作 ID 查询后再决定是否重传；不要用新上传替代。'}
                </p>
              </div>
            )}
          </div>

          <div className="mt-3 flex flex-wrap items-center gap-2">
            {(stage === 'unknown' || stage === 'failed') && uploadId && (
              <Button
                variant="outline"
                size="sm"
                disabled={busy}
                onClick={() => void queryStatus()}
              >
                <RefreshCw className="size-4" />
                查询状态
              </Button>
            )}
            {(stage === 'done' || stage === 'failed') && (
              <Button variant="ghost" size="sm" onClick={reset}>
                重新开始
              </Button>
            )}
            {!busy && stage === 'idle' && file && (
              <Button variant="ghost" size="sm" onClick={reset}>
                清除
              </Button>
            )}
          </div>
        </div>
      )}

      <p className="text-muted-foreground mt-3 text-xs leading-relaxed">
        说明：重复提交同一文件会复用同一个幂等键；上传并发已满（429）或每日配额用尽时有单独提示，不会自动重试，
        也不会自动更换幂等键。
      </p>
    </div>
  );
}

function stageText(stage: Stage) {
  switch (stage) {
    case 'hashing':
      return '计算 SHA-256';
    case 'creating':
      return '创建上传意图';
    case 'uploading':
      return '上传正文';
    case 'polling':
      return '等待服务端处理';
    case 'done':
      return '已完成';
    case 'failed':
      return '已失败';
    case 'unknown':
      return '结果未知';
    default:
      return '待上传';
  }
}
