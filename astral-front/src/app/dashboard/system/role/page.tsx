'use client';

import { useEffect, useState } from 'react';
import { Card, Button, Space, Modal, Form, Input, InputNumber, Switch, Tag, message, Popconfirm, TreeSelect, Row, Col } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, SafetyOutlined } from '@ant-design/icons';
import { request } from '@/api/client';
import { ResizableTable } from '@/components/ResizableTable';

/** 角色实体接口 */
interface Role {
  /** 角色ID */
  id: number;
  /** 角色编码 */
  roleCode: string;
  /** 角色名称 */
  roleName: string;
  /** 角色描述 */
  description: string;
  /** 状态：1启用/0禁用 */
  status: number;
  /** 排序号 */
  sort: number;
}

/** 权限实体接口（树形结构） */
interface Permission {
  /** 权限ID */
  id: number;
  /** 权限编码 */
  permissionCode: string;
  /** 权限名称 */
  permissionName: string;
  /** 父级权限ID */
  parentId: number;
  /** 权限类型 */
  type: number;
  /** 子权限列表 */
  children?: Permission[];
}

/** 角色管理API封装 */
const roleApi = {
  /** 分页查询角色 */
  getPage: (pageNum: number, pageSize: number, params?: Record<string, any>) =>
    request.get('/api/v1/admin/system/role/page', { params: { pageNum, pageSize, ...params } }),
  /** 获取所有角色 */
  getAll: () => request.get('/api/v1/admin/system/role/all'),
  /** 创建角色 */
  create: (data: any) => request.post('/api/v1/admin/system/role', data),
  /** 更新角色 */
  update: (id: number, data: any) => request.put(`/api/v1/admin/system/role/${id}`, data),
  /** 删除角色 */
  delete: (id: number) => request.delete(`/api/v1/admin/system/role/${id}`),
  /** 获取角色的权限列表 */
  getPermissions: (id: number) => request.get(`/api/v1/admin/system/role/${id}/permissions`),
  /** 为角色分配权限 */
  assignPermissions: (id: number, permissionIds: number[]) => request.put(`/api/v1/admin/system/role/${id}/permissions`, { permissionIds }),
};

/** 权限管理API封装 */
const permissionApi = {
  /** 获取权限树 */
  getTree: () => request.get('/api/v1/admin/system/permission/tree'),
};

/**
 * 角色权限管理页面组件
 * 提供角色的增删改查和权限分配功能
 */
export default function RolePage() {
  /** 加载状态 */
  const [loading, setLoading] = useState(false);
  /** 角色列表数据 */
  const [data, setData] = useState<Role[]>([]);
  /** 分页状态 */
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  /** 角色新增/编辑弹窗显示状态 */
  const [modalVisible, setModalVisible] = useState(false);
  /** 权限分配弹窗显示状态 */
  const [permModalVisible, setPermModalVisible] = useState(false);
  /** 当前编辑的角色 */
  const [editingRole, setEditingRole] = useState<Role | null>(null);
  /** 当前分配权限的目标角色 */
  const [targetRole, setTargetRole] = useState<Role | null>(null);
  /** 权限树数据 */
  const [permissionTree, setPermissionTree] = useState<Permission[]>([]);
  /** 已选中的权限ID列表 */
  const [selectedPerms, setSelectedPerms] = useState<number[]>([]);
  /** 角色表单实例 */
  const [form] = Form.useForm();

  /** 组件挂载时加载角色列表和权限树 */
  useEffect(() => {
    loadData();
    permissionApi.getTree().then((res: any) => {
      if (res.code === 200) setPermissionTree(res.data || []);
    });
  }, []);

  /** 加载角色列表 */
  const loadData = (page = 1, size = 10) => {
    setLoading(true);
    roleApi.getPage(page, size)
      .then((res: any) => {
        if (res.code === 200) {
          setData(res.data?.records || []);
          setPagination({ current: res.data?.current || 1, pageSize: res.data?.size || 10, total: res.data?.total || 0 });
        }
      })
      .finally(() => setLoading(false));
  };

  /** 打开新增角色弹窗 */
  const handleCreate = () => {
    setEditingRole(null);
    form.resetFields();
    setModalVisible(true);
  };

  /** 打开编辑角色弹窗 */
  const handleEdit = (record: Role) => {
    setEditingRole(record);
    form.setFieldsValue(record);
    setModalVisible(true);
  };

  /** 删除角色 */
  const handleDelete = async (id: number) => {
    try {
      await roleApi.delete(id);
      message.success('删除成功');
      loadData();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 提交角色表单 */
  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editingRole?.id) {
        await roleApi.update(editingRole.id, values);
        message.success('更新成功');
      } else {
        await roleApi.create(values);
        message.success('创建成功');
      }
      setModalVisible(false);
      loadData();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 打开权限分配弹窗，加载角色已有权限 */
  const handleAssignPermissions = async (record: Role) => {
    setTargetRole(record);
    const res: any = await roleApi.getPermissions(record.id);
    if (res.code === 200) {
      setSelectedPerms((res.data || []).map((p: Permission) => p.id));
    }
    setPermModalVisible(true);
  };

  /** 提交权限分配 */
  const handleAssignPermissionsSubmit = async () => {
    try {
      if (targetRole) {
        await roleApi.assignPermissions(targetRole.id, selectedPerms);
        message.success('权限分配成功');
        setPermModalVisible(false);
      }
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 表格列定义 */
  const columns = [
    { title: '角色编码', dataIndex: 'roleCode', key: 'roleCode' },
    { title: '角色名称', dataIndex: 'roleName', key: 'roleName' },
    { title: '描述', dataIndex: 'description', key: 'description' },
    { title: '排序', dataIndex: 'sort', key: 'sort' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (v: number) => <Tag color={v === 1 ? 'green' : 'red'}>{v === 1 ? '启用' : '禁用'}</Tag>,
    },
    {
      title: '操作',
      key: 'action',
      width: 160,
      render: (_: any, r: Role) => (
        <Space>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(r)}>编辑</Button>
          <Button type="link" icon={<SafetyOutlined />} onClick={() => handleAssignPermissions(r)}>权限</Button>
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
        <div className="filter-bar" style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
          <Input.Search placeholder="搜索角色" allowClear style={{ width: 300 }} />
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新建角色</Button>
        </div>
        <ResizableTable dataSource={data} columns={columns} rowKey="id" loading={loading} scroll={{ x: 'max-content' }} pagination={{ ...pagination, showQuickJumper: true, showSizeChanger: true, pageSizeOptions: ['5', '10', '20', '50', '100'] }} />
      </Card>

      <Modal title={editingRole ? '编辑角色' : '新建角色'} open={modalVisible} onOk={handleSubmit} onCancel={() => setModalVisible(false)}>
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="roleCode" label="角色编码" rules={[{ required: true }]}>
            <Input disabled={!!editingRole} />
          </Form.Item>
          <Form.Item name="roleName" label="角色名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="描述"><Input.TextArea rows={2} /></Form.Item>
          <Row gutter={[16,16]}>
            <Col xs={{ span: 24 }} md={{ span: 12 }}><Form.Item name="sort" label="排序" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={{ span: 24 }} md={{ span: 12 }}><Form.Item name="status" label="状态" valuePropName="checked" initialValue><Switch checkedChildren="启用" unCheckedChildren="禁用" /></Form.Item></Col>
          </Row>
        </Form>
      </Modal>

      <Modal title="分配权限" open={permModalVisible} onOk={handleAssignPermissionsSubmit} onCancel={() => setPermModalVisible(false)} width={600}>
        <div style={{ marginTop: 16 }}>
          <TreeSelect
            treeData={permissionTree.map(p => ({ title: p.permissionName, value: p.id, children: p.children?.map(c => ({ title: c.permissionName, value: c.id })) }))}
            treeCheckable
            showCheckedStrategy="SHOW_ALL"
            placeholder="选择权限"
            style={{ width: '100%' }}
            value={selectedPerms}
            onChange={(val) => setSelectedPerms(val || [])}
          />
        </div>
      </Modal>
    </div>
  );
}
