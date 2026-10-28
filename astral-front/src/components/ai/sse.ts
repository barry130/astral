/**
 * 浏览器端 SSE 消费。
 *
 * 为什么不用 `EventSource`：它无法携带自定义请求头，而本系统的认证走
 * HttpOnly Cookie + 统一客户端标识头（`X-App-Ut` 等，见 `src/lib/client-info.ts`），
 * 后端接口统计与反馈链路都依赖这些头。因此这里用 `fetch` + `ReadableStream`
 * 自己解析 `text/event-stream`。
 *
 * 协议形状由后端 `AiAdminChatController` 决定：
 *   `id:`    事件序号（断线续传时作为 `after` / `Last-Event-ID`）
 *   `event:` 事件类型（`message.delta` 等）
 *   `data:`  事件载荷，单行 JSON
 * 终态时后端额外下发 `event: stream.end`，`data: {"status":"..."}`。
 */

import type { AiEvent } from '@/api/ai';

/** 一帧原始 SSE 事件 */
export interface SseFrame {
  /** 事件序号；为空表示服务端未给 */
  id: string;
  /** 事件类型；为空表示 `message`（SSE 默认事件名） */
  event: string;
  /** 原始 data 文本（多行 data 以 \n 拼接） */
  data: string;
}

/** 终态事件名（后端在请求进入终态且本轮无新行时补发） */
export const STREAM_END_EVENT = 'stream.end';

/**
 * 增量 SSE 解析器。
 *
 * 每次喂入一个文本块，返回本次解析出的完整帧；不完整的残片留在闭包缓冲里，
 * 等下一块补齐。按 SSE 规范以空行（`\n\n`）分帧，兼容 `\r\n`。
 */
export function createSseDecoder(): (chunk: string) => SseFrame[] {
  let buffer = '';

  return (chunk: string): SseFrame[] => {
    buffer += chunk.replace(/\r\n/g, '\n');
    const frames: SseFrame[] = [];

    let sep = buffer.indexOf('\n\n');
    while (sep >= 0) {
      const block = buffer.slice(0, sep);
      buffer = buffer.slice(sep + 2);
      const frame = parseBlock(block);
      if (frame) frames.push(frame);
      sep = buffer.indexOf('\n\n');
    }
    return frames;
  };
}

/** 解析单个事件块（`field: value` 行集合） */
function parseBlock(block: string): SseFrame | null {
  if (!block.trim()) return null;

  let id = '';
  let event = '';
  const dataLines: string[] = [];

  for (const line of block.split('\n')) {
    // 空行与以 `:` 开头的心跳/注释行一律忽略
    if (!line || line.startsWith(':')) continue;

    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    if (field === 'id') id = value;
    else if (field === 'event') event = value;
    else if (field === 'data') dataLines.push(value);
  }

  if (!event && dataLines.length === 0) return null;
  return { id, event: event || 'message', data: dataLines.join('\n') };
}

/** 把一帧转成统一的 `AiEvent`；payload 不是 JSON 对象时退化为空对象 */
export function toAiEvent(frame: SseFrame): AiEvent {
  let data: Record<string, unknown> = {};
  if (frame.data) {
    try {
      const parsed = JSON.parse(frame.data) as unknown;
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        data = parsed as Record<string, unknown>;
      }
    } catch {
      // 非 JSON 载荷（例如纯文本心跳）按空对象处理，不影响后续帧
      data = {};
    }
  }
  const sequence = Number(frame.id);
  return {
    sequence: Number.isFinite(sequence) ? sequence : undefined,
    type: frame.event,
    data,
  };
}

/** 流消费回调 */
export interface StreamHandlers {
  /** 每收到一帧调用一次（已转成 AiEvent） */
  onEvent: (event: AiEvent) => void;
  /** 连接彻底结束时调用（含正常终止与用户中断） */
  onClose?: () => void;
  /** 连接失败时调用；调用方应据此回落到轮询 */
  onError?: (error: Error) => void;
}

export interface StreamOptions {
  /** 从该序号之后开始拉（断线续传） */
  after?: number;
  /** 外部中断（切走页面 / 点停止） */
  signal?: AbortSignal;
}

/**
 * 打开一条事件流并消费到结束。
 *
 * 返回值在流正常关闭、被中断或出错后 resolve —— 出错信息通过 `onError` 上报，
 * 调用方据此决定是否回落到 `/events` 轮询。
 */
export async function openEventStream(
  url: string,
  handlers: StreamHandlers,
  options: StreamOptions = {},
): Promise<void> {
  const { onEvent, onClose, onError } = handlers;
  const controller = new AbortController();

  // 外部中断与内部中断合并：任一方 abort 都断开读取
  const external = options.signal;
  const forwardAbort = () => controller.abort();
  if (external) {
    if (external.aborted) controller.abort();
    else external.addEventListener('abort', forwardAbort, { once: true });
  }

  const target = options.after ? `${url}${url.includes('?') ? '&' : '?'}after=${options.after}` : url;

  try {
    const { clientHeaders } = await import('@/lib/client-info');
    const response = await fetch(target, {
      method: 'GET',
      credentials: 'include',
      cache: 'no-store',
      headers: {
        Accept: 'text/event-stream',
        ...clientHeaders(),
      },
      signal: controller.signal,
    });

    // 新契约下业务失败（未登录 401、无权限 403、限流 429…）是 HTTP 200 + Result JSON，
    // 因此必须校验 Content-Type：不是事件流就按错误体读 msg，避免把 JSON 当 SSE 解析。
    const contentType = response.headers.get('Content-Type') || '';
    if (!response.ok || !response.body || !contentType.includes('text/event-stream')) {
      const data = (await response.json().catch(() => null)) as { msg?: string } | null;
      throw new Error(data?.msg || `事件流连接失败（HTTP ${response.status}）`);
    }

    const reader = response.body.getReader();
    const textDecoder = new TextDecoder('utf-8');
    const decode = createSseDecoder();
    let ended = false;

    while (!ended) {
      const { value, done } = await reader.read();
      if (done) break;

      const frames = decode(textDecoder.decode(value, { stream: true }));
      for (const frame of frames) {
        if (frame.event === STREAM_END_EVENT) {
          ended = true;
          break;
        }
        onEvent(toAiEvent(frame));
      }
    }

    // 收尾：把缓冲区里最后一帧（若服务端未以空行结束）也交付出去
    if (!ended) {
      for (const frame of decode('\n\n')) {
        if (frame.event === STREAM_END_EVENT) break;
        onEvent(toAiEvent(frame));
      }
    }

    // 主动断开，避免连接悬挂
    controller.abort();
  } catch (error) {
    // 用户主动中断不算错误
    if (controller.signal.aborted && !external?.aborted) {
      // 内部 abort 只发生在收尾阶段，忽略
    } else if (external?.aborted) {
      // 用户切走或点了停止，静默结束
    } else {
      onError?.(error instanceof Error ? error : new Error(String(error)));
    }
  } finally {
    external?.removeEventListener('abort', forwardAbort);
    onClose?.();
  }
}
