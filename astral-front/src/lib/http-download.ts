/**
 * 把一次「文件导出」的 fetch 响应落成浏览器下载。
 *
 * 新契约（AGENTS.md「统一响应体」）：业务失败是 **HTTP 200 + `Result` JSON**，
 * 只有传输层错误才是非 2xx。因此不能再用 `response.ok` 判断导出是否成功，
 * 必须先看 `Content-Type`：是 JSON 就说明后端回了错误体，把 `msg` 抛给调用方。
 */
export async function downloadResponse(response: Response, filename: string): Promise<void> {
  const contentType = response.headers.get('Content-Type') || '';
  if (contentType.includes('application/json')) {
    const data = (await response.json().catch(() => null)) as { msg?: string } | null;
    throw new Error(data?.msg || '导出失败');
  }
  if (!response.ok) {
    throw new Error(`导出失败（HTTP ${response.status}）`);
  }
  const blob = await response.blob();
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  URL.revokeObjectURL(a.href);
}
