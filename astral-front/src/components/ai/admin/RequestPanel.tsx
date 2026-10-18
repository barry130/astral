/**
 * 请求记录与会话审计。
 *
 * 这一页解决的是「用户说 AI 答错了，到底怎么回事」：
 * 从请求列表点进详情，能看到当时的输入快照、命中上下文、配置快照、知识快照、
 * 工具调用与完整事件流——不依赖服务端日志，历史请求也能复盘。
 *
 * 会话正文单独一栏、单独权限（`admin:ai:conversation:content:view`），
 * 因为它是真实用户的对话内容，默认不该被所有管理员随手翻。
 */

'use client';

import React, { useCallback, useEffect, useState } from 'react';

import {
  Alert,
  Button,
  Descriptions,
  Empty,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Tabs,
  Tag,
  Timeline,
  message,
} from '@/components/antd-compat';
import { EyeOutlined, ReloadOutlined, SearchOutlined } from '@/components/antd-compat/icons';
import { ResizableTable } from '@/components/ResizableTable';

import {
  aiRequestApi,
  type AiAdminMessage,
  type AiConversationAudit,
  type AiRequest,
  type AiRequestDetail,
} from '@/api/ai';
import { usePerm } from '@/lib/perm';

import { Hint, JsonBlock, StatusTag, formatNumber, formatTime, truncate, useAiDict } from './shared';

const PAGE_SIZE = 20;

export default function RequestPanel() {
  const dict = useAiDict();

  return (
    <div>
      <Hint>
        请求详情里的「配置快照」是当时的真实取值，不是现在的配置——
        排查历史问题时请以快照为准，否则会被后来改过的配置误导。
      </Hint>
      <Tabs
        items={[
          { key: 'requests', label: '请求', children: <RequestList dict={dict} /> },
          { key: 'conversations', label: '会话', children: <ConversationList dict={dict} /> },
        ]}
      />
    </div>
  );
}

/* ==================== 请求列表 ==================== */

function RequestList({ dict }: { dict: ReturnType<typeof useAiDict> }) {
  const hasPerm = usePerm();
  const [rows, setRows] = useState<AiRequest[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState<string | undefined>();
  const [addon, setAddon] = useState('');
  const [focus, setFocus] = useState<AiRequestDetail | undefined>();
  const [focusLoading, setFocusLoading] = useState(false);

  const load = useCallback(
    async (page = pageNum) => {
      setLoading(true);
      try {
        const res = await aiRequestApi.list({
          status,
          pageNum: page,
          pageSize: PAGE_SIZE,
        });
        setRows(res.data?.items ?? []);
        setTotal(res.data?.total ?? 0);
        setPageNum(page);
      } catch (error) {
        message.error(error instanceof Error ? error.message : '请求列表加载失败');
      } finally {
        setLoading(false);
      }
    },
    [pageNum, status],
  );

  useEffect(() => {
    void load(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status]);

  const openDetail = async (id?: number) => {
    if (!id) return;
    setFocusLoading(true);
    setFocus(undefined);
    try {
      const res = await aiRequestApi.detail(id, true);
      setFocus(res.data);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '详情加载失败');
    } finally {
      setFocusLoading(false);
    }
  };

  const columns = [
    {
      key: 'id',
      title: '#',
      dataIndex: 'id',
      width: 90,
      render: (value: number) => <code style={{ fontSize: 12 }}>{value}</code>,
    },
    {
      key: 'agent',
      title: '智能体 / 版本',
      dataIndex: 'agentName',
      width: 170,
      render: (_: unknown, row: AiRequest) => (
        <Space size={2} direction="vertical">
          <span style={{ fontSize: 13 }}>{row.agentName ?? '-'}</span>
          <span style={{ fontSize: 11, opacity: 0.55 }}>v{row.agentVersionNo ?? '?'}</span>
        </Space>
      ),
    },
    {
      key: 'client',
      title: '客户端',
      dataIndex: 'clientType',
      width: 110,
      render: (value: string) => <Tag color="default">{dict.label('ai_client_type', value)}</Tag>,
    },
    {
      key: 'status',
      title: '状态',
      dataIndex: 'status',
      width: 120,
      render: (value: string) => <StatusTag dict={dict} code="ai_request_status" value={value} />,
    },
    {
      key: 'tokens',
      title: 'token 入/出',
      dataIndex: 'tokenIn',
      width: 120,
      align: 'right' as const,
      render: (_: unknown, row: AiRequest) => (
        <span style={{ fontSize: 12 }}>
          {formatNumber(row.tokenIn)} / {formatNumber(row.tokenOut)}
        </span>
      ),
    },
    {
      key: 'duration',
      title: '耗时',
      dataIndex: 'durationMs',
      width: 90,
      align: 'right' as const,
      render: (value: number) =>
        value ? <span style={{ fontSize: 12 }}>{(value / 1000).toFixed(1)}s</span> : <span style={{ opacity: 0.4 }}>-</span>,
    },
    {
      key: 'error',
      title: '错误',
      dataIndex: 'errorCode',
      width: 160,
      render: (value: string, row: AiRequest) =>
        value ? (
          <span style={{ fontSize: 12, color: 'var(--destructive, #d4380d)' }} title={row.errorSummary}>
            {value}
          </span>
        ) : (
          <span style={{ opacity: 0.35 }}>-</span>
        ),
    },
    {
      key: 'time',
      title: '时间',
      dataIndex: 'createTime',
      width: 150,
      render: (value: string) => <span style={{ fontSize: 11.5, opacity: 0.6 }}>{formatTime(value)}</span>,
    },
    {
      key: 'action',
      title: '操作',
      dataIndex: 'id',
      width: 70,
      render: (_: unknown, row: AiRequest) => (
        <Button size="small" type="text" icon={<EyeOutlined />} onClick={() => void openDetail(row.id)} />
      ),
    },
  ];

  const filtered = addon.trim()
    ? rows.filter((row) => String(row.id ?? '').includes(addon.trim()) || (row.userId ?? 0) === Number(addon.trim()))
    : rows;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10, gap: 8, flexWrap: 'wrap' }}>
        <Space size={8} wrap>
          <Tag color="default">{formatNumber(total)} 条</Tag>
          <Select
            size="small"
            allowClear
            placeholder="状态"
            style={{ width: 140 }}
            value={status}
            onChange={(value) => setStatus(value)}
            options={dict.options('ai_request_status')}
          />
          <Input
            size="small"
            allowClear
            prefix={<SearchOutlined style={{ opacity: 0.5 }} />}
            placeholder="请求 ID / 用户 ID"
            style={{ width: 170 }}
            value={addon}
            onChange={(event) => setAddon(event.target.value)}
            onPressEnter={() => void load(1)}
          />
        </Space>
        <Space size={8}>
          <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
            刷新
          </Button>
        </Space>
      </div>

      {loading && rows.length === 0 ? (
        <div style={{ padding: 40, textAlign: 'center' }}>
          <Spin />
        </div>
      ) : filtered.length === 0 ? (
        <Empty description="没有匹配的请求" />
      ) : (
        <ResizableTable
          resizeKey="ai-request"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          pagination={{
            current: pageNum,
            pageSize: PAGE_SIZE,
            total,
            onChange: (page) => void load(page),
            showSizeChanger: false,
          }}
          onRow={(row: AiRequest) => ({ onDoubleClick: () => void openDetail(row.id) })}
        />
      )}

      <Modal
        title={focus ? `请求 #${focus.request?.id}` : '请求详情'}
        open={focusLoading || Boolean(focus)}
        onCancel={() => setFocus(undefined)}
        footer={
          <Space>
            <Button onClick={() => setFocus(undefined)}>关闭</Button>
            {focus?.request?.conversationId && hasPerm('admin:ai:conversation:content:view') && (
              <Button type="primary" onClick={() => window.open(`/dashboard/plugin/ai/request`)}>
                查看会话
              </Button>
            )}
          </Space>
        }
        width={1000}
        destroyOnClose
        styles={{ body: { maxHeight: '70vh', overflowY: 'auto' } }}
      >
        {focusLoading || !focus ? (
          <div style={{ padding: 40, textAlign: 'center' }}>
            <Spin />
          </div>
        ) : (
          <RequestDetailBody detail={focus} dict={dict} />
        )}
      </Modal>
    </div>
  );
}

function RequestDetailBody({ detail, dict }: { detail: AiRequestDetail; dict: ReturnType<typeof useAiDict> }) {
  const req = detail.request ?? {};
  return (
    <div>
      <Descriptions
        column={3}
        size="small"
        bordered
        items={[
          { key: 'status', label: '状态', children: <StatusTag dict={dict} code="ai_request_status" value={req.status} /> },
          { key: 'client', label: '客户端', children: dict.label('ai_client_type', req.clientType) },
          { key: 'version', label: '版本', children: `v${req.agentVersionNo ?? '?'}` },
          { key: 'tokens', label: 'token', children: `${formatNumber(req.tokenIn)} / ${formatNumber(req.tokenOut)}` },
          { key: 'duration', label: '耗时', children: req.durationMs ? `${(req.durationMs / 1000).toFixed(1)}s` : '-' },
          { key: 'error', label: '错误码', children: req.errorCode ?? '-' },
        ]}
      />

      {req.errorSummary && (
        <Alert
          type="error"
          showIcon
          style={{ marginTop: 12 }}
          message={req.errorCode ?? '失败'}
          description={req.errorSummary}
        />
      )}

      <div style={{ marginTop: 14 }}>
        <Tabs
          size="small"
          items={[
            {
              key: 'input',
              label: '输入快照',
              children: <pre style={preStyle}>{detail.inputSnapshot || '（无）'}</pre>,
            },
            {
              key: 'context',
              label: '上下文快照',
              children: <JsonBlock value={detail.contextSnapshot} maxHeight={280} />,
            },
            {
              key: 'config',
              label: '配置快照',
              children: <JsonBlock value={detail.configurationSnapshot} maxHeight={280} />,
            },
            {
              key: 'knowledge',
              label: '知识快照',
              children: <JsonBlock value={detail.knowledgeSnapshot} maxHeight={280} />,
            },
            {
              key: 'safety',
              label: '安全/工具策略',
              children: (
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  <div style={{ fontSize: 12, opacity: 0.7 }}>工具策略</div>
                  <JsonBlock value={detail.toolPolicySnapshot} maxHeight={200} />
                  <div style={{ fontSize: 12, opacity: 0.7 }}>安全策略</div>
                  <JsonBlock value={detail.safetyPolicySnapshot} maxHeight={200} />
                </Space>
              ),
            },
            {
              key: 'tools',
              label: `工具调用 (${detail.toolCalls?.length ?? 0})`,
              children:
                detail.toolCalls && detail.toolCalls.length > 0 ? (
                  <Space direction="vertical" size={10} style={{ width: '100%' }}>
                    {detail.toolCalls.map((call) => (
                      <div
                        key={call.id}
                        style={{
                          border: '1px solid var(--border, rgba(127,127,127,0.18))',
                          borderRadius: 8,
                          padding: 10,
                        }}
                      >
                        <Space size={8} wrap>
                          <span style={{ fontWeight: 500 }}>{call.toolName ?? call.toolCode}</span>
                          <StatusTag dict={dict} code="ai_tool_risk_level" value={call.riskLevel} />
                          <Tag color="default">{call.status}</Tag>
                          {call.approvalStatus && <Tag color="warning">审批：{call.approvalStatus}</Tag>}
                        </Space>
                        <div style={{ marginTop: 8, fontSize: 12, opacity: 0.7 }}>参数</div>
                        <JsonBlock value={call.argumentsJson} maxHeight={140} />
                        {call.resultJson && (
                          <>
                            <div style={{ marginTop: 8, fontSize: 12, opacity: 0.7 }}>结果</div>
                            <JsonBlock value={call.resultJson} maxHeight={180} />
                          </>
                        )}
                        {call.errorMessage && (
                          <Alert type="error" showIcon style={{ marginTop: 8 }} message={call.errorMessage} />
                        )}
                      </div>
                    ))}
                  </Space>
                ) : (
                  <Empty description="本次请求没有调用工具" />
                ),
            },
            {
              key: 'events',
              label: `事件 (${detail.events?.length ?? 0})`,
              children:
                detail.events && detail.events.length > 0 ? (
                  <Timeline
                    items={detail.events.map((event) => ({
                      label: <span style={{ fontSize: 11, opacity: 0.55 }}>{formatTime(event.createdAt)}</span>,
                      children: (
                        <div>
                          <Space size={6}>
                            <Tag color="default">{event.sequence}</Tag>
                            <code style={{ fontSize: 12 }}>{event.type}</code>
                          </Space>
                          {event.dataJson && (
                            <div style={{ marginTop: 4 }}>
                              <JsonBlock value={event.dataJson} maxHeight={120} />
                            </div>
                          )}
                        </div>
                      ),
                    }))}
                  />
                ) : (
                  <Empty description="没有事件记录" />
                ),
            },
            {
              key: 'messages',
              label: `消息 (${detail.messages?.length ?? 0})`,
              children:
                detail.messages && detail.messages.length > 0 ? (
                  <Space direction="vertical" size={10} style={{ width: '100%' }}>
                    {detail.messages.map((item) => (
                      <div key={item.id} style={{ borderLeft: '2px solid var(--border, rgba(127,127,127,0.2))', paddingLeft: 10 }}>
                        <Space size={6}>
                          <Tag color={item.role === 'USER' ? 'blue' : 'green'}>{item.role}</Tag>
                          <span style={{ fontSize: 11, opacity: 0.55 }}>{formatTime(item.createTime)}</span>
                        </Space>
                        <div style={{ marginTop: 4, fontSize: 13, whiteSpace: 'pre-wrap', lineHeight: 1.7 }}>
                          {truncate(item.content, 1200)}
                        </div>
                      </div>
                    ))}
                  </Space>
                ) : (
                  <Empty description="没有消息" />
                ),
            },
          ]}
        />
      </div>
    </div>
  );
}

/* ==================== 会话审计 ==================== */

function ConversationList({ dict }: { dict: ReturnType<typeof useAiDict> }) {
  const hasPerm = usePerm();
  const canReadContent = hasPerm('admin:ai:conversation:content:view');

  const [rows, setRows] = useState<AiConversationAudit[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<{ row: AiConversationAudit; messages: AiAdminMessage[] } | undefined>();
  const [detailLoading, setDetailLoading] = useState(false);

  const load = useCallback(
    async (page = pageNum) => {
      setLoading(true);
      try {
        const res = await aiRequestApi.conversations({ pageNum: page, pageSize: PAGE_SIZE });
        setRows(res.data?.items ?? []);
        setTotal(res.data?.total ?? 0);
        setPageNum(page);
      } catch (error) {
        message.error(error instanceof Error ? error.message : '会话列表加载失败');
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

  const open = async (row: AiConversationAudit) => {
    if (!row.id || !canReadContent) return;
    setDetailLoading(true);
    try {
      const res = await aiRequestApi.conversationMessages(row.id, undefined, 100);
      setDetail({ row, messages: res.data ?? [] });
    } catch (error) {
      message.error(error instanceof Error ? error.message : '会话正文加载失败');
    } finally {
      setDetailLoading(false);
    }
  };

  const columns = [
    { key: 'id', title: '#', dataIndex: 'id', width: 80, render: (value: number) => <code style={{ fontSize: 12 }}>{value}</code> },
    { key: 'title', title: '标题', dataIndex: 'title', width: 220, ellipsis: true },
    {
      key: 'agent',
      title: '智能体',
      dataIndex: 'agentName',
      width: 140,
      render: (value: string, row: AiConversationAudit) => (
        <Space size={2} direction="vertical">
          <span style={{ fontSize: 13 }}>{value ?? '-'}</span>
          <span style={{ fontSize: 11, opacity: 0.5 }}>{row.agentCode}</span>
        </Space>
      ),
    },
    { key: 'user', title: '用户', dataIndex: 'userId', width: 80, align: 'right' as const },
    {
      key: 'client',
      title: '客户端',
      dataIndex: 'clientType',
      width: 110,
      render: (value: string) => <Tag color="default">{dict.label('ai_client_type', value)}</Tag>,
    },
    { key: 'count', title: '消息数', dataIndex: 'messageCount', width: 80, align: 'right' as const },
    {
      key: 'last',
      title: '最后消息',
      dataIndex: 'lastMessageTime',
      width: 150,
      render: (value: string) => <span style={{ fontSize: 11.5, opacity: 0.6 }}>{formatTime(value)}</span>,
    },
    {
      key: 'action',
      title: '操作',
      dataIndex: 'id',
      width: 90,
      render: (_: unknown, row: AiConversationAudit) => (
        <Button
          size="small"
          type="text"
          icon={<EyeOutlined />}
          disabled={!canReadContent}
          title={canReadContent ? '' : '需要会话正文查看权限'}
          onClick={() => void open(row)}
        />
      ),
    },
  ];

  return (
    <div>
      {!canReadContent && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 10 }}
          message="只能看到会话列表"
          description="查看正文需要 admin:ai:conversation:content:view 权限，后端会记录每次查阅。"
        />
      )}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <Tag color="default">{formatNumber(total)} 个会话</Tag>
        <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
          刷新
        </Button>
      </div>

      {loading && rows.length === 0 ? (
        <div style={{ padding: 40, textAlign: 'center' }}>
          <Spin />
        </div>
      ) : rows.length === 0 ? (
        <Empty description="还没有会话" />
      ) : (
        <ResizableTable
          resizeKey="ai-conversation-audit"
          rowKey="id"
          columns={columns}
          dataSource={rows}
          loading={loading}
          pagination={{
            current: pageNum,
            pageSize: PAGE_SIZE,
            total,
            onChange: (page) => void load(page),
            showSizeChanger: false,
          }}
        />
      )}

      <Modal
        title={detail ? `会话 #${detail.row.id} · ${detail.row.title ?? ''}` : '会话正文'}
        open={detailLoading || Boolean(detail)}
        onCancel={() => setDetail(undefined)}
        footer={<Button onClick={() => setDetail(undefined)}>关闭</Button>}
        width={860}
        destroyOnClose
        styles={{ body: { maxHeight: '68vh', overflowY: 'auto' } }}
      >
        {detailLoading || !detail ? (
          <div style={{ padding: 40, textAlign: 'center' }}>
            <Spin />
          </div>
        ) : detail.messages.length === 0 ? (
          <Empty description="这个会话没有消息" />
        ) : (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {detail.messages.map((item) => (
              <div key={item.id}>
                <Space size={6}>
                  <Tag color={item.role === 'USER' ? 'blue' : 'green'}>{item.role}</Tag>
                  <span style={{ fontSize: 11, opacity: 0.55 }}>{formatTime(item.createTime)}</span>
                  {item.modelName && <span style={{ fontSize: 11, opacity: 0.45 }}>{item.modelName}</span>}
                </Space>
                <div style={{ marginTop: 4, fontSize: 13, lineHeight: 1.75, whiteSpace: 'pre-wrap' }}>{item.content}</div>
              </div>
            ))}
          </Space>
        )}
      </Modal>
    </div>
  );
}

const preStyle: React.CSSProperties = {
  margin: 0,
  padding: '8px 10px',
  borderRadius: 6,
  background: 'var(--muted, rgba(127,127,127,0.08))',
  fontSize: 12.5,
  lineHeight: 1.7,
  maxHeight: 280,
  overflow: 'auto',
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
};
