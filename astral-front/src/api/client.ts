import axios, { AxiosRequestConfig, AxiosResponse } from 'axios';
import { beginRequest, endRequest } from '@/lib/requestLoading';
import { clientHeaders } from '@/lib/client-info';

// API 方法自身已包含 /api 前缀, 同源访问时 baseURL 必须为空, 避免拼成 /api/api/...
const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || '';

/** 扩展请求配置：silent 为 true 时不触发全局加载指示（轮询 / 心跳等后台请求） */
export interface ApiRequestConfig extends AxiosRequestConfig<unknown> {
  silent?: boolean;
}

/** 文件流须显式声明允许的媒体类型，普通 request 方法仍要求 Result。 */
export interface ApiDownloadConfig extends Omit<ApiRequestConfig, 'responseType'> {
  mediaTypes: readonly string[];
}

/** 判断请求是否为静默请求（不显示全局加载动画） */
const isSilent = (config?: AxiosRequestConfig): boolean =>
  Boolean((config as ApiRequestConfig | undefined)?.silent);

/** 非 plain-object 标记经 axios 配置合并仍保留身份，finally 只归还本请求。 */
class RequestTracker {
  private active = false;
  private finished = false;

  get isFinished(): boolean {
    return this.finished;
  }

  start(silent: boolean): void {
    if (!silent && !this.active && !this.finished) {
      this.active = true;
      beginRequest();
    }
  }

  finish(): void {
    if (this.finished) return;
    this.finished = true;
    if (this.active) endRequest();
  }
}

function finishRequest(config?: AxiosRequestConfig): void {
  (config as CsrfRetryConfig | undefined)?.__requestTracker?.finish();
}

function redirectUnauthorized(config?: CsrfRetryConfig): void {
  if (config?.__csrfSessionRevision !== csrfSessionRevision || typeof window === 'undefined')
    return;
  const pathname = window.location.pathname;
  if (pathname === '/dashboard' || pathname.startsWith('/dashboard/')) {
    window.location.href = '/login';
  }
}

function isApiResult(value: unknown): value is ApiResult<unknown> {
  return (
    typeof value === 'object' &&
    value !== null &&
    'code' in value &&
    typeof value.code === 'number' &&
    Number.isInteger(value.code) &&
    'msg' in value &&
    typeof value.msg === 'string' &&
    // 后端 Result 的 Jackson 序列化对成功响应输出 "errorCode": null（字段存在且为 null，
    // 而不是省略字段）。显式 null 必须与「字段不存在」同等视为无细分错误码，
    // 否则所有 Result.success 响应都会被判为格式异常（登录即触发）。
    (!('errorCode' in value) ||
      value.errorCode === undefined ||
      value.errorCode === null ||
      typeof value.errorCode === 'string')
  );
}

/**
 * CSRF 双提交令牌的 Cookie 名 / 请求头名。
 *
 * HttpOnly 使 JS 不能经 document.cookie 读取 satoken，但不是 XSS 免疫：
 * 同源脚本仍可发带凭据的请求，且新登录的兼容响应仍含 token；不能将它长期保存或放进用户上下文。
 * 浏览器自动携带 Cookie，跨站写请求因此仍需要双提交校验：
 * 后端下发一枚非 HttpOnly 的 `astral_csrf` Cookie，前端读出后原样回填请求头，
 * 两者一致才放行（契约见后端 com.astral.common.web.CsrfTokenSupport）。
 */
const CSRF_COOKIE = 'astral_csrf';
const CSRF_HEADER = 'X-CSRF-Token';

/**
 * 内存中的 CSRF 令牌缓存。
 *
 * 为什么不能只读 Cookie：令牌写在 `astral_csrf` Cookie 里，但**跨域直连**部署
 * （如 web.canace.cn 直连 astral.canace.cn）时该 Cookie 属于 API 域，页面所在域的
 * `document.cookie` 根本看不到它 —— 于是回填不了请求头，所有写请求被后端双提交校验
 * 拦成 403（AUTH013），只读请求却一切正常，表现为「能看不能改」。
 * 后端因此在登录与 `/api/v1/all/auth/info` 的响应体里一并返回该令牌，前端缓存在内存。
 * 这不削弱双提交模型：跨站页面同样读不到我们的响应体。
 */
let csrfTokenCache = '';
let csrfSessionRevision = 0;

/** 写入令牌缓存（登录、拉取用户信息成功后调用） */
export function setCsrfToken(token?: string | null): void {
  csrfSessionRevision += 1;
  csrfTokenCache = typeof token === 'string' ? token : '';
}

/** 清空令牌缓存（登出时调用，避免下次登录前带着过期指纹） */
export function clearCsrfToken(): void {
  csrfSessionRevision += 1;
  csrfTokenCache = '';
}

/**
 * 读取 CSRF 令牌：优先 Cookie（同源部署，值最新），读不到时回退内存缓存（跨域直连）。
 * SSR 阶段两者皆空，返回空串（服务端渲染不发写请求）。
 */
export function readCsrfToken(): string {
  if (typeof document !== 'undefined') {
    try {
      const matched = document.cookie.match(new RegExp(`(?:^|;\\s*)${CSRF_COOKIE}=([^;]*)`));
      if (matched && matched[1]) return decodeURIComponent(matched[1]);
    } catch {
      // 损坏的编码或受限 Cookie 访问不可阻断全部接口；仍可使用登录响应下发的缓存。
    }
  }
  return csrfTokenCache;
}

/** 请求配置上的内部标记：AUTH013 已刷新重试过一次，避免死循环 */
interface CsrfRetryConfig extends ApiRequestConfig {
  __downloadMediaTypes?: readonly string[];
  __csrfRetried?: boolean;
  __csrfSessionRevision?: number;
  __csrfFailure?: Error;
  __requestTracker?: RequestTracker;
}

/**
 * 拉取一次有效令牌并写入缓存，返回「令牌是否真的变了」。
 *
 * `/api/v1/all/auth/info` 是安全方法（GET 不参与双提交校验），后端会在响应体里带上当前
 * 有效令牌，因此刷新它不会再次触发 AUTH013。
 *
 * 返回值是**自限开关**：令牌没变说明重放也是同一个 403（例如服务端与会话不匹配，
 * 该重新登录而不是重试），调用方据此放弃重放 —— 这样无论 axios 是否保留自定义配置键，
 * 都不可能重试打转。
 */
let csrfRefresh: { revision: number; promise: Promise<boolean> } | undefined;

function refreshCsrfToken(): Promise<boolean> {
  const revision = csrfSessionRevision;
  if (csrfRefresh?.revision === revision) return csrfRefresh.promise;
  const before = readCsrfToken();
  const promise = (async () => {
    try {
      const info = await request.get<{ csrfToken?: string }>('/api/v1/all/auth/info', {
        silent: true,
      });
      if (revision !== csrfSessionRevision) return false;
      // 同一会话并发失败只刷新一次；账号切换会递增 revision，旧响应不得回填。
      const token = info?.data?.csrfToken;
      if (typeof token !== 'string') return false;
      csrfTokenCache = token;
      const after = readCsrfToken();
      return Boolean(after) && after !== before;
    } catch {
      // 刷新失败不重放，由原请求保留准确的业务错误。
      return false;
    }
  })().finally(() => {
    if (csrfRefresh?.promise === promise) csrfRefresh = undefined;
  });
  csrfRefresh = { revision, promise };
  return promise;
}

const client = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  // 认证靠 Cookie，跨源部署（NEXT_PUBLIC_API_URL 指向别的域名）时必须带上凭据。
  // 同源部署（默认，走 Next.js rewrites）下该选项不影响任何行为。
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json',
  },
});

client.interceptors.request.use(
  (config) => {
    const retryConfig = config as CsrfRetryConfig;
    if (retryConfig.__csrfRetried && retryConfig.__csrfSessionRevision !== csrfSessionRevision) {
      throw retryConfig.__csrfFailure ?? new Error('会话已变更，请重新操作');
    }
    if (!retryConfig.__csrfRetried) retryConfig.__csrfSessionRevision = csrfSessionRevision;
    // 统一客户端系统头（X-App-Ut=web / X-App-Version / X-Device / X-OS）：
    // 本实例的请求全部走 /api/**，由 next.config.js 的 rewrite 转发到 astral，
    // 因此整个管理台只要是经这里发出的请求都满足「请求 astral 必带这几个头」。
    // 契约见 src/lib/client-info.ts。
    for (const [name, value] of Object.entries(clientHeaders())) {
      config.headers.set(name, value);
    }
    // CSRF 令牌回填：后端对「凭据来自 Cookie」的写请求校验它，
    // 跨站页面读不到这枚 Cookie，因此伪造不出匹配的头。
    const csrfToken = readCsrfToken();
    if (csrfToken) {
      config.headers.set(CSRF_HEADER, csrfToken);
    }
    const tracker = retryConfig.__requestTracker;
    retryConfig.__requestTracker = tracker && !tracker.isFinished ? tracker : new RequestTracker();
    const adapter = axios.getAdapter(config.adapter ?? client.defaults.adapter);
    config.adapter = async (dispatched) => {
      try {
        return await adapter(dispatched);
      } finally {
        finishRequest(dispatched);
      }
    };
    retryConfig.__requestTracker.start(isSilent(config));
    return config;
  },
  (error: unknown) => {
    if (axios.isAxiosError(error)) finishRequest(error.config);
    return Promise.reject(error);
  },
);

/**
 * 把一次业务失败（HTTP 200 + body.code ≠ 200）翻译成统一的 Error。
 *
 * 后端契约（AGENTS.md「统一响应体」）：HTTP 状态码只表达传输层结果，**业务失败一律仍是
 * HTTP 200**，由 body 的 `code` 表达 —— 300 业务失败 / 320 参数错误 / 401 未登录 /
 * 403 无权限 / 429 限流 / 500 系统错误。`errorCode` 是细分码（AUTH001、AUTH013…），
 * `msg` 是人类可读消息。因此「HTTP 200 但没有业务成功」必须在这里兜住，不能当成功放行。
 */
function businessFailure(result: ApiResult, fallbackCode?: string): Error {
  const err = new Error(result.msg || '请求失败') as Error & { errorCode?: string };
  const code = fallbackCode || result.errorCode;
  if (code) {
    // 业务错误码透传（如 AUTH010 需要动态验证码）：登录页等场景按码做特殊交互
    err.errorCode = code;
  }
  return err;
}

/**
 * 处理「HTTP 200 送达、body.code 非 200」的业务失败。
 *
 * 与错误分支保持同一套侧效应：AUTH013 刷新 CSRF 令牌后重放一次；401 只在已进入受保护区
 * 时踢回登录页。其余一律 reject 带 msg/errorCode 的 Error，交给调用方展示。
 */
async function handleBusinessFailure(
  result: ApiResult,
  config: CsrfRetryConfig | undefined,
): Promise<unknown> {
  // 双提交校验失败（AUTH013）：多半是跨域直连下 Cookie 读不到、令牌缓存又为空。
  // 刷新一次令牌，只有确实拿到新值才重放原请求（见 refreshCsrfToken 的自限说明）；
  // 拿不到就落回下面的通用处理，把后端原始 msg 交给调用方。
  if (
    result.code === 403 &&
    result.errorCode === 'AUTH013' &&
    config &&
    !config.__csrfRetried &&
    config.__csrfSessionRevision === csrfSessionRevision
  ) {
    const revision = csrfSessionRevision;
    config.__csrfRetried = true;
    config.__csrfFailure = businessFailure(result);
    if ((await refreshCsrfToken()) && revision === csrfSessionRevision) {
      return client.request(config);
    }
  }
  if (result.code === 401) {
    // 与错误分支一致：只在「确定已登录过」的页面上跳转，避免落地页匿名访客被弹走。
    redirectUnauthorized(config);
  }
  throw businessFailure(result);
}

/** Bound JSON decoding, not the browser adapter's network receive buffer. */
async function decodeJsonBlob(blob: Blob): Promise<unknown> {
  if (blob.size > 64 * 1024) {
    throw new Error('服务器下载响应格式异常，请稍后重试');
  }
  return JSON.parse(await blob.text()) as unknown;
}

/**
 * 响应拦截器按【新契约全量适配】：业务结论只从 body 读，传输层错误只从 HTTP 状态读。
 *
 * - HTTP 200：服务端已处理，成功还是失败看 `body.code`（200 成功 / 300 业务失败 /
 *   320 参数错误 / 401 未登录 / 403 无权限 / 429 限流 / 500 系统错误）。
 * - 真实非 2xx：只剩传输层错误 —— 404 路由不存在、405 方法不支持、413 上传超限、
 *   500 未预期异常、503 服务不可用，以及网络层错误。
 *
 * 前端与后端同步发版，因此这里**不再兼容**旧服务端「业务失败也回 4xx」的行为。
 */
client.interceptors.response.use(
  // axios 的类型签名要求返回 AxiosResponse，但这里刻意把 body 直接交给调用方
  // （`request.*` 返回 Promise<ApiResult<T>>），因此这里对 axios 的签名做一次显式断言：
  // 拦截器实际返回的是 ApiResult（成功）或 reject（业务/传输失败）。
  (async (response: AxiosResponse<unknown>): Promise<unknown> => {
    finishRequest(response.config);
    let result: unknown = response.data;
    const contentType = response.headers['content-type'];
    const mediaType =
      typeof contentType === 'string' ? contentType.split(';')[0].trim().toLowerCase() : '';
    if (
      typeof Blob !== 'undefined' &&
      result instanceof Blob &&
      (mediaType === 'application/json' || mediaType.endsWith('+json'))
    ) {
      try {
        result = await decodeJsonBlob(result);
      } catch {
        return Promise.reject(new Error('服务器下载响应格式异常，请稍后重试'));
      }
    }
    const fileTypes = (response.config as CsrfRetryConfig | undefined)?.__downloadMediaTypes;
    if (fileTypes) {
      if (typeof Blob !== 'undefined' && result instanceof Blob && fileTypes.includes(mediaType)) {
        return result;
      }
      if (!isApiResult(result) || (result.code === 200 && !result.errorCode)) {
        return Promise.reject(new Error('服务器下载响应格式异常，请稍后重试'));
      }
    }
    if (!isApiResult(result)) {
      return Promise.reject(new Error('服务器响应格式异常，请稍后重试'));
    }
    if (result.code === 200) {
      if (result.errorCode) return Promise.reject(businessFailure(result));
      return result;
    }
    return handleBusinessFailure(result, response.config as CsrfRetryConfig | undefined);
  }) as unknown as (response: AxiosResponse<unknown>) => AxiosResponse<unknown>,
  async (error: unknown): Promise<unknown> => {
    if (!axios.isAxiosError<unknown>(error)) return Promise.reject(error);
    finishRequest(error.config);
    if (axios.isCancel(error)) return Promise.reject(error);
    let body: unknown = error.response?.data;
    const contentType = error.response?.headers['content-type'];
    const mediaType =
      typeof contentType === 'string' ? contentType.split(';')[0].trim().toLowerCase() : '';
    if (
      typeof Blob !== 'undefined' &&
      body instanceof Blob &&
      (mediaType === 'application/json' || mediaType.endsWith('+json'))
    ) {
      try {
        body = await decodeJsonBlob(body);
      } catch {
        // An unreadable gateway payload must fall back to the transport status.
        body = null;
      }
    }
    if (error.response?.status === 401) {
      redirectUnauthorized(error.config as CsrfRetryConfig | undefined);
    }
    // 网关的失败载荷不一定完整，但字符串消息和细分码仍可安全保留。
    if (
      typeof body === 'object' &&
      body !== null &&
      'msg' in body &&
      typeof body.msg === 'string' &&
      body.msg
    ) {
      const errorCode =
        'errorCode' in body && typeof body.errorCode === 'string' ? body.errorCode : undefined;
      return Promise.reject(
        businessFailure({
          code: error.response?.status ?? 500,
          msg: body.msg,
          errorCode,
          data: null,
          timestamp: 0,
        }),
      );
    }
    if (error.code === 'ERR_NETWORK' || error.message === 'Network Error') {
      return Promise.reject(new Error('无法连接到服务器，请检查后端服务是否启动'));
    }
    if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
      return Promise.reject(new Error('请求超时，请稍后重试'));
    }
    if (error.response?.status) {
      return Promise.reject(new Error(`服务暂时不可用（HTTP ${error.response.status}）`));
    }
    return Promise.reject(error);
  },
);

export interface ApiResult<T = unknown> {
  code: number;
  errorCode?: string;
  msg: string;
  data: T;
  timestamp: number;
}

/** 即使在 adapter 之前序列化失败，也只归还这次调用自己的加载计数。 */
async function send<T>(config: ApiRequestConfig): Promise<ApiResult<T>> {
  const tracker = new RequestTracker();
  const tracked: CsrfRetryConfig = { ...config, __requestTracker: tracker };
  try {
    return await client.request<unknown, ApiResult<T>, unknown>(tracked);
  } finally {
    tracker.finish();
  }
}

async function sendBlob(url: string, config: ApiDownloadConfig): Promise<Blob> {
  const { mediaTypes, ...options } = config;
  const downloadMediaTypes = mediaTypes.map((type) => type.trim().toLowerCase());
  if (!downloadMediaTypes.length || downloadMediaTypes.some((type) => !type)) {
    throw new Error('请指定明确的下载文件类型');
  }
  const tracker = new RequestTracker();
  const tracked: CsrfRetryConfig = {
    ...options,
    url,
    method: 'get',
    responseType: 'blob',
    __downloadMediaTypes: downloadMediaTypes,
    __requestTracker: tracker,
  };
  try {
    return await client.request<unknown, Blob, unknown>(tracked);
  } finally {
    tracker.finish();
  }
}

export const request = {
  getBlob: sendBlob,

  get: <T = unknown>(url: string, config?: ApiRequestConfig): Promise<ApiResult<T>> =>
    send<T>({ ...config, url, method: 'get' }),

  post: <T = unknown>(
    url: string,
    data?: unknown,
    config?: ApiRequestConfig,
  ): Promise<ApiResult<T>> => send<T>({ ...config, url, method: 'post', data }),

  put: <T = unknown>(
    url: string,
    data?: unknown,
    config?: ApiRequestConfig,
  ): Promise<ApiResult<T>> => send<T>({ ...config, url, method: 'put', data }),

  delete: <T = unknown>(url: string, config?: ApiRequestConfig): Promise<ApiResult<T>> =>
    send<T>({ ...config, url, method: 'delete' }),
};

export default client;
