'use client';

import { useEffect, useRef, useState } from 'react';
import { Card, Button, Space, Modal, Form, Input, InputNumber, Select, Switch, Tag, Popconfirm, message } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, MenuOutlined, HolderOutlined } from '@ant-design/icons';
import { menuApi, SysMenu } from '@/api/menu';
import { ResizableTable } from '@/components/ResizableTable';

const iconOptions = [
  'DashboardOutlined', 'ApiOutlined', 'BarChartOutlined', 'ClusterOutlined',
  'SettingOutlined', 'TeamOutlined', 'SafetyOutlined', 'BookOutlined',
  'KeyOutlined', 'TableOutlined', 'MenuOutlined', 'FileTextOutlined',
  'BlockOutlined', 'UserOutlined', 'ToolOutlined', 'LogoutOutlined',
];

/** 在树中定位节点及其兄弟数组 */
const findNodeContext = (
  nodes: SysMenu[],
  id: number,
): { node: SysMenu; siblings: SysMenu[]; index: number } | null => {
  for (let i = 0; i < nodes.length; i++) {
    if (nodes[i].id === id) return { node: nodes[i], siblings: nodes, index: i };
    if (nodes[i].children) {
      const found = findNodeContext(nodes[i].children!, id);
      if (found) return found;
    }
  }
  return null;
};

/** 收集某节点及其所有后代的 id */
const collectSubtreeIds = (node: SysMenu, acc: Set<number>) => {
  acc.add(node.id);
  node.children?.forEach((c) => collectSubtreeIds(c, acc));
  return acc;
};

/** 收集某节点的所有后代 id（含自身），用于编辑时排除自身及子孙 */
const getDescendantIds = (tree: SysMenu[], id: number): Set<number> => {
  const ctx = findNodeContext(tree, id);
  const set = new Set<number>();
  if (ctx) collectSubtreeIds(ctx.node, set);
  return set;
};

export default function MenuPage() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<SysMenu[]>([]);
  const [modalOpen, setModalOpen] = useState(false);
  const [editRecord, setEditRecord] = useState<SysMenu | null>(null);
  const [form] = Form.useForm();
  const dragIdRef = useRef<number | null>(null);

  const loadData = () => {
    setLoading(true);
    menuApi.getTree().then(res => {
      if (res.code === 200) setData(res.data);
    }).finally(() => setLoading(false));
  };

  useEffect(() => { loadData(); }, []);

  /** 父菜单下拉选项：展示中文名，带层级缩进 */
  const buildParentOptions = (excludeId?: number) => {
    const opts: { value: number; label: string }[] = [{ value: 0, label: '顶级菜单' }];
    const exclude = excludeId ? getDescendantIds(data, excludeId) : new Set<number>();
    const walk = (nodes: SysMenu[], depth: number) => {
      nodes.forEach(n => {
        if (exclude.has(n.id)) return;
        opts.push({ value: n.id, label: '　'.repeat(depth) + n.name });
        if (n.children) walk(n.children, depth + 1);
      });
    };
    walk(data, 0);
    return opts;
  };

  const handleCreate = () => {
    setEditRecord(null);
    form.resetFields();
    form.setFieldsValue({ visible: 1, type: 0, sort: 0, parentId: 0 });
    setModalOpen(true);
  };

  const handleEdit = (record: SysMenu) => {
    setEditRecord(record);
    form.resetFields();
    form.setFieldsValue(record);
    setModalOpen(true);
  };

  const handleDelete = async (id: number) => {
    const res = await menuApi.delete(id);
    if (res.code === 200) { message.success('已删除'); loadData(); }
  };

  const handleSave = async () => {
    const values = await form.validateFields();
    const res = editRecord
      ? await menuApi.update(editRecord.id, values)
      : await menuApi.create(values);
    if (res.code === 200) { message.success(editRecord ? '已更新' : '已创建'); setModalOpen(false); loadData(); }
  };

  /** 拖拽落下：同父级重排，跨父级则移动并改 parentId */
  const handleDrop = async (targetId: number) => {
    const dragId = dragIdRef.current;
    dragIdRef.current = null;
    if (!dragId || dragId === targetId) return;
    const src = findNodeContext(data, dragId);
    const tgt = findNodeContext(data, targetId);
    if (!src || !tgt) return;
    if (src.node.id === tgt.node.id) return;
    // 不能拖进自己的子孙节点
    if (collectSubtreeIds(src.node, new Set()).has(tgt.node.id)) return;

    const moved = src.siblings.splice(src.index, 1)[0];
    moved.parentId = tgt.node.parentId;
    tgt.siblings.splice(tgt.index, 0, moved);

    const updates: { id: number; sort: number; parentId?: number }[] = [];
    const reassign = (sibs: SysMenu[]) =>
      sibs.forEach((n, i) =>
        updates.push({ id: n.id, sort: i, ...(n.id === moved.id ? { parentId: moved.parentId } : {}) }),
      );
    reassign(src.siblings);
    reassign(tgt.siblings);

    setData([...data]);
    try {
      await Promise.all(updates.map(u => menuApi.update(u.id, { sort: u.sort, parentId: u.parentId })));
      message.success('排序已保存');
    } catch {
      message.error('保存失败');
      loadData();
    }
  };

  const columns = [
    {
      title: '拖拽',
      key: 'drag',
      width: 50,
      render: () => <HolderOutlined style={{ color: '#999', cursor: 'move' }} />,
    },
    { title: '菜单名称', dataIndex: 'name', key: 'name' },
    { title: '图标', dataIndex: 'icon', key: 'icon', render: (v: string) => v || '-' },
    { title: '路由', dataIndex: 'path', key: 'path', render: (v: string) => v ? <Tag>{v}</Tag> : '-' },
    { title: '权限', dataIndex: 'permission', key: 'permission', render: (v: string) => v ? <Tag color="blue">{v}</Tag> : '-' },
    { title: '排序', dataIndex: 'sort', key: 'sort', width: 60 },
    {
      title: '可见', dataIndex: 'visible', key: 'visible', width: 60,
      render: (v: number) => v === 1 ? <Tag color="green">是</Tag> : <Tag color="red">否</Tag>,
    },
    {
      title: '操作', key: 'action', width: 160,
      render: (_: any, record: SysMenu) => (
        <Space>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)}>编辑</Button>
          <Popconfirm title="确认删除？" onConfirm={() => handleDelete(record.id)}>
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card
        title={<><MenuOutlined /> 菜单管理</>}
        extra={<Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新增菜单</Button>}
      >
        <ResizableTable
          columns={columns}
          dataSource={data}
          rowKey="id"
          loading={loading}
          scroll={{ x: 'max-content' }}
          pagination={false}
          defaultExpandAllRows
          onRow={(record) => ({
            draggable: true,
            style: { cursor: 'move' },
            onDragStart: () => { dragIdRef.current = record.id; },
            onDragOver: (e: React.DragEvent) => e.preventDefault(),
            onDrop: () => handleDrop(record.id),
          })}
        />
      </Card>

      <Modal
        title={editRecord ? '编辑菜单' : '新增菜单'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={handleSave}
        width={600}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="parentId" label="父菜单" rules={[{ required: true }]}>
            <Select
              showSearch
              optionFilterProp="label"
              placeholder="选择父菜单"
              options={buildParentOptions(editRecord?.id)}
            />
          </Form.Item>
          <Form.Item name="name" label="菜单名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="icon" label="图标名称">
            <Select allowClear placeholder="选择图标" options={iconOptions.map(i => ({ value: i, label: i }))} />
          </Form.Item>
          <Form.Item name="path" label="路由路径">
            <Input placeholder="/dashboard/xxx" />
          </Form.Item>
          <Form.Item name="permission" label="权限标识">
            <Input placeholder="module:action" />
          </Form.Item>
          <Space style={{ width: '100%' }} size="large">
            <Form.Item name="sort" label="排序">
              <InputNumber />
            </Form.Item>
            <Form.Item name="visible" label="可见" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="type" label="类型">
              <Select style={{ width: 120 }} options={[
                { value: 0, label: '目录' },
                { value: 1, label: '菜单' },
                { value: 2, label: '按钮' },
              ]} />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </div>
  );
}
