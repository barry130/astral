'use client';

import { useEffect, useState } from 'react';
import { Card, Table, Button, Space, Modal, Form, Input, Select, Switch, Tag, message, Popconfirm, InputNumber } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, KeyOutlined, LockOutlined } from '@ant-design/icons';
import { request, ApiResult } from '@/api/client';

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
  /** 状态：1启用/0禁用 */
  status: number;
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
    request.get<ApiResult<any>>('/api/v1/system/user/page', { params: { pageNum, pageSize, ...params } }),
  /** 创建用户 */
  create: (data: any) => request.post('/api/v1/system/user', data),
  /** 更新用户 */
  update: (id: number, data: any) => request.put(`/api/v1/system/user/${id}`, data),
  /** 删除用户 */
  delete: (id: number) => request.delete(`/api/v1/system/user/${id}`),
  /** 重置用户密码 */
  resetPassword: (id: number, password: string) => request.put(`/api/v1/system/user/${id}/password`, { password }),
  /** 更新用户状态 */
  updateStatus: (id: number, status: number) => request.put(`/api/v1/system/user/${id}/status`, { status }),
  /** 为用户分配角色 */
  assignRoles: (id: number, roleIds: number[]) => request.put(`/api/v1/system/user/${id}/roles`, { roleIds }),
  /** 获取用户已有角色 */
  getUserRoles: (id: number) => request.get<Role[]>(`/api/v1/system/user/${id}/roles`),
};

/** 角色管理API封装 */
const roleApi = {
  /** 获取所有角色（用于角色分配下拉） */
  getAll: () => request.get<Role[]>('/api/v1/system/role/all'),
};

/**
 * 用户管理页面组件
 * 提供用户的增删改查、密码重置、角色分配、状态切换功能
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
  /** 用户新增/编辑弹窗显示状态 */
  const [modalVisible, setModalVisible] = useState(false);
  /** 密码重置弹窗显示状态 */
  const [passwordModalVisible, setPasswordModalVisible] = useState(false);
  /** 角色分配弹窗显示状态 */
  const [roleModalVisible, setRoleModalVisible] = useState(false);
  /** 当前编辑的用户 */
  const [editingUser, setEditingUser] = useState<User | null>(null);
  /** 当前操作的目标用户（密码重置/角色分配） */
  const [targetUser, setTargetUser] = useState<User | null>(null);
  /** 所有角色列表 */
  const [roles, setRoles] = useState<Role[]>([]);
  /** 用户已有角色ID列表 */
  const [userRoles, setUserRoles] = useState<number[]>([]);
  /** 用户表单实例 */
  const [form] = Form.useForm();
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
  const loadData = (page = 1, size = 10, keyword = '') => {
    setLoading(true);
    userApi.getPage(page, size, keyword ? { username: keyword } : undefined)
      .then((res: any) => {
        if (res.code === 200) {
          setData(res.data?.records || []);
          setPagination({ current: res.data?.current || 1, pageSize: res.data?.size || 10, total: res.data?.total || 0 });
        }
      })
      .finally(() => setLoading(false));
  };

  /** 打开新增用户弹窗 */
  const handleCreate = () => {
    setEditingUser(null);
    form.resetFields();
    setModalVisible(true);
  };

  /** 打开编辑用户弹窗 */
  const handleEdit = (record: User) => {
    setEditingUser(record);
    form.setFieldsValue(record);
    setModalVisible(true);
  };

  /** 删除用户 */
  const handleDelete = async (id: number) => {
    try {
      await userApi.delete(id);
      message.success('删除成功');
      loadData();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 提交用户表单 */
  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editingUser?.id) {
        await userApi.update(editingUser.id, values);
        message.success('更新成功');
      } else {
        await userApi.create(values);
        message.success('创建成功');
      }
      setModalVisible(false);
      loadData();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 打开密码重置弹窗 */
  const handleResetPassword = (record: User) => {
    setTargetUser(record);
    passwordForm.resetFields();
    setPasswordModalVisible(true);
  };

  /** 提交密码重置 */
  const handleResetPasswordSubmit = async () => {
    try {
      const values = await passwordForm.validateFields();
      if (targetUser) {
        await userApi.resetPassword(targetUser.id, values.password);
        message.success('密码重置成功');
        setPasswordModalVisible(false);
      }
    } catch (error: any) {
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

  /** 打开角色分配弹窗，加载用户已有角色 */
  const handleAssignRoles = async (record: User) => {
    setTargetUser(record);
    const res: any = await userApi.getUserRoles(record.id);
    if (res.code === 200) {
      setUserRoles((res.data || []).map((r: Role) => r.id));
      roleForm.setFieldsValue({ roleIds: (res.data || []).map((r: Role) => r.id) });
    }
    setRoleModalVisible(true);
  };

  /** 提交角色分配 */
  const handleAssignRolesSubmit = async () => {
    try {
      const values = await roleForm.validateFields();
      if (targetUser) {
        await userApi.assignRoles(targetUser.id, values.roleIds || []);
        message.success('角色分配成功');
        setRoleModalVisible(false);
      }
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 表格列定义 */
  const columns = [
    { title: '用户名', dataIndex: 'username', key: 'username' },
    { title: '昵称', dataIndex: 'nickname', key: 'nickname' },
    { title: '邮箱', dataIndex: 'email', key: 'email' },
    { title: '手机号', dataIndex: 'phone', key: 'phone' },
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
      render: (_: any, r: User) => (
        <Space>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(r)}>编辑</Button>
          <Button type="link" icon={<KeyOutlined />} onClick={() => handleAssignRoles(r)}>角色</Button>
          <Button type="link" icon={<LockOutlined />} onClick={() => handleResetPassword(r)}>重置密码</Button>
          <Popconfirm title="确认删除?" onConfirm={() => handleDelete(r.id)}>
            <Button type="link" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
          <Input.Search placeholder="搜索用户名" allowClear onSearch={(v) => loadData(1, pagination.pageSize, v)} style={{ width: 300 }} />
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新建用户</Button>
        </div>
        <Table dataSource={data} columns={columns} rowKey="id" loading={loading} pagination={{ ...pagination, onChange: (p, ps) => loadData(p, ps, search) }} />
      </Card>

      <Modal title={editingUser ? '编辑用户' : '新建用户'} open={modalVisible} onOk={handleSubmit} onCancel={() => setModalVisible(false)}>
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="username" label="用户名" rules={[{ required: true }]}>
            <Input disabled={!!editingUser} />
          </Form.Item>
          {/* 仅新建用户时需要输入密码 */}
          {!editingUser && (
            <Form.Item name="password" label="密码" rules={[{ required: true, min: 6, message: '密码至少6位' }]}>
              <Input.Password />
            </Form.Item>
          )}
          <Form.Item name="nickname" label="昵称"><Input /></Form.Item>
          <Form.Item name="email" label="邮箱"><Input /></Form.Item>
          <Form.Item name="phone" label="手机号"><Input /></Form.Item>
        </Form>
      </Modal>

      <Modal title="重置密码" open={passwordModalVisible} onOk={handleResetPasswordSubmit} onCancel={() => setPasswordModalVisible(false)}>
        <Form form={passwordForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="password" label="新密码" rules={[{ required: true, min: 6, message: '密码至少6位' }]}>
            <Input.Password />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title="分配角色" open={roleModalVisible} onOk={handleAssignRolesSubmit} onCancel={() => setRoleModalVisible(false)}>
        <Form form={roleForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="roleIds" label="角色" rules={[{ required: true }]}>
            <Select mode="multiple" options={roles.map(r => ({ label: r.roleName, value: r.id }))} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
