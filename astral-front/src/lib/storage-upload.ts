import { storageApi, type TicketView, type UploadOperationStatus } from '@/api/storage';

/** 文件正文只直传 Provider，不带 Astral 的认证头或客户端信息头。 */
export async function uploadStorageFile(
  folderId: number,
  file: File,
  admin = false,
  providerType?: string,
): Promise<string> {
  // CUSTOM_HTTP 渠道没有浏览器直传入口，一律走服务端两段式上传（设计 v1.1 §7）
  if (providerType === 'CUSTOM_HTTP') return uploadViaUploadOperations(folderId, file);
  const result = await storageApi.managerTicket(
    {
      folderId,
      fileName: file.name,
      contentType: file.type || 'application/octet-stream',
      sizeBytes: file.size,
    },
    admin,
  );
  if (result.code !== 200) throw new Error(result.msg);
  return uploadWithStorageTicket(result.data, file);
}

/** 计算文件字节的 SHA-256 十六进制摘要，作为两段式上传的意图指纹。 */
async function sha256Hex(buffer: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

const TWO_STEP_POLL_MS = 500;
const TWO_STEP_MAX_POLLS = 40;

/**
 * 把字节内容派生成形如 UUID 的幂等键（§7.1：客户端把该键与文件绑定，重试复用）。
 * 同一「目录 + 文件内容 + 文件名」的重试命中同一键，服务端幂等去重；
 * 相同内容再次发起是全新意图（用户显式重新上传），所以混入发起时间戳以外的
 * 全部不稳定因素都不做——lastModified/size/name 已足够区分同一会话内的重复选择。
 */
async function idempotencyKeyFor(folderId: number, file: File, digest: string): Promise<string> {
  const seed = await crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(
      `${folderId}|${file.name}|${file.size}|${file.lastModified}|${digest}`,
    ),
  );
  const hex = Array.from(new Uint8Array(seed))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
}

/**
 * 两段式上传：POST 创建意图 → PUT 原始字节 → 确认终态。
 * 200 只代表请求被受理，只有 state=COMMITTED 才算上传完成；
 * 失败/结果未知都直接抛错，不允许静默换键重试（避免重复占用配额）。
 */
export async function uploadViaUploadOperations(folderId: number, file: File): Promise<string> {
  const contentType = file.type || 'application/octet-stream';
  // 摘要必须基于最终上传的字节；PUT 直接用同一个 File，避免二次拷贝
  const digest = await sha256Hex(await file.arrayBuffer());
  const created = await storageApi.createUploadOperation(
    folderId,
    file.name,
    contentType,
    file.size,
    digest,
    await idempotencyKeyFor(folderId, file, digest),
  );
  if (created.code !== 200) throw new Error(created.msg);
  const operation = created.data;
  if (file.size > operation.maxBytes) {
    throw new Error(
      `文件大小超过该目标允许的 ${Math.floor(operation.maxBytes / 1048576)} MiB 上限`,
    );
  }
  if (operation.canSendContent) {
    let sent: Awaited<ReturnType<typeof storageApi.putUploadContent>>;
    try {
      sent = await storageApi.putUploadContent(operation.uploadId, file, contentType);
    } catch (error) {
      const message = error instanceof Error ? error.message : '上传请求失败';
      // 正文请求断网/超时时服务端可能已收到字节，不能按普通失败让用户直接重传
      const mayHaveReachedServer =
        message.includes('无法连接到服务器') ||
        message.includes('服务暂时不可用') ||
        message.includes('Network Error') ||
        message.includes('ERR_NETWORK') ||
        message.toLowerCase().includes('timeout') ||
        message.includes('超时');
      if (mayHaveReachedServer) {
        throw new Error(`上传结果未知：${message}。请稍后在文件列表确认，不要重复上传同一份文件`);
      }
      throw error;
    }
    if (sent.code !== 200) throw new Error(sent.msg);
    // contentAccepted 只表示正文已收到，不代表已落库：切换候选目标后可能仍是 CREATED/REMOTE_SUCCEEDED，
    // 必须继续查询到终态，不能把「已受理」当成功或失败。
    if (sent.data.contentAccepted && isTerminal(sent.data.operation)) {
      return settle(sent.data.operation);
    }
  }
  // 重复提交命中已有操作、或 PUT 返回仍非终态：转 GET 查询原操作
  for (let attempt = 0; attempt < TWO_STEP_MAX_POLLS; attempt += 1) {
    const status = await storageApi.getUploadOperation(operation.uploadId, true);
    if (status.code !== 200) throw new Error(status.msg);
    if (isTerminal(status.data)) return settle(status.data);
    await new Promise((resolve) => setTimeout(resolve, TWO_STEP_POLL_MS));
  }
  throw new Error('上传状态查询超时，请稍后在文件列表确认；不要重复上传同一份文件');
}

function isTerminal(status: UploadOperationStatus): boolean {
  return (
    status.state === 'COMMITTED' ||
    status.state === 'FAILED' ||
    status.state === 'EXPIRED' ||
    status.state === 'UNKNOWN_OUTCOME'
  );
}

function settle(status: UploadOperationStatus): string {
  if (status.state === 'COMMITTED') return status.publicId;
  if (status.state === 'UNKNOWN_OUTCOME') {
    throw new Error('结果未知，请稍后在文件列表确认；不要重复上传同一份文件');
  }
  throw new Error(`上传未完成${status.safeFailureCode ? `（${status.safeFailureCode}）` : ''}`);
}

/** 直传请求的错误可能来自浏览器网络层，原始 "Failed to fetch" 对用户没有可操作信息。 */
async function fetchUpload(url: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init);
  } catch (error) {
    const message = error instanceof Error ? error.message : '';
    const networkish =
      message.includes('Failed to fetch') ||
      message.includes('fetch failed') ||
      message.includes('NetworkError') ||
      message.includes('network') ||
      message.toLowerCase().includes('timeout') ||
      message.toLowerCase().includes('abort') ||
      message.includes('超时') ||
      message.includes('中止');
    if (networkish) {
      throw new Error(
        '上传连接中断，无法确认服务端是否收到文件。请检查网络后重试；如稍后文件列表出现同名文件，请勿重复上传',
      );
    }
    throw error;
  }
}

/** 可复用已签发的凭证，供后台逐步验证上传链路。 */
export async function uploadWithStorageTicket(ticket: TicketView, file: File): Promise<string> {
  if (ticket.method === 'PUT') {
    const response = await fetchUpload(ticket.uploadUrl, {
      method: 'PUT',
      body: file,
      signal: AbortSignal.timeout(600_000),
    });
    if (!response.ok) throw new Error(`上传返回 ${response.status}`);
  } else {
    const form = new FormData();
    if (ticket.form) {
      form.append('policy', ticket.form.policy);
      form.append('authorization', ticket.form.authorization);
    }
    form.append(ticket.formField || 'file', file);
    const response = await fetchUpload(ticket.uploadUrl, {
      method: ticket.method,
      body: form,
      signal: AbortSignal.timeout(600_000),
    });
    if (!ticket.form) {
      const body = await response.json().catch(() => ({}));
      if (!response.ok || !body.ok || !body.publicId)
        throw new Error(body.message || `上传返回 ${response.status}`);
      return body.publicId;
    }
    if (!response.ok) throw new Error(`上传返回 ${response.status}`);
  }
  const registered = await storageApi.registerUpload(ticket.uploadId);
  if (registered.code !== 200) throw new Error(registered.msg);
  const publicId = registered.data.publicId;
  if (!publicId) throw new Error('上传登记未返回文件标识，请在文件列表确认结果');
  return publicId;
}
