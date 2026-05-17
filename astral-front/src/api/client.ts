import axios, { AxiosRequestConfig, AxiosResponse } from 'axios';

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';

const client = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
});

client.interceptors.request.use((config) => {
  if (typeof window !== 'undefined') {
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.set('satoken', token);
    }
  }
  return config;
});

client.interceptors.response.use(
  (response: AxiosResponse) => {
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
  get: <T = any>(url: string, config?: AxiosRequestConfig): Promise<ApiResult<T>> =>
    client.get(url, config),

  post: <T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<ApiResult<T>> =>
    client.post(url, data, config),

  put: <T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<ApiResult<T>> =>
    client.put(url, data, config),

  delete: <T = any>(url: string, config?: AxiosRequestConfig): Promise<ApiResult<T>> =>
    client.delete(url, config),
};

export default client;
