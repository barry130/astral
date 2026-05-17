'use client';

import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, Select, Switch, message, Popconfirm, Card, Space, InputNumber, Tag, Row, Col } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, ReloadOutlined, SettingOutlined } from '@ant-design/icons';
import { sequenceConfigApi, SequenceConfig } from '@/api/sequenceConfig';
import { sequenceApi, SequenceType } from '@/api/sequence';

/**
 * 序列配置管理页面组件
 * 提供序列配置的增删改查和启用/禁用功能
 */
export default function ConfigPage() {
  /** 配置列表数据 */
  const [data, setData] = useState<SequenceConfig[]>([]);
  /** 加载状态 */
  const [loading, setLoading] = useState(true);
  /** 新增/编辑弹窗显示状态 */
  const [modalVisible, setModalVisible] = useState(false);
  /** 当前编辑的配置ID，null表示新增模式 */
  const [editingId, setEditingId] = useState<number | null>(null);
  /** 可用的序列类型列表 */
  const [types, setTypes] = useState<SequenceType[]>([]);
  /** Ant Design表单实例 */
  const [form] = Form.useForm();

  /** 加载所有配置数据 */
  const loadData = () => {
    setLoading(true);
    sequenceConfigApi.getAll()
      .then((res) => {
        if (res.code === 200) {
          setData(res.data);
        }
      })
      .finally(() => setLoading(false));
  };

  /** 组件挂载时加载配置数据和序列类型 */
  useEffect(() => {
    loadData();
    sequenceApi.getTypes().then((res) => {
      if (res.code === 200) setTypes(res.data);
    });
  }, []);

  /** 打开新增配置弹窗 */
  const handleAdd = () => {
    setEditingId(null);
    form.resetFields();
    setModalVisible(true);
  };

  /** 打开编辑配置弹窗，回填表单数据 */
  const handleEdit = (record: SequenceConfig) => {
    setEditingId(record.id!);
    form.setFieldsValue(record);
    setModalVisible(true);
  };

  /** 删除指定配置 */
  const handleDelete = async (id: number) => {
    try {
      await sequenceConfigApi.delete(id);
      message.success('删除成功');
      loadData();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 切换配置启用/禁用状态 */
  const handleToggle = async (id: number, enabled: boolean) => {
    try {
      await sequenceConfigApi.toggle(id, enabled);
      message.success(enabled ? '已启用' : '已禁用');
      loadData();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 提交表单：根据editingId判断是新增还是更新 */
  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editingId) {
        await sequenceConfigApi.update(editingId, values);
        message.success('更新成功');
      } else {
        await sequenceConfigApi.create(values);
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
    { 
      title: '业务键', 
      dataIndex: 'bizKey', 
      key: 'bizKey', 
      render: (v: string) => <Tag color="blue" style={{ fontWeight: 500 }}>{v}</Tag> 
    },
    { title: '序列类型', dataIndex: 'sequenceType', key: 'sequenceType', render: (v: string) => <Tag>{v}</Tag> },
    { title: '当前值', dataIndex: 'currentValue', key: 'currentValue', render: (v: number) => v ? <code>{v}</code> : '-' },
    { title: '步长', dataIndex: 'step', key: 'step', render: (v: number) => v ?? '-' },
    { 
      title: '状态', 
      dataIndex: 'enabled', 
      key: 'enabled', 
      render: (v: boolean, r: SequenceConfig) => (
        <Switch 
          checked={v} 
          onChange={(checked) => handleToggle(r.id!, checked)}
          checkedChildren="启用"
          unCheckedChildren="禁用"
        />
      )
    },
    { 
      title: '操作', 
      key: 'action', 
      width: 120,
      render: (_: any, r: SequenceConfig) => (
        <Space>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(r)}>
            编辑
          </Button>
          <Popconfirm title="确认删除此配置?" onConfirm={() => handleDelete(r.id!)}>
            <Button type="link" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      )
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 24 }}>
        <h2 className="page-title" style={{ marginBottom: 8 }}>配置管理</h2>
        <p style={{ color: '#909399', margin: 0 }}>管理序列生成器配置信息</p>
      </div>
      
      <Card className="fade-in-up">
        <div style={{ marginBottom: 20, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div>
            <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd} size="large">
              新建配置
            </Button>
          </div>
          <Button icon={<ReloadOutlined />} onClick={loadData} size="large">
            刷新
          </Button>
        </div>
        <Table 
          dataSource={data} 
          columns={columns} 
          rowKey="id" 
          loading={loading}
          pagination={{ 
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条配置`
          }}
        />
      </Card>

      <Modal
        title={
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <SettingOutlined style={{ color: '#4a90d9' }} />
            <span>{editingId ? '编辑配置' : '新建配置'}</span>
          </div>
        }
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        okText="保存"
        cancelText="取消"
        width={520}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 24 }}>
          <Row gutter={16}>
            <Col span={24}>
              <Form.Item name="bizKey" label="业务键" rules={[{ required: true, message: '请输入业务键' }]}>
                <Input placeholder="如: order_id, user_id" style={{ height: 40 }} />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="sequenceType" label="序列类型" rules={[{ required: true, message: '请选择序列类型' }]}>
                <Select placeholder="请选择序列类型" style={{ height: 40 }}>
                  {types.map((t) => (
                    <Select.Option key={t.code} value={t.code}>{t.description}</Select.Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="enabled" label="启用状态" valuePropName="checked" initialValue={true}>
                <Switch checkedChildren="启用" unCheckedChildren="禁用" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </div>
  );
}