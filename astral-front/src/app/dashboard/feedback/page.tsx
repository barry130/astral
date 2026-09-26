'use client';

import { useEffect, useMemo, useState } from 'react';
import { Card, Row, Col, Statistic, Tag, Button, Space, Select, Input, Switch, Popconfirm, Drawer, Timeline, Form, message, Typography, Divider, Tabs, Tooltip } from 'antd';
import { ReloadOutlined, MessageOutlined, DeleteOutlined, SendOutlined, CheckCircleOutlined, ExclamationCircleOutlined } from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import {
  feedbackAdminApi, FEEDBACK_STATUS_COLOR,
  feedbackStatusLabel, feedbackTypeLabel, feedbackStatusOptions, feedbackTypeOptions,
  loadFeedbackDicts,
  Feedback, FeedbackReply, FeedbackStat,
} from '@/api/feedback';
import NoticeManagement from '@/components/admin/NoticeManagement';
import { ResizableTable } from '@/components/ResizableTable';

const { Text, Paragraph } = Typography;

/** 合法状态流转（与后端一致） */
const TRANSITIONS: Record<string, string[]> = {
  pending: ['received', 'deprecated'],
  received: ['resolved', 'deprecated'],
  resolved: ['published', 'deprecated'],
  published: ['deprecated'],
  // 已废弃允许任意转回（防止误操作无法恢复）
  deprecated: ['pending', 'received', 'resolved', 'published'],
};

/** 反馈管理面板（Tabs 内嵌） */
function FeedbackPanel() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<Feedback[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);
  const [typeFilter, setTypeFilter] = useState<string | undefined>(undefined);
  const [keyword, setKeyword] = useState('');
  const [stat, setStat] = useState<FeedbackStat | null>(null);

  // 详情 Drawer
  const [detail, setDetail] = useState<Feedback | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [replies, setReplies] = useState<FeedbackReply[]>([]);
  const [replyText, setReplyText] = useState('');
  const [replying, setReplying] = useState(false);

  const load = (p = page, s = pageSize) => {
    setLoading(true);
    const params: Record<string, any> = { pageNum: p, pageSize: s };
    if (statusFilter) params.status = statusFilter;
    if (typeFilter) params.type = typeFilter;
    if (keyword) params.keyword = keyword;
    feedbackAdminApi.page(params)
      .then((res) => {
        setData(res.data?.records || []);
        setTotal(res.data?.total || 0);
      })
      .finally(() => setLoading(false));
  };

  const loadStat = () => {
    feedbackAdminApi.stat().then((res) => {
      if (res.code === 200) setStat(res.data);
    }).catch(() => {});
  };

  useEffect(() => {
    load(1, pageSize);
    loadStat();
    loadFeedbackDicts().catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [statusFilter, typeFilter]);

  const refresh = () => { load(); loadStat(); };

  const openDetail = async (record: Feedback) => {
    setDetail(record);
    setDrawerOpen(true);
    try {
      const res = await feedbackAdminApi.replies(record.id!);
      if (res.code === 200) setReplies(res.data || []);
    } catch (e: any) {
      setReplies([]);
    }
  };

  const handleStatusChange = async (id: number, status: string) => {
    try {
      await feedbackAdminApi.changeStatus(id, status);
      message.success('状态已更新');
      refresh();
      if (detail?.id === id) { setDetail((d) => d ? { ...d, status } : d); openDetail(detail); }
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handlePublicChange = async (record: Feedback, checked: boolean) => {
    try {
      await feedbackAdminApi.changePublic(record.id!, checked);
      message.success(checked ? '已设为公开' : '已设为私有');
      refresh();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await feedbackAdminApi.delete(id);
      message.success('已删除');
      refresh();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const submitReply = async () => {
    if (!detail || !replyText.trim()) {
      message.warning('请输入回复内容');
      return;
    }
    setReplying(true);
    try {
      await feedbackAdminApi.reply(detail.id!, replyText.trim());
      message.success('回复成功');
      setReplyText('');
      openDetail(detail);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setReplying(false);
    }
  };

  /** 近7天柱状图 */
  const chartOption = useMemo(() => {
    const days = stat?.last7Days || [];
    return {
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 20, top: 30, bottom: 30 },
      xAxis: { type: 'category', data: days.map((d) => d.date) },
      yAxis: { type: 'value', minInterval: 1 },
      series: [{
        name: '新增反馈',
        type: 'bar',
        data: days.map((d) => d.count),
        itemStyle: { color: '#18181b', borderRadius: [4, 4, 0, 0] },
        barMaxWidth: 36,
      }],
    };
  }, [stat]);

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    {
      title: '类型', dataIndex: 'type', width: 90,
      render: (v: string) => v === 'request'
        ? <Tag color="orange">{feedbackTypeLabel(v)}</Tag>
        : <Tag color="blue">{feedbackTypeLabel(v)}</Tag>,
    },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    {
      title: '状态', dataIndex: 'status', width: 130,
      render: (v: string, record: Feedback) => {
        const targets = TRANSITIONS[v] || [];
        return (
          <Select
            size="small"
            value={v}
            style={{ width: 110 }}
            onChange={(s) => handleStatusChange(record.id!, s)}
            disabled={targets.length === 0}
          >
            {/* 当前状态（disabled 仅展示中文标签），下方为可流转的目标状态 */}
            <Select.Option value={v} disabled>{feedbackStatusLabel(v)}</Select.Option>
            {targets.map((s) => (
              <Select.Option key={s} value={s}>{feedbackStatusLabel(s)}</Select.Option>
            ))}
          </Select>
        );
      },
    },
    {
      title: '公开', dataIndex: 'isPublic', width: 110,
      render: (v: boolean, record: Feedback) => {
        // 公开墙生效条件：is_public=true 且 status=published（与后端 publicPage 一致）
        const onWall = !!v && record.status === 'published';
        return (
          <Space size={4}>
            <Switch size="small" checked={!!v} onChange={(c) => handlePublicChange(record, c)} />
            {onWall && (
              <Tooltip title="已展示在公开墙">
                <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 13 }} />
              </Tooltip>
            )}
            {!!v && !onWall && (
              <Tooltip title="已设公开，但状态需流转到「已发布」才会展示在公开墙">
                <ExclamationCircleOutlined style={{ color: '#faad14', fontSize: 13 }} />
              </Tooltip>
            )}
          </Space>
        );
      },
    },
    { title: '设备', dataIndex: 'device', width: 140, ellipsis: true, render: (v: string) => v || '-' },
    { title: '版本', dataIndex: 'appVersion', width: 90, render: (v: string) => v || '-' },
    {
      title: '提交人', dataIndex: 'username', width: 130, ellipsis: true,
      render: (v: string, record: Feedback) => v || (record.userId ? `用户${record.userId}` : '-'),
    },
    { title: '用户邮箱', dataIndex: 'email', width: 170, ellipsis: true, render: (v: string) => v || '-' },
    { title: '联系方式', dataIndex: 'contact', width: 150, ellipsis: true, render: (v: string) => v || '-' },
    { title: '时间', dataIndex: 'createTime', width: 170, render: (v: string) => v || '-' },
    {
      title: '操作', key: 'action', width: 160, fixed: 'right' as const,
      render: (_: any, record: Feedback) => (
        <Space>
          <Button type="link" size="small" icon={<MessageOutlined />} onClick={() => openDetail(record)}>详情</Button>
          <Popconfirm title="确认删除该反馈?" onConfirm={() => handleDelete(record.id!)}>
            <Button type="link" danger size="small" icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      {/* 统计卡片 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 12 }}>
        <Col xs={{ span: 12 }} sm={{ span: 8 }} lg={{ span: 4 }}><Card size="small"><Statistic title="全部" value={stat?.total ?? '-'} /></Card></Col>
        <Col xs={{ span: 12 }} sm={{ span: 8 }} lg={{ span: 4 }}><Card size="small"><Statistic title="待处理" value={stat?.pending ?? '-'} valueStyle={{ color: '#fa8c16' }} /></Card></Col>
        <Col xs={{ span: 12 }} sm={{ span: 8 }} lg={{ span: 4 }}><Card size="small"><Statistic title="已接收" value={stat?.received ?? '-'} valueStyle={{ color: '#18181b' }} /></Card></Col>
        <Col xs={{ span: 12 }} sm={{ span: 8 }} lg={{ span: 4 }}><Card size="small"><Statistic title="今日新增" value={stat?.todayNew ?? '-'} valueStyle={{ color: '#52c41a' }} /></Card></Col>
        <Col xs={{ span: 12 }} sm={{ span: 8 }} lg={{ span: 4 }}><Card size="small"><Statistic title="未回复" value={stat?.unreplied ?? '-'} valueStyle={{ color: '#eb2f96' }} /></Card></Col>
        <Col xs={{ span: 12 }} sm={{ span: 8 }} lg={{ span: 4 }}><Card size="small"><Statistic title="已解决" value={stat?.resolved ?? '-'} /></Card></Col>
      </Row>

      <Card>
        <div className="filter-bar" style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Space wrap>
            <Input.Search placeholder="搜索标题/内容" allowClear style={{ width: 220 }}
              onSearch={(v) => { setKeyword(v); load(1, pageSize); }} />
            <Select placeholder="状态" allowClear style={{ width: 130 }} value={statusFilter}
              onChange={(v) => setStatusFilter(v)}
              options={feedbackStatusOptions()} />
            <Select placeholder="类型" allowClear style={{ width: 120 }} value={typeFilter}
              onChange={(v) => setTypeFilter(v)}
              options={feedbackTypeOptions()} />
          </Space>
          <div className="page-toolbar">
            <Button icon={<ReloadOutlined />} onClick={refresh}>刷新</Button>
          </div>
        </div>

        {/* 近7天柱状图 */}
        <Card size="small" style={{ marginBottom: 16 }}>
          <Text strong>近7天新增反馈</Text>
          <div className="chart-box" style={{ height: 320 }}>
            <ReactECharts option={chartOption} notMerge autoResize style={{ height: '100%' }} />
          </div>
        </Card>

        <ResizableTable
          rowKey="id"
          columns={columns}
          dataSource={data}
          loading={loading}
          scroll={{ x: 'max-content' }}
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

      {/* 详情 Drawer */}
      <Drawer
        title={detail ? `${feedbackTypeLabel(detail.type)}：${detail.title}` : '反馈详情'}
        width={560}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
      >
        {detail && (
          <div>
            <div style={{ marginBottom: 16 }}>
              <Space wrap>
                <Tag color={FEEDBACK_STATUS_COLOR[detail.status!]}>{feedbackStatusLabel(detail.status!)}</Tag>
                <Tag color={detail.type === 'request' ? 'orange' : 'blue'}>{feedbackTypeLabel(detail.type!)}</Tag>
                {detail.isPublic
                  ? (detail.status === 'published'
                    ? <Tag color="green">公开 · 已上墙</Tag>
                    : <Tag color="gold">公开 · 待发布</Tag>)
                  : <Tag>私有</Tag>}
              </Space>
            </div>
            <Paragraph>{detail.content}</Paragraph>
            <Divider style={{ margin: '12px 0' }} />
            <Space direction="vertical" size={2}>
              <Text type="secondary">提交人：{detail.username || `用户${detail.userId}`}　用户ID：{detail.userId}</Text>
              <Text type="secondary">用户邮箱：{detail.email || '-'}　联系方式：{detail.contact || '-'}</Text>
              <Text type="secondary">设备：{detail.device || '-'}　系统：{detail.os || '-'}</Text>
              <Text type="secondary">版本：{detail.appVersion || '-'}　平台：{detail.platform || '-'}　IP：{detail.ip || '-'}</Text>
              <Text type="secondary">提交时间：{detail.createTime || '-'}</Text>
            </Space>
            <Divider style={{ margin: '12px 0' }}>回复记录</Divider>
            <Timeline
              items={(replies || []).map((r) => ({
                children: (
                  <div>
                    <Space>
                      <Text strong>{r.nickname || `用户${r.userId}`}</Text>
                      {r.userType === 'ADMIN' && <Tag color="red">官方</Tag>}
                      <Text type="secondary" style={{ fontSize: 12 }}>{r.replyTime}</Text>
                    </Space>
                    <div style={{ marginTop: 4 }}>{r.content}</div>
                  </div>
                ),
              }))}
            />
            {(!replies || replies.length === 0) && <Text type="secondary">暂无回复</Text>}
            <Divider style={{ margin: '16px 0 12px' }} />
            <Space.Compact style={{ width: '100%' }}>
              <Input.TextArea
                rows={2}
                placeholder="输入回复内容..."
                value={replyText}
                onChange={(e) => setReplyText(e.target.value)}
              />
              <Button type="primary" icon={<SendOutlined />} loading={replying} onClick={submitReply} style={{ height: 'auto' }}>回复</Button>
            </Space.Compact>
          </div>
        )}
      </Drawer>
    </div>
  );
}

/**
 * 反馈插件管理页（侧边栏唯一入口 /dashboard/feedback）
 * <p>Tabs 内切换：反馈管理（sys_feedback）/ 通知管理（sys_notice）。</p>
 */
export default function FeedbackPage() {
  return (
    <Tabs
      defaultActiveKey="feedback"
      destroyInactiveTabPane
      items={[
        { key: 'feedback', label: '反馈管理', children: <FeedbackPanel /> },
        { key: 'message', label: '通知管理', children: <NoticeManagement /> },
      ]}
    />
  );
}
