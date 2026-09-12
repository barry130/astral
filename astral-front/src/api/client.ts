import axios, { AxiosRequestConfig, AxiosResponse } from 'axios';
import { beginRequest, endRequest } from '@/lib/requestLoading';

// API 方法自身已包含 /api 前缀, 同源访问时 baseURL 必须为空, 避免拼成 /api/api/...
const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || '';

/** 扩展请求配置：silent 为 true 时不触发全局加载指示（轮询 / 心跳等后台请求） */
export interface ApiRequestConfig extends AxiosRequestConfig {
  silent?: boolean;
}

/** 判断请求是否为静默请求（不显示全局加载动画） */
const isSilent = (config?: AxiosRequestConfig): boolean =>
  Boolean((config as ApiRequestConfig | undefined)?.silent);

const client = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
});

client.interceptors.request.use(
  (config) => {
    if (typeof window !== 'undefined') {
      const token = localStorage.getItem('token');
      if (token) {
        config.headers.set('satoken', token);
      }
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
  (error) => {
    // 成功与失败都要归还计数，否则泄漏会让加载指示永久常驻
    if (!isSilent(error.config)) {
      endRequest();
    }
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      if (typeof window !== 'undefined' && !window.location.pathname.includes('/login')) {
        window.location.href = '/login';
      }
    }
    if (error.response?.data?.message) {
      return Promise.reject(new Error(error.response.data.message));
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
