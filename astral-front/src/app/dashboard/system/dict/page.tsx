'use client';

import { useCallback, useEffect, useState, useRef, useSyncExternalStore } from 'react';
import {
  Card,
  Button,
  Space,
  Modal,
  Form,
  Input,
  InputNumber,
  Tag,
  message,
  Popconfirm,
  Tabs,
  Select,
} from '@/components/antd-compat';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@/components/antd-compat/icons';
import {
  adminDictApi as dictApi,
  paginateDictTypes,
  dictTypePayload,
  dictDataPayload,
  loadDictDataPage,
  type AdminDictType as DictType,
  type AdminDictData as DictData,
  type DictTypeWrite,
  type DictDataWrite,
} from '@/api/system-admin';
import {
  loadAdminDictOptions as fetchDictOptions,
  dictLabel,
  dictColor,
  type DictOption,
} from '@/components/admin/admin-dictionaries';
import { AdminLoadNotice, AdminOptionNotice } from '@/components/admin/AdminLoadNotice';
import { useAdminLoader, useAdminMutation, useAdminQuery } from '@/components/admin/use-admin-task';
import { requireAdminSuccess, adminErrorMessage } from '@/components/admin/admin-task';
import { createAdminCache } from '@/components/admin/admin-cache';
import { usePerm } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';
import { PageHeader } from '@/components/page-header';
import { FilterBar } from '@/components/filter-bar';

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

/**
 * 数据字典管理页面组件
 * 提供字典类型和字典数据的增删改查，支持展开查看子数据
 */
export default function DictPage() {
  /** 字典类型加载状态 */
  const {
    loading: typeLoading,
    error: typeError,
    run: runTypes,
  } = useAdminLoader('加载字典类型失败');
  const { error: optionError, run: runOptions } = useAdminLoader('加载字典标签失败');
  const { busy: saving, run: runMutation, isBusy } = useAdminMutation('字典操作失败');
  const typeQuery = useAdminQuery({ current: 1, pageSize: 10, keyword: '' });
  const dataQuery = useAdminQuery({ current: 1, pageSize: 10 });
  const [typeKeyword, setTypeKeyword] = useState('');
  const [dicts, setDicts] = useState<Record<string, DictOption[]>>({});
  const editorScope = useRef<{ kind: 'type' | 'data'; id: number | null } | null>(null);
  /** 字典数据加载状态 */
  const {
    loading: dataLoading,
    error: dataError,
    run: runData,
    invalidate: invalidateData,
  } = useAdminLoader('加载字典数据失败');
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
  const [cache] = useState(() => createAdminCache<DictData[]>());
  useSyncExternalStore(cache.subscribe, cache.getSnapshot, cache.getServerSnapshot);
  const expandedIds = useRef(new Set<number>());
  const [expandedErrors, setExpandedErrors] = useState<Record<number, string>>({});
  /** 字典类型表单实例 */
  const [typeForm] = Form.useForm<DictTypeWrite>();
  /** 字典数据表单实例 */
  const [dataForm] = Form.useForm<DictDataWrite>();

  const hasPerm = usePerm();
  /** 是否具备数据字典维护权限：权限码需与后端 @RequiresPermission("admin:system:dict:edit") 一致 */
  const canEdit = hasPerm('admin:system:dict:edit');

  const handleSelectType = useCallback(
    (id: number | null | undefined) => {
      const next = id == null ? null : id;
      if (next != null && (!Number.isSafeInteger(next) || next <= 0)) return;
      invalidateData();
      selectedTypeIdRef.current = next;
      setSelectedTypeId(next);
      dataQuery.read({ current: 1, pageSize: 10 });
      setDictData([]);
      setDataPagination({ current: 1, pageSize: 10, total: 0 });
    },
    [invalidateData, dataQuery],
  );

  const loadExpandedData = useCallback(
    async (typeId: number) => {
      setExpandedErrors((previous) => ({ ...previous, [typeId]: '' }));
      try {
        await cache.load(typeId, async () => (await loadDictDataPage(1, 100, typeId)).records);
      } catch (error: unknown) {
        setExpandedErrors((previous) => ({
          ...previous,
          [typeId]: adminErrorMessage(error, '加载预览失败'),
        }));
      }
    },
    [cache],
  );
  useEffect(() => () => cache.invalidate(), [cache]);
  const refreshPreviews = useCallback(() => {
    cache.invalidate();
    setExpandedErrors({});
    for (const id of expandedIds.current) void loadExpandedData(id);
  }, [cache, loadExpandedData]);

  const loadTypes = useCallback(
    (page?: number, size?: number, keyword?: string) => {
      const query = typeQuery.read({
        ...(page !== undefined ? { current: page } : {}),
        ...(size !== undefined ? { pageSize: size } : {}),
        ...(keyword !== undefined ? { keyword } : {}),
      });
      return runTypes(
        async () => {
          const rows = requireAdminSuccess(await dictApi.getAllTypes(), '加载字典类型失败');
          if (
            !Array.isArray(rows) ||
            rows.some((row) => !row || !Number.isSafeInteger(row.id) || row.id <= 0)
          )
            throw new Error('字典类型数据缺失或无效');
          return { rows, page: paginateDictTypes(rows, query) };
        },
        ({ rows, page: result }) => {
          setAllTypes(rows);
          setTypes(result.records);
          typeQuery.read({ current: result.current, pageSize: result.size });
          setTypePagination({
            current: result.current,
            pageSize: result.size,
            total: result.total,
          });
          if (
            selectedTypeIdRef.current &&
            !rows.some((row) => row.id === selectedTypeIdRef.current)
          )
            handleSelectType(null);
          expandedIds.current = new Set(
            [...expandedIds.current].filter((id) => rows.some((row) => row.id === id)),
          );
        },
      );
    },
    [typeQuery, runTypes, handleSelectType],
  );

  const loadData = useCallback(
    (page?: number, size?: number) => {
      const id = selectedTypeIdRef.current;
      if (!id) return;
      const query = dataQuery.read({
        ...(page !== undefined ? { current: page } : {}),
        ...(size !== undefined ? { pageSize: size } : {}),
      });
      return runData(
        () => loadDictDataPage(query.current, query.pageSize, id),
        (result) => {
          setDictData(result.records);
          setDataPagination({
            current: result.current ?? query.current,
            pageSize: result.size ?? query.pageSize,
            total: result.total,
          });
        },
      );
    },
    [dataQuery, runData],
  );

  const loadOptions = useCallback(
    () => runOptions(() => fetchDictOptions(['enable_status', 'qt_yes_no']), setDicts),
    [runOptions],
  );
  useEffect(() => {
    void loadTypes();
    void loadOptions();
  }, [loadTypes, loadOptions]);
  useEffect(() => {
    if (selectedTypeId) void loadData(1, 10);
    else invalidateData();
  }, [selectedTypeId, loadData, invalidateData]);

  const handleCreateType = () => {
    if (!canEdit || isBusy()) return;
    editorScope.current = { kind: 'type', id: null };
    setEditingType(null);
    typeForm.resetFields();
    typeForm.setFieldsValue({ dataType: 'String', jdbcType: 'VARCHAR', status: 1 });
    setTypeModalVisible(true);
  };
  const handleEditType = (record: DictType) => {
    if (!canEdit || isBusy()) return;
    editorScope.current = { kind: 'type', id: record.id };
    typeForm.resetFields();
    setEditingType(record);
    typeForm.setFieldsValue(dictTypePayload(record));
    setTypeModalVisible(true);
  };
  const handleDeleteType = (id: number) => {
    if (!canEdit) return;
    return runMutation(
      async () => requireAdminSuccess(await dictApi.deleteType(id), '删除类型失败'),
      () => {
        message.success('删除成功');
        expandedIds.current.delete(id);
        if (selectedTypeIdRef.current === id) handleSelectType(null);
        refreshPreviews();
        void loadTypes();
      },
    );
  };
  const handleSubmitType = () => {
    const scope = editorScope.current;
    if (!canEdit || !scope || scope.kind !== 'type') return;
    return runMutation(
      async () => {
        const values = await typeForm.validateFields();
        if (editorScope.current !== scope) throw new Error('编辑目标已变化');
        requireAdminSuccess(
          scope.id
            ? await dictApi.updateType(scope.id, dictTypePayload(values))
            : await dictApi.createType(dictTypePayload(values)),
          '保存类型失败',
        );
      },
      () => {
        message.success('保存成功');
        setTypeModalVisible(false);
        editorScope.current = null;
        refreshPreviews();
        void loadTypes();
      },
    );
  };
  const handleCreateData = () => {
    if (!canEdit || isBusy() || !selectedTypeIdRef.current || typeLoading || typeError) return;
    editorScope.current = { kind: 'data', id: null };
    setEditingData(null);
    dataForm.resetFields();
    dataForm.setFieldsValue({
      dictTypeId: selectedTypeIdRef.current,
      dictSort: 0,
      status: 1,
      isDefault: 0,
    });
    setDataModalVisible(true);
  };
  const handleEditData = (record: DictData) => {
    if (!canEdit || isBusy()) return;
    editorScope.current = { kind: 'data', id: record.id };
    setEditingData(record);
    dataForm.resetFields();
    dataForm.setFieldsValue(dictDataPayload(record));
    setDataModalVisible(true);
  };
  const handleDeleteData = (id: number) => {
    if (!canEdit) return;
    return runMutation(
      async () => requireAdminSuccess(await dictApi.deleteData(id), '删除数据失败'),
      () => {
        message.success('删除成功');
        refreshPreviews();
        void loadData(1);
      },
    );
  };
  const handleSubmitData = () => {
    const scope = editorScope.current;
    if (!canEdit || !scope || scope.kind !== 'data') return;
    const typeId = editingData?.dictTypeId ?? selectedTypeIdRef.current;
    if (!typeId) return;
    return runMutation(
      async () => {
        const values = await dataForm.validateFields();
        if (editorScope.current !== scope) throw new Error('编辑目标已变化');
        const payload = dictDataPayload(values);
        payload.dictTypeId = typeId;
        requireAdminSuccess(
          scope.id
            ? await dictApi.updateData(scope.id, payload)
            : await dictApi.createData(payload),
          '保存数据失败',
        );
      },
      () => {
        message.success('保存成功');
        setDataModalVisible(false);
        editorScope.current = null;
        refreshPreviews();
        void loadData();
      },
    );
  };
  const renderStatus = (value: number) => (
    <Tag color={dictColor(dicts.enable_status, value)}>{dictLabel(dicts.enable_status, value)}</Tag>
  );
  const renderDefault = (value: number) => (
    <Tag color={dictColor(dicts.qt_yes_no, value)}>{dictLabel(dicts.qt_yes_no, value)}</Tag>
  );

  /** 字典类型表格列定义 */
  const typeColumns = [
    { title: '字典编码', dataIndex: 'dictCode', key: 'dictCode' },
    { title: '字典名称', dataIndex: 'dictName', key: 'dictName' },
    {
      title: '数据类型',
      dataIndex: 'dataType',
      key: 'dataType',
      render: (v: string) => <Tag color="blue">{v || '-'}</Tag>,
    },
    {
      title: 'JDBC类型',
      dataIndex: 'jdbcType',
      key: 'jdbcType',
      render: (v: string) => <Tag color="cyan">{v || '-'}</Tag>,
    },
    { title: '长度', dataIndex: 'dataLength', key: 'dataLength', render: (v: number) => v ?? '-' },
    { title: '描述', dataIndex: 'description', key: 'description' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: renderStatus,
    },
    {
      title: '操作',
      key: 'action',
      width: 140,
      render: (_: unknown, r: DictType) => (
        <Space>
          <Button
            type="link"
            icon={<EditOutlined />}
            onClick={() => handleEditType(r)}
            disabled={!canEdit || saving}
          >
            编辑
          </Button>
          <Popconfirm
            title="确认删除?"
            disabled={!canEdit || saving}
            onConfirm={() => handleDeleteType(r.id)}
          >
            <Button type="link" danger icon={<DeleteOutlined />} disabled={!canEdit || saving}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  /** 展开行渲染：显示该类型下的字典数据（预览前 100 条，完整列表在右侧数据表） */
  const expandedRowRender = (record: DictType) => {
    const data = cache.get(record.id) || [];
    return (
      <div>
        <AdminLoadNotice
          error={expandedErrors[record.id]}
          hasData={data.length > 0}
          onRetry={() => loadExpandedData(record.id)}
        />
        <ResizableTable
          loading={cache.isBusy(record.id)}
          dataSource={data}
          columns={[
            { title: '字典标签', dataIndex: 'dictLabel', key: 'dictLabel' },
            { title: '字典值', dataIndex: 'dictValue', key: 'dictValue' },
            { title: '排序', dataIndex: 'dictSort', key: 'dictSort' },
            {
              title: '默认',
              dataIndex: 'isDefault',
              key: 'isDefault',
              render: renderDefault,
            },
            {
              title: '状态',
              dataIndex: 'status',
              key: 'status',
              render: renderStatus,
            },
          ]}
          rowKey="id"
          scroll={{ x: 'max-content' }}
          pagination={false}
          size="small"
          locale={{ emptyText: expandedErrors[record.id] ? '预览未加载成功' : '暂无字典数据' }}
        />
        <div className="text-muted-foreground mt-1 text-xs">
          仅预览前 100 条，查看全部请选中左侧类型后在右侧「字典数据」表翻页
        </div>
      </div>
    );
  };

  /** 字典数据表格列定义 */
  const dataColumns = [
    { title: '字典标签', dataIndex: 'dictLabel', key: 'dictLabel' },
    { title: '字典值', dataIndex: 'dictValue', key: 'dictValue' },
    { title: '排序', dataIndex: 'dictSort', key: 'dictSort' },
    {
      title: '默认',
      dataIndex: 'isDefault',
      key: 'isDefault',
      render: renderDefault,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: renderStatus,
    },
    {
      title: '操作',
      key: 'action',
      width: 140,
      render: (_: unknown, r: DictData) => (
        <Space>
          <Button
            type="link"
            icon={<EditOutlined />}
            onClick={() => handleEditData(r)}
            disabled={!canEdit || saving}
          >
            编辑
          </Button>
          <Popconfirm
            title="确认删除?"
            disabled={!canEdit || saving}
            onConfirm={() => handleDeleteData(r.id)}
          >
            <Button type="link" danger icon={<DeleteOutlined />} disabled={!canEdit || saving}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <PageHeader title="数据字典" description="枚举字典与字典数据的统一维护" />
      <Tabs
        items={[
          {
            key: 'type',
            label: '字典类型',
            children: (
              <Card>
                <FilterBar split>
                  <Input.Search
                    aria-label="搜索字典编码或名称"
                    placeholder="搜索字典编码或名称"
                    value={typeKeyword}
                    allowClear
                    className="w-full sm:w-[300px]"
                    onChange={(event) => {
                      const next = event.target.value;
                      setTypeKeyword(next);
                      if (!next) void loadTypes(1, undefined, '');
                    }}
                    onSearch={(value) => {
                      setTypeKeyword(value);
                      void loadTypes(1, undefined, value);
                    }}
                  />
                  {canEdit && (
                    <Button
                      type="primary"
                      disabled={saving}
                      icon={<PlusOutlined />}
                      onClick={handleCreateType}
                    >
                      新建类型
                    </Button>
                  )}
                </FilterBar>
                <AdminLoadNotice error={typeError} hasData={types.length > 0} onRetry={loadTypes} />
                <AdminOptionNotice error={optionError} onRetry={loadOptions} />
                <ResizableTable
                  dataSource={types}
                  columns={typeColumns}
                  rowKey="id"
                  loading={typeLoading}
                  scroll={{ x: 'max-content' }}
                  expandable={{
                    expandedRowRender,
                    onExpand: (expanded, record) => {
                      if (expanded) {
                        expandedIds.current.add(record.id);
                        void loadExpandedData(record.id);
                      } else expandedIds.current.delete(record.id);
                    },
                  }}
                  pagination={{
                    ...typePagination,
                    onChange: (page, pageSize) => loadTypes(page, pageSize),
                    showQuickJumper: true,
                    showSizeChanger: true,
                    pageSizeOptions: ['5', '10', '20', '50', '100'],
                  }}
                />
              </Card>
            ),
          },
          {
            key: 'data',
            label: '字典数据',
            children: (
              <Card>
                <FilterBar split>
                  <Select
                    placeholder="选择字典类型"
                    aria-label="选择字典类型"
                    allowClear
                    disabled={typeLoading || !!typeError || saving}
                    className="w-full sm:w-[300px]"
                    value={selectedTypeId}
                    onChange={handleSelectType}
                    options={allTypes.map((t) => ({ label: t.dictName, value: t.id }))}
                  />
                  {canEdit && (
                    <Button
                      type="primary"
                      icon={<PlusOutlined />}
                      onClick={handleCreateData}
                      disabled={!selectedTypeId || saving || typeLoading || !!typeError}
                    >
                      新建数据
                    </Button>
                  )}
                </FilterBar>
                <AdminLoadNotice
                  error={typeError}
                  hasData={allTypes.length > 0}
                  onRetry={loadTypes}
                />
                <AdminLoadNotice
                  error={dataError}
                  hasData={dictData.length > 0}
                  onRetry={loadData}
                />
                <AdminOptionNotice error={optionError} onRetry={loadOptions} />
                <ResizableTable
                  locale={{
                    emptyText: !selectedTypeId
                      ? '请先选择字典类型'
                      : dataError
                        ? '字典数据未加载成功'
                        : '暂无字典数据',
                  }}
                  dataSource={dictData}
                  columns={dataColumns}
                  rowKey="id"
                  loading={dataLoading}
                  scroll={{ x: 'max-content' }}
                  pagination={{
                    ...dataPagination,
                    onChange: (page, pageSize) => {
                      void loadData(page, pageSize);
                    },
                    showQuickJumper: true,
                    showSizeChanger: true,
                    pageSizeOptions: ['5', '10', '20', '50', '100'],
                  }}
                />
              </Card>
            ),
          },
        ]}
      />

      <Modal
        title={editingType ? '编辑字典类型' : '新建字典类型'}
        open={typeModalVisible}
        onOk={handleSubmitType}
        onCancel={() => {
          if (!isBusy()) {
            editorScope.current = null;
            setTypeModalVisible(false);
          }
        }}
        confirmLoading={saving}
        cancelButtonProps={{ disabled: saving }}
        okButtonProps={{ disabled: saving || !canEdit }}
      >
        <Form form={typeForm} layout="vertical" disabled={saving} style={{ marginTop: 16 }}>
          <Form.Item name="dictCode" label="字典编码" rules={[{ required: true }]}>
            <Input disabled={!!editingType} />
          </Form.Item>
          <Form.Item name="dictName" label="字典名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="dataType" label="数据类型" rules={[{ required: true }]}>
            <Select
              options={Object.keys(typeMapping).map((t) => ({ label: t, value: t }))}
              onChange={(val) => {
                if (typeMapping[val]) {
                  typeForm.setFieldsValue({ jdbcType: typeMapping[val][0] });
                }
              }}
            />
          </Form.Item>
          <Form.Item name="jdbcType" label="JDBC类型">
            <Form.Item<DictTypeWrite>
              noStyle
              shouldUpdate={(prev, next) => prev.dataType !== next.dataType}
            >
              {({ getFieldValue }) => {
                const dataType = getFieldValue('dataType');
                const options = (typeMapping[dataType as string] || []).map((t) => ({
                  label: t,
                  value: t,
                }));
                return <Select options={options} />;
              }}
            </Form.Item>
          </Form.Item>
          <Form.Item name="dataLength" label="长度">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={editingData ? '编辑字典数据' : '新建字典数据'}
        open={dataModalVisible}
        onOk={handleSubmitData}
        onCancel={() => {
          if (!isBusy()) {
            editorScope.current = null;
            setDataModalVisible(false);
          }
        }}
        confirmLoading={saving}
        cancelButtonProps={{ disabled: saving }}
        okButtonProps={{ disabled: saving || !canEdit }}
      >
        <Form form={dataForm} layout="vertical" disabled={saving} style={{ marginTop: 16 }}>
          <Form.Item name="dictTypeId" hidden>
            <Input />
          </Form.Item>
          <Form.Item name="dictLabel" label="字典标签" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="dictValue" label="字典值" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="dictSort" label="排序" initialValue={0}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
