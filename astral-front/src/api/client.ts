import axios, { AxiosRequestConfig, AxiosResponse } from 'axios';

/** API基础URL，从环境变量获取或使用默认值 */
const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';

/** 创建axios实例，配置基础URL、超时时间和请求头 */
const client = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
});

/** 请求拦截器：自动附加Sa-Token认证令牌 */
client.interceptors.request.use((config) => {
  // 仅在浏览器环境下从localStorage读取token
  if (typeof window !== 'undefined') {
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.set('satoken', token);
    }
  }
  return config;
});

/** 响应拦截器：统一处理401未授权和网络错误 */
client.interceptors.response.use(
  (response: AxiosResponse) => response.data,
  (error) => {
    // 401未授权：清除token并跳转到登录页
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      if (typeof window !== 'undefined' && !window.location.pathname.includes('/login')) {
        window.location.href = '/login';
      }
    }
    // 提取后端返回的错误消息
    if (error.response?.data?.message) {
      return Promise.reject(new Error(error.response.data.message));
    }
    // 网络错误：提供友好的中文提示
    if (error.code === 'ERR_NETWORK' || error.message === 'Network Error') {
      return Promise.reject(new Error('无法连接到服务器，请检查后端服务是否启动'));
    }
    return Promise.reject(error);
  }
);

/** 统一API响应结构 */
export interface ApiResult<T = any> {
  /** 状态码，200表示成功 */
  code: number;
  /** 消息提示 */
  message: string;
  /** 响应数据 */
  data: T;
  /** 时间戳 */
  timestamp: number;
}

/** 封装的HTTP请求方法 */
export const request = {
  /** GET请求 */
  get: <T = any>(url: string, config?: AxiosRequestConfig): Promise<ApiResult<T>> =>
    client.get(url, config),

  /** POST请求 */
  post: <T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<ApiResult<T>> =>
    client.post(url, data, config),

  /** PUT请求 */
  put: <T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<ApiResult<T>> =>
    client.put(url, data, config),

  /** DELETE请求 */
  delete: <T = any>(url: string, config?: AxiosRequestConfig): Promise<ApiResult<T>> =>
    client.delete(url, config),
};

export default client;