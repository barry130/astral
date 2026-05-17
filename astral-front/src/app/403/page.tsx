'use client';

import { Result, Button } from 'antd';
import { useRouter } from 'next/navigation';
import { HomeOutlined, BackwardOutlined } from '@ant-design/icons';

export default function ForbiddenPage() {
  const router = useRouter();

  return (
    <div style={{ 
      minHeight: '100vh', 
      display: 'flex', 
      alignItems: 'center', 
      justifyContent: 'center',
      background: '#f5f5f5'
    }}>
      <Result
        status="403"
        title="403"
        subTitle="抱歉，您没有权限访问此页面"
        extra={[
          <Button type="primary" icon={<HomeOutlined />} onClick={() => router.push('/dashboard')}>
            返回首页
          </Button>,
          <Button icon={<BackwardOutlined />} onClick={() => router.back()}>
            返回上一页
          </Button>,
        ]}
      />
    </div>
  );
}
