'use client';

import { useEffect, useState } from 'react';
import { Card, Table, Row, Col, Statistic, DatePicker, Input, Select, Button, Space, Tag, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { mailApi, MailLog, MailStatistics } from '@/api/mail';
import type { RangePickerProps } from 'antd/es/date-picker';

const { RangePicker } = DatePicker;

export default function MailLogPage() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<MailLog[]>([]);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [stats, setStats] = useState<MailStatistics>({});
  const [filters, setFilters] = useState<{ toEmail?: string; status?: number; range?: any }>({});

  useEffect(() => { loadStats(); loadData(); }, []);

  const loadStats = () => {
    mailApi.logStatistics().then((res: any) => { if (res.code === 200) setStats(res.data || {}); }).catch(() => {});
  };

  // f 允许调用方显式传入「本次要生效的筛选条件」：setState 是异步的，
  // 紧接着 loadData 时闭包里的 filters 仍是旧值，直接读会漏掉本次筛选。
  const loadData = (page = 1, size = 10, f = filters) => {
    setLoading(true);
    const params: any = { pageNum: page, pageSize: size };
    if (f.toEmail) params.toEmail = f.toEmail;
    if (f.status !== undefined) params.status = f.status;
    if (f.range && f.range[0] && f.range[1]) {
      params.start = f.range[0].format('YYYY-MM-DD HH:mm:ss');
      params.end = f.range[1].format('YYYY-MM-DD HH:mm:ss');
    }
    mailApi.logPage(params)
      .then((res: any) => {
        if (res.code === 200) {
          setData(res.data?.records || []);
          setPagination({ current: res.data?.current || 1, pageSize: res.data?.size || 10, total: res.data?.total || 0 });
        }
      })
      .finally(() => setLoading(false));
  };

  const statusTag = (s?: number) => {
    if (s === 1) return <Tag color="green">成功</Tag>;
    if (s === 0) return <Tag color="red">失败</Tag>;
    return <Tag>{s}</Tag>;
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 70 },
    { title: '插件', dataIndex: 'pluginId', key: 'pluginId' },
    { title: '场景', dataIndex: 'scene', key: 'scene' },
    { title: '收件人', dataIndex: 'toEmail', key: 'toEmail' },
    { title: '主题', dataIndex: 'subject', key: 'subject', ellipsis: true },
    { title: '状态', dataIndex: 'status', key: 'status', render: (v: number) => statusTag(v) },
    {
      title: '错误信息', dataIndex: 'errorMsg', key: 'errorMsg',
      render: (v: string) => v ? <span style={{ color: '#cf1322' }}>{v}</span> : '-',
    },
    { title: '发送时间', dataIndex: 'sendTime', key: 'sendTime', render: (v?: string) => (v ? new Date(v).toLocaleString() : '-') },
  ];

  return (
    <div>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={{ span: 12 }} sm={{ span: 8 }} lg={{ span: 4 }}><Card><Statistic title="累计发送" value={stats.total || 0} /></Card></Col>
        <Col xs={{ span: 12 }} sm={{ span: 8 }} lg={{ span: 4 }}><Card><Statistic title="累计成功" value={stats.success || 0} valueStyle={{ color: '#3f8600' }} /></Card></Col>
        <Col xs={{ span: 12 }} sm={{ span: 8 }} lg={{ span: 4 }}><Card><Statistic title="累计失败" value={stats.fail || 0} valueStyle={{ color: '#cf1322' }} /></Card></Col>
        <Col xs={{ span: 12 }} sm={{ span: 8 }} lg={{ span: 4 }}><Card><Statistic title="今日发送" value={stats.todayTotal || 0} /></Card></Col>
        <Col xs={{ span: 12 }} sm={{ span: 8 }} lg={{ span: 4 }}><Card><Statistic title="今日成功" value={stats.todaySuccess || 0} valueStyle={{ color: '#3f8600' }} /></Card></Col>
        <Col xs={{ span: 12 }} sm={{ span: 8 }} lg={{ span: 4 }}><Card><Statistic title="今日失败" value={stats.todayFail || 0} valueStyle={{ color: '#cf1322' }} /></Card></Col>
      </Row>

      <Card>
        <div className="filter-bar" style={{ marginBottom: 16, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <Input.Search placeholder="收件人邮箱" allowClear style={{ width: 240 }}
            onSearch={(v) => { const next = { ...filters, toEmail: v }; setFilters(next); loadData(1, pagination.pageSize, next); }} />
          <Select placeholder="状态" allowClear style={{ width: 120 }}
            onChange={(v) => { const next = { ...filters, status: v }; setFilters(next); loadData(1, pagination.pageSize, next); }}
            options={[{ label: '成功', value: 1 }, { label: '失败', value: 0 }]} />
          <RangePicker showTime onChange={(v) => { const next = { ...filters, range: v }; setFilters(next); loadData(1, pagination.pageSize, next); }} />
          <div className="page-toolbar">
            <Button icon={<ReloadOutlined />} onClick={() => { setFilters({}); loadData(1, pagination.pageSize, {}); loadStats(); }}>刷新</Button>
          </div>
        </div>
        <Table dataSource={data} columns={columns} rowKey="id" loading={loading} scroll={{ x: 'max-content' }} pagination={pagination}
          onChange={(p) => loadData(p.current, p.pageSize)} />
      </Card>
    </div>
  );
}
