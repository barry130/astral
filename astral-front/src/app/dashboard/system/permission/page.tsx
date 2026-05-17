'use client';

import { useState, useEffect } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Select, message, Popconfirm, Tag } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { request } from '@/api/client';

const { Option } = Select;

export default function PermissionPage() {
  const [permissions, setPermissions] = useState([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingPermission, setEditingPermission] = useState<any>(null);
  const [form] = Form.useForm();

  useEffect(() => {
    fetchPermissions();
  }, []);

  const fetchPermissions = async () => {
    setLoading(true);
    try {
      const res = await request.get('/api/v1/system/permission/page', {
        params: { pageNum: 1, pageSize: 1000 }
      });
      setPermissions(res.data.records || []);
    } catch (error: any) {
      message.error(error.message || '获取权限列表失败');
    } finally {
      setLoading(false);
    }
  };

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
      const res = await request.delete(`/api/v1/system/permission/${id}`);
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
        await request.put(`/api/v1/system/permission/${editingPermission.id}`, values);
        message.success('更新成功');
      } else {
        await request.post('/api/v1/system/permission', values);
        message.success('创建成功');
      }
      setModalVisible(false);
      fetchPermissions();
    } catch (error: any) {
      message.error(error.message || '操作失败');
    }
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '权限编码', dataIndex: 'permissionCode' },
    { title: '权限名称', dataIndex: 'permissionName' },
    { title: '路径', dataIndex: 'url', ellipsis: true },
    { title: '方法', dataIndex: 'method', width: 80 },
    {
      title: '类型',
      dataIndex: 'type',
      width: 80,
      render: (type: number) => {
        const colors: Record<number, string> = { 1: 'blue', 2: 'green', 3: 'orange' };
        const labels: Record<number, string> = { 1: '菜单', 2: '按钮', 3: '接口' };
        return <Tag color={colors[type]}>{labels[type]}</Tag>;
      }
    },
    { title: '图标', dataIndex: 'icon', width: 100 },
    { title: '排序', dataIndex: 'sort', width: 80 },
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
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <h2>权限管理</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>新建权限</Button>
      </div>
      <Table
        columns={columns}
        dataSource={permissions}
        rowKey="id"
        loading={loading}
        pagination={false}
      />
      <Modal
        title={editingPermission ? '编辑权限' : '新建权限'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="permissionCode" label="权限编码" rules={[{ required: true }]}>
            <Input placeholder="如: system:user:view" />
          </Form.Item>
          <Form.Item name="permissionName" label="权限名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="type" label="类型" rules={[{ required: true }]}>
            <Select>
              <Option value={1}>菜单</Option>
              <Option value={2}>按钮</Option>
              <Option value={3}>接口</Option>
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
          <Form.Item name="parentId" label="父级ID">
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
