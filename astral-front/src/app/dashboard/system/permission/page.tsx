'use client';

import { useState, useEffect, useMemo } from 'react';
import { Button, Modal, Form, Input, InputNumber, Select, message, Popconfirm, Tag } from '@/components/antd-compat';
import { PlusOutlined, EditOutlined, DeleteOutlined, ReloadOutlined } from '@/components/antd-compat/icons';
import { request } from '@/api/client';
import { ResizableTable } from '@/components/ResizableTable';
import { fetchDictOptions, DictOption } from '@/api/dict';
import { PermissionSideTag, SIDE_META, sideOfCode } from '@/lib/permission-side';

const { Option } = Select;

/** 权限类型字典编码（文案来自数据字典 permission_type，禁止前端硬编码） */
const PERMISSION_TYPE_DICT = 'permission_type';

/** 类型标签配色：目录/菜单/按钮 属菜单树；接口/数据 属后端权限（不参与导航渲染） */
const TYPE_COLORS: Record<string, string> = {
  '1': 'blue',
  '2': 'green',
  '3': 'orange',
  '4': 'purple',
  '5': 'magenta',
};

export default function PermissionPage() {
  const [permissions, setPermissions] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingPermission, setEditingPermission] = useState<any>(null);
  const [typeOptions, setTypeOptions] = useState<DictOption[]>([]);
  const [domainFilter, setDomainFilter] = useState<string | undefined>(undefined);
  const [typeFilter, setTypeFilter] = useState<string | undefined>(undefined);
  const [sideFilter, setSideFilter] = useState<string | undefined>(undefined);
  const [keyword, setKeyword] = useState('');
  const [form] = Form.useForm();

  useEffect(() => {
    fetchPermissions();
    fetchDictOptions([PERMISSION_TYPE_DICT])
      .then((map) => setTypeOptions(map[PERMISSION_TYPE_DICT] || []))
      .catch(() => {});
  }, []);

  const typeLabel = (type: any): string => {
    const key = String(type ?? '');
    const hit = typeOptions.find((o) => String(o.value) === key);
    return hit ? hit.label : key || '-';
  };

  const fetchPermissions = async () => {
    setLoading(true);
    try {
      const res = await request.get('/api/v1/admin/system/permission/page', {
        params: { pageNum: 1, pageSize: 1000 }
      });
      setPermissions(res.data.records || []);
    } catch (error: any) {
      message.error(error.message || '获取权限列表失败');
    } finally {
      setLoading(false);
    }
  };

  /** 权限域：后端 domain 字段为准，缺失时回退权限码首段 */
  const domainOf = (record: any): string => {
    if (record?.domain) return record.domain;
    const code: string = record?.permissionCode || '';
    const idx = code.indexOf(':');
    return idx > 0 ? code.slice(0, idx) : 'system';
  };

  /** 域下拉选项：从现有权限里汇总（权限域是数据驱动的，不登记字典） */
  const domainOptions = useMemo(() => {
    const set = new Set<string>();
    permissions.forEach((p) => set.add(domainOf(p)));
    return Array.from(set).sort();
  }, [permissions]);

  /** 过滤 + 按「域 → 端 → 类型 → 权限码」排序，同域权限天然聚在一起 */
  const dataSource = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    return permissions
      .filter((p) => (domainFilter ? domainOf(p) === domainFilter : true))
      .filter((p) => (typeFilter ? String(p.type) === typeFilter : true))
      .filter((p) => (sideFilter ? sideOfCode(p.permissionCode) === sideFilter : true))
      .filter((p) => {
        if (!kw) return true;
        return String(p.permissionCode || '').toLowerCase().includes(kw)
          || String(p.permissionName || '').toLowerCase().includes(kw);
      })
      .sort((a, b) => {
        const d = domainOf(a).localeCompare(domainOf(b));
        if (d !== 0) return d;
        const sa = sideOfCode(a.permissionCode) ?? 'zz';
        const sb = sideOfCode(b.permissionCode) ?? 'zz';
        if (sa !== sb) return sa.localeCompare(sb);
        const t = (a.type ?? 0) - (b.type ?? 0);
        if (t !== 0) return t;
        return String(a.permissionCode || '').localeCompare(String(b.permissionCode || ''));
      });
  }, [permissions, domainFilter, typeFilter, sideFilter, keyword]);

  const handleAdd = () => {
    setEditingPermission(null);
    form.resetFields();
    setModalVisible(true);
  };

  const handleEdit = (record: any) => {
    setEditingPermission(record);
    form.setFieldsValue(record);
    setModalVisible(true);
  };

  const handleDelete = async (id: number) => {
    try {
      await request.delete(`/api/v1/admin/system/permission/${id}`);
      message.success('删除成功');
      fetchPermissions();
    } catch (error: any) {
      message.error(error.message || '删除失败');
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editingPermission) {
        await request.put(`/api/v1/admin/system/permission/${editingPermission.id}`, values);
        message.success('更新成功');
      } else {
        await request.post('/api/v1/admin/system/permission', values);
        message.success('创建成功');
      }
      setModalVisible(false);
      fetchPermissions();
    } catch (error: any) {
      message.error(error.message || '操作失败');
    }
  };

  const columns = [
    {
      title: '权限域',
      dataIndex: 'domain',
      width: 110,
      render: (_: any, record: any) => <Tag color="cyan">{domainOf(record)}</Tag>,
    },
    {
      title: '端',
      dataIndex: 'permissionCode',
      width: 90,
      render: (_: any, record: any) => <PermissionSideTag code={record.permissionCode} />,
    },
    { title: '权限编码', dataIndex: 'permissionCode', width: 260 },
    { title: '权限名称', dataIndex: 'permissionName' },
    {
      title: '类型',
      dataIndex: 'type',
      width: 90,
      render: (type: number) => (
        <Tag color={TYPE_COLORS[String(type)] || 'default'}>{typeLabel(type)}</Tag>
      ),
    },
    { title: '路径', dataIndex: 'url', ellipsis: true },
    { title: '方法', dataIndex: 'method', width: 80 },
    { title: '排序', dataIndex: 'sort', width: 70 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 80,
      render: (status: number) => (
        <Tag color={status === 1 ? 'success' : 'default'}>{status === 1 ? '启用' : '禁用'}</Tag>
      ),
    },
    {
      title: '操作',
      width: 150,
      render: (_: any, record: any) => (
        <>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(record)}>编辑</Button>
          <Popconfirm title="确定删除?" onConfirm={() => handleDelete(record.id)}>
            <Button type="link" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </>
      )
    }
  ];

  return (
    <div>
      <div className="filter-bar" style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
        <h2 className="page-title">权限管理</h2>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <Input
            placeholder="搜索权限编码/名称"
            allowClear
            style={{ width: 220 }}
            value={keyword}
            onChange={(e: any) => setKeyword(e.target.value)}
          />
          <Select
            placeholder="权限域"
            allowClear
            style={{ width: 140 }}
            value={domainFilter}
            onChange={(v: string | undefined) => setDomainFilter(v)}
          >
            {domainOptions.map((d) => <Option key={d} value={d}>{d}</Option>)}
          </Select>
          <Select
            placeholder="端"
            allowClear
            style={{ width: 110 }}
            value={sideFilter}
            onChange={(v: string | undefined) => setSideFilter(v)}
          >
            {Object.entries(SIDE_META).map(([value, meta]) => (
              <Option key={value} value={value}>{meta.label}</Option>
            ))}
          </Select>
          <Select
            placeholder="类型"
            allowClear
            style={{ width: 120 }}
            value={typeFilter}
            onChange={(v: string | undefined) => setTypeFilter(v)}
          >
            {typeOptions.map((o) => <Option key={o.value} value={String(o.value)}>{o.label}</Option>)}
          </Select>
          <Button icon={<ReloadOutlined />} onClick={fetchPermissions}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>新建权限</Button>
        </div>
      </div>
      <ResizableTable
        columns={columns}
        dataSource={dataSource}
        rowKey="id"
        loading={loading}
        pagination={false}
        scroll={{ x: 'max-content' }}
      />
      <Modal
        title={editingPermission ? '编辑权限' : '新建权限'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="permissionCode" label="权限编码" rules={[{ required: true }]}
            extra="规范：端:域:资源:操作[:范围]，端=admin(管理端)/user(用户端)/all(通用)，如 admin:system:user:view、user:qt:update:channel:beta。全小写；范围段用于结果级权限">
            <Input placeholder="如: admin:system:user:view" />
          </Form.Item>
          <Form.Item name="permissionName" label="权限名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="domain" label="权限域" extra="留空按权限码首段归域（管理端按域分组展示）">
            <Input placeholder="如: system / qt / storage" />
          </Form.Item>
          <Form.Item name="type" label="类型" rules={[{ required: true }]}
            extra="接口=控制 API 可达性；数据=结果级权限（同一接口按人群返回不同结果）">
            <Select>
              {typeOptions.map((o) => <Option key={o.value} value={Number(o.value)}>{o.label}</Option>)}
            </Select>
          </Form.Item>
          <Form.Item name="url" label="路径">
            <Input placeholder="/api/v1/..." />
          </Form.Item>
          <Form.Item name="method" label="方法">
            <Select allowClear>
              <Option value="GET">GET</Option>
              <Option value="POST">POST</Option>
              <Option value="PUT">PUT</Option>
              <Option value="DELETE">DELETE</Option>
            </Select>
          </Form.Item>
          <Form.Item name="parentId" label="父级ID" extra="留空或 0 表示顶级；仅菜单树（目录/菜单/按钮）需要挂父级">
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="icon" label="图标">
            <Input placeholder="如: UserOutlined" />
          </Form.Item>
          <Form.Item name="sort" label="排序" initialValue={0}>
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="status" label="状态" initialValue={1}>
            <Select>
              <Option value={1}>启用</Option>
              <Option value={0}>禁用</Option>
            </Select>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
