'use client';

import { useEffect, useState, useRef } from 'react';
import { Card, Form, Input, Select, Button, message, Spin, InputNumber, Row, Col, Tag, Empty, Tabs, Popconfirm, Switch, Space, Modal, AutoComplete } from 'antd';
import { ApiOutlined, SendOutlined, BulbOutlined, HistoryOutlined, PlusOutlined, EditOutlined, DeleteOutlined, ToolOutlined } from '@ant-design/icons';
import { sequenceApi, SequenceType, SequenceResponse } from '@/api/sequence';
import { sequenceConfigApi, SequenceConfig } from '@/api/sequenceConfig';
import { ResizableTable } from '@/components/ResizableTable';

/**
 * 序列管理页面组件
 * 提供序列号生成（单个/批量）、历史记录查看、序列配置管理功能
 */
export default function SequencePage() {
  /** 当前激活的标签页：generate（序列生成）或config（序列配置） */
  const [activeTab, setActiveTab] = useState('generate');
  /** 生成操作加载状态 */
  const [loading, setLoading] = useState(false);
  /** 可用的序列类型列表 */
  const [types, setTypes] = useState<SequenceType[]>([]);
  /** 最近一次生成结果 */
  const [result, setResult] = useState<SequenceResponse | null>(null);
  /** 序列生成历史记录 */
  const [history, setHistory] = useState<any[]>([]);
  /** 业务键下拉选项（从配置中提取） */
  const [bizKeys, setBizKeys] = useState<string[]>([]);
  /** 序列配置列表 */
  const [configs, setConfigs] = useState<SequenceConfig[]>([]);
  /** 配置列表加载状态 */
  const [configLoading, setConfigLoading] = useState(true);
  /** 配置新增/编辑弹窗显示状态 */
  const [modalVisible, setModalVisible] = useState(false);
  /** 当前编辑的配置 */
  const [editingConfig, setEditingConfig] = useState<SequenceConfig | null>(null);
  /** 单个生成表单实例 */
  const [form] = Form.useForm();
  /** 批量生成表单实例 */
  const [batchForm] = Form.useForm();
  /** 配置表单实例 */
  const [configForm] = Form.useForm();
  
  /** 配置分页状态 */
  const [configPagination, setConfigPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  /** 配置搜索关键词 */
  const [configSearch, setConfigSearch] = useState('');
  
  /** 单个生成类型锁定状态（业务键已配置类型时锁定） */
  const [singleTypeLocked, setSingleTypeLocked] = useState(false);
  /** 批量生成类型锁定状态 */
  const [batchTypeLocked, setBatchTypeLocked] = useState(false);

  /** 组件挂载时加载序列类型、配置列表和历史记录 */
  useEffect(() => {
    sequenceApi.getTypes().then((res) => {
      if (res.code === 200) {
        setTypes(res.data);
      }
    });
    loadConfigs();
    sequenceApi.getHistoryRecent(undefined, 100).then((res) => {
      if (res.code === 200) {
        setHistory(res.data || []);
      }
    });
  }, []);

  /** 加载配置列表（分页+搜索） */
  const loadConfigs = (page = 1, size = 10, search?: string) => {
    setConfigLoading(true);
    sequenceConfigApi.getPage(page, size, search ? { bizKey: search } : undefined).then((res) => {
      if (res.code === 200) {
        setConfigs(res.data?.records || []);
        setConfigPagination({
          current: res.data?.current || 1,
          pageSize: res.data?.size || 10,
          total: res.data?.total || 0
        });
        // 同时更新下拉列表的业务键
        sequenceConfigApi.getAll().then((r) => {
          if (r.code === 200) {
            setBizKeys(r.data?.map((c: SequenceConfig) => c.bizKey) || []);
          }
        });
      }
    }).finally(() => setConfigLoading(false));
  };

  /** 配置搜索处理 */
  const handleConfigSearch = (value: string) => {
    setConfigSearch(value);
    loadConfigs(1, configPagination.pageSize, value);
  };

  /** 配置分页变化处理 */
  const handleConfigPageChange = (page: number, pageSize: number) => {
    loadConfigs(page, pageSize, configSearch);
  };

  /** 打开新增配置弹窗 */
  const handleConfigAdd = () => {
    setEditingConfig(null);
    configForm.resetFields();
    setModalVisible(true);
  };

  /** 打开编辑配置弹窗 */
  const handleConfigEdit = (record: SequenceConfig) => {
    setEditingConfig(record);
    configForm.setFieldsValue(record);
    setModalVisible(true);
  };

  /** 删除配置 */
  const handleConfigDelete = async (id: number) => {
    try {
      await sequenceConfigApi.delete(id);
      message.success('删除成功');
      loadConfigs();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 切换配置启用状态 */
  const handleConfigToggle = async (id: number, enabled: boolean) => {
    try {
      await sequenceConfigApi.toggle(id, enabled);
      message.success(enabled ? '已启用' : '已禁用');
      loadConfigs();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 提交配置表单 */
  const handleConfigSubmit = async () => {
    try {
      const values = await configForm.validateFields();
      if (editingConfig?.id) {
        await sequenceConfigApi.update(editingConfig.id, values);
        message.success('更新成功');
      } else {
        await sequenceConfigApi.create(values);
        message.success('创建成功');
      }
      setModalVisible(false);
      loadConfigs();
      loadHistory();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 业务键解析：调用后端实时查询该业务键是否已存在配置，存在则自动填充并锁定序列类型 */
  const resolveTypeRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const resolveType = (value: string | null | undefined, isBatch = false) => {
    const v = (value || '').trim();
    const targetForm = isBatch ? batchForm : form;
    const setLock = isBatch ? setBatchTypeLocked : setSingleTypeLocked;
    if (!v) {
      setLock(false);
      targetForm.setFieldValue('type', undefined);
      return;
    }
    if (resolveTypeRef.current) clearTimeout(resolveTypeRef.current);
    resolveTypeRef.current = setTimeout(() => {
      sequenceConfigApi.getByBizKey(v).then((res) => {
        if (res.code === 200 && res.data?.sequenceType) {
          targetForm.setFieldValue('type', res.data.sequenceType);
          setLock(true);
        } else {
          setLock(false);
          targetForm.setFieldValue('type', undefined);
        }
      }).catch(() => {
        setLock(false);
      });
    }, 300);
  };

  /** 单个生成业务键输入变化：实时查询后端配置类型 */
  const handleBizKeyInputChange = (value: string) => resolveType(value, false);
  /** 批量生成业务键输入变化：实时查询后端配置类型 */
  const handleBatchBizKeyInputChange = (value: string) => resolveType(value, true);
  /** 业务键选择（下拉选中）变化 */
  const handleBizKeyChange = (value: string | null, isBatch = false) => resolveType(value, isBatch);
  
  /** 刷新历史记录 */
  const loadHistory = () => {
    sequenceApi.getHistoryRecent(undefined, 100).then((res) => {
      if (res.code === 200) {
        setHistory(res.data || []);
      }
    });
  };

  /** 生成单个序列号 */
  const handleNext = async (values: { bizKey: string; type: string }) => {
    setLoading(true);
    try {
      // 优先使用配置中的类型，其次使用表单选择的类型，最后使用默认SNOWFLAKE
      const config = configs.find(c => c.bizKey === values.bizKey);
      const type = config?.sequenceType || values.type || 'SNOWFLAKE';
      const res = await sequenceApi.next({ ...values, type });
      if (res.code === 200) {
        setResult(res.data);
        loadHistory();
        loadConfigs();
        message.success('生成成功');
      } else {
        message.error(res.message);
      }
    } finally {
      setLoading(false);
    }
  };

  /** 批量生成序列号 */
  const handleBatch = async (values: { bizKey: string; type: string; count: number }) => {
    setLoading(true);
    try {
      const config = configs.find(c => c.bizKey === values.bizKey);
      const type = config?.sequenceType || values.type || 'SNOWFLAKE';
      const res = await sequenceApi.batch({ ...values, type });
      if (res.code === 200) {
        setResult(res.data);
        loadHistory();
        loadConfigs();
        message.success(`批量生成 ${values.count} 个序列号成功`);
      } else {
        message.error(res.message);
      }
    } finally {
      setLoading(false);
    }
  };

  /** 历史记录表格列定义 */
  const historyColumns = [
    { title: '业务键', dataIndex: 'bizKey', key: 'bizKey', render: (v: string) => <Tag color="blue">{v}</Tag> },
    { title: '类型', dataIndex: 'sequenceType', key: 'sequenceType', render: (v: string) => <Tag>{v}</Tag> },
    { title: '序列号', dataIndex: 'sequenceValue', key: 'sequenceValue', render: (v: number) => <code style={{ background: '#f5f5f5', padding: '2px 6px', borderRadius: 3, fontSize: 12 }}>{v}</code> },
    { title: '时间', dataIndex: 'createTime', key: 'createTime', render: (v: string) => new Date(v).toLocaleString() },
  ];

  return (
    <div style={{ background: '#f5f5f5', minHeight: '100%' }}>
      <div style={{ 
        background: '#fff', 
        padding: '16px 24px 0', 
        borderBottom: '1px solid #e8e8e8',
        position: 'sticky',
        top: 64,
        zIndex: 100
      }}>
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            { key: 'generate', label: <span><ApiOutlined /> 序列生成</span> },
            { key: 'config', label: <span><ToolOutlined /> 序列配置</span> }
          ]}
        />
      </div>

      <div style={{ padding: '24px' }}>
        <Spin spinning={loading}>
          {activeTab === 'generate' ? (
            <Row gutter={[16, 16]}>
              <Col xs={24} lg={12}>
                <Card title="生成序列号">
                  <Form form={form} layout="vertical" onFinish={handleNext}>
                    <Form.Item name="bizKey" label="业务键" rules={[{ required: true }]}>
                      <AutoComplete
                        placeholder="选择或输入业务键"
                        options={bizKeys.map(k => ({ value: k }))}
                        allowClear
                        onSelect={(value) => handleBizKeyChange(value, false)}
                        onChange={handleBizKeyInputChange}
                        onClear={() => resolveType(undefined, false)}
                      />
                    </Form.Item>
                    <Form.Item name="type" label="序列类型" rules={[{ required: true, message: '请选择序列类型' }]} extra={singleTypeLocked ? <span style={{ color: '#faad14' }}>⚠ 该业务键已绑定此类型，不可更改</span> : null}>
                      <Select placeholder="选择类型" disabled={singleTypeLocked}>
                        {types.map((t) => (
                          <Select.Option key={t.code} value={t.code}>{t.description}</Select.Option>
                        ))}
                      </Select>
                    </Form.Item>
                    <Form.Item style={{ marginBottom: 0 }}>
                      <Button type="primary" htmlType="submit" block icon={<SendOutlined />}>
                        生成下一个
                      </Button>
                    </Form.Item>
                  </Form>

                  <div style={{ margin: '20px 0', textAlign: 'center', color: '#999' }}>批量生成</div>

                  <Form form={batchForm} layout="vertical" onFinish={handleBatch}>
                    <Form.Item name="bizKey" label="业务键" rules={[{ required: true }]}>
                      <AutoComplete
                        placeholder="选择或输入业务键"
                        options={bizKeys.map(k => ({ value: k }))}
                        allowClear
                        onSelect={(value) => handleBizKeyChange(value, true)}
                        onChange={handleBatchBizKeyInputChange}
                        onClear={() => resolveType(undefined, true)}
                      />
                    </Form.Item>
                    <Row gutter={8}>
                      <Col xs={{ span: 24 }} sm={{ span: 12 }}>
                        <Form.Item name="type" label="类型" rules={[{ required: true, message: '请选择序列类型' }]} extra={batchTypeLocked ? <span style={{ color: '#faad14' }}>⚠ 该业务键已绑定此类型，不可更改</span> : null}>
                          <Select placeholder="类型" disabled={batchTypeLocked}>
                            {types.map((t) => (
                              <Select.Option key={t.code} value={t.code}>{t.description}</Select.Option>
                            ))}
                          </Select>
                        </Form.Item>
                      </Col>
                      <Col xs={{ span: 24 }} sm={{ span: 12 }}>
                        <Form.Item name="count" label="数量" initialValue={10}>
                          <InputNumber min={1} max={1000} style={{ width: '100%' }} />
                        </Form.Item>
                      </Col>
                    </Row>
                    <Form.Item style={{ marginBottom: 0 }}>
                      <Button htmlType="submit" block icon={<BulbOutlined />}>批量生成</Button>
                    </Form.Item>
                  </Form>
                </Card>
              </Col>

              <Col xs={24} lg={12}>
                <Card title="生成结果" style={{ marginBottom: 16 }}>
                  {result ? (
                    <div>
                      <Row gutter={16} style={{ marginBottom: 16 }}>
                        <Col xs={{ span: 24 }} sm={{ span: 12 }}>
                          <div style={{ color: '#999', fontSize: 12 }}>业务键</div>
                          <div style={{ fontWeight: 500 }}>{result.bizKey}</div>
                        </Col>
                        <Col xs={{ span: 24 }} sm={{ span: 12 }}>
                          <div style={{ color: '#999', fontSize: 12 }}>类型</div>
                          <div style={{ fontWeight: 500 }}>{result.type}</div>
                        </Col>
                      </Row>
                      <div style={{ 
                        background: '#f5f5f5', 
                        padding: 16, 
                        borderRadius: 8,
                        fontSize: 24,
                        fontWeight: 600,
                        fontFamily: 'monospace',
                        textAlign: 'center'
                      }}>
                        {result.sequence}
                      </div>
                    </div>
                  ) : (
                    <Empty description="暂无生成结果" />
                  )}
                </Card>

                <Card title="历史记录">
                  <ResizableTable
                    dataSource={history}
                    columns={historyColumns}
                    rowKey="id"
                    size="small"
                    pagination={{ pageSize: 5 }}
                    locale={{ emptyText: '暂无历史记录' }}
                    scroll={{ x: 'max-content' }}
                  />
                </Card>
              </Col>
            </Row>
          ) : (
            <Card>
              <div className="filter-bar" style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
                <Input.Search
                  placeholder="搜索业务键"
                  allowClear
                  enterButton="搜索"
                  style={{ width: 300 }}
                  onSearch={handleConfigSearch}
                />
                <div className="page-toolbar">
                  <Button type="primary" icon={<PlusOutlined />} onClick={handleConfigAdd}>
                    新建配置
                  </Button>
                </div>
              </div>
              <ResizableTable
                dataSource={configs}
                loading={configLoading}
                rowKey="id"
                columns={[
                  { title: '业务键', dataIndex: 'bizKey', key: 'bizKey', render: (v: string) => <Tag color="blue">{v}</Tag> },
                  { title: '序列类型', dataIndex: 'sequenceType', key: 'sequenceType', render: (v: string) => <Tag>{v}</Tag> },
                  { title: '当前值', dataIndex: 'currentValue', key: 'currentValue', render: (v: number, r: SequenceConfig) => r.sequenceType === 'SEGMENT' ? (v ?? '-') : '—' },
                  { title: '步长', dataIndex: 'step', key: 'step', render: (v: number) => v || '-' },
                  { 
                    title: '状态', 
                    dataIndex: 'enabled', 
                    key: 'enabled', 
                    render: (v: boolean, r: SequenceConfig) => (
                      <Switch 
                        checked={v} 
                        onChange={(checked) => handleConfigToggle(r.id!, checked)} 
                        checkedChildren="启用" 
                        unCheckedChildren="禁用" 
                      />
                    )
                  },
                  { 
                    title: '操作', 
                    key: 'action', 
                    render: (_: any, r: SequenceConfig) => (
                      <Space>
                        <Button type="link" icon={<EditOutlined />} onClick={() => handleConfigEdit(r)}>编辑</Button>
                        <Popconfirm title="确认删除?" onConfirm={() => handleConfigDelete(r.id!)}>
                          <Button type="link" danger icon={<DeleteOutlined />}>删除</Button>
                        </Popconfirm>
                      </Space>
                    )
                  }
                ]}
                pagination={{
                  current: configPagination.current,
                  pageSize: configPagination.pageSize,
                  total: configPagination.total,
                  onChange: handleConfigPageChange,
                  showSizeChanger: true,
                  showTotal: (total: number) => `共 ${total} 条`
                }}
                scroll={{ x: 'max-content' }}
              />
            </Card>
          )}
        </Spin>
      </div>

      <Modal
        title={editingConfig ? '编辑配置' : '新建配置'}
        open={modalVisible}
        onOk={handleConfigSubmit}
        onCancel={() => setModalVisible(false)}
        okText="保存"
      >
        <Form form={configForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="bizKey" label="业务键" rules={[{ required: true }]}>
            <Input placeholder="如: order_id" />
          </Form.Item>
          <Form.Item name="sequenceType" label="序列类型" rules={[{ required: true }]}>
            <Select>
              {types.map(t => <Select.Option key={t.code} value={t.code}>{t.description}</Select.Option>)}
            </Select>
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked" initialValue>
            <Switch checkedChildren="启用" unCheckedChildren="禁用" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}