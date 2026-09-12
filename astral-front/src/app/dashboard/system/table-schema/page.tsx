'use client';

import { useEffect, useState } from 'react';
import { Card, Button, Space, Tag, Modal, Tabs, message, Select, Input, Form, Switch, Popconfirm, Tooltip, Radio } from 'antd';
import { CodeOutlined, DatabaseOutlined, FileTextOutlined, ApiOutlined, EditOutlined, SaveOutlined, PlusOutlined, DeleteOutlined, DownloadOutlined } from '@ant-design/icons';
import { request } from '@/api/client';
import { fetchDictOptions } from '@/api/dict';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { vscDarkPlus } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { ResizableTable } from '@/components/ResizableTable';

const { TabPane } = Tabs;

/** 表结构实体接口 */
interface TableSchema {
  /** 表名 */
  tableName: string;
  /** 表注释 */
  tableComment: string;
  /** 所属模块 */
  moduleName: string;
  /** 对应的Java类名 */
  className: string;
  /** 字段列表 */
  fields: FieldSchema[];
  /** 非数据库字段（如关联对象） */
  nonDbFields?: FieldSchema[];
  /** 索引列表 */
  indexes?: IndexSchema[];
}

/** 字段结构接口 */
interface FieldSchema {
  /** 数据库列名 */
  columnName: string;
  /** Java字段名（驼峰命名） */
  fieldName: string;
  /** Java字段类型 */
  fieldType: string;
  /** JDBC类型 */
  jdbcType: string;
  /** 字段注释 */
  comment: string;
  /** 字段长度 */
  length?: number;
  /** 默认值 */
  defaultValue?: string;
  /** 是否为主键 */
  isPrimaryKey?: boolean;
  /** 是否自增 */
  isAutoIncrement?: boolean;
  /** 是否必填 */
  isRequired?: boolean;
  /** 是否唯一 */
  isUnique?: boolean;
  /** 是否为逻辑删除字段 */
  isLogicDelete?: boolean;
  /** 是否为乐观锁字段 */
  isVersion?: boolean;
  /** 是否忽略JSON序列化 */
  isJsonIgnore?: boolean;
  /** 自动填充策略 */
  isAutoFill?: string;
}

/** 索引结构接口 */
interface IndexSchema {
  /** 索引名称 */
  indexName: string;
  /** 索引包含的列 */
  columns: string[];
  /** 是否唯一索引 */
  isUnique: boolean;
}

/** 表结构管理API封装 */
const tableSchemaApi = {
  /** 获取所有表结构 */
  getAll: () => request.get('/api/v1/admin/system/table-schema'),
  /** 按模块获取表结构 */
  getByModule: (moduleName: string) => request.get(`/api/v1/admin/system/table-schema/module/${moduleName}`),
  /** 生成完整代码（Entity/Mapper/Service/Controller） */
  getFullCode: (tableName: string) => request.get(`/api/v1/admin/system/table-schema/${tableName}/full-code`),
  /** 生成建表SQL（dialect：mysql / postgresql） */
  getSql: (tableName: string, dialect: string) => request.get(`/api/v1/admin/system/table-schema/${tableName}/sql`, { params: { dialect } }),
  /** 更新表结构（dialect：mysql / postgresql） */
  update: (tableName: string, data: TableSchema, dialect: string) => request.put(`/api/v1/admin/system/table-schema/${tableName}`, data, { params: { dialect } }),
  /** 生成ALTER SQL（dialect：mysql / postgresql） */
  generateAlterSql: (tableName: string, data: TableSchema, dialect: string) => request.post(`/api/v1/admin/system/table-schema/${tableName}/alter-sql`, data, { params: { dialect } }),
  /** 导出单个表结构JSON */
  exportSchema: (tableName: string) => request.get(`/api/v1/admin/system/table-schema/${tableName}/export`),
  /** 导出所有表结构JSON */
  exportAll: () => request.get('/api/v1/admin/system/table-schema/export-all'),
  /** 创建新表结构 */
  create: (data: any) => request.post('/api/v1/admin/system/table-schema', data),
  /** 删除表结构 */
  delete: (tableName: string) => request.delete(`/api/v1/admin/system/table-schema/${tableName}`),
};

/** 模块筛选选项 */
const modules = [
  { label: '全部', value: '' },
  { label: '系统管理', value: 'system' },
  { label: '序列管理', value: 'sequence' },
  { label: '日志管理', value: 'log' },
];

/** Java类型与JDBC类型的映射关系 */
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

/** 所有可用的Java字段类型 */
const fieldTypes = Object.keys(typeMapping);

/** SQL方言兜底选项（字典 sql_dialect 未配置时使用） */
const fallbackDialects = [
  { value: 'postgresql', label: 'PostgreSQL' },
  { value: 'mysql', label: 'MySQL' },
];

/**
 * 表结构管理页面组件
 * 提供表结构查看、编辑、代码生成、SQL生成、导出等功能
 */
export default function TableSchemaPage() {
  /** 加载状态 */
  const [loading, setLoading] = useState(false);
  /** 表结构列表数据 */
  const [data, setData] = useState<TableSchema[]>([]);
  /** 当前选中的模块 */
  const [selectedModule, setSelectedModule] = useState('');
  /** 搜索关键词 */
  const [keyword, setKeyword] = useState('');
  /** 代码生成弹窗显示状态 */
  const [codeModalVisible, setCodeModalVisible] = useState(false);
  /** SQL生成弹窗显示状态 */
  const [sqlModalVisible, setSqlModalVisible] = useState(false);
  /** 编辑表结构弹窗显示状态 */
  const [editModalVisible, setEditModalVisible] = useState(false);
  /** 当前查看的表结构（用于代码生成展示） */
  const [currentSchema, setCurrentSchema] = useState<TableSchema | null>(null);
  /** 当前编辑的表结构 */
  const [editingSchema, setEditingSchema] = useState<TableSchema | null>(null);
  /** 生成的代码（Entity/Mapper/Service/Controller） */
  const [generatedCode, setGeneratedCode] = useState<Record<string, string>>({});
  /** 生成的建表SQL */
  const [generatedSql, setGeneratedSql] = useState('');
  /** 生成的ALTER SQL */
  const [alterSql, setAlterSql] = useState('');
  /** 更新后的Entity代码 */
  const [updatedEntity, setUpdatedEntity] = useState('');
  /** 更新后的JSON数据 */
  const [updatedJson, setUpdatedJson] = useState('');
  /** 代码生成加载状态 */
  const [codeLoading, setCodeLoading] = useState(false);
  /** 表单实例 */
  const [form] = Form.useForm();
  /** 字典字段列表（用于快速选择字段） */
  const [dictFields, setDictFields] = useState<any[]>([]);
  /** 新建表结构弹窗显示状态 */
  const [createModalVisible, setCreateModalVisible] = useState(false);
  /** 新建表单实例 */
  const [createForm] = Form.useForm();
  /** SQL方言（mysql / postgresql，默认与运行时数据库一致） */
  const [sqlDialect, setSqlDialect] = useState('postgresql');
  /** SQL方言下拉选项（字典 sql_dialect） */
  const [dialectOptions, setDialectOptions] = useState<{ value: string; label: string }[]>(fallbackDialects);

  /** 组件挂载时加载表结构列表、字典字段和SQL方言字典 */
  useEffect(() => {
    loadData();
    loadDictFields();
    loadDialects();
  }, []);

  /** 加载SQL方言选项（字典 sql_dialect，未配置时回退内置选项） */
  const loadDialects = async () => {
    try {
      const map = await fetchDictOptions(['sql_dialect']);
      const options = map['sql_dialect'] || [];
      if (options.length > 0) {
        setDialectOptions(options);
      }
    } catch (error: any) {
      // 字典接口不可用时回退内置选项
    }
  };

  /** 加载字典字段（用于编辑时快速选择） */
  const loadDictFields = async () => {
    try {
      const res = await request.get('/api/v1/admin/system/dict/type/all');
      if (res.code === 200) {
        setDictFields(res.data || []);
      }
    } catch (error: any) {
      // 字典接口不可用时忽略
    }
  };

  /** 加载表结构列表（可按模块筛选） */
  const loadData = async (moduleName = '') => {
    setLoading(true);
    try {
      const res = moduleName
        ? await tableSchemaApi.getByModule(moduleName)
        : await tableSchemaApi.getAll();
      if (res.code === 200) {
        setData(res.data || []);
      }
    } catch (error: any) {
      message.error(error.message);
    } finally {
      setLoading(false);
    }
  };

  /** 生成代码：获取Entity/Mapper/Service/Controller代码并展示 */
  const handleGenerateCode = async (schema: TableSchema) => {
    setCurrentSchema(schema);
    setCodeLoading(true);
    try {
      const res = await tableSchemaApi.getFullCode(schema.tableName);
      if (res.code === 200) {
        setGeneratedCode(res.data);
        setCodeModalVisible(true);
      }
    } catch (error: any) {
      message.error(error.message);
    } finally {
      setCodeLoading(false);
    }
  };

  /** 生成SQL：按指定方言获取建表SQL并展示 */
  const handleGenerateSql = (schema: TableSchema) => {
    setCurrentSchema(schema);
    setSqlModalVisible(true);
    fetchSql(schema.tableName, sqlDialect);
  };

  /** 拉取建表SQL（方言切换时重新拉取） */
  const fetchSql = async (tableName: string, dialect: string) => {
    setCodeLoading(true);
    try {
      const res = await tableSchemaApi.getSql(tableName, dialect);
      if (res.code === 200) {
        setGeneratedSql(res.data);
      }
    } catch (error: any) {
      message.error(error.message);
    } finally {
      setCodeLoading(false);
    }
  };

  /** 切换SQL方言（SQL弹窗内切换后重新生成） */
  const handleDialectChange = (dialect: string) => {
    setSqlDialect(dialect);
    if (currentSchema) {
      fetchSql(currentSchema.tableName, dialect);
    }
  };

  /** 打开编辑表结构弹窗：深拷贝避免直接修改原始数据 */
  const handleEdit = (schema: TableSchema) => {
    setEditingSchema(JSON.parse(JSON.stringify(schema)));
    setEditModalVisible(true);
  };

  /** 保存表结构：更新并生成ALTER SQL */
  const handleSaveSchema = async () => {
    if (!editingSchema) return;
    setCodeLoading(true);
    try {
      const res = await tableSchemaApi.update(editingSchema.tableName, editingSchema, sqlDialect);
      if (res.code === 200) {
        setAlterSql(res.data.alterSql);
        setUpdatedEntity(res.data.entityCode);
        setUpdatedJson(JSON.stringify(editingSchema, null, 2));
        message.success('表结构已更新');
        loadData(selectedModule);
      }
    } catch (error: any) {
      message.error(error.message);
    } finally {
      setCodeLoading(false);
    }
  };

  /** 添加新字段到编辑中的表结构 */
  const addField = () => {
    if (!editingSchema) return;
    const newField: FieldSchema = {
      columnName: '',
      fieldName: '',
      fieldType: '',
      jdbcType: '',
      comment: '',
      length: 0,
      isRequired: false,
    };
    setEditingSchema({
      ...editingSchema,
      fields: [...editingSchema.fields, newField],
    });
  };

  /** 从字典字段快速填充：自动转换命名和类型 */
  const selectDictField = (index: number, dictCode: string) => {
    if (!editingSchema) return;
    const dict = dictFields.find((d: any) => d.dictCode === dictCode);
    if (!dict) return;
    // 将驼峰命名转换为下划线命名
    const columnName = dict.dictCode.replace(/([A-Z])/g, '_$1').toLowerCase().replace(/^_/, '');
    const newFields = [...editingSchema.fields];
    newFields[index] = {
      ...newFields[index],
      columnName,
      fieldName: dict.dictCode,
      fieldType: dict.dataType,
      jdbcType: dict.jdbcType,
      length: dict.dataLength || 0,
      comment: dict.dictName,
    };
    setEditingSchema({ ...editingSchema, fields: newFields });
  };

  /** 从编辑中的表结构移除字段 */
  const removeField = (index: number) => {
    if (!editingSchema) return;
    const newFields = [...editingSchema.fields];
    newFields.splice(index, 1);
    setEditingSchema({ ...editingSchema, fields: newFields });
  };

  /** 更新编辑中的字段属性，自动匹配JDBC类型 */
  const updateField = (index: number, field: Partial<FieldSchema>) => {
    if (!editingSchema) return;
    const newFields = [...editingSchema.fields];
    const updatedField = { ...newFields[index], ...field };
    if (field.fieldType && typeMapping[field.fieldType]) {
      updatedField.jdbcType = typeMapping[field.fieldType][0];
    }
    newFields[index] = updatedField;
    setEditingSchema({ ...editingSchema, fields: newFields });
  };

  /** 复制文本到剪贴板 */
  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
    message.success('已复制到剪贴板');
  };

  /** 下载JSON文件 */
  const downloadJson = (data: any, filename: string) => {
    const json = JSON.stringify(data, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
    message.success(`已下载 ${filename}`);
  };

  /** 导出单个表结构JSON */
  const handleExportSchema = async (schema: TableSchema) => {
    try {
      const res = await tableSchemaApi.exportSchema(schema.tableName);
      if (res.code === 200) {
        downloadJson(res.data, `${schema.tableName}.json`);
      }
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 批量导出所有表结构JSON */
  const handleExportAll = async () => {
    try {
      const res = await tableSchemaApi.exportAll();
      if (res.code === 200) {
        for (const schema of res.data) {
          downloadJson(schema, `${schema.tableName}.json`);
        }
        message.success(`已导出 ${res.data.length} 个表结构`);
      }
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 创建新表结构 */
  const handleCreateTable = async (values: any) => {
    setCodeLoading(true);
    try {
      const res = await tableSchemaApi.create(values);
      if (res.code === 200) {
        message.success('表结构创建成功');
        setCreateModalVisible(false);
        createForm.resetFields();
        loadData(selectedModule);
        const newSchema = res.data;
        setEditingSchema(JSON.parse(JSON.stringify(newSchema)));
        setEditModalVisible(true);
      }
    } catch (error: any) {
      message.error(error.message);
    } finally {
      setCodeLoading(false);
    }
  };

  /** 删除表结构 */
  const handleDeleteTable = async (schema: TableSchema) => {
    setCodeLoading(true);
    try {
      const res = await tableSchemaApi.delete(schema.tableName);
      if (res.code === 200) {
        message.success('表结构已删除');
        loadData(selectedModule);
      }
    } catch (error: any) {
      message.error(error.message);
    } finally {
      setCodeLoading(false);
    }
  };

  /** 根据关键词过滤表结构列表 */
  const filteredData = data.filter(d =>
    d.tableName.toLowerCase().includes(keyword.toLowerCase()) ||
    d.tableComment.includes(keyword) ||
    d.className.toLowerCase().includes(keyword.toLowerCase())
  );

  /** 表格列定义 */
  const columns = [
    { title: '表名', dataIndex: 'tableName', key: 'tableName', render: (v: string) => <code style={{ background: '#f5f5f5', padding: '2px 6px', borderRadius: 3, fontSize: 12 }}>{v}</code> },
    { title: '注释', dataIndex: 'tableComment', key: 'tableComment' },
    { title: '类名', dataIndex: 'className', key: 'className', render: (v: string) => <Tag color="blue">{v}</Tag> },
    { title: '模块', dataIndex: 'moduleName', key: 'moduleName', render: (v: string) => {
      const colors: Record<string, string> = { system: 'green', sequence: 'orange', log: 'purple' };
      return <Tag color={colors[v] || 'default'}>{v}</Tag>;
    }},
    { title: '字段数', key: 'fieldCount', render: (_: any, r: TableSchema) => r.fields?.length || 0 },
    {
      title: '操作',
      key: 'action',
      width: 180,
      render: (_: any, r: TableSchema) => (
        <Space>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(r)}>编辑</Button>
          <Button type="link" icon={<CodeOutlined />} onClick={() => handleGenerateCode(r)} loading={codeLoading}>生成代码</Button>
          <Button type="link" icon={<DatabaseOutlined />} onClick={() => handleGenerateSql(r)} loading={codeLoading}>生成SQL</Button>
          <Button type="link" icon={<DownloadOutlined />} onClick={() => handleExportSchema(r)}>导出JSON</Button>
          <Popconfirm title="确认删除此表结构?" onConfirm={() => handleDeleteTable(r)}>
            <Button type="link" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  /** 字段详情表格列定义 */
  const fieldColumns = [
    { title: '列名', dataIndex: 'columnName', key: 'columnName', render: (v: string) => <code style={{ fontSize: 12 }}>{v}</code> },
    { title: '字段名', dataIndex: 'fieldName', key: 'fieldName' },
    { title: '类型', dataIndex: 'fieldType', key: 'fieldType', render: (v: string) => <Tag color="blue">{v}</Tag> },
    { title: 'JDBC类型', dataIndex: 'jdbcType', key: 'jdbcType' },
    { title: '注释', dataIndex: 'comment', key: 'comment' },
    {
      title: '属性',
      key: 'flags',
      render: (_: any, r: FieldSchema) => (
        <Space>
          {r.isPrimaryKey && <Tag color="red">PK</Tag>}
          {r.isAutoIncrement && <Tag>自增</Tag>}
          {r.isRequired && <Tag color="orange">必填</Tag>}
          {r.isUnique && <Tag color="cyan">唯一</Tag>}
          {r.isLogicDelete && <Tag color="magenta">逻辑删除</Tag>}
          {r.isVersion && <Tag color="geekblue">乐观锁</Tag>}
          {r.isJsonIgnore && <Tag color="default">JsonIgnore</Tag>}
          {r.isAutoFill && <Tag color="volcano">{r.isAutoFill}</Tag>}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }} className="filter-bar">
          <Space>
            <Select style={{ width: 150 }} value={selectedModule} onChange={(v) => { setSelectedModule(v); loadData(v); }} options={modules} />
            <Input.Search placeholder="搜索表名/注释/类名" allowClear onSearch={setKeyword} style={{ width: 300 }} />
          </Space>
          <Space>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalVisible(true)}>新建表</Button>
            <Button icon={<DatabaseOutlined />} onClick={handleExportAll}>批量导出JSON</Button>
            <Tag color="blue">共 {filteredData.length} 张表</Tag>
          </Space>
        </div>
        <ResizableTable dataSource={filteredData} columns={columns} rowKey="tableName" loading={loading} scroll={{ x: 'max-content' }} pagination={false} />
      </Card>

      <Modal title={`代码生成 - ${currentSchema?.tableName} (${currentSchema?.tableComment})`} open={codeModalVisible} onCancel={() => setCodeModalVisible(false)} width="80%" footer={null}>
        <Tabs defaultActiveKey="entity">
          <TabPane tab={<span><FileTextOutlined /> Entity</span>} key="entity">
            <div style={{ position: 'relative' }}>
              <Button size="small" style={{ position: 'absolute', right: 8, top: 8, zIndex: 1 }} onClick={() => copyToClipboard(generatedCode.entity)}>复制</Button>
              <SyntaxHighlighter language="java" style={vscDarkPlus} customStyle={{ maxHeight: 500, overflow: 'auto' }}>
                {generatedCode.entity || ''}
              </SyntaxHighlighter>
            </div>
          </TabPane>
          <TabPane tab={<span><ApiOutlined /> Mapper</span>} key="mapper">
            <div style={{ position: 'relative' }}>
              <Button size="small" style={{ position: 'absolute', right: 8, top: 8, zIndex: 1 }} onClick={() => copyToClipboard(generatedCode.mapper)}>复制</Button>
              <SyntaxHighlighter language="java" style={vscDarkPlus} customStyle={{ maxHeight: 500, overflow: 'auto' }}>
                {generatedCode.mapper || ''}
              </SyntaxHighlighter>
            </div>
          </TabPane>
          <TabPane tab={<span>Service</span>} key="service">
            <div style={{ position: 'relative' }}>
              <Button size="small" style={{ position: 'absolute', right: 8, top: 8, zIndex: 1 }} onClick={() => copyToClipboard(generatedCode.service)}>复制</Button>
              <SyntaxHighlighter language="java" style={vscDarkPlus} customStyle={{ maxHeight: 500, overflow: 'auto' }}>
                {generatedCode.service || ''}
              </SyntaxHighlighter>
            </div>
          </TabPane>
          <TabPane tab={<span>ServiceImpl</span>} key="serviceImpl">
            <div style={{ position: 'relative' }}>
              <Button size="small" style={{ position: 'absolute', right: 8, top: 8, zIndex: 1 }} onClick={() => copyToClipboard(generatedCode.serviceImpl)}>复制</Button>
              <SyntaxHighlighter language="java" style={vscDarkPlus} customStyle={{ maxHeight: 500, overflow: 'auto' }}>
                {generatedCode.serviceImpl || ''}
              </SyntaxHighlighter>
            </div>
          </TabPane>
          <TabPane tab={<span>Controller</span>} key="controller">
            <div style={{ position: 'relative' }}>
              <Button size="small" style={{ position: 'absolute', right: 8, top: 8, zIndex: 1 }} onClick={() => copyToClipboard(generatedCode.controller)}>复制</Button>
              <SyntaxHighlighter language="java" style={vscDarkPlus} customStyle={{ maxHeight: 500, overflow: 'auto' }}>
                {generatedCode.controller || ''}
              </SyntaxHighlighter>
            </div>
          </TabPane>
          <TabPane tab={<span>字段详情</span>} key="fields">
            <ResizableTable dataSource={currentSchema?.fields} columns={fieldColumns} rowKey="columnName" scroll={{ x: 'max-content' }} pagination={false} size="small" />
            {currentSchema?.nonDbFields && currentSchema.nonDbFields.length > 0 && (
              <>
                <h4 style={{ marginTop: 16 }}>非数据库字段</h4>
                <ResizableTable dataSource={currentSchema.nonDbFields} columns={fieldColumns} rowKey="columnName" scroll={{ x: 'max-content' }} pagination={false} size="small" />
              </>
            )}
          </TabPane>
        </Tabs>
      </Modal>

      <Modal title={`SQL生成 - ${currentSchema?.tableName} (${currentSchema?.tableComment})`} open={sqlModalVisible} onCancel={() => setSqlModalVisible(false)} width="60%" footer={null}>
        <div style={{ marginBottom: 12, display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 13 }}>数据库方言：</span>
          <Radio.Group
            value={sqlDialect}
            onChange={(e) => handleDialectChange(e.target.value)}
            optionType="button"
            buttonStyle="solid"
            size="small"
            options={dialectOptions}
          />
          <Tooltip title="MySQL：内联COMMENT + AUTO_INCREMENT；PostgreSQL：COMMENT ON + BIGSERIAL">
            <span style={{ fontSize: 12, color: '#999' }}>切换方言会重新生成SQL</span>
          </Tooltip>
        </div>
        <div style={{ position: 'relative' }}>
          <Button size="small" style={{ position: 'absolute', right: 8, top: 8, zIndex: 1 }} onClick={() => copyToClipboard(generatedSql)}>复制</Button>
          <SyntaxHighlighter language="sql" style={vscDarkPlus} customStyle={{ maxHeight: 500, overflow: 'auto' }}>
            {generatedSql}
          </SyntaxHighlighter>
        </div>
      </Modal>

      <Modal
        title={`编辑表结构 - ${editingSchema?.tableName} (${editingSchema?.tableComment})`}
        open={editModalVisible}
        onCancel={() => setEditModalVisible(false)}
        width="90%"
        footer={[
          <Button key="cancel" onClick={() => setEditModalVisible(false)}>取消</Button>,
          <Button key="save" type="primary" icon={<SaveOutlined />} onClick={handleSaveSchema} loading={codeLoading}>保存并生成ALTER SQL</Button>,
        ]}
      >
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }} className="filter-bar">
          <Space>
            <Button type="primary" icon={<PlusOutlined />} onClick={addField}>添加字段</Button>
          </Space>
          <Space>
            <span style={{ fontSize: 13 }}>ALTER SQL方言：</span>
            <Select
              value={sqlDialect}
              onChange={setSqlDialect}
              options={dialectOptions}
              size="small"
              style={{ width: 140 }}
            />
            <Tag color="blue">共 {editingSchema?.fields.length || 0} 个字段</Tag>
          </Space>
        </div>
        <ResizableTable
          dataSource={editingSchema?.fields}
          rowKey="columnName"
          pagination={false}
          size="small"
          scroll={{ x: 'max-content', y: 400 }}
          columns={[
            { title: '列名', dataIndex: 'columnName', key: 'columnName', width: 220, render: (v: string, _: any, index: number) => {
              const existingCodes = editingSchema?.fields.map((f: FieldSchema) => f.columnName).filter(Boolean) || [];
              const toSnake = (code: string) => code.replace(/([A-Z])/g, '_$1').toLowerCase().replace(/^_/, '');
              const toCamel = (col: string) => col.replace(/_([a-z])/g, (_: string, c: string) => c.toUpperCase());
              const currentDictCode = toCamel(v || '');
              const currentDict = dictFields.find((d: any) => d.dictCode === currentDictCode);
              const availableDicts = dictFields.filter((d: any) => {
                const colName = toSnake(d.dictCode);
                return !existingCodes.includes(colName) || colName === v;
              });
              return (
                <Select
                  value={currentDictCode || undefined}
                  onChange={(val) => selectDictField(index, val)}
                  options={availableDicts.map((d: any) => ({ label: `${toSnake(d.dictCode)} (${d.dictName})`, value: d.dictCode }))}
                  placeholder="选择字典字段"
                  size="small"
                  style={{ width: '100%' }}
                  showSearch
                  optionFilterProp="label"
                />
              );
            }},
            { title: '字段名', dataIndex: 'fieldName', key: 'fieldName', width: 140, render: (v: string) => <span style={{ fontSize: 12 }}>{v || '-'}</span> },
            { title: '类型', dataIndex: 'fieldType', key: 'fieldType', width: 120, render: (v: string) => <Tag color="blue">{v || '-'}</Tag> },
            { title: 'JDBC类型', dataIndex: 'jdbcType', key: 'jdbcType', width: 120, render: (v: string) => <Tag color="cyan">{v || '-'}</Tag> },
            { title: '长度', dataIndex: 'length', key: 'length', width: 70, render: (v: number) => <span style={{ fontSize: 12 }}>{v || 0}</span> },
            { title: '注释', dataIndex: 'comment', key: 'comment', width: 200, render: (v: string, _: any, index: number) => <Input value={v} onChange={(e) => updateField(index, { comment: e.target.value })} size="small" placeholder="字典名称+枚举值" /> },
            {
              title: '属性',
              key: 'flags',
              width: 250,
              render: (_: any, r: FieldSchema, index: number) => (
                <Space size="small">
                  <Tooltip title="主键"><Switch size="small" checked={r.isPrimaryKey} onChange={(v) => updateField(index, { isPrimaryKey: v })} checkedChildren="PK" /></Tooltip>
                  <Tooltip title="自增"><Switch size="small" checked={r.isAutoIncrement} onChange={(v) => updateField(index, { isAutoIncrement: v })} checkedChildren="AI" /></Tooltip>
                  <Tooltip title="必填"><Switch size="small" checked={r.isRequired} onChange={(v) => updateField(index, { isRequired: v })} checkedChildren="Req" /></Tooltip>
                  <Tooltip title="唯一"><Switch size="small" checked={r.isUnique} onChange={(v) => updateField(index, { isUnique: v })} checkedChildren="Uni" /></Tooltip>
                  <Tooltip title="逻辑删除"><Switch size="small" checked={r.isLogicDelete} onChange={(v) => updateField(index, { isLogicDelete: v })} checkedChildren="Del" /></Tooltip>
                </Space>
              ),
            },
            {
              title: '操作',
              key: 'action',
              width: 80,
              render: (_: any, __: any, index: number) => (
                <Popconfirm title="确认删除此字段?" onConfirm={() => removeField(index)}>
                  <Button type="link" danger icon={<DeleteOutlined />} size="small" />
                </Popconfirm>
              ),
            },
          ]}
        />
      </Modal>

      <Modal
        title={`ALTER SQL - ${editingSchema?.tableName}`}
        open={!!alterSql}
        onCancel={() => { setAlterSql(''); setUpdatedEntity(''); setUpdatedJson(''); }}
        width="70%"
        footer={[
          <Button key="copySql" onClick={() => copyToClipboard(alterSql)}>复制SQL</Button>,
          <Button key="copyEntity" onClick={() => copyToClipboard(updatedEntity)}>复制Entity</Button>,
          <Button key="copyJson" onClick={() => copyToClipboard(updatedJson)}>复制JSON</Button>,
          <Button key="downloadJson" onClick={() => editingSchema && downloadJson(editingSchema, `${editingSchema.tableName}.json`)}>下载JSON</Button>,
          <Button key="close" type="primary" onClick={() => { setAlterSql(''); setUpdatedEntity(''); setUpdatedJson(''); }}>关闭</Button>,
        ]}
      >
        <Tabs defaultActiveKey="alter">
          <TabPane tab="ALTER SQL" key="alter">
            <SyntaxHighlighter language="sql" style={vscDarkPlus} customStyle={{ maxHeight: 400, overflow: 'auto' }}>
              {alterSql}
            </SyntaxHighlighter>
          </TabPane>
          <TabPane tab="更新后的Entity" key="entity">
            <SyntaxHighlighter language="java" style={vscDarkPlus} customStyle={{ maxHeight: 400, overflow: 'auto' }}>
              {updatedEntity}
            </SyntaxHighlighter>
          </TabPane>
          <TabPane tab="最新JSON" key="json">
            <SyntaxHighlighter language="json" style={vscDarkPlus} customStyle={{ maxHeight: 400, overflow: 'auto' }}>
              {updatedJson}
            </SyntaxHighlighter>
          </TabPane>
        </Tabs>
      </Modal>

      <Modal
        title="新建表结构"
        open={createModalVisible}
        onCancel={() => { setCreateModalVisible(false); createForm.resetFields(); }}
        footer={null}
        width={500}
      >
        <Form
          form={createForm}
          layout="vertical"
          onFinish={handleCreateTable}
          initialValues={{ includeCommonFields: true, moduleName: 'system' }}
        >
          <Form.Item
            name="tableName"
            label="表名"
            rules={[{ required: true, message: '请输入表名' }]}
            extra="使用下划线命名法，如：my_table"
          >
            <Input placeholder="例如：sys_example" />
          </Form.Item>
          <Form.Item
            name="tableComment"
            label="表注释"
            rules={[{ required: true, message: '请输入表注释' }]}
          >
            <Input placeholder="例如：示例表" />
          </Form.Item>
          <Form.Item
            name="moduleName"
            label="模块"
            rules={[{ required: true, message: '请选择模块' }]}
          >
            <Select options={modules.filter(m => m.value)} />
          </Form.Item>
          <Form.Item
            name="includeCommonFields"
            label="包含通用字段"
            valuePropName="checked"
            extra="自动添加 id, create_time, update_time, deleted 字段"
          >
            <Switch checkedChildren="是" unCheckedChildren="否" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block loading={codeLoading}>
              创建并编辑
            </Button>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
