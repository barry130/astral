/**
 * 操作审计。
 *
 * 后端直接读宿主的 `sys_operate_log`（按 `/api/v1/admin/ai` 前缀过滤），
 * 因此这里展示的是「真的落库了的操作日志」，而不是本模块另记一份——
 * 两套日志一定会不一致，排查时没人说得清该信哪个。
 */

'use client';

import React, { useCallback, useEffect, useMemo, useState } from 'react';

import { Button, Empty, Input, Select, Space, Spin, Tag, message } from '@/components/antd-compat';
import { ReloadOutlined, SearchOutlined } from '@/components/antd-compat/icons';
import { ResizableTable } from '@/components/ResizableTable';

import { aiAuditApi, type AiAudit } from '@/api/ai';

import { Hint, formatNumber, formatTime, truncate } from './shared';

const PAGE_SIZE = 20;

/** 从 action 里拆出「做了什么」，action 形如 `ai.provider.create` 或中文描述 */
function actionLabel(action?: string): string {
  if (!action) return '-';
  const parts = action.split('.');
  return parts.length > 1 ? parts.slice(-1)[0] : action;
}

export default function AuditPanel() {
  const [rows, setRows] = useState<AiAudit[]>([]);
  const [loading, setLoading] = useState(false);
  const [pageNum, setPageNum] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [actionFilter, setActionFilter] = useState<string | undefined>();

  const load = useCallback(
    async (page = pageNum) => {
      setLoading(true);
      try {
        const res = await aiAuditApi.list(page, PAGE_SIZE);
        setRows(res.data ?? []);
        setPageNum(page);
      } catch (error) {
        message.error(error instanceof Error ? error.message : '审计日志加载失败');
      } finally {
        setLoading(false);
      }
    },
    [pageNum],
  );

  useEffect(() => {
    void load(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** 后端该接口不分页返回全量（宿主日志服务语义），这里前端做筛选 + 本地分页视图 */
  const actionOptions = useMemo(() => {
    const set = new Set<string>();
    rows.forEach((row) => {
      if (row.action) set.add(row.action);
    });
    return [...set].slice(0, 50).map((item) => ({ label: item, value: item }));
  }, [rows]);

  const filtered = useMemo(() => {
    const key = keyword.trim().toLowerCase();
    return rows.filter((row) => {
      if (actionFilter && row.action !== actionFilter) return false;
      if (!key) return true;
      return [row.action, row.targetType, row.targetId, row.operatorName, row.detail]
        .filter(Boolean)
        .some((text) => String(text).toLowerCase().includes(key));
    });
  }, [rows, keyword, actionFilter]);

  const columns = [
    {
      key: 'id',
      title: '#',
      dataIndex: 'id',
      width: 84,
      render: (value: number) => <code style={{ fontSize: 12 }}>{value}</code>,
    },
    {
      key: 'action',
      title: '操作',
      dataIndex: 'action',
      width: 200,
      render: (value: string) => (
        <Space size={4} direction="vertical">
          <span style={{ fontSize: 13 }}>{actionLabel(value)}</span>
          <code style={{ fontSize: 11, opacity: 0.5 }}>{value}</code>
        </Space>
      ),
    },
    {
      key: 'target',
      title: '对象',
      dataIndex: 'targetType',
      width: 170,
      render: (_: unknown, row: AiAudit) => (
        <span style={{ fontSize: 12.5 }}>
          {row.targetType ?? '-'}
          {row.targetId ? <span style={{ opacity: 0.55 }}> #{row.targetId}</span> : null}
        </span>
      ),
    },
    {
      key: 'operator',
      title: '操作人',
      dataIndex: 'operatorName',
      width: 130,
      render: (value: string, row: AiAudit) =>
        value ? (
          <span style={{ fontSize: 12.5 }}>
            {value}
            {row.operatorId ? <span style={{ opacity: 0.5 }}> #{row.operatorId}</span> : null}
          </span>
        ) : (
          <span style={{ opacity: 0.5 }}>#{row.operatorId ?? '-'}</span>
        ),
    },
    {
      key: 'detail',
      title: '详情',
      dataIndex: 'detail',
      ellipsis: true,
      render: (value: string) => (
        <span style={{ fontSize: 12, opacity: 0.72 }} title={value}>
          {truncate(value, 90)}
        </span>
      ),
    },
    {
      key: 'ip',
      title: 'IP',
      dataIndex: 'clientIp',
      width: 130,
      render: (value: string) => (
        <span style={{ fontSize: 11.5, opacity: 0.55 }}>{value ?? '-'}</span>
      ),
    },
    {
      key: 'time',
      title: '时间',
      dataIndex: 'createTime',
      width: 150,
      render: (value: string) => (
        <span style={{ fontSize: 11.5, opacity: 0.6 }}>{formatTime(value)}</span>
      ),
    },
  ];

  return (
    <div>
      <Hint>
        审计日志来自宿主统一操作日志，只覆盖<strong>改了配置</strong>
        的动作（新建、修改、发布、删除、审批）。
        用户对话内容不在其中——那属于会话审计，需要单独权限。
      </Hint>

      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 10,
          gap: 8,
          flexWrap: 'wrap',
        }}
      >
        <Space size={8} wrap>
          <Tag color="default">{formatNumber(filtered.length)} 条</Tag>
          <Select
            size="small"
            allowClear
            showSearch
            placeholder="操作类型"
            style={{ width: 230 }}
            value={actionFilter}
            onChange={(value) => setActionFilter(value)}
            options={actionOptions}
          />
          <Input
            size="small"
            allowClear
            prefix={<SearchOutlined style={{ opacity: 0.5 }} />}
            placeholder="操作人 / 对象 / 详情"
            style={{ width: 210 }}
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
          />
        </Space>
        <Button
          size="small"
          icon={<ReloadOutlined />}
          onClick={() => void load(1)}
          loading={loading}
        >
          刷新
        </Button>
      </div>

      {loading && rows.length === 0 ? (
        <div style={{ padding: 40, textAlign: 'center' }}>
          <Spin />
        </div>
      ) : filtered.length === 0 ? (
        <Empty description={rows.length === 0 ? '还没有配置变更记录' : '没有匹配的记录'} />
      ) : (
        <ResizableTable
          resizeKey="ai-audit"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          pagination={{
            current: pageNum,
            pageSize: PAGE_SIZE,
            total: filtered.length,
            onChange: (page) => setPageNum(page),
            showSizeChanger: false,
          }}
        />
      )}
    </div>
  );
}
