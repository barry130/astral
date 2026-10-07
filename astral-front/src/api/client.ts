import axios, { AxiosRequestConfig, AxiosResponse } from 'axios';
import { beginRequest, endRequest } from '@/lib/requestLoading';
import { clientHeaders } from '@/lib/client-info';

// API 方法自身已包含 /api 前缀, 同源访问时 baseURL 必须为空, 避免拼成 /api/api/...
const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || '';

/** 扩展请求配置：silent 为 true 时不触发全局加载指示（轮询 / 心跳等后台请求） */
export interface ApiRequestConfig extends AxiosRequestConfig {
  silent?: boolean;
}

/** 判断请求是否为静默请求（不显示全局加载动画） */
const isSilent = (config?: AxiosRequestConfig): boolean =>
  Boolean((config as ApiRequestConfig | undefined)?.silent);

/**
 * CSRF 双提交令牌的 Cookie 名 / 请求头名。
 *
 * 认证令牌由后端写在 HttpOnly Cookie（`satoken`）里，浏览器自动携带 —— JS 读不到，
 * XSS 也就偷不走。代价是「跨站发起的写请求也会自动带上凭据」，于是需要双提交校验：
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

/** 写入令牌缓存（登录、拉取用户信息成功后调用） */
export function setCsrfToken(token?: string | null): void {
  csrfTokenCache = typeof token === 'string' ? token : '';
}

/** 清空令牌缓存（登出时调用，避免下次登录前带着过期指纹） */
export function clearCsrfToken(): void {
  csrfTokenCache = '';
}

/**
 * 读取 CSRF 令牌：优先 Cookie（同源部署，值最新），读不到时回退内存缓存（跨域直连）。
 * SSR 阶段两者皆空，返回空串（服务端渲染不发写请求）。
 */
export function readCsrfToken(): string {
  if (typeof document !== 'undefined') {
    const matched = document.cookie.match(new RegExp(`(?:^|;\\s*)${CSRF_COOKIE}=([^;]*)`));
    if (matched && matched[1]) {
      return decodeURIComponent(matched[1]);
    }
  }
  return csrfTokenCache;
}

/** 请求配置上的内部标记：AUTH013 已刷新重试过一次，避免死循环 */
interface CsrfRetryConfig extends ApiRequestConfig {
  __csrfRetried?: boolean;
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
async function refreshCsrfToken(): Promise<boolean> {
  const before = readCsrfToken();
  try {
    const info = await client.get('/api/v1/all/auth/info', { silent: true } as ApiRequestConfig);
    setCsrfToken(info?.data?.csrfToken);
  } catch {
    // 刷新失败（未登录 / 网络异常）：不重放，交给通用错误处理给出准确提示
    return false;
  }
  const after = readCsrfToken();
  return Boolean(after) && after !== before;
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
    if (!isSilent(config)) {
      beginRequest();
    }
    return config;
  },
  (error) => {
    if (!isSilent(error.config)) {
      endRequest();
    }
    return Promise.reject(error);
  },
);

client.interceptors.response.use(
  (response: AxiosResponse) => {
    if (!isSilent(response.config)) {
      endRequest();
    }
    const result = response.data;
    if (result.code === 200) {
      if (result.errorCode) {
        return Promise.reject(new Error(result.message));
      }
      return result;
    }
    return Promise.reject(new Error(result.message || '请求失败'));
  },
  async (error) => {
    // 成功与失败都要归还计数，否则泄漏会让加载指示永久常驻
    if (!isSilent(error.config)) {
      endRequest();
    }
    const errorCode = error.response?.data?.errorCode;
    // 双提交校验失败（AUTH013）：多半是跨域直连下 Cookie 读不到、令牌缓存又为空。
    // 刷新一次令牌，只有确实拿到新值才重放原请求（见 refreshCsrfToken 的自限说明）；
    // 拿不到就落回下面的通用处理，把后端原始 message 交给调用方，而不是 axios 的英文原文。
    const retryConfig = error.config as CsrfRetryConfig | undefined;
    if (errorCode === 'AUTH013' && retryConfig && !retryConfig.__csrfRetried) {
      retryConfig.__csrfRetried = true;
      if (await refreshCsrfToken()) {
        return client.request(retryConfig);
      }
    }
    if (error.response?.status === 401) {
      // 令牌在 HttpOnly Cookie 里，前端删不掉也不需要删：
      // 跳转登录页会重新走一次登录流程，登录成功时后端覆盖 Cookie。
      // 登出请走后端 /logout（会清 Cookie），不要只做前端清理。
      //
      // 只在「确定已登录过」的页面上跳转：令牌不再能从 localStorage 判断存在性，
      // 无差别 401 跳转会让落地页（/、/lightlisten）的匿名访客也被弹到登录页。
      // 需要登录态的页面自己做了守卫（dashboard 布局、/imgbed），这里只兜底
      // 「已进入受保护区但在途 token 失效」的会话中途踢出。
      if (typeof window !== 'undefined' && window.location.pathname.startsWith('/dashboard')) {
        window.location.href = '/login';
      }
    }
    if (error.response?.data?.message) {
      const err = new Error(error.response.data.message);
      // 业务错误码透传（如 AUTH010 需要动态验证码）：登录页等场景按码做特殊交互
      const errCode = error.response?.data?.errorCode;
      if (errCode) {
        (err as Error & { errorCode?: string }).errorCode = errCode;
      }
      return Promise.reject(err);
    }
    if (error.code === 'ERR_NETWORK' || error.message === 'Network Error') {
      return Promise.reject(new Error('无法连接到服务器，请检查后端服务是否启动'));
    }
    return Promise.reject(error);
  }
);

export interface ApiResult<T = any> {
  code: number;
  errorCode?: string;
  message: string;
  data: T;
  timestamp: number;
}

export const request = {
  get: <T = any>(url: string, config?: ApiRequestConfig): Promise<ApiResult<T>> =>
    client.get(url, config),

  post: <T = any>(url: string, data?: any, config?: ApiRequestConfig): Promise<ApiResult<T>> =>
    client.post(url, data, config),

  put: <T = any>(url: string, data?: any, config?: ApiRequestConfig): Promise<ApiResult<T>> =>
    client.put(url, data, config),

  delete: <T = any>(url: string, config?: ApiRequestConfig): Promise<ApiResult<T>> =>
    client.delete(url, config),
};

export default client;
