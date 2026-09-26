'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { Form, Input, Button, message, Spin } from 'antd';
import { UserOutlined, LockOutlined, SafetyOutlined } from '@ant-design/icons';
import { useAuth } from '@/context/AuthContext';
import { encryptPassword, clearPublicKeyCache } from '@/lib/crypto';

export default function LoginPage() {
  const [loading, setLoading] = useState(false);
  const [publicKeyLoading, setPublicKeyLoading] = useState(true);
  const { login, isLogin, loading: authLoading } = useAuth();
  const router = useRouter();

  // 已登录用户直接跳转到首页（仪表盘），避免重复登录
  useEffect(() => {
    if (!authLoading && isLogin) {
      router.replace('/dashboard');
    }
  }, [authLoading, isLogin, router]);

  useEffect(() => {
    encryptPassword('test').then(() => {
      setPublicKeyLoading(false);
    }).catch((error) => {
      console.error('加密初始化失败:', error);
      message.error(error.message || '加密初始化失败，请确保后端服务已启动');
    });
  }, []);

  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      const encryptedPassword = await encryptPassword(values.password);
      await login(values.username, encryptedPassword);
      clearPublicKeyCache();
      message.success('登录成功');
      router.push('/dashboard');
    } catch (error: any) {
      message.error(error.message || '登录失败');
    } finally {
      setLoading(false);
    }
  };

  // 校验登录状态期间不展示表单，避免闪现后跳转
  if (authLoading) {
    return (
      <div style={{
        display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh',
      }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div className="login-container">
      <div className="login-box fade-in-up">
        <div style={{ textAlign: 'center', marginBottom: 32 }}>
          <div style={{
            width: 56,
            height: 56,
            background: 'var(--color-brand)',
            borderRadius: 14,
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            marginBottom: 20,
            boxShadow: '0 8px 20px rgba(24, 24, 27, 0.16)'
          }}>
            <SafetyOutlined style={{ fontSize: 26, color: '#fff' }} />
          </div>
          <h1 className="login-title">Astral</h1>
          <p className="login-subtitle">Astral 后台管理系统</p>
        </div>

        <Form
          name="login"
          onFinish={onFinish}
          size="large"
          autoComplete="off"
        >
          <Form.Item
            name="username"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input
              prefix={<UserOutlined style={{ color: 'var(--color-text-tertiary)' }} />}
              placeholder="用户名"
              autoComplete="username"
              style={{ height: 44, borderRadius: 10 }}
            />
          </Form.Item>
          <Form.Item
            name="password"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password
              prefix={<LockOutlined style={{ color: 'var(--color-text-tertiary)' }} />}
              placeholder="密码"
              autoComplete="current-password"
              style={{ height: 44, borderRadius: 10 }}
            />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0, marginTop: 28 }}>
            <Button
              type="primary"
              htmlType="submit"
              loading={loading || publicKeyLoading}
              block
              style={{ height: 44, fontSize: 15, fontWeight: 600, borderRadius: 10 }}
            >
              登 录
            </Button>
          </Form.Item>
        </Form>

        <div style={{
          marginTop: 24,
          textAlign: 'center',
          color: 'var(--color-text-tertiary)',
          fontSize: 12
        }}>
          默认账号: admin / admin
        </div>

        <div style={{ textAlign: 'center', marginTop: 12 }}>
          <a
            onClick={() => router.push('/')}
            style={{ fontSize: 12, color: 'var(--color-text-tertiary)', cursor: 'pointer' }}
          >
            ← 返回首页
          </a>
        </div>
      </div>
    </div>
  );
}