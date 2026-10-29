/**
 * 通用 AI 对话面板。
 *
 * 管理助手（`admin_assistant`）与独立答疑（`admin_qa`）共用这一套交互：
 * 两者的差异全部来自后端返回的 `capabilities` 与智能体版本的 Prompt/工具/知识配置，
 * 前端不按 agentCode 写分支——否则后台新配一个智能体就得改代码，
 * 这与「智能体必须能在后台独立配置」的设计前提冲突。
 *
 * 因此本组件只做三件事：把 `capabilities` 映射成界面开关、渲染消息、驱动 `useAiChat`。
 */

'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  Modal,
  Popconfirm,
  Space,
  Spin,
  Tag,
  Tooltip,
  message as antdMessage,
} from '@/components/antd-compat';
import {
  ClearOutlined,
  DeleteOutlined,
  EditOutlined,
  HistoryOutlined,
  PlusOutlined,
  ReloadOutlined,
  SendOutlined,
  StarOutlined,
} from '@/components/antd-compat/icons';

import { useRouter } from 'next/navigation';

import AnswerBlocks from './AnswerBlocks';
import RichText from './RichText';
import { useAiChat, type ChatAction, type ChatMessage } from './useAiChat';

/* ==================== 小工具 ==================== */

/** 相对时间，避免长列表里全是完整时间戳 */
function relativeTime(value?: string): string {
  if (!value) return '';
  const time = new Date(value.replace(' ', 'T')).getTime();
  if (!Number.isFinite(time)) return '';
  const diff = Date.now() - time;
  if (diff < 60_000) return '刚刚';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`;
  if (diff < 7 * 86_400_000) return `${Math.floor(diff / 86_400_000)} 天前`;
  return new Date(time).toLocaleDateString('zh-CN');
}

const ACTION_LABEL: Record<string, string> = {
  OPEN_PAGE: '打开页面',
  FILL_FILTER: '填充筛选',
  SEARCH: '查询',
  OPEN_SONG: '打开歌曲',
  PLAY_SONG: '播放',
  SEARCH_MUSIC: '去搜索',
};

/* ==================== 消息气泡 ==================== */

interface MessageBubbleProps {
  message: ChatMessage;
  onFeedback?: (message: ChatMessage, type: string) => void;
  onAction?: (action: ChatAction) => void;
  showFeedback?: boolean;
  citationRequired?: boolean;
}

// 流式期间 useAiChat 每 60ms flush 一次 setMessages（新数组），
// 不 memo 的话列表里所有气泡都会跟着重渲染；配合 handleFeedback/handleAction
// 的稳定引用，memo 后只有内容真正变化的气泡会重渲染。
const MessageBubble = React.memo(function MessageBubble({
  message,
  onFeedback,
  onAction,
  showFeedback,
  citationRequired,
}: MessageBubbleProps) {
  const isUser = message.role === 'USER';
  const [voted, setVoted] = useState<string | null>(null);

  if (isUser) {
    return (
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 16 }}>
        <div
          style={{
            maxWidth: '78%',
            padding: '9px 13px',
            borderRadius: 12,
            borderTopRightRadius: 4,
            background: 'var(--primary, #0f766e)',
            color: 'var(--primary-foreground, #fff)',
            fontSize: 14,
            lineHeight: 1.7,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}
        >
          {message.content}
        </div>
      </div>
    );
  }

  const streaming = message.status === 'STREAMING' || message.status === 'PENDING';
  const failed = message.status === 'FAILED';
  const actions = message.actions ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', marginBottom: 20 }}>
      <div
        style={{
          maxWidth: '88%',
          padding: '11px 14px',
          borderRadius: 12,
          borderTopLeftRadius: 4,
          background: 'var(--muted, rgba(127,127,127,0.08))',
          border: failed
            ? '1px solid var(--destructive, #dc2626)'
            : '1px solid var(--border, rgba(127,127,127,0.16))',
          fontSize: 14,
        }}
      >
        {message.content ? <RichText text={message.content} /> : null}

        {!message.content && streaming && (
          <Space size={8} style={{ fontSize: 13, opacity: 0.7 }}>
            <Spin size="small" />
            正在思考…
          </Space>
        )}

        {streaming && message.content && (
          <span style={{ marginLeft: 2, opacity: 0.5 }} aria-hidden>
            ▍
          </span>
        )}

        {failed && (
          <Alert
            type="error"
            showIcon
            style={{ marginTop: message.content ? 10 : 0 }}
            message={message.error || '回答失败'}
            description="可以重新发送，或先到「AI 配置 → 请求记录」查看失败原因。"
          />
        )}

        {!streaming && !failed && (
          <AnswerBlocks
            blocks={message.blocks}
            citations={message.citations}
            citationRequired={citationRequired}
          />
        )}
      </div>

      {/* 动作与反馈 */}
      {!streaming && (actions.length > 0 || (showFeedback && message.id > 0)) && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            marginTop: 8,
            marginLeft: 2,
            flexWrap: 'wrap',
          }}
        >
          {actions.map((action, index) => (
            <Button
              key={action.actionId ?? index}
              size="small"
              onClick={() => onAction?.(action)}
              disabled={!onAction}
            >
              {ACTION_LABEL[action.type ?? ''] ?? action.type ?? '操作'}
            </Button>
          ))}

          {showFeedback && message.id > 0 && (
            <Space size={2}>
              <Tooltip title="回答有帮助">
                <Button
                  size="small"
                  type="text"
                  icon={<StarOutlined />}
                  onClick={() => {
                    onFeedback?.(message, 'LIKE');
                    setVoted('LIKE');
                  }}
                  style={{ opacity: voted === 'LIKE' ? 1 : 0.45 }}
                />
              </Tooltip>
              <Tooltip title="回答有问题">
                <Button
                  size="small"
                  type="text"
                  icon={<ClearOutlined />}
                  onClick={() => {
                    onFeedback?.(message, 'DISLIKE');
                    setVoted('DISLIKE');
                  }}
                  style={{ opacity: voted === 'DISLIKE' ? 1 : 0.45 }}
                />
              </Tooltip>
            </Space>
          )}

          {message.modelName && (
            <span style={{ fontSize: 12, opacity: 0.45 }}>{message.modelName}</span>
          )}
        </div>
      )}
    </div>
  );
});

/* ==================== 面板 ==================== */

export interface ChatPanelProps {
  /** 智能体编码；助手页传 `admin_assistant`，答疑页传 `admin_qa` */
  agentCode: string;
  /** 空状态标题 */
  emptyTitle?: string;
  /** 空状态下的示例提问（点击直接填入输入框） */
  suggestions?: string[];
  /** 顶部说明条 */
  banner?: React.ReactNode;
  /** 每轮附带的页面上下文（后端白名单只认 pageContext / selectedRecord） */
  context?: () => Record<string, unknown> | undefined;
  /** 引用是否必须（一般由后端 capabilities.citationRequired 决定，这里可强制覆盖） */
  requireCitation?: boolean;
  /** 无权限时的整页提示 */
  deniedText?: string;
}

export default function ChatPanel({
  agentCode,
  emptyTitle = '开始一段对话',
  suggestions = [],
  banner,
  context,
  requireCitation,
  deniedText,
}: ChatPanelProps) {
  const chat = useAiChat({ agentCode, context });
  const router = useRouter();
  const [draft, setDraft] = useState('');
  const [renamingId, setRenamingId] = useState<number | undefined>();
  const [renameValue, setRenameValue] = useState('');
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const lastCountRef = useRef(0);

  const citationRequired = requireCitation ?? chat.capabilities?.citationRequired ?? false;

  /** 新消息或流式增量时贴底；用户手动上翻查看历史时不打扰 */
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 200;
    const grew = chat.messages.length !== lastCountRef.current;
    lastCountRef.current = chat.messages.length;
    if (grew || nearBottom) {
      el.scrollTop = el.scrollHeight;
    }
  }, [chat.messages]);

  const submit = useCallback(async () => {
    const text = draft.trim();
    if (!text || chat.run.running) return;
    setDraft('');
    await chat.send(text);
  }, [chat, draft]);

  const onKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      // Enter 发送、Shift+Enter 换行：与主流对话产品一致
      if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
        e.preventDefault();
        void submit();
      }
    },
    [submit],
  );

  const handleFeedback = useCallback(
    async (target: ChatMessage, type: string) => {
      try {
        await chat.feedback(target.id, type);
        antdMessage.success('已记录反馈');
      } catch (error) {
        antdMessage.error(error instanceof Error ? error.message : '反馈提交失败');
      }
    },
    // 只依赖 chat.feedback（useAiChat 里是空依赖 useCallback，恒稳定）：
    // 依赖整个 chat 会随流式 flush 重建引用，击穿 MessageBubble 的 memo。
    [chat.feedback],
  );

  const handleAction = useCallback(
    (action: ChatAction) => {
      // 管理端动作只做「打开页面」这类无副作用跳转；真正的写操作由服务端确认卡片承载
      const path = typeof action.path === 'string' ? action.path : undefined;
      if (path && path.startsWith('/dashboard')) {
        // 走客户端路由跳转，避免整页刷新打断会话
        router.push(path);
        return;
      }
      antdMessage.info('该动作在当前页面没有可跳转的目标');
    },
    [router],
  );

  const activeConversation = useMemo(
    () => chat.conversations.find((item) => item.id === chat.activeConversationId),
    [chat.activeConversationId, chat.conversations],
  );

  /* ---------------- 不可用状态 ---------------- */

  if (!chat.loading && !chat.card) {
    return (
      <Card>
        <Empty
          description={
            deniedText ??
            `当前账号无法使用「${agentCode}」。请让管理员在「AI 配置 → 智能体」中给该智能体授权，并确认它已发布版本、且客户端包含管理后台。`
          }
        />
      </Card>
    );
  }

  return (
    <div
      style={{
        display: 'flex',
        gap: 16,
        alignItems: 'stretch',
        // 固定高度：消息多了只能滚消息区，页面本身不滚动（否则左列表会被一起带走）
        height: 'calc(100vh - 216px)',
        minHeight: 460,
      }}
    >
      {/* ---------------- 会话列表 ---------------- */}
      {chat.capabilities?.conversationEnabled !== false && (
        <Card
          size="small"
          style={{
            width: 240,
            flex: 'none',
            height: '100%',
            display: 'flex',
            flexDirection: 'column',
            overflow: 'hidden',
          }}
          bodyStyle={{
            padding: 8,
            display: 'flex',
            flexDirection: 'column',
            gap: 6,
            flex: 1,
            minHeight: 0,
          }}
        >
          <Button
            block
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => void chat.newConversation()}
          >
            新建对话
          </Button>

          <div style={{ flex: 1, overflowY: 'auto', marginTop: 4, minHeight: 0 }}>
            {chat.conversationsLoading && chat.conversations.length === 0 ? (
              <div style={{ padding: 16, textAlign: 'center' }}>
                <Spin size="small" />
              </div>
            ) : chat.conversations.length === 0 ? (
              <div
                style={{ padding: '20px 8px', textAlign: 'center', fontSize: 12.5, opacity: 0.55 }}
              >
                还没有对话记录
              </div>
            ) : (
              chat.conversations.map((item) => {
                const active = item.id === chat.activeConversationId;
                return (
                  <div
                    key={item.id}
                    onClick={() => item.id !== undefined && void chat.selectConversation(item.id)}
                    className="group"
                    style={{
                      padding: '8px 10px',
                      borderRadius: 8,
                      cursor: 'pointer',
                      fontSize: 13,
                      marginBottom: 2,
                      background: active ? 'var(--accent, rgba(127,127,127,0.12))' : 'transparent',
                    }}
                  >
                    <div
                      title={item.title || '未命名对话'}
                      style={{
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                        fontWeight: active ? 500 : 400,
                      }}
                    >
                      {item.title || '未命名对话'}
                    </div>
                    <div
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        marginTop: 2,
                      }}
                    >
                      <span style={{ fontSize: 11.5, opacity: 0.5 }}>
                        {relativeTime(item.lastMessageTime || item.createTime)}
                      </span>
                      {item.id !== undefined && (
                        // stopPropagation 放在包装层：既不触发行点击（切换会话），
                        // 又让按钮点击正常冒泡给 Popconfirm 触发器——
                        // 若写在删除按钮上，气泡永远弹不出来（正是「点击删除无反应」的根因）
                        <span
                          className={
                            active ? 'inline-flex' : 'invisible inline-flex group-hover:visible'
                          }
                          onClick={(e) => e.stopPropagation()}
                        >
                          <Space size={0}>
                            <Tooltip title="重命名">
                              <Button
                                size="small"
                                type="text"
                                icon={<EditOutlined />}
                                onClick={() => {
                                  setRenameValue(item.title || '');
                                  setRenamingId(item.id);
                                }}
                              />
                            </Tooltip>
                            <Popconfirm
                              title="删除这个对话？"
                              description="删除后消息不可恢复。"
                              okButtonProps={{ danger: true }}
                              onConfirm={() => {
                                void chat
                                  .removeConversation(item.id as number)
                                  .catch((error: unknown) =>
                                    antdMessage.error(
                                      error instanceof Error ? error.message : '删除失败',
                                    ),
                                  );
                              }}
                            >
                              <Button size="small" type="text" danger icon={<DeleteOutlined />} />
                            </Popconfirm>
                          </Space>
                        </span>
                      )}
                    </div>
                  </div>
                );
              })
            )}
          </div>

          <div
            style={{
              borderTop: '1px solid var(--border, rgba(127,127,127,0.16))',
              paddingTop: 6,
              fontSize: 11.5,
              opacity: 0.5,
              display: 'flex',
              alignItems: 'center',
              gap: 4,
            }}
          >
            <HistoryOutlined />
            {chat.conversations.length} 个对话
          </div>
        </Card>
      )}

      {/* ---------------- 主区域 ---------------- */}
      <Card
        style={{
          flex: 1,
          height: '100%',
          display: 'flex',
          flexDirection: 'column',
          minWidth: 0,
          overflow: 'hidden',
        }}
        bodyStyle={{ padding: 0, display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}
        title={
          <Space size={8}>
            <span style={{ fontWeight: 600 }}>{chat.card?.name ?? agentCode}</span>
            {chat.run.running && (
              <Tag color="processing">{chat.run.transport === 'poll' ? '轮询中' : '生成中'}</Tag>
            )}
          </Space>
        }
        extra={
          <Space size={6}>
            {citationRequired && (
              <Tooltip title="该智能体要求结论能追溯到文档">
                <Tag color="default">需引用</Tag>
              </Tooltip>
            )}
            <Button
              size="small"
              type="text"
              icon={<ReloadOutlined />}
              onClick={() => void chat.reload()}
              loading={chat.loading}
            >
              刷新
            </Button>
          </Space>
        }
      >
        {banner && <div style={{ padding: '10px 16px 0' }}>{banner}</div>}

        {chat.error && (
          <div style={{ padding: '10px 16px 0' }}>
            <Alert type="error" showIcon message={chat.error} />
          </div>
        )}

        {/* 消息区：唯一的滚动容器 */}
        <div
          ref={scrollRef}
          style={{ flex: 1, overflowY: 'auto', padding: '16px 20px', minHeight: 0 }}
        >
          {chat.loadingMessages ? (
            <div style={{ padding: 40, textAlign: 'center' }}>
              <Spin />
            </div>
          ) : chat.messages.length === 0 ? (
            <div style={{ padding: '48px 0', textAlign: 'center' }}>
              <div style={{ fontSize: 15, fontWeight: 500, marginBottom: 6 }}>{emptyTitle}</div>
              <div style={{ fontSize: 13, opacity: 0.6, marginBottom: 18 }}>
                {chat.card?.description || '直接在下方输入问题，用中文即可。'}
              </div>
              {suggestions.length > 0 && (
                <Space size={8} wrap style={{ justifyContent: 'center' }}>
                  {suggestions.map((item) => (
                    <Button key={item} size="small" onClick={() => setDraft(item)}>
                      {item}
                    </Button>
                  ))}
                </Space>
              )}
            </div>
          ) : (
            chat.messages.map((item) => (
              <MessageBubble
                key={item.id}
                message={item}
                onFeedback={
                  chat.capabilities?.feedbackEnabled === false ? undefined : handleFeedback
                }
                onAction={handleAction}
                showFeedback={chat.capabilities?.feedbackEnabled !== false}
                citationRequired={citationRequired}
              />
            ))
          )}
        </div>

        {/* 输入区 */}
        <div
          style={{
            borderTop: '1px solid var(--border, rgba(127,127,127,0.16))',
            padding: '10px 16px 12px',
          }}
        >
          <Input.TextArea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder={
              chat.run.running ? '正在生成回答…' : '输入问题，Enter 发送，Shift+Enter 换行'
            }
            autoSize={{ minRows: 2, maxRows: 6 }}
            maxLength={20000}
            disabled={chat.run.running}
          />
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              marginTop: 8,
            }}
          >
            <span style={{ fontSize: 12, opacity: 0.5 }}>
              {activeConversation?.title
                ? `当前对话：${activeConversation.title}`
                : '发送后自动创建对话'}
              {chat.capabilities?.userContextEnabled ? ' · 附带当前页面上下文' : ''}
            </span>
            <Space size={8}>
              {chat.run.running && (
                <Button danger onClick={() => void chat.stop()}>
                  停止
                </Button>
              )}
              <Button
                type="primary"
                icon={<SendOutlined />}
                onClick={() => void submit()}
                disabled={!draft.trim() || chat.run.running}
                loading={chat.loading}
              >
                发送
              </Button>
            </Space>
          </div>
        </div>
      </Card>

      <Modal
        title="重命名对话"
        open={renamingId !== undefined}
        onCancel={() => setRenamingId(undefined)}
        onOk={async () => {
          const title = renameValue.trim();
          if (renamingId !== undefined && title) {
            try {
              await chat.renameConversation(renamingId, title);
            } catch (error) {
              antdMessage.error(error instanceof Error ? error.message : '重命名失败');
              return;
            }
          }
          setRenamingId(undefined);
        }}
        okText="保存"
        cancelText="取消"
        width={380}
        destroyOnClose
      >
        <Input
          value={renameValue}
          onChange={(e) => setRenameValue(e.target.value)}
          maxLength={64}
          placeholder="例如：排查存储配置"
        />
      </Modal>
    </div>
  );
}
