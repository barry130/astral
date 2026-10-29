import { storageApi, type TicketView } from '@/api/storage';

/** 文件正文只直传 Provider，不带 Astral 的认证头或客户端信息头。 */
export async function uploadStorageFile(
  folderId: number,
  file: File,
  admin = false,
): Promise<string> {
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

/** 可复用已签发的凭证，供后台逐步验证上传链路。 */
export async function uploadWithStorageTicket(ticket: TicketView, file: File): Promise<string> {
  if (ticket.method === 'PUT') {
    const response = await fetch(ticket.uploadUrl, {
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
    const response = await fetch(ticket.uploadUrl, {
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
  return registered.data.publicId;
}
