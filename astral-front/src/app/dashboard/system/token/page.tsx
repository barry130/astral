'use client';

import { useEffect, useState } from 'react';
import { Card, Button, Space, Tag, message, Popconfirm, Select } from 'antd';
import { DeleteOutlined, LogoutOutlined } from '@ant-design/icons';
import { request } from '@/api/client';
import { ResizableTable } from '@/components/ResizableTable';

/** Token会话实体接口 */
interface Token {
  /** Token ID */
  id: string;
  /** 用户ID */
  userId: number;
  /** 用户名 */
  username: string;
  /** Token值 */
  token: string;
  /** 过期时间 */
  expireTime: string;
  /** 登录IP */
  loginIp: string;
  /** 状态：1有效/0已吊销 */
  status: number;
  /** 创建时间 */
  createTime: string;
}

/** 用户简要信息接口 */
interface User {
  /** 用户ID */
  id: number;
  /** 用户名 */
  username: string;
}

/** Token管理API封装 */
const tokenApi = {
  /** 分页查询Token */
  getPage: (pageNum: number, pageSize: number, params?: Record<string, any>) =>
    request.get('/api/v1/admin/system/token/page', { params: { pageNum, pageSize, ...params } }),
  /** 吊销指定Token */
  revoke: (id: string) => request.put(`/api/v1/admin/system/token/${encodeURIComponent(id)}/revoke`),
  /** 踢出用户所有会话 */
  kickOut: (userId: number) => request.put(`/api/v1/admin/system/token/user/${userId}/kick`),
  /** 清理所有过期Token */
  cleanExpired: () => request.delete('/api/v1/admin/system/token/expired'),
};

/** 用户管理API封装（用于获取用户列表筛选） */
const userApi = {
  /** 分页查询用户 */
  getPage: (pageNum: number, pageSize: number) =>
    request.get('/api/v1/admin/system/user/page', { params: { pageNum, pageSize } }),
};

/**
 * Token管理页面组件
 * 提供Token会话列表查看、吊销、踢出用户、清理过期Token功能
 */
export default function TokenPage() {
  /** 加载状态 */
  const [loading, setLoading] = useState(false);
  /** Token列表数据 */
  const [data, setData] = useState<Token[]>([]);
  /** 用户列表（用于筛选） */
  const [users, setUsers] = useState<User[]>([]);
  /** 分页状态 */
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  /** 按用户筛选 */
  const [selectedUserId, setSelectedUserId] = useState<number | undefined>(undefined);
  /** 按状态筛选 */
  const [selectedStatus, setSelectedStatus] = useState<number | undefined>(undefined);

  /** 组件挂载时加载Token列表和用户列表 */
  useEffect(() => {
    loadData();
    userApi.getPage(1, 1000).then((res: any) => {
      if (res.code === 200) setUsers(res.data?.records || []);
    });
  }, []);

  /** 加载Token列表（支持按用户和状态筛选） */
  const loadData = (page = 1, size = 10, userId?: number, status?: number) => {
    setLoading(true);
    const params: Record<string, any> = {};
    if (userId) params.userId = userId;
    if (status !== undefined) params.status = status;
    tokenApi.getPage(page, size, params)
      .then((res: any) => {
        if (res.code === 200) {
          setData(res.data?.records || []);
          setPagination({ current: res.data?.current || 1, pageSize: res.data?.size || 10, total: res.data?.total || 0 });
        }
      })
      .finally(() => setLoading(false));
  };

  /** 吊销指定Token */
  const handleRevoke = async (id: string) => {
    try {
      await tokenApi.revoke(id);
      message.success('已吊销');
      loadData();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 踢出用户所有会话 */
  const handleKickOut = async (userId: number) => {
    try {
      await tokenApi.kickOut(userId);
      message.success('已踢出');
      loadData();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 清理所有过期Token */
  const handleCleanExpired = async () => {
    try {
      await tokenApi.cleanExpired();
      message.success('已清理过期Token');
      loadData();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 表格列定义 */
  const columns = [
    { title: '用户ID', dataIndex: 'userId', key: 'userId', width: 80 },
    { title: '用户', dataIndex: 'username', key: 'username' },
    { title: 'Token', dataIndex: 'token', key: 'token', ellipsis: true, render: (v: string) => `${v.substring(0, 20)}...` },
    { title: '登录IP', dataIndex: 'loginIp', key: 'loginIp' },
    { title: '过期时间', dataIndex: 'expireTime', key: 'expireTime', render: (v: string) => v ? new Date(v).toLocaleString() : '-' },
    { title: '状态', dataIndex: 'status', key: 'status', render: (v: number) => <Tag color={v === 1 ? 'green' : 'red'}>{v === 1 ? '有效' : '已吊销'}</Tag> },
    { title: '创建时间', dataIndex: 'createTime', key: 'createTime', render: (v: string) => v ? new Date(v).toLocaleString() : '-' },
    {
      title: '操作',
      key: 'action',
      width: 160,
      render: (_: any, r: Token) => (
        <Space>
          {/* 仅对有效状态的Token显示踢出和吊销操作 */}
          {r.status === 1 && (
            <Popconfirm title="确认踢出该用户所有会话?" onConfirm={() => handleKickOut(r.userId)}>
              <Button type="link" danger icon={<LogoutOutlined />}>踢出</Button>
            </Popconfirm>
          )}
          {r.status === 1 && (
            <Popconfirm title="确认吊销此Token?" onConfirm={() => handleRevoke(r.id)}>
              <Button type="link" danger icon={<DeleteOutlined />}>吊销</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card>
        <div className="filter-bar" style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Space>
            <Select placeholder="选择用户" allowClear style={{ width: 200 }} value={selectedUserId} onChange={(v) => { setSelectedUserId(v); loadData(1, pagination.pageSize, v, selectedStatus); }} options={users.map(u => ({ label: u.username, value: u.id }))} />
            <Select placeholder="状态" allowClear style={{ width: 120 }} value={selectedStatus} onChange={(v) => { setSelectedStatus(v); loadData(1, pagination.pageSize, selectedUserId, v); }} options={[{ label: '有效', value: 1 }, { label: '已吊销', value: 0 }]} />
          </Space>
          <Space>
            <Popconfirm title="确认清理所有过期Token?" onConfirm={handleCleanExpired}>
              <Button icon={<DeleteOutlined />}>清理过期</Button>
            </Popconfirm>
          </Space>
        </div>
        <ResizableTable dataSource={data} columns={columns} rowKey="id" loading={loading} scroll={{ x: 'max-content' }} pagination={{ ...pagination, showQuickJumper: true, showSizeChanger: true, pageSizeOptions: ['5', '10', '20', '50', '100'], onChange: (page, pageSize) => loadData(page, pageSize, selectedUserId, selectedStatus) }} />
      </Card>
    </div>
  );
}
