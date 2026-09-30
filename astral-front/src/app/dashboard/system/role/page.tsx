'use client';

import { useEffect, useState, useMemo } from 'react';
import { Card, Button, Space, Modal, Form, Input, InputNumber, Select, Switch, Tag, message, Popconfirm, TreeSelect, Row, Col } from '@/components/antd-compat';
import { PlusOutlined, EditOutlined, DeleteOutlined, SafetyOutlined } from '@/components/antd-compat/icons';
import { request } from '@/api/client';
import { usePerm } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';
import { fetchDictOptions, DictOption } from '@/api/dict';

const { Option } = Select;

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
  /** 是否超级管理员角色：1=持该角色即拥有全部权限 */
  isSuper?: number;
}

/** 权限实体接口（树形结构；域分组节点的 id 为空） */
interface Permission {
  /** 权限ID（域分组节点为 null） */
  id: number | null;
  /** 权限编码 */
  permissionCode: string;
  /** 权限名称 */
  permissionName: string;
  /** 父级权限ID */
  parentId: number;
  /** 权限类型：1目录 2菜单 3按钮 4接口 5数据 */
  type: number;
  /** 权限域 */
  domain?: string;
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
  /**
   * 获取权限树。
   * groupBy=domain 时按「权限域」分组返回（域节点 id 为空，仅作展示分组，
   * 勾选域会把该域下全部权限一并选中，不会提交出非法 ID）。
   */
  getTree: (groupBy?: 'domain') =>
    request.get('/api/v1/admin/system/permission/tree', { params: groupBy ? { groupBy } : {} }),
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
  /** 权限类型字典（文案来源：数据字典 permission_type） */
  const [typeOptions, setTypeOptions] = useState<DictOption[]>([]);
  /** 角色表单实例 */
  const [form] = Form.useForm();

  const hasPerm = usePerm();
  /** 是否具备角色维护权限：权限码需与后端 @RequiresPermission("admin:system:role:edit") 一致 */
  const canEdit = hasPerm('admin:system:role:edit');

  /** 组件挂载时加载角色列表、权限树（按域分组）与类型字典 */
  useEffect(() => {
    loadData();
    permissionApi.getTree('domain').then((res: any) => {
      if (res.code === 200) setPermissionTree(res.data || []);
    });
    fetchDictOptions(['permission_type'])
      .then((map) => setTypeOptions(map['permission_type'] || []))
      .catch(() => {});
  }, []);

  /** 权限类型文案（字典兜底为原始值） */
  const typeLabel = (type: any): string => {
    const key = String(type ?? '');
    const hit = typeOptions.find((o) => String(o.value) === key);
    return hit ? hit.label : key;
  };

  /** 权限类型标签配色：4=接口 / 5=数据（结果级权限）与菜单树类型区分 */
  const TYPE_COLORS: Record<string, string> = {
    '1': 'blue', '2': 'green', '3': 'orange', '4': 'purple', '5': 'magenta',
  };

  /**
   * 权限树 → TreeSelect 节点。
   * 域分组节点（id 为空）不产出 value：勾选它等于勾选该域下全部权限，
   * 提交给后端的永远是真实权限 ID，不会写入非法值。
   */
  const toTreeNodes = (nodes: Permission[]): any[] =>
    nodes.map((p) => ({
      title: p.id == null
        ? <span className="font-medium">{p.permissionName}</span>
        : (
          <span className="inline-flex items-center gap-1">
            {p.permissionName}
            <Tag color={TYPE_COLORS[String(p.type)] || 'default'}>{typeLabel(p.type)}</Tag>
          </span>
        ),
      value: p.id ?? undefined,
      key: p.id != null ? p.id : `domain-${p.permissionName}`,
      disabled: false,
      children: p.children?.length ? toTreeNodes(p.children) : undefined,
    }));

  /** 当前分配弹窗的树数据（域分组的 value 需为 undefined，见 toTreeNodes） */
  const treeData = useMemo(() => toTreeNodes(permissionTree), [permissionTree, typeOptions]);

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
      title: '超管',
      dataIndex: 'isSuper',
      key: 'isSuper',
      width: 80,
      render: (v: number) => (v === 1
        ? <Tag color="gold">超管</Tag>
        : <Tag color="default">普通</Tag>),
    },
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
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(r)} disabled={!canEdit}>编辑</Button>
          {/* 分配权限后端要求超管，前端不做门控 */}
          <Button
            type="link"
            icon={<SafetyOutlined />}
            disabled={r.isSuper === 1}
            title={r.isSuper === 1 ? '超管角色默认拥有全部权限，无需分配' : undefined}
            onClick={() => handleAssignPermissions(r)}
          >
            权限
          </Button>
          <Popconfirm title="确认删除?" disabled={!canEdit} onConfirm={() => handleDelete(r.id)}>
            <Button type="link" danger icon={<DeleteOutlined />} disabled={!canEdit}>删除</Button>
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
          {canEdit && <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新建角色</Button>}
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
          <Form.Item
            name="isSuper"
            label="超级管理员角色"
            initialValue={0}
            extra="超管角色无需分配权限即拥有全部权限（后端合成 *:*:*）；仅超级管理员可创建/修改"
          >
            <Select style={{ width: 120 }}>
              <Option value={0}>否</Option>
              <Option value={1}>是</Option>
            </Select>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`分配权限${targetRole ? ` - ${targetRole.roleName}` : ''}`}
        open={permModalVisible}
        onOk={handleAssignPermissionsSubmit}
        onCancel={() => setPermModalVisible(false)}
        width={640}
      >
        <div style={{ marginTop: 16 }}>
          <div style={{ marginBottom: 8, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, color: 'var(--muted-foreground, #888)', fontSize: 12 }}>
            <span>按「权限域」分组；接口/数据类权限不参与左侧菜单渲染。勾选域名可全选该域权限。</span>
            <span style={{ flexShrink: 0 }}>已选 {selectedPerms.length} 项</span>
          </div>
          {/* overflowX: hidden —— 弹窗内绝不出现横向滚动条；宽度问题由 TreeSelect 自身换行消化 */}
          <div style={{ maxHeight: 420, overflowY: 'auto', overflowX: 'hidden', border: '1px solid var(--border, #e5e7eb)', borderRadius: 6, padding: 8 }}>
            <TreeSelect
              treeData={treeData}
              treeCheckable
              showCheckedStrategy="SHOW_ALL"
              allowClear
              placeholder="选择权限"
              style={{ width: '100%' }}
              value={selectedPerms}
              onChange={(val) => setSelectedPerms(val || [])}
            />
          </div>
        </div>
      </Modal>
    </div>
  );
}
