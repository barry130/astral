'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/context/AuthContext';
import { Spin } from 'antd';

/**
 * 首页组件（根路由 /）
 * 根据登录状态自动重定向到仪表盘或登录页
 */
export default function HomePage() {
  /** 从认证上下文获取登录状态和加载状态 */
  const { isLogin, loading } = useAuth();
  const router = useRouter();

  /** 加载完成后根据登录状态进行路由跳转 */
  useEffect(() => {
    if (!loading) {
      if (isLogin) {
        router.replace('/dashboard');
      } else {
        router.replace('/login');
      }
    }
  }, [isLogin, loading, router]);

  /** 加载期间显示全屏loading动画 */
  return (
    <div style={{ 
      display: 'flex', 
      justifyContent: 'center', 
      alignItems: 'center', 
      height: '100vh' 
    }}>
      <Spin size="large" />
    </div>
  );
}