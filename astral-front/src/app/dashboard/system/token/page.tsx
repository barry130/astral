'use client';

import { useCallback, useEffect, useState } from 'react';
import { formatDateTime } from '@/lib/format';
import { Card, Button, Space, Tag, message, Popconfirm, Select } from '@/components/antd-compat';
import { DeleteOutlined, LogoutOutlined, RollbackOutlined } from '@/components/antd-compat/icons';
import { tokenApi, userApi, type Token, type SysUser as User } from '@/api/system-admin';
import { AdminLoadNotice } from '@/components/admin/AdminLoadNotice';
import {
  useAdminLoader,
  useAdminMutation,
  useAdminQuery,
  useAdminTask,
} from '@/components/admin/use-admin-task';
import { requireAdminSuccess, adminErrorMessage } from '@/components/admin/admin-task';
import {
  loadAdminDictOptions as fetchDictOptions,
  dictLabel,
  dictColor,
  DictOption,
} from '@/components/admin/admin-dictionaries';
import { usePerm } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';
import { PageHeader } from '@/components/page-header';
import { FilterBar } from '@/components/filter-bar';

/**
 * Token管理页面组件
 * 提供Token会话列表查看、吊销、踢出用户、清理过期Token功能
 */
export default function TokenPage() {
  /** 加载状态 */
  const { loading, error: loadError, run: runLoad } = useAdminLoader('加载会话列表失败');
  const { busy: mutating, run: runMutation } = useAdminMutation('会话操作失败');
  const userTask = useAdminTask();
  const query = useAdminQuery<{
    current: number;
    pageSize: number;
    userId?: number;
    status?: number;
  }>({ current: 1, pageSize: 10 });
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
  /** Token 状态枚举一律取数据字典 */
  const [dicts, setDicts] = useState<Record<string, DictOption[]>>({});
  const tokenStatusDict = dicts.token_status;

  const hasPerm = usePerm();
  /** 是否具备 Token 维护权限：权限码需与后端 @RequiresPermission("admin:system:token:edit") 一致 */
  const canEdit = hasPerm('admin:system:token:edit');
  /** 全端会话重置属全局高危操作，与后端 @RequiresSuper 对齐 */
  const isSuper = hasPerm('*:*:*');

  const loadData = useCallback(
    (
      patch: Partial<{ current: number; pageSize: number; userId?: number; status?: number }> = {},
    ) => {
      const q = query.read(patch);
      return runLoad(
        async () => {
          const page = requireAdminSuccess(
            await tokenApi.getPage(q.current, q.pageSize, { userId: q.userId, status: q.status }),
            '加载会话列表失败',
          );
          if (!page) throw new Error('会话分页数据缺失');
          return page;
        },
        (page) => {
          setData(page.records || []);
          setPagination({
            current: page.current || q.current,
            pageSize: page.size || q.pageSize,
            total: page.total || 0,
          });
        },
      );
    },
    [query, runLoad],
  );

  const loadUsers = useCallback(
    (keyword = '') =>
      userTask.run(
        async () => {
          const page = requireAdminSuccess(
            await userApi.getPage(1, 50, { username: keyword || undefined }),
            '加载用户筛选失败',
          );
          return page?.records || [];
        },
        {
          onSuccess: setUsers,
          onError: (error) => message.error(adminErrorMessage(error, '加载用户筛选失败')),
        },
      ),
    [userTask],
  );

  useEffect(() => {
    void loadData();
    void loadUsers();
    let active = true;
    fetchDictOptions(['token_status', 'user_type'])
      .then((map) => {
        if (active) setDicts(map);
      })
      .catch(() => {
        /* Unknown labels retain their raw values. */
      });
    return () => {
      active = false;
    };
  }, [loadData, loadUsers]);

  const handleRevoke = (id: string) => {
    if (!canEdit) return;
    return runMutation(
      async () => requireAdminSuccess(await tokenApi.revoke(id), '吊销失败'),
      () => {
        message.success('已吊销');
        void loadData();
      },
    );
  };
  const handleKickOut = (userId: number) => {
    if (!canEdit) return;
    return runMutation(
      async () => requireAdminSuccess(await tokenApi.kickOut(userId), '踢出失败'),
      () => {
        message.success('已踢出');
        void loadData();
      },
    );
  };
  const handleCleanExpired = () => {
    if (!canEdit) return;
    return runMutation(
      async () => requireAdminSuccess(await tokenApi.cleanExpired(), '清理失败'),
      () => {
        message.success('已清理过期会话');
        void loadData();
      },
    );
  };
  const handleRevokeAll = () => {
    if (!isSuper) return;
    return runMutation(
      async () => requireAdminSuccess(await tokenApi.revokeAll(), '会话重置失败'),
      () => {
        message.success('已重置全部会话，请重新登录');
        window.location.href = '/login';
      },
    );
  };

  const renderDevice = (v?: string) => (
    <Tag color={dictColor(dicts.user_type, v) || 'default'}>
      {dictLabel(dicts.user_type, v, v || '-')}
    </Tag>
  );

  /** 表格列定义 */
  const columns = [
    { title: '用户ID', dataIndex: 'userId', key: 'userId', width: 80 },
    { title: '用户', dataIndex: 'username', key: 'username' },
    {
      title: '会话指纹',
      dataIndex: 'id',
      key: 'id',
      ellipsis: true,
      render: (v?: string) =>
        typeof v === 'string' ? (v.length > 20 ? `${v.slice(0, 20)}…` : v) : '-',
    },
    { title: '登录IP', dataIndex: 'loginIp', key: 'loginIp' },
    {
      title: '登录端',
      dataIndex: 'device',
      key: 'device',
      width: 90,
      render: (v: string) => renderDevice(v),
    },
    {
      title: '过期时间',
      dataIndex: 'expireTime',
      key: 'expireTime',
      render: (v: string) => (v ? formatDateTime(v) : '-'),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (v: number) => (
        <Tag color={dictColor(tokenStatusDict, v) || 'default'}>
          {dictLabel(tokenStatusDict, v, String(v))}
        </Tag>
      ),
    },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      key: 'createTime',
      render: (v: string) => (v ? formatDateTime(v) : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 160,
      render: (_: unknown, r: Token) => (
        <Space>
          {/* 仅对有效状态的Token显示踢出和吊销操作 */}
          {r.status === 1 && (
            <Popconfirm
              title="确认踢出该用户所有会话?"
              disabled={!canEdit || mutating}
              onConfirm={() => handleKickOut(r.userId)}
            >
              <Button type="link" danger icon={<LogoutOutlined />} disabled={!canEdit || mutating}>
                踢出
              </Button>
            </Popconfirm>
          )}
          {r.status === 1 && (
            <Popconfirm
              title="确认吊销此Token?"
              disabled={!canEdit || mutating}
              onConfirm={() => handleRevoke(r.id)}
            >
              <Button type="link" danger icon={<DeleteOutlined />} disabled={!canEdit || mutating}>
                吊销
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <PageHeader title="Token 管理" description="在线会话与访问令牌的查看与吊销" />
      <Card>
        <FilterBar split>
          <Space>
            <Select
              placeholder="选择用户"
              allowClear
              style={{ width: 200 }}
              value={selectedUserId}
              onChange={(v) => {
                setSelectedUserId(v);
                void loadData({ current: 1, userId: v });
              }}
              filterOption={false}
              onSearch={(keyword) => void loadUsers(keyword)}
              options={users.map((u) => ({ label: u.username, value: u.id }))}
            />
            <Select
              placeholder="状态"
              allowClear
              style={{ width: 120 }}
              value={selectedStatus}
              onChange={(v) => {
                setSelectedStatus(v);
                void loadData({ current: 1, status: v });
              }}
              options={(tokenStatusDict || []).map((o) => ({
                label: o.label,
                value: Number(o.value),
              }))}
            />
          </Space>
          <Space>
            <Popconfirm
              title="确认清理所有过期Token?"
              disabled={!canEdit || mutating}
              onConfirm={handleCleanExpired}
            >
              <Button icon={<DeleteOutlined />} disabled={!canEdit || mutating}>
                清理过期
              </Button>
            </Popconfirm>
            <Popconfirm
              title="全端会话重置会吊销所有在线Token，所有人（包括你自己）都需要重新登录，确认执行？"
              disabled={!isSuper || mutating}
              onConfirm={handleRevokeAll}
            >
              <Button danger icon={<RollbackOutlined />} disabled={!isSuper || mutating}>
                全端会话重置
              </Button>
            </Popconfirm>
          </Space>
        </FilterBar>
        <AdminLoadNotice
          error={loadError}
          hasData={data.length > 0}
          onRetry={() => void loadData()}
        />
        <ResizableTable
          dataSource={data}
          columns={columns}
          rowKey="id"
          loading={loading}
          scroll={{ x: 'max-content' }}
          pagination={{
            ...pagination,
            showQuickJumper: true,
            showSizeChanger: true,
            pageSizeOptions: ['5', '10', '20', '50', '100'],
            onChange: (current, pageSize) => void loadData({ current, pageSize }),
          }}
        />
      </Card>
    </div>
  );
}
