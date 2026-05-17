'use client';

import React from 'react';
import { ConfigProvider } from 'antd';
import { AuthProvider } from '@/context/AuthContext';

/**
 * 全局Provider组件
 * 包裹认证提供者和Ant Design主题配置
 */
export function Providers({ children }: { children: React.ReactNode }) {
  return (
    <AuthProvider>
      <ConfigProvider
        theme={{
          token: {
            /** 主题主色调（蓝色） */
            colorPrimary: '#1677ff',
            /** 圆角大小 */
            borderRadius: 6,
          },
        }}
      >
        {children}
      </ConfigProvider>
    </AuthProvider>
  );
}