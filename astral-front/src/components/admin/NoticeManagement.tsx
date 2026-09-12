'use client';

import { useEffect, useState } from 'react';
import { Card, Tag, Button, Space, Select, Input, Modal, Form, Row, Col, Switch, DatePicker, InputNumber, message, Popconfirm, Typography } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import {
  messageAdminApi, SysNotice,
  noticeTypeLabel, noticeChannelLabel, noticeTypeOptions, noticeChannelOptions, loadFeedbackDicts,
} from '@/api/feedback';
import { ResizableTable } from '@/components/ResizableTable';

const { Paragraph } = Typography;

/** 展示渠道选项（位掩码） */
const DISPLAY_OPTIONS = [
  { label: '开屏弹窗 (1)', value: 1 },
  { label: '首页通告栏 (2)', value: 2 },
  { label: '消息中心 (4)', value: 4 },
];

/** 可见人群 */
const AUDIENCE_OPTIONS = [
  { label: '全部', value: 'ALL' },
  { label: '仅登录用户', value: 'LOGGED_IN' },
  { label: '仅游客', value: 'NOT_LOGGED_IN' },
];

/**
 * 通知管理面板（反馈插件 sys_notice）
 * <p>被 dashboard/feedback 页 Tabs 与 dashboard/message 页共用。</p>
 */
export default function NoticeManagement() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<SysNotice[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [channelFilter, setChannelFilter] = useState<string | undefined>(undefined);
  const [typeFilter, setTypeFilter] = useState<string | undefined>(undefined);
  const [keyword, setKeyword] = useState('');

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<SysNotice | null>(null);
  const [form] = Form.useForm();

  const load = (p = page, s = pageSize) => {
    setLoading(true);
    const params: Record<string, any> = { pageNum: p, pageSize: s };
    if (channelFilter) params.channel = channelFilter;
    if (typeFilter) params.noticeType = typeFilter;
    if (keyword) params.keyword = keyword;
    messageAdminApi.page(params)
      .then((res) => {
        setData(res.data?.records || []);
        setTotal(res.data?.total || 0);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load(1, pageSize);
    loadFeedbackDicts().catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [channelFilter, typeFilter]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      channel: 'app',
      noticeType: 'announce',
      display: [4],
      isShow: 1,
      isTop: 0,
      dialogClosable: 1,
      firstLoginOnly: 0,
      marquee: 0,
      audience: 'ALL',
    });
    setModalOpen(true);
  };

  const openEdit = (record: SysNotice) => {
    setEditing(record);
    form.resetFields();
    const display = record.display ? [1, 2, 4].filter((b) => (record.display! & b) === b) : [4];
    form.setFieldsValue({
      ...record,
      display,
      isShow: record.isShow ?? 1,
      isTop: record.isTop ?? 0,
      dialogClosable: record.dialogClosable ?? 1,
      firstLoginOnly: record.firstLoginOnly ?? 0,
      marquee: record.marquee ?? 0,
      audience: record.audience || 'ALL',
      channel: record.channel || 'app',
      noticeType: record.noticeType || 'announce',
      effectiveRange: record.effectiveStart && record.effectiveEnd
        ? [dayjs(record.effectiveStart), dayjs(record.effectiveEnd)] : undefined,
    });
    setModalOpen(true);
  };

  const submit = async () => {
    const values = await form.validateFields();
    // 位掩码列表 → 数值
    const display = Array.isArray(values.display)
      ? (values.display as number[]).reduce((acc, b) => acc | b, 0) : values.display;
    const payload: SysNotice = {
      id: editing?.id,
      channel: values.channel,
      noticeType: values.noticeType,
      title: values.title,
      content: values.content,
      url: values.url,
      userId: values.userId,
      display,
      audience: values.audience || 'ALL',
      versionMin: values.versionMin,
      versionMax: values.versionMax,
      isShow: values.isShow ?? 1,
      isTop: values.isTop ?? 0,
      dialogClosable: values.dialogClosable ?? 1,
      firstLoginOnly: values.firstLoginOnly ?? 0,
      marquee: values.marquee ?? 0,
      effectiveStart: values.effectiveRange?.[0]?.format('YYYY-MM-DD HH:mm:ss') || undefined,
      effectiveEnd: values.effectiveRange?.[1]?.format('YYYY-MM-DD HH:mm:ss') || undefined,
    };
    try {
      if (editing?.id) {
        await messageAdminApi.update(editing.id, payload);
        message.success('已更新');
      } else {
        await messageAdminApi.create(payload);
        message.success('已发布');
      }
      setModalOpen(false);
      load();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await messageAdminApi.delete(id);
      message.success('已删除');
      load();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    {
      title: '渠道', dataIndex: 'channel', width: 80,
      render: (v: string) => <Tag color={v === 'all' ? 'purple' : v === 'web' ? 'green' : 'blue'}>{noticeChannelLabel(v)}</Tag>,
    },
    {
      title: '类型', dataIndex: 'noticeType', width: 90,
      render: (v: string) => <Tag color={v === 'announce' ? 'cyan' : v === 'request' ? 'orange' : 'geekblue'}>{noticeTypeLabel(v)}</Tag>,
    },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    {
      title: '展示位', dataIndex: 'display', width: 140,
      render: (v: number) => DISPLAY_OPTIONS.filter((o) => (v & o.value) === o.value).map((o) => o.label.replace(/ \(\d\)$/, '')).join(' / ') || '-',
    },
    {
      title: '人群', dataIndex: 'audience', width: 100,
      render: (v: string) => v === 'ALL' ? '全部' : v === 'LOGGED_IN' ? '登录' : '游客',
    },
    { title: '目标用户', dataIndex: 'userId', width: 90, render: (v: number) => v ?? <Tag>广播</Tag> },
    {
      title: '启用', dataIndex: 'isShow', width: 70,
      render: (v: number) => v === 1 ? <Tag color="green">是</Tag> : <Tag color="red">否</Tag>,
    },
    { title: '时间', dataIndex: 'createTime', width: 170, render: (v: string) => v || '-' },
    {
      title: '操作', key: 'action', width: 150,
      render: (_: any, record: SysNotice) => (
        <Space>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>编辑</Button>
          <Popconfirm title="确认删除该通知?" onConfirm={() => handleDelete(record.id!)}>
            <Button type="link" danger size="small" icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div>
            <h2 style={{ margin: 0 }}>通知管理</h2>
            <Paragraph type="secondary" style={{ margin: '4px 0 0' }}>
              统一通知 sys_notice：公告/反馈/需求通知（App/Web 渠道，广播或点对点）
            </Paragraph>
          </div>
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => load()}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>发布通知</Button>
          </Space>
        </div>

        <div style={{ marginBottom: 16 }}>
          <Space wrap>
            <Input.Search placeholder="搜索标题/内容" allowClear style={{ width: 220 }}
              onSearch={(v) => { setKeyword(v); load(1, pageSize); }} />
            <Select placeholder="渠道" allowClear style={{ width: 120 }} value={channelFilter}
              onChange={(v) => setChannelFilter(v)}
              options={noticeChannelOptions()} />
            <Select placeholder="类型" allowClear style={{ width: 120 }} value={typeFilter}
              onChange={(v) => setTypeFilter(v)}
              options={noticeTypeOptions()} />
          </Space>
        </div>

        <ResizableTable
          rowKey="id"
          columns={columns}
          dataSource={data}
          loading={loading}
          pagination={{
            current: page,
            total,
            pageSize,
            showSizeChanger: true,
            showQuickJumper: true,
            pageSizeOptions: ['5', '10', '20', '50', '100'],
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p, ps) => { setPage(p); setPageSize(ps); load(p, ps); },
          }}
        />
      </Card>

      {/* 发布/编辑通知弹窗 */}
      <Modal
        title={editing?.id ? '编辑通知' : '发布通知'}
        open={modalOpen}
        onOk={submit}
        onCancel={() => setModalOpen(false)}
        width={640}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="channel" label="渠道" rules={[{ required: true, message: '请选择渠道' }]}>
                <Select options={noticeChannelOptions()} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="noticeType" label="类型" rules={[{ required: true, message: '请选择类型' }]}>
                <Select options={noticeTypeOptions()} />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="通知/公告标题" />
          </Form.Item>

          <Form.Item name="display" label="展示类型（可多选）" rules={[{ required: true, message: '请至少选择一个展示渠道' }]}>
            <Select mode="multiple" options={DISPLAY_OPTIONS} placeholder="选择展示位置" />
          </Form.Item>

          <Form.Item name="content" label="内容">
            <Input.TextArea rows={4} placeholder="通知内容（支持富文本）" />
          </Form.Item>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="url" label="跳转链接">
                <Input placeholder="点击后跳转链接（可选）" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="userId" label="目标用户ID（留空=广播）">
                <InputNumber style={{ width: '100%' }} placeholder="广播则留空" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="effectiveRange" label="生效时间区间（留空=长期）">
                <DatePicker.RangePicker showTime format="YYYY-MM-DD HH:mm:ss" style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="audience" label="可见用户人群">
                <Select options={AUDIENCE_OPTIONS} />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="versionMin" label="生效版本码下限"><InputNumber style={{ width: '100%' }} placeholder="如 300（3.0.0），可选" /></Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="versionMax" label="生效版本码上限"><InputNumber style={{ width: '100%' }} placeholder="如 399，可选" /></Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={8}>
              <Form.Item name="isShow" label="是否启用" valuePropName="checked" getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)} getValueProps={(v: number) => ({ checked: v === 1 })}>
                <Switch checkedChildren="启用" unCheckedChildren="停用" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="isTop" label="是否置顶" valuePropName="checked" getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)} getValueProps={(v: number) => ({ checked: v === 1 })}>
                <Switch checkedChildren="置顶" unCheckedChildren="普通" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="marquee" label="通告栏跑马灯" valuePropName="checked" getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)} getValueProps={(v: number) => ({ checked: v === 1 })}>
                <Switch checkedChildren="滚动" unCheckedChildren="静止" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="dialogClosable" label="开屏弹窗可关闭" valuePropName="checked" getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)} getValueProps={(v: number) => ({ checked: v === 1 })}>
                <Switch checkedChildren="可关闭" unCheckedChildren="不可关闭" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="firstLoginOnly" label="仅首次登录弹出" valuePropName="checked" getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)} getValueProps={(v: number) => ({ checked: v === 1 })}>
                <Switch checkedChildren="仅首次" unCheckedChildren="每次" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </div>
  );
}
