'use client';

import { useEffect, useState } from 'react';
import { Card, Button, Space, Modal, Form, Input, Select, Switch, Tag, message, Popconfirm, Tabs, Divider, Typography } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, KeyOutlined, LockOutlined, LoginOutlined } from '@ant-design/icons';
import { request, ApiResult } from '@/api/client';
import { ResizableTable } from '@/components/ResizableTable';

/** 用户实体接口 */
interface User {
  /** 用户ID */
  id: number;
  /** 用户名 */
  username: string;
  /** 用户昵称 */
  nickname: string;
  /** 邮箱 */
  email: string;
  /** 手机号 */
  phone: string;
  /** 头像地址 */
  avatar?: string;
  /** 状态：1启用/0禁用 */
  status: number;
  /** 用户类型：ADMIN 管理端 / APP 轻听App */
  userType?: string;
  /** 密码（仅创建时需要） */
  password?: string;
  /** 创建时间 */
  createTime: string;
  /** 更新时间 */
  updateTime: string;
}

/** 角色实体接口 */
interface Role {
  /** 角色ID */
  id: number;
  /** 角色编码 */
  roleCode: string;
  /** 角色名称 */
  roleName: string;
}

/** 用户管理API封装 */
const userApi = {
  /** 分页查询用户 */
  getPage: (pageNum: number, pageSize: number, params?: Record<string, any>) =>
    request.get<ApiResult<any>>('/api/v1/admin/system/user/page', { params: { pageNum, pageSize, ...params } }),
  /** 创建用户 */
  create: (data: any) => request.post('/api/v1/admin/system/user', data),
  /** 更新用户 */
  update: (id: number, data: any) => request.put(`/api/v1/admin/system/user/${id}`, data),
  /** 删除用户 */
  delete: (id: number) => request.delete(`/api/v1/admin/system/user/${id}`),
  /** 重置用户密码 */
  resetPassword: (id: number, password: string) => request.put(`/api/v1/admin/system/user/${id}/password`, { password }),
  /** 更新用户状态 */
  updateStatus: (id: number, status: number) => request.put(`/api/v1/admin/system/user/${id}/status`, null, { params: { status } }),
  /** 踢下线 */
  kickOut: (id: number) => request.put(`/api/v1/admin/system/user/${id}/kick`),
  /** 为用户分配角色 */
  assignRoles: (id: number, roleIds: number[]) => request.put(`/api/v1/admin/system/user/${id}/roles`, { roleIds }),
  /** 获取用户已有角色 */
  getUserRoles: (id: number) => request.get<Role[]>(`/api/v1/admin/system/user/${id}/roles`),
};

/** 角色管理API封装 */
const roleApi = {
  /** 获取所有角色（用于角色分配下拉） */
  getAll: () => request.get<Role[]>('/api/v1/admin/system/role/all'),
};

/**
 * 用户管理页面组件
 * 提供用户的增删改查、密码重置、角色分配、状态切换功能
 * 编辑入口为完整的用户管理面板（基本信息 / 角色 / 账户操作）
 */
export default function UserPage() {
  /** 加载状态 */
  const [loading, setLoading] = useState(false);
  /** 用户列表数据 */
  const [data, setData] = useState<User[]>([]);
  /** 分页状态 */
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  /** 搜索关键词 */
  const [search, setSearch] = useState('');
  /** 用户类型筛选：''全部 / ADMIN / APP */
  const [typeFilter, setTypeFilter] = useState('');
  /** 新建用户弹窗显示状态 */
  const [createVisible, setCreateVisible] = useState(false);
  /** 编辑用户面板显示状态 */
  const [editVisible, setEditVisible] = useState(false);
  /** 当前编辑的用户 */
  const [editingUser, setEditingUser] = useState<User | null>(null);
  /** 编辑面板当前 Tab */
  const [activeTab, setActiveTab] = useState('info');
  /** 所有角色列表 */
  const [roles, setRoles] = useState<Role[]>([]);
  /** 用户已有角色ID列表 */
  const [userRoles, setUserRoles] = useState<number[]>([]);
  /** 用户表单实例 */
  const [form] = Form.useForm();
  /** 新建用户表单实例 */
  const [createForm] = Form.useForm();
  /** 密码表单实例 */
  const [passwordForm] = Form.useForm();
  /** 角色表单实例 */
  const [roleForm] = Form.useForm();

  /** 组件挂载时加载用户列表和角色列表 */
  useEffect(() => {
    loadData();
    roleApi.getAll().then((res: any) => {
      if (res.code === 200) setRoles(res.data || []);
    });
  }, []);

  /** 加载用户列表 */
  const loadData = (page = 1, size = 10, keyword = '', utype = typeFilter) => {
    setLoading(true);
    const params: Record<string, any> = {};
    if (keyword) params.username = keyword;
    if (utype) params.userType = utype;
    userApi.getPage(page, size, params)
      .then((res: any) => {
        if (res.code === 200) {
          setData(res.data?.records || []);
          setPagination({ current: res.data?.current || 1, pageSize: res.data?.size || 10, total: res.data?.total || 0 });
        }
      })
      .finally(() => setLoading(false));
  };

  /** 打开新建用户弹窗 */
  const handleCreate = () => {
    createForm.resetFields();
    createForm.setFieldsValue({ userType: 'ADMIN' });
    setCreateVisible(true);
  };

  /** 打开编辑用户面板，加载基本信息、角色、密码重置默认 */
  const openEdit = async (record: User) => {
    setEditingUser(record);
    form.setFieldsValue(record);
    setActiveTab('info');
    // 加载该用户已有角色
    try {
      const res: any = await userApi.getUserRoles(record.id);
      if (res.code === 200) {
        setUserRoles((res.data || []).map((r: Role) => r.id));
        roleForm.setFieldsValue({ roleIds: (res.data || []).map((r: Role) => r.id) });
      } else {
        setUserRoles([]);
        roleForm.setFieldsValue({ roleIds: [] });
      }
    } catch {
      setUserRoles([]);
      roleForm.setFieldsValue({ roleIds: [] });
    }
    passwordForm.resetFields();
    setEditVisible(true);
  };

  /** 提交编辑（基本信息 + 用户类型一并保存） */
  const handleSubmitInfo = async () => {
    if (!editingUser) return;
    try {
      const values = await form.validateFields();
      await userApi.update(editingUser.id, values);
      message.success('保存成功');
      setEditingUser((prev) => prev ? { ...prev, ...values } : prev);
      loadData();
    } catch (error: any) {
      if (error?.errorFields) return; // 表单校验失败
      message.error(error.message);
    }
  };

  /** 创建用户 */
  const handleCreateSubmit = async () => {
    try {
      const values = await createForm.validateFields();
      await userApi.create(values);
      message.success('创建成功');
      setCreateVisible(false);
      loadData();
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(error.message);
    }
  };

  /** 删除用户 */
  const handleDelete = async (id: number) => {
    try {
      await userApi.delete(id);
      message.success('删除成功');
      setEditVisible(false);
      setEditingUser(null);
      loadData();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 提交密码重置 */
  const handleResetPasswordSubmit = async () => {
    if (!editingUser) return;
    try {
      const values = await passwordForm.validateFields();
      await userApi.resetPassword(editingUser.id, values.password);
      message.success('密码重置成功');
      passwordForm.resetFields();
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(error.message);
    }
  };

  /** 切换用户启用/禁用状态 */
  const handleToggleStatus = async (id: number, checked: boolean) => {
    try {
      await userApi.updateStatus(id, checked ? 1 : 0);
      message.success(checked ? '已启用' : '已禁用');
      loadData();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 提交角色分配 */
  const handleAssignRolesSubmit = async () => {
    if (!editingUser) return;
    try {
      const values = await roleForm.validateFields();
      await userApi.assignRoles(editingUser.id, values.roleIds || []);
      setUserRoles(values.roleIds || []);
      message.success('角色分配成功');
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(error.message);
    }
  };

  /** 踢下线 */
  const handleKickOut = async () => {
    if (!editingUser) return;
    try {
      await userApi.kickOut(editingUser.id);
      message.success('已踢下线');
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 表格列定义 */
  const columns = [
    { title: '用户ID', dataIndex: 'id', key: 'id', width: 80 },
    { title: '用户名', dataIndex: 'username', key: 'username' },
    { title: '昵称', dataIndex: 'nickname', key: 'nickname' },
    { title: '邮箱', dataIndex: 'email', key: 'email' },
    { title: '手机号', dataIndex: 'phone', key: 'phone' },
    {
      title: '用户类型',
      dataIndex: 'userType',
      key: 'userType',
      render: (v: string) => (
        v === 'APP'
          ? <Tag color="blue">App用户</Tag>
          : v === 'ADMIN' ? <Tag color="purple">管理端</Tag> : <Tag>{v || '-'}</Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (v: number, r: User) => (
        <Switch checked={v === 1} onChange={(checked) => handleToggleStatus(r.id, checked)} checkedChildren="启用" unCheckedChildren="禁用" />
      ),
    },
    { title: '创建时间', dataIndex: 'createTime', key: 'createTime', render: (v: string) => new Date(v).toLocaleString() },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_: any, r: User) => (
        <Space>
          <Button type="link" icon={<EditOutlined />} onClick={() => openEdit(r)}>编辑</Button>
        </Space>
      ),
    },
  ];

  /** 编辑面板 Tab 配置 */
  const editTabItems = editingUser ? [
    {
      key: 'info',
      label: '基本信息',
      children: (
        <Form form={form} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item name="userType" label="用户类型" extra="修改后保存可变更用户归属（管理端/App）">
            <Select disabled={editingUser.userType === 'ADMIN' && editingUser.username === 'admin'} options={[
              { label: '管理端', value: 'ADMIN' },
              { label: 'App用户', value: 'APP' },
            ]} />
          </Form.Item>
          <Form.Item name="username" label="用户名">
            <Input disabled />
          </Form.Item>
          <Form.Item name="nickname" label="昵称"><Input /></Form.Item>
          <Form.Item name="avatar" label="头像地址"><Input placeholder="头像图片URL" /></Form.Item>
          <Form.Item name="email" label="邮箱"><Input /></Form.Item>
          <Form.Item name="phone" label="手机号"><Input /></Form.Item>
          <Space>
            <Button type="primary" onClick={handleSubmitInfo}>保存</Button>
          </Space>
        </Form>
      ),
    },
    {
      key: 'roles',
      label: '角色',
      children: (
        <Form form={roleForm} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item name="roleIds" label="分配角色" rules={[{ required: true, message: '请选择角色' }]}>
            <Select mode="multiple" placeholder="选择角色" options={roles.map(r => ({ label: r.roleName, value: r.id }))} />
          </Form.Item>
          <Button type="primary" onClick={handleAssignRolesSubmit}>保存角色</Button>
        </Form>
      ),
    },
    {
      key: 'account',
      label: '账户操作',
      children: (
        <div>
          <Typography.Title level={5}>账号状态</Typography.Title>
          <Space direction="vertical" style={{ width: '100%' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <span>{editingUser.status === 1 ? '当前状态：已启用' : '当前状态：已封禁'}</span>
              {editingUser.status === 1
                ? <Popconfirm title="确认封禁该用户？封禁后其将被强制下线" onConfirm={() => handleToggleStatus(editingUser.id, false)}>
                    <Button danger icon={<LockOutlined />}>封禁</Button>
                  </Popconfirm>
                : <Button icon={<DeleteOutlined />} onClick={() => handleToggleStatus(editingUser.id, true)}>解封</Button>}
            </div>
            <Button icon={<LoginOutlined />} onClick={handleKickOut}>踢下线</Button>
          </Space>

          <Divider />
          <Typography.Title level={5}>重置密码</Typography.Title>
          <Form form={passwordForm} layout="vertical">
            <Form.Item name="password" label="新密码" rules={[{ required: true, min: 6, message: '密码至少6位' }]}>
              <Input.Password />
            </Form.Item>
            <Button icon={<KeyOutlined />} onClick={handleResetPasswordSubmit}>重置密码</Button>
          </Form>

          <Divider />
          <Typography.Title level={5} type="danger">危险操作</Typography.Title>
          <Popconfirm title="确认删除该用户?" onConfirm={() => handleDelete(editingUser.id)}>
            <Button danger icon={<DeleteOutlined />}>删除该用户</Button>
          </Popconfirm>
        </div>
      ),
    },
  ] : [];

  return (
    <div>
      <Card>
        <div className="filter-bar" style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', gap: 12 }}>
          <div style={{ display: 'flex', gap: 12 }}>
            <Input.Search placeholder="搜索用户名" allowClear onSearch={(v) => loadData(1, pagination.pageSize, v)} style={{ width: 300 }} />
            <Select
              value={typeFilter}
              style={{ width: 140 }}
              allowClear
              placeholder="用户类型"
              options={[
                { label: '管理端', value: 'ADMIN' },
                { label: 'App用户', value: 'APP' },
              ]}
              onChange={(v) => { setTypeFilter(v || ''); loadData(1, pagination.pageSize, search, v || ''); }}
            />
          </div>
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新建用户</Button>
        </div>
        <ResizableTable dataSource={data} columns={columns} rowKey="id" loading={loading} scroll={{ x: 'max-content' }} pagination={{ ...pagination, onChange: (p, ps) => loadData(p, ps, search), showQuickJumper: true, showSizeChanger: true, pageSizeOptions: ['5', '10', '20', '50', '100'] }} />
      </Card>

      {/* 新建用户弹窗 */}
      <Modal title="新建用户" open={createVisible} onOk={handleCreateSubmit} onCancel={() => setCreateVisible(false)}>
        <Form form={createForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="userType" label="用户类型" rules={[{ required: true }]}>
            <Select options={[
              { label: '管理端', value: 'ADMIN' },
              { label: 'App用户', value: 'APP' },
            ]} />
          </Form.Item>
          <Form.Item name="username" label="用户名" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, min: 6, message: '密码至少6位' }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item name="nickname" label="昵称"><Input /></Form.Item>
          <Form.Item name="email" label="邮箱"><Input /></Form.Item>
          <Form.Item name="phone" label="手机号"><Input /></Form.Item>
        </Form>
      </Modal>

      {/* 编辑用户完整面板 */}
      <Modal
        title={editingUser ? `编辑用户：${editingUser.username}` : '编辑用户'}
        open={editVisible}
        width={560}
        footer={null}
        onCancel={() => setEditVisible(false)}
      >
        <Tabs activeKey={activeTab} onChange={setActiveTab} items={editTabItems} />
      </Modal>
    </div>
  );
}
