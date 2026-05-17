'use client';

import { useEffect, useState } from 'react';
import { Card, Table, Button, Space, Modal, Form, Input, Select, Tag, message, Popconfirm } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { request } from '@/api/client';

/** 系统配置实体接口 */
interface SysConfig {
  /** 配置ID */
  id: number;
  /** 配置名称 */
  configName: string;
  /** 配置键 */
  configKey: string;
  /** 配置值 */
  configValue: string;
  /** 配置类型：1内置/2自定义 */
  configType: number;
  /** 配置描述 */
  description: string;
  /** 创建时间 */
  createTime: string;
}

/** 系统配置API封装 */
const configApi = {
  /** 分页查询配置 */
  getPage: (pageNum: number, pageSize: number, configName?: string) =>
    request.get('/api/v1/system/config/page', { params: { pageNum, pageSize, configName } }),
  /** 创建配置 */
  create: (data: any) => request.post('/api/v1/system/config', data),
  /** 更新配置 */
  update: (id: number, data: any) => request.put(`/api/v1/system/config/${id}`, data),
  /** 删除配置 */
  delete: (id: number) => request.delete(`/api/v1/system/config/${id}`),
};

/**
 * 系统配置管理页面组件
 * 提供系统配置的增删改查功能，内置配置不可编辑/删除
 */
export default function ConfigPage() {
  /** 加载状态 */
  const [loading, setLoading] = useState(false);
  /** 配置列表数据 */
  const [data, setData] = useState<SysConfig[]>([]);
  /** 分页状态 */
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  /** 新增/编辑弹窗显示状态 */
  const [modalVisible, setModalVisible] = useState(false);
  /** 当前编辑的配置，null表示新增模式 */
  const [editingConfig, setEditingConfig] = useState<SysConfig | null>(null);
  /** 表单实例 */
  const [form] = Form.useForm();

  /** 组件挂载时加载数据 */
  useEffect(() => {
    loadData();
  }, []);

  /** 加载配置列表 */
  const loadData = (page = 1, size = 10, keyword = '') => {
    setLoading(true);
    configApi.getPage(page, size, keyword)
      .then((res: any) => {
        if (res.code === 200) {
          setData(res.data?.records || []);
          setPagination({ current: res.data?.current || 1, pageSize: res.data?.size || 10, total: res.data?.total || 0 });
        }
      })
      .finally(() => setLoading(false));
  };

  /** 打开新增配置弹窗 */
  const handleCreate = () => {
    setEditingConfig(null);
    form.resetFields();
    setModalVisible(true);
  };

  /** 打开编辑配置弹窗 */
  const handleEdit = (record: SysConfig) => {
    setEditingConfig(record);
    form.setFieldsValue(record);
    setModalVisible(true);
  };

  /** 删除配置 */
  const handleDelete = async (id: number) => {
    try {
      await configApi.delete(id);
      message.success('删除成功');
      loadData();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 提交表单：新增或更新 */
  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editingConfig?.id) {
        await configApi.update(editingConfig.id, values);
        message.success('更新成功');
      } else {
        await configApi.create(values);
        message.success('创建成功');
      }
      setModalVisible(false);
      loadData();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 表格列定义 */
  const columns = [
    { title: '配置名称', dataIndex: 'configName', key: 'configName' },
    { title: '配置键', dataIndex: 'configKey', key: 'configKey', render: (v: string) => <code style={{ background: '#f5f5f5', padding: '2px 6px', borderRadius: 3, fontSize: 12 }}>{v}</code> },
    { title: '配置值', dataIndex: 'configValue', key: 'configValue', ellipsis: true },
    { title: '类型', dataIndex: 'configType', key: 'configType', render: (v: number) => <Tag color={v === 1 ? 'blue' : 'orange'}>{v === 1 ? '内置' : '自定义'}</Tag> },
    { title: '描述', dataIndex: 'description', key: 'description' },
    { title: '创建时间', dataIndex: 'createTime', key: 'createTime', render: (v: string) => new Date(v).toLocaleString() },
    {
      title: '操作',
      key: 'action',
      render: (_: any, r: SysConfig) => (
        <Space>
          {/* 内置配置不允许编辑和删除 */}
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(r)} disabled={r.configType === 1}>编辑</Button>
          <Popconfirm title="确认删除?" onConfirm={() => handleDelete(r.id)}>
            <Button type="link" danger icon={<DeleteOutlined />} disabled={r.configType === 1}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
          <Input.Search placeholder="搜索配置名称" allowClear onSearch={(v) => loadData(1, pagination.pageSize, v)} style={{ width: 300 }} />
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新建配置</Button>
        </div>
        <Table dataSource={data} columns={columns} rowKey="id" loading={loading} pagination={pagination} />
      </Card>

      <Modal title={editingConfig ? '编辑配置' : '新建配置'} open={modalVisible} onOk={handleSubmit} onCancel={() => setModalVisible(false)}>
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="configName" label="配置名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="configKey" label="配置键" rules={[{ required: true }]}>
            <Input disabled={!!editingConfig} />
          </Form.Item>
          <Form.Item name="configValue" label="配置值" rules={[{ required: true }]}>
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item name="configType" label="类型" initialValue={2}>
            <Select options={[{ label: '内置', value: 1 }, { label: '自定义', value: 2 }]} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
