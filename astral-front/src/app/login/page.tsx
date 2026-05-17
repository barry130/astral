'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { Form, Input, Button, message } from 'antd';
import { UserOutlined, LockOutlined, SafetyOutlined } from '@ant-design/icons';
import { useAuth } from '@/context/AuthContext';

/**
 * 登录页面组件
 * 提供用户名和密码输入表单，调用认证API进行登录
 */
export default function LoginPage() {
  /** 登录按钮加载状态 */
  const [loading, setLoading] = useState(false);
  /** 从认证上下文获取登录方法 */
  const { login } = useAuth();
  const router = useRouter();

  /** 表单提交处理：调用登录API，成功后跳转到仪表盘 */
  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      await login(values.username, values.password);
      message.success('登录成功');
      router.push('/dashboard');
    } catch (error: any) {
      message.error(error.message || '登录失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-container">
      <div className="login-box fade-in-up">
        <div style={{ textAlign: 'center', marginBottom: 32 }}>
          <div style={{
            width: 56,
            height: 56,
            background: 'linear-gradient(135deg, #1a1a2e 0%, #2d2d44 100%)',
            borderRadius: 14,
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            marginBottom: 20,
            boxShadow: '0 8px 24px rgba(26, 26, 46, 0.2)'
          }}>
            <SafetyOutlined style={{ fontSize: 28, color: '#fff' }} />
          </div>
          <h1 className="login-title">Astral</h1>
          <p className="login-subtitle">Astral后台管理系统</p>
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
              prefix={<UserOutlined style={{ color: '#909399' }} />}
              placeholder="用户名"
              autoComplete="username"
              style={{ height: 48 }}
            />
          </Form.Item>
          <Form.Item
            name="password"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password
              prefix={<LockOutlined style={{ color: '#909399' }} />}
              placeholder="密码"
              autoComplete="current-password"
              style={{ height: 48 }}
            />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0, marginTop: 32 }}>
            <Button 
              type="primary" 
              htmlType="submit" 
              loading={loading} 
              block
              style={{ height: 48, fontSize: 16, fontWeight: 500 }}
            >
              登 录
            </Button>
          </Form.Item>
        </Form>

        <div style={{ 
          marginTop: 24, 
          textAlign: 'center',
          color: '#909399',
          fontSize: 12
        }}>
          默认账号: admin / admin
        </div>
      </div>
    </div>
  );
}