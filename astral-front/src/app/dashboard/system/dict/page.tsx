'use client';

import { useEffect, useState, useRef } from 'react';
import { Card, Table, Button, Space, Modal, Form, Input, InputNumber, Switch, Tag, message, Popconfirm, Tabs, Select } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, EyeOutlined } from '@ant-design/icons';
import { request } from '@/api/client';

/** Java类型与JDBC类型的映射关系，用于字典类型创建时自动匹配 */
const typeMapping: Record<string, string[]> = {
  String: ['VARCHAR', 'CHAR', 'LONGVARCHAR', 'TEXT'],
  Character: ['CHAR'],
  Clob: ['CLOB'],
  Byte: ['TINYINT'],
  Short: ['SMALLINT'],
  Integer: ['INT', 'INTEGER'],
  Long: ['BIGINT'],
  Float: ['FLOAT'],
  Double: ['DOUBLE'],
  BigDecimal: ['DECIMAL', 'NUMERIC'],
  LocalDate: ['DATE'],
  LocalTime: ['TIME'],
  LocalDateTime: ['TIMESTAMP', 'DATETIME'],
  Date: ['DATE', 'TIMESTAMP'],
  Timestamp: ['TIMESTAMP'],
  Boolean: ['BOOLEAN', 'TINYINT'],
  'byte[]': ['BINARY', 'VARBINARY', 'LONGVARBINARY'],
  Blob: ['BLOB'],
};

/** 字典类型实体接口 */
interface DictType {
  /** 类型ID */
  id: number;
  /** 字典编码 */
  dictCode: string;
  /** 字典名称 */
  dictName: string;
  /** Java数据类型 */
  dataType: string;
  /** JDBC类型 */
  jdbcType: string;
  /** 数据长度 */
  dataLength: number;
  /** 描述 */
  description: string;
  /** 状态：1启用/0禁用 */
  status: number;
}

/** 字典数据实体接口 */
interface DictData {
  /** 数据ID */
  id: number;
  /** 所属字典类型ID */
  dictTypeId: number;
  /** 字典标签（显示名称） */
  dictLabel: string;
  /** 字典值（实际存储值） */
  dictValue: string;
  /** 排序号 */
  dictSort: number;
  /** CSS样式类 */
  cssClass: string;
  /** 列表样式类 */
  listClass: string;
  /** 是否为默认值：1是/0否 */
  isDefault: number;
  /** 状态：1启用/0禁用 */
  status: number;
  /** 描述 */
  description: string;
}

/** 数据字典API封装 */
const dictApi = {
  /** 分页查询字典类型 */
  getTypePage: (pageNum: number, pageSize: number, params?: Record<string, any>) =>
    request.get('/api/v1/system/dict/type/page', { params: { pageNum, pageSize, ...params } }),
  /** 获取所有字典类型（用于下拉选择） */
  getAllTypes: () => request.get('/api/v1/system/dict/type/all'),
  /** 创建字典类型 */
  createType: (data: any) => request.post('/api/v1/system/dict/type', data),
  /** 更新字典类型 */
  updateType: (id: number, data: any) => request.put(`/api/v1/system/dict/type/${id}`, data),
  /** 删除字典类型 */
  deleteType: (id: number) => request.delete(`/api/v1/system/dict/type/${id}`),
  /** 分页查询字典数据 */
  getDataPage: (pageNum: number, pageSize: number, dictTypeId?: number) =>
    request.get('/api/v1/system/dict/data/page', { params: { pageNum, pageSize, dictTypeId } }),
  /** 创建字典数据 */
  createData: (data: any) => request.post('/api/v1/system/dict/data', data),
  /** 更新字典数据 */
  updateData: (id: number, data: any) => request.put(`/api/v1/system/dict/data/${id}`, data),
  /** 删除字典数据 */
  deleteData: (id: number) => request.delete(`/api/v1/system/dict/data/${id}`),
};

/**
 * 数据字典管理页面组件
 * 提供字典类型和字典数据的增删改查，支持展开查看子数据
 */
export default function DictPage() {
  /** 字典类型加载状态 */
  const [typeLoading, setTypeLoading] = useState(false);
  /** 字典数据加载状态 */
  const [dataLoading, setDataLoading] = useState(false);
  /** 字典类型列表 */
  const [types, setTypes] = useState<DictType[]>([]);
  /** 字典类型分页状态 */
  const [typePagination, setTypePagination] = useState({ current: 1, pageSize: 10, total: 0 });
  /** 字典数据列表 */
  const [dictData, setDictData] = useState<DictData[]>([]);
  /** 字典数据分页状态 */
  const [dataPagination, setDataPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  /** 当前选中的字典类型ID */
  const [selectedTypeId, setSelectedTypeId] = useState<number | null>(null);
  /** 选中ID的ref引用，用于在回调中获取最新值 */
  const selectedTypeIdRef = useRef<number | null>(null);
  /** 所有字典类型（用于下拉选择） */
  const [allTypes, setAllTypes] = useState<DictType[]>([]);
  
  /** 字典类型弹窗显示状态 */
  const [typeModalVisible, setTypeModalVisible] = useState(false);
  /** 字典数据弹窗显示状态 */
  const [dataModalVisible, setDataModalVisible] = useState(false);
  /** 当前编辑的字典类型 */
  const [editingType, setEditingType] = useState<DictType | null>(null);
  /** 当前编辑的字典数据 */
  const [editingData, setEditingData] = useState<DictData | null>(null);
  /** 展开行数据缓存 */
  const [expandedRowData, setExpandedRowData] = useState<Record<number, DictData[]>>({});
  /** 字典类型表单实例 */
  const [typeForm] = Form.useForm();
  /** 字典数据表单实例 */
  const [dataForm] = Form.useForm();

  /** 组件挂载时加载字典类型列表 */
  useEffect(() => {
    loadTypes();
    loadAllTypes();
  }, []);

  /** 选中字典类型：切换右侧数据列表 */
  const handleSelectType = (id: number | null) => {
    setSelectedTypeId(id);
  };

  /** 选中类型变化时加载对应的字典数据 */
  useEffect(() => {
    selectedTypeIdRef.current = selectedTypeId;
    if (selectedTypeId) {
      setDataLoading(true);
      dictApi.getDataPage(1, 10, selectedTypeId)
        .then((res: any) => {
          if (res.code === 200) {
            setDictData(res.data?.records || []);
            setDataPagination({ current: res.data?.current || 1, pageSize: res.data?.size || 10, total: res.data?.total || 0 });
          }
        })
        .finally(() => setDataLoading(false));
    } else {
      setDictData([]);
      setDataPagination({ current: 1, pageSize: 10, total: 0 });
    }
  }, [selectedTypeId]);

  /** 加载字典类型列表（分页） */
  const loadTypes = (page = 1, size = 10) => {
    setTypeLoading(true);
    dictApi.getTypePage(page, size)
      .then((res: any) => {
        if (res.code === 200) {
          setTypes(res.data?.records || []);
          setTypePagination({ current: res.data?.current || 1, pageSize: res.data?.size || 10, total: res.data?.total || 0 });
        }
      })
      .finally(() => setTypeLoading(false));
  };

  /** 加载所有字典类型（用于下拉选择） */
  const loadAllTypes = () => {
    dictApi.getAllTypes()
      .then((res: any) => {
        if (res.code === 200) {
          setAllTypes(res.data || []);
        }
      });
  };

  /** 加载展开行的字典数据（懒加载） */
  const loadExpandedData = async (typeId: number) => {
    if (expandedRowData[typeId]) return;
    try {
      const res = await dictApi.getDataPage(1, 100, typeId);
      if (res.code === 200) {
        setExpandedRowData(prev => ({ ...prev, [typeId]: res.data?.records || [] }));
      }
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 加载字典数据列表（使用ref获取最新选中ID） */
  const loadData = (page = 1, size = 10) => {
    setDataLoading(true);
    dictApi.getDataPage(page, size, selectedTypeIdRef.current || undefined)
      .then((res: any) => {
        if (res.code === 200) {
          setDictData(res.data?.records || []);
          setDataPagination({ current: res.data?.current || 1, pageSize: res.data?.size || 10, total: res.data?.total || 0 });
        }
      })
      .finally(() => setDataLoading(false));
  };

  /** 打开新增字典类型弹窗 */
  const handleCreateType = () => {
    setEditingType(null);
    typeForm.resetFields();
    setTypeModalVisible(true);
  };

  /** 打开编辑字典类型弹窗 */
  const handleEditType = (record: DictType) => {
    setEditingType(record);
    typeForm.setFieldsValue(record);
    setTypeModalVisible(true);
  };

  /** 删除字典类型 */
  const handleDeleteType = async (id: number) => {
    try {
      await dictApi.deleteType(id);
      message.success('删除成功');
      loadTypes();
      loadAllTypes();
      if (selectedTypeId === id) {
        setSelectedTypeId(null);
        setDictData([]);
      }
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 提交字典类型表单 */
  const handleSubmitType = async () => {
    try {
      const values = await typeForm.validateFields();
      if (editingType?.id) {
        await dictApi.updateType(editingType.id, values);
        message.success('更新成功');
      } else {
        await dictApi.createType(values);
        message.success('创建成功');
      }
      setTypeModalVisible(false);
      loadTypes();
      loadAllTypes();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 打开新增字典数据弹窗 */
  const handleCreateData = () => {
    setEditingData(null);
    dataForm.resetFields();
    dataForm.setFieldValue('dictTypeId', selectedTypeId);
    setDataModalVisible(true);
  };

  /** 打开编辑字典数据弹窗 */
  const handleEditData = (record: DictData) => {
    setEditingData(record);
    dataForm.setFieldsValue(record);
    setDataModalVisible(true);
  };

  /** 删除字典数据 */
  const handleDeleteData = async (id: number) => {
    try {
      await dictApi.deleteData(id);
      message.success('删除成功');
      loadData();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 提交字典数据表单 */
  const handleSubmitData = async () => {
    try {
      const values = await dataForm.validateFields();
      if (editingData?.id) {
        await dictApi.updateData(editingData.id, values);
        message.success('更新成功');
      } else {
        await dictApi.createData(values);
        message.success('创建成功');
      }
      setDataModalVisible(false);
      loadData();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 字典类型表格列定义 */
  const typeColumns = [
    { title: '字典编码', dataIndex: 'dictCode', key: 'dictCode' },
    { title: '字典名称', dataIndex: 'dictName', key: 'dictName' },
    { title: '数据类型', dataIndex: 'dataType', key: 'dataType', render: (v: string) => <Tag color="blue">{v || '-'}</Tag> },
    { title: 'JDBC类型', dataIndex: 'jdbcType', key: 'jdbcType', render: (v: string) => <Tag color="cyan">{v || '-'}</Tag> },
    { title: '长度', dataIndex: 'dataLength', key: 'dataLength', render: (v: number) => v || '-' },
    { title: '描述', dataIndex: 'description', key: 'description' },
    { title: '状态', dataIndex: 'status', key: 'status', render: (v: number) => <Tag color={v === 1 ? 'green' : 'red'}>{v === 1 ? '启用' : '禁用'}</Tag> },
    {
      title: '操作',
      key: 'action',
      render: (_: any, r: DictType) => (
        <Space>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEditType(r)}>编辑</Button>
          <Popconfirm title="确认删除?" onConfirm={() => handleDeleteType(r.id)}>
            <Button type="link" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  /** 展开行渲染：显示该类型下的字典数据 */
  const expandedRowRender = (record: DictType) => {
    const data = expandedRowData[record.id] || [];
    return (
      <Table
        dataSource={data}
        columns={[
          { title: '字典标签', dataIndex: 'dictLabel', key: 'dictLabel' },
          { title: '字典值', dataIndex: 'dictValue', key: 'dictValue' },
          { title: '排序', dataIndex: 'dictSort', key: 'dictSort' },
          { title: '默认', dataIndex: 'isDefault', key: 'isDefault', render: (v: number) => <Tag color={v === 1 ? 'blue' : 'default'}>{v === 1 ? '是' : '否'}</Tag> },
          { title: '状态', dataIndex: 'status', key: 'status', render: (v: number) => <Tag color={v === 1 ? 'green' : 'red'}>{v === 1 ? '启用' : '禁用'}</Tag> },
        ]}
        rowKey="id"
        pagination={false}
        size="small"
        locale={{ emptyText: '暂无字典数据' }}
      />
    );
  };

  /** 字典数据表格列定义 */
  const dataColumns = [
    { title: '字典标签', dataIndex: 'dictLabel', key: 'dictLabel' },
    { title: '字典值', dataIndex: 'dictValue', key: 'dictValue' },
    { title: '排序', dataIndex: 'dictSort', key: 'dictSort' },
    { title: '默认', dataIndex: 'isDefault', key: 'isDefault', render: (v: number) => <Tag color={v === 1 ? 'blue' : 'default'}>{v === 1 ? '是' : '否'}</Tag> },
    { title: '状态', dataIndex: 'status', key: 'status', render: (v: number) => <Tag color={v === 1 ? 'green' : 'red'}>{v === 1 ? '启用' : '禁用'}</Tag> },
    {
      title: '操作',
      key: 'action',
      render: (_: any, r: DictData) => (
        <Space>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEditData(r)}>编辑</Button>
          <Popconfirm title="确认删除?" onConfirm={() => handleDeleteData(r.id)}>
            <Button type="link" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Tabs
        items={[
          {
            key: 'type',
            label: '字典类型',
            children: (
              <Card>
                <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
                  <Input.Search placeholder="搜索字典类型" allowClear style={{ width: 300 }} />
                  <Button type="primary" icon={<PlusOutlined />} onClick={handleCreateType}>新建类型</Button>
                </div>
                <Table 
                  dataSource={types} 
                  columns={typeColumns} 
                  rowKey="id" 
                  loading={typeLoading} 
                  expandable={{
                    expandedRowRender,
                    onExpand: (expanded, record) => {
                      if (expanded) loadExpandedData(record.id);
                    },
                  }}
                  pagination={{
                    ...typePagination,
                    onChange: (page, pageSize) => loadTypes(page, pageSize),
                  }} />
              </Card>
            ),
          },
          {
            key: 'data',
            label: '字典数据',
            children: (
              <Card>
                <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Select placeholder="选择字典类型" style={{ width: 300 }} value={selectedTypeId} onChange={handleSelectType} options={allTypes.map(t => ({ label: t.dictName, value: t.id }))} />
                  <Button type="primary" icon={<PlusOutlined />} onClick={handleCreateData} disabled={!selectedTypeId}>新建数据</Button>
                </div>
                <Table dataSource={dictData} columns={dataColumns} rowKey="id" loading={dataLoading}
                  pagination={{
                    ...dataPagination,
                    onChange: (page, pageSize) => {
                      if (selectedTypeId) {
                        dictApi.getDataPage(page, pageSize, selectedTypeId)
                          .then((res: any) => {
                            if (res.code === 200) {
                              setDictData(res.data?.records || []);
                              setDataPagination({ current: res.data?.current || page, pageSize: res.data?.size || pageSize, total: res.data?.total || 0 });
                            }
                          });
                      }
                    },
                  }} />
              </Card>
            ),
          },
        ]}
      />

      <Modal title={editingType ? '编辑字典类型' : '新建字典类型'} open={typeModalVisible} onOk={handleSubmitType} onCancel={() => setTypeModalVisible(false)}>
        <Form form={typeForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="dictCode" label="字典编码" rules={[{ required: true }]}>
            <Input disabled={!!editingType} />
          </Form.Item>
          <Form.Item name="dictName" label="字典名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="dataType" label="数据类型" rules={[{ required: true }]}>
            <Select
              options={Object.keys(typeMapping).map(t => ({ label: t, value: t }))}
              onChange={(val) => {
                if (typeMapping[val]) {
                  typeForm.setFieldsValue({ jdbcType: typeMapping[val][0] });
                }
              }}
            />
          </Form.Item>
          <Form.Item name="jdbcType" label="JDBC类型">
            <Form.Item noStyle shouldUpdate={(prev, next) => prev.dataType !== next.dataType}>
              {({ getFieldValue }) => {
                const dataType = getFieldValue('dataType');
                const options = (typeMapping[dataType] || []).map(t => ({ label: t, value: t }));
                return <Select options={options} />;
              }}
            </Form.Item>
          </Form.Item>
          <Form.Item name="dataLength" label="长度">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="description" label="描述"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>

      <Modal title={editingData ? '编辑字典数据' : '新建字典数据'} open={dataModalVisible} onOk={handleSubmitData} onCancel={() => setDataModalVisible(false)}>
        <Form form={dataForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="dictTypeId" hidden><Input /></Form.Item>
          <Form.Item name="dictLabel" label="字典标签" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="dictValue" label="字典值" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="dictSort" label="排序" initialValue={0}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="description" label="描述"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
