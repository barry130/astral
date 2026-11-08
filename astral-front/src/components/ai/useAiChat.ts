/**
 * AI 会话状态机。
 *
 * 后端把一次提问拆成「同步建请求 + 异步推进」，因此前端不能只等一个 HTTP 响应：
 * `send` 立刻返回 `requestId` 与占位的助手消息 id，正文要靠事件流补齐。
 * 这里把两条通道（SSE 推送、`/events` 轮询）统一成同一个事件消费循环，
 * SSE 连不上就自动退化为轮询，业务层不需要感知差别。
 */

'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import {
  aiChatApi,
  aiStreamUrl,
  type AiAgentCard,
  type AiBlock,
  type AiCapabilities,
  type AiCitation,
  type AiConversation,
  type AiEvent,
} from '@/api/ai';

import { openEventStream } from './sse';

/* ==================== 视图模型 ==================== */

/** 回答里携带的动作（管理端只用于「打开页面 / 填充筛选」这类无副作用动作） */
export interface ChatAction {
  actionId?: string;
  type?: string;
  [key: string]: unknown;
}

/** 工具调用轨迹（让用户看见助手在查什么，而不是干等） */
export interface ChatToolTrace {
  toolCallId: number;
  toolCode?: string;
  name?: string;
  risk?: string;
  status: string;
  summary?: string;
  /** 需要用户确认时的参数快照 */
  arguments?: Record<string, unknown>;
  approvalPolicy?: string;
  submitting?: boolean;
}

/** 会话中的一条消息（在后端消息基础上叠加流式态） */
export interface ChatMessage {
  id: number;
  role: string;
  content: string;
  status: string;
  blocks?: AiBlock[];
  citations?: AiCitation[];
  actions?: ChatAction[];
  modelName?: string;
  createTime?: string;
  /** 本地占位标记：id 尚未由服务端确认 */
  pending?: boolean;
  /** 本地错误（发送失败、事件流断开且轮询也未恢复） */
  error?: string;
}

/** 一轮请求的进行状态 */
export interface ChatRunState {
  requestId?: number;
  assistantMessageId?: number;
  /** 正在跑（可取消） */
  running: boolean;
  /** 传输方式，用于界面上提示「已降级为轮询」 */
  transport?: 'sse' | 'poll';
}

const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED']);
const CONVERSATION_MESSAGE_LIMIT = 30;
const POLL_INTERVAL_MS = 800;

const str = (value: unknown): string | undefined =>
  typeof value === 'string' ? value : typeof value === 'number' ? String(value) : undefined;

const num = (value: unknown): number | undefined =>
  typeof value === 'number' && Number.isFinite(value)
    ? value
    : typeof value === 'string' && value.trim() && Number.isFinite(Number(value))
      ? Number(value)
      : undefined;

const asArray = <T>(value: unknown): T[] => (Array.isArray(value) ? (value as T[]) : []);

/* ==================== Hook ==================== */

export interface UseAiChatOptions {
  /** 智能体编码，例如 `admin_assistant` / `admin_qa` */
  agentCode: string;
  /** 每轮请求附带给模型的页面上下文（后端白名单只认 pageContext / selectedRecord） */
  context?: () => Record<string, unknown> | undefined;
}

export interface UseAiChatResult {
  /** 智能体卡片（含能力开关） */
  card?: AiAgentCard;
  capabilities?: AiCapabilities;
  conversations: AiConversation[];
  activeConversationId?: number;
  messages: ChatMessage[];
  tools: ChatToolTrace[];
  approveTool: (id: number, approved: boolean, admin?: boolean) => Promise<void>;
  run: ChatRunState;
  loading: boolean;
  /** 首屏/切会话时的加载态，与发送中的 loading 区分开 */
  loadingMessages: boolean;
  conversationsLoading: boolean;
  error?: string;
  /** 新建会话并切换（不发消息） */
  newConversation: () => Promise<number | undefined>;
  selectConversation: (id: number) => Promise<void>;
  renameConversation: (id: number, title: string) => Promise<void>;
  removeConversation: (id: number) => Promise<void>;
  /** 发送一条提问；conversationId 缺省时自动建会话 */
  send: (content: string) => Promise<void>;
  /** 取消进行中的请求 */
  stop: () => Promise<void>;
  /** 提交反馈 */
  feedback: (messageId: number, feedbackType: string, comment?: string) => Promise<void>;
  /** 重新拉取智能体与会话（例如后台刚发布了新版本） */
  reload: () => Promise<void>;
}

export function useAiChat({ agentCode, context }: UseAiChatOptions): UseAiChatResult {
  const [card, setCard] = useState<AiAgentCard | undefined>();
  const [capabilities, setCapabilities] = useState<AiCapabilities | undefined>();
  const [conversations, setConversations] = useState<AiConversation[]>([]);
  const [activeConversationId, setActiveConversationId] = useState<number | undefined>();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [tools, setTools] = useState<ChatToolTrace[]>([]);
  const [run, setRun] = useState<ChatRunState>({ running: false });
  const [loading, setLoading] = useState(false);
  const [loadingMessages, setLoadingMessages] = useState(false);
  const [conversationsLoading, setConversationsLoading] = useState(false);
  const [error, setError] = useState<string | undefined>();

  /** 流式期间的高频更新走 ref 累积，定时 flush 到 state，避免每个 delta 都触发重渲染 */
  const bufferRef = useRef<Map<number, string>>(new Map());
  const flushTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  /** 事件游标：断线续传与轮询降级都以它为准 */
  const cursorRef = useRef(0);
  /** 当前请求的会话上下文 */
  const runningRef = useRef<{
    requestId?: number;
    assistantMessageId?: number;
    conversationId?: number;
  }>({});
  const pollTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  /** 组件卸载标记，阻止已废弃的异步回写 */
  const aliveRef = useRef(true);
  const viewRef = useRef(0);
  const approvalRef = useRef(new Set<number>());
  const sendRef = useRef<{ view: number; cancel: boolean } | null>(null);
  const historyLoadingRef = useRef(false);

  const detach = useCallback(() => {
    viewRef.current += 1;
    historyLoadingRef.current = false;
    abortRef.current?.abort();
    if (pollTimerRef.current) clearTimeout(pollTimerRef.current);
    pollTimerRef.current = null;
    if (flushTimerRef.current) clearTimeout(flushTimerRef.current);
    flushTimerRef.current = null;
    bufferRef.current.clear();
    runningRef.current = {};
    cursorRef.current = 0;
    setRun({ running: false });
    setTools([]);
    setLoading(false);
    setLoadingMessages(false);
    setConversationsLoading(false);
  }, []);

  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
      abortRef.current?.abort();
      if (pollTimerRef.current) clearTimeout(pollTimerRef.current);
      if (flushTimerRef.current) clearTimeout(flushTimerRef.current);
    };
  }, []);

  /* ==================== 消息增量 flush ==================== */

  const flush = useCallback(() => {
    flushTimerRef.current = null;
    if (bufferRef.current.size === 0) return;
    const pending = new Map(bufferRef.current);
    bufferRef.current.clear();
    setMessages((prev) =>
      prev.map((message) => {
        const delta = pending.get(message.id);
        return delta ? { ...message, content: message.content + delta } : message;
      }),
    );
  }, []);

  const scheduleFlush = useCallback(() => {
    if (flushTimerRef.current) return;
    flushTimerRef.current = setTimeout(flush, 60);
  }, [flush]);

  const applyDelta = useCallback(
    (messageId: number | undefined, delta: string) => {
      if (messageId === undefined || !delta) return;
      const current = bufferRef.current.get(messageId) ?? '';
      bufferRef.current.set(messageId, current + delta);
      scheduleFlush();
    },
    [scheduleFlush],
  );

  const patchMessage = useCallback((messageId: number, patch: Partial<ChatMessage>) => {
    setMessages((prev) =>
      prev.some((message) => message.id === messageId)
        ? prev.map((message) => (message.id === messageId ? { ...message, ...patch } : message))
        : [...prev, { id: messageId, role: 'ASSISTANT', content: '', status: 'PENDING', ...patch }],
    );
  }, []);

  /* ==================== 事件消费 ==================== */

  const finishRun = useCallback(
    (status?: string) => {
      if (flushTimerRef.current) {
        clearTimeout(flushTimerRef.current);
        flushTimerRef.current = null;
      }
      flush();
      setRun({ running: false, transport: undefined });
      const assistantMessageId = runningRef.current.assistantMessageId;
      if (assistantMessageId !== undefined) {
        patchMessage(assistantMessageId, {
          status: status && TERMINAL.has(status) ? status : 'COMPLETED',
        });
      }
      runningRef.current = {};
    },
    [flush, patchMessage],
  );

  const handleEvent = useCallback(
    (event: AiEvent) => {
      if (typeof event.sequence === 'number' && event.sequence > cursorRef.current) {
        cursorRef.current = event.sequence;
      } else if (
        typeof event.sequence === 'number' &&
        event.sequence > 0 &&
        event.type !== 'stream.end'
      ) {
        return;
      }
      const data = (event.data ?? {}) as Record<string, unknown>;
      const messageId = num(data.messageId) ?? runningRef.current.assistantMessageId;

      switch (event.type) {
        case 'message.started': {
          if (messageId !== undefined) {
            runningRef.current.assistantMessageId = messageId;
            patchMessage(messageId, { status: 'STREAMING' });
          }
          break;
        }
        case 'message.delta': {
          if (messageId !== undefined) {
            runningRef.current.assistantMessageId = messageId;
            patchMessage(messageId, { status: 'STREAMING' });
          }
          const delta = str(data.delta) ?? '';
          // 兼容后端未来可能改字段名的情况：content 也认
          applyDelta(messageId, delta || str(data.content) || '');
          break;
        }
        case 'message.completed': {
          if (messageId === undefined) break;
          if (flushTimerRef.current) {
            clearTimeout(flushTimerRef.current);
            flushTimerRef.current = null;
          }
          bufferRef.current.delete(messageId);
          patchMessage(messageId, {
            // 服务端的最终正文是权威版本，直接覆盖本地累积，避免 delta 丢包导致残缺
            content: str(data.content) ?? '',
            blocks: asArray<AiBlock>(data.blocks),
            actions: asArray<ChatAction>(data.actions),
            status: 'COMPLETED',
          });
          break;
        }
        case 'message.citation': {
          if (messageId !== undefined)
            patchMessage(messageId, { citations: asArray<AiCitation>(data.citations) });
          break;
        }
        case 'message.metadata': {
          if (messageId !== undefined) patchMessage(messageId, { modelName: str(data.modelName) });
          break;
        }
        case 'recommendation.updated': {
          if (messageId !== undefined)
            patchMessage(messageId, { actions: asArray<ChatAction>(data.actions) });
          break;
        }
        case 'tool.planned':
        case 'tool.started':
        case 'tool.approval.required':
        case 'tool.completed': {
          const id = num(data.toolCallId);
          if (id === undefined) break;
          const status =
            event.type === 'tool.approval.required'
              ? 'WAITING_APPROVAL'
              : event.type === 'tool.started'
                ? 'RUNNING'
                : event.type === 'tool.completed'
                  ? (str(data.status) ?? 'FAILED')
                  : 'PLANNED';
          setTools((prev) => {
            const old = prev.find((item) => item.toolCallId === id);
            const item: ChatToolTrace = {
              ...old,
              toolCallId: id,
              status,
              toolCode: str(data.toolCode) ?? old?.toolCode,
              name: str(data.name) ?? old?.name,
              risk: str(data.risk) ?? old?.risk,
              approvalPolicy: str(data.approvalPolicy) ?? old?.approvalPolicy,
              arguments:
                data.arguments &&
                typeof data.arguments === 'object' &&
                !Array.isArray(data.arguments)
                  ? (data.arguments as Record<string, unknown>)
                  : old?.arguments,
              summary: str(data.summary) ?? old?.summary,
            };
            return old
              ? prev.map((trace) => (trace.toolCallId === id ? item : trace))
              : [...prev, item];
          });
          if (event.type === 'tool.approval.required' && messageId !== undefined) {
            patchMessage(messageId, { status: 'COMPLETED' });
          }
          break;
        }
        case 'request.failed': {
          const message = str(data.message) ?? 'AI 请求失败';
          const code = str(data.errorCode);
          if (messageId !== undefined) {
            patchMessage(messageId, {
              status: 'FAILED',
              error: code ? `${message}（${code}）` : message,
            });
          }
          setError(code ? `${message}（${code}）` : message);
          break;
        }
        case 'stream.end': {
          finishRun(str(data.status));
          break;
        }
        default:
          break;
      }
    },
    [applyDelta, finishRun, patchMessage],
  );

  /* ==================== 轮询降级 ==================== */

  const pollOnce = useCallback(
    async (requestId: number, view: number) => {
      if (
        !aliveRef.current ||
        view !== viewRef.current ||
        runningRef.current.requestId !== requestId
      )
        return;
      try {
        const res = await aiChatApi.events(requestId, cursorRef.current, 100);
        if (
          !aliveRef.current ||
          view !== viewRef.current ||
          runningRef.current.requestId !== requestId
        )
          return;
        const batch = res.data;
        for (const event of batch?.events ?? []) {
          handleEvent({ ...event, requestId });
        }
        if (batch?.terminal) {
          finishRun(batch.status);
          return;
        }
        setRun((prev) => ({ ...prev, running: true, transport: 'poll' }));
      } catch (err) {
        // 轮询失败不立即放弃：网络抖动会自愈，只有连续失败由用户手动停止
        if (
          !aliveRef.current ||
          view !== viewRef.current ||
          runningRef.current.requestId !== requestId
        )
          return;
        setError(err instanceof Error ? err.message : String(err));
      }
      if (
        aliveRef.current &&
        view === viewRef.current &&
        runningRef.current.requestId === requestId
      ) {
        pollTimerRef.current = setTimeout(() => void pollOnce(requestId, view), POLL_INTERVAL_MS);
      }
    },
    [finishRun, handleEvent],
  );

  const watch = useCallback(
    async (requestId: number, after = 0) => {
      const view = viewRef.current;
      cursorRef.current = after;
      const controller = new AbortController();
      abortRef.current = controller;

      await openEventStream(
        `${aiStreamUrl(requestId)}?after=${after}`,
        {
          onEvent: (event) => {
            if (
              !aliveRef.current ||
              view !== viewRef.current ||
              controller.signal.aborted ||
              runningRef.current.requestId !== requestId
            )
              return;
            setRun((prev) => ({ ...prev, running: true, transport: 'sse' }));
            handleEvent({ ...event, requestId });
          },
        },
        { signal: controller.signal },
      );

      // 流正常关闭：若还卡在 running，说明服务端没发终态，用轮询补齐
      if (
        aliveRef.current &&
        view === viewRef.current &&
        !controller.signal.aborted &&
        runningRef.current.requestId === requestId
      ) {
        void pollOnce(requestId, view);
      }
    },
    [handleEvent, pollOnce],
  );

  /* ==================== 会话加载 ==================== */

  const loadMessages = useCallback(
    async (conversationId: number) => {
      const view = viewRef.current;
      historyLoadingRef.current = true;
      setLoadingMessages(true);
      try {
        const res = await aiChatApi.messages(conversationId, undefined, CONVERSATION_MESSAGE_LIMIT);
        const page = res.data;
        if (!aliveRef.current || view !== viewRef.current) return;
        const items: ChatMessage[] = (page?.items ?? [])
          .filter((item) => item.role !== 'TOOL')
          .map((item) => ({
            id: item.id ?? 0,
            role: item.role ?? 'ASSISTANT',
            content: item.content ?? '',
            status: item.status ?? 'COMPLETED',
            blocks: item.blocks,
            citations: item.citations,
            modelName: item.modelName,
            createTime: item.createTime,
          }));
        setMessages(items);
        const latest = [...(page?.items ?? [])]
          .reverse()
          .find((item) => item.requestId !== undefined);
        if (latest?.requestId !== undefined) {
          const batch = (await aiChatApi.events(latest.requestId, 0, 100)).data;
          if (!aliveRef.current || view !== viewRef.current) return;
          const assistant = [...(page?.items ?? [])]
            .reverse()
            .find((item) => item.requestId === latest.requestId && item.role === 'ASSISTANT');
          runningRef.current = {
            requestId: latest.requestId,
            conversationId,
            assistantMessageId: assistant?.id,
          };
          cursorRef.current = 0;
          setRun({ running: true, requestId: latest.requestId });
          for (const event of batch.events ?? [])
            handleEvent({ ...event, requestId: latest.requestId });
          if (batch.terminal) finishRun(batch.status);
          else void watch(latest.requestId, cursorRef.current);
        }
      } catch (err) {
        if (aliveRef.current && view === viewRef.current)
          setError(err instanceof Error ? err.message : String(err));
      } finally {
        if (aliveRef.current && view === viewRef.current) {
          historyLoadingRef.current = false;
          setLoadingMessages(false);
        }
      }
    },
    [finishRun, handleEvent, watch],
  );

  const loadConversations = useCallback(
    async (selectFirst: boolean) => {
      const view = viewRef.current;
      setConversationsLoading(true);
      try {
        const res = await aiChatApi.conversations(agentCode);
        if (!aliveRef.current || view !== viewRef.current) return;
        const list = res.data ?? [];
        setConversations(list);
        if (selectFirst && list.length > 0 && list[0].id !== undefined) {
          setActiveConversationId(list[0].id);
          await loadMessages(list[0].id);
        } else if (list.length === 0) {
          setActiveConversationId(undefined);
          setMessages([]);
        }
      } catch (err) {
        if (aliveRef.current && view === viewRef.current)
          setError(err instanceof Error ? err.message : String(err));
      } finally {
        if (aliveRef.current && view === viewRef.current) setConversationsLoading(false);
      }
    },
    [agentCode, loadMessages],
  );

  const reload = useCallback(async () => {
    detach();
    const view = viewRef.current;
    setError(undefined);
    setLoading(true);
    try {
      // assistants 的 AgentCard 已内嵌 capabilities——单独的 capabilities 请求是重复查询，省掉；
      // 会话列表（内部自行串联首屏消息）与智能体卡片互相独立，并行拉取缩短首屏瀑布
      const [cards] = await Promise.all([
        aiChatApi.assistants(),
        loadConversations(true).catch(() => undefined),
      ]);
      if (!aliveRef.current || view !== viewRef.current) return;
      const found = (cards.data ?? []).find((item) => item.agentCode === agentCode);
      setCard(found);
      setCapabilities(found?.capabilities);
    } catch (err) {
      if (aliveRef.current && view === viewRef.current)
        setError(err instanceof Error ? err.message : String(err));
    } finally {
      if (aliveRef.current && view === viewRef.current) setLoading(false);
    }
  }, [agentCode, detach, loadConversations]);

  useEffect(() => {
    void reload();
  }, [reload]);

  /* ==================== 操作 ==================== */

  const newConversation = useCallback(async (): Promise<number | undefined> => {
    detach();
    const view = viewRef.current;
    try {
      const res = await aiChatApi.createConversation(agentCode);
      if (!aliveRef.current || view !== viewRef.current) return undefined;
      const created = res.data;
      if (created?.id === undefined) return undefined;
      setConversations((prev) => [created, ...prev]);
      setActiveConversationId(created.id);
      setMessages([]);
      return created.id;
    } catch (err) {
      if (aliveRef.current && view === viewRef.current)
        setError(err instanceof Error ? err.message : String(err));
      return undefined;
    }
  }, [agentCode, detach]);

  const selectConversation = useCallback(
    async (id: number) => {
      detach();
      setActiveConversationId(id);
      setError(undefined);
      await loadMessages(id);
    },
    [detach, loadMessages],
  );

  const renameConversation = useCallback(async (id: number, title: string) => {
    const view = viewRef.current;
    const res = await aiChatApi.renameConversation(id, title);
    if (!aliveRef.current || view !== viewRef.current) return;
    const updated = res.data;
    setConversations((prev) =>
      prev.map((item) => (item.id === id ? { ...item, ...updated } : item)),
    );
  }, []);

  const removeConversation = useCallback(
    async (id: number) => {
      const view = viewRef.current;
      await aiChatApi.removeConversation(id);
      if (!aliveRef.current || view !== viewRef.current) return;
      if (id === activeConversationId) detach();
      const next = conversations.filter((item) => item.id !== id);
      setConversations(next);
      if (id === activeConversationId) {
        const fallback = next[0]?.id;
        setActiveConversationId(fallback);
        if (fallback !== undefined) await loadMessages(fallback);
        else setMessages([]);
      }
    },
    [activeConversationId, conversations, detach, loadMessages],
  );

  const stop = useCallback(async () => {
    const view = viewRef.current;
    const requestId = runningRef.current.requestId;
    if (requestId === undefined && sendRef.current?.view === view) {
      sendRef.current.cancel = true;
      return;
    }
    let status = 'CANCELLED';
    if (requestId !== undefined) {
      try {
        const response = await aiChatApi.cancel(requestId, '用户停止');
        status = response.data?.status ?? 'CANCELLED';
      } catch (error) {
        if (aliveRef.current && view === viewRef.current)
          setError(error instanceof Error ? error.message : '取消未成功，请重试');
        return;
      }
    }
    if (!aliveRef.current || view !== viewRef.current || requestId !== runningRef.current.requestId)
      return;
    if (status !== 'CANCELLED') return; // Continue receiving the server's final answer/failure.
    abortRef.current?.abort();
    if (pollTimerRef.current) clearTimeout(pollTimerRef.current);
    pollTimerRef.current = null;
    finishRun('CANCELLED');
  }, [finishRun]);

  const send = useCallback(
    async (content: string) => {
      const text = content.trim();
      if (
        !text ||
        historyLoadingRef.current ||
        loading ||
        run.running ||
        sendRef.current?.view === viewRef.current
      )
        return;

      const operation = { view: viewRef.current, cancel: false };
      sendRef.current = operation;

      setError(undefined);
      setTools([]);
      setLoading(true);
      try {
        let conversationId = activeConversationId;
        if (conversationId === undefined) {
          operation.view = viewRef.current + 1;
          conversationId = await newConversation();
        }
        if (
          !aliveRef.current ||
          operation.view !== viewRef.current ||
          sendRef.current !== operation
        )
          return;
        if (conversationId === undefined) {
          setError('无法创建会话，请检查 AI 插件是否启用');
          return;
        }

        // 两个本地占位：用户消息与助手消息。id 先用负数占据位置，
        // 拿到服务端 id 后再替换，保证界面立刻有反馈。
        const tempUser = -Date.now();
        const tempAssistant = tempUser - 1;
        setMessages((prev) => [
          ...prev,
          { id: tempUser, role: 'USER', content: text, status: 'COMPLETED', pending: true },
          { id: tempAssistant, role: 'ASSISTANT', content: '', status: 'PENDING', pending: true },
        ]);
        setRun({ running: true, assistantMessageId: tempAssistant });

        const res = await aiChatApi.send(conversationId, {
          content: text,
          clientMessageId: `web-${tempUser}`,
          idempotencyKey: `web-${tempUser}`,
          context: context?.(),
        });
        const result = res.data;
        const requestId = result?.requestId;
        if (operation.cancel && requestId !== undefined) {
          try {
            await aiChatApi.cancel(requestId, '用户停止');
          } catch {
            if (aliveRef.current && operation.view === viewRef.current)
              setError('取消未成功，请重试');
          }
        }
        if (
          !aliveRef.current ||
          operation.view !== viewRef.current ||
          sendRef.current !== operation
        )
          return;
        const assistantMessageId = result?.assistantMessageId;
        const userMessageId = result?.userMessageId;

        if (requestId === undefined) {
          setMessages((prev) =>
            prev.map((message) =>
              message.id === tempAssistant
                ? { ...message, status: 'FAILED', error: '服务端未返回请求编号' }
                : message,
            ),
          );
          setRun({ running: false });
          return;
        }

        runningRef.current = { requestId, assistantMessageId, conversationId };

        setMessages((prev) =>
          prev.map((message) => {
            if (message.id === tempUser) {
              return {
                ...message,
                id: userMessageId ?? tempUser,
                pending: userMessageId === undefined,
              };
            }
            if (message.id === tempAssistant) {
              return {
                ...message,
                id: assistantMessageId ?? tempAssistant,
                status: 'STREAMING',
                pending: assistantMessageId === undefined,
              };
            }
            return message;
          }),
        );
        setRun({ running: true, requestId, assistantMessageId, transport: 'sse' });

        // 会话标题由后端在首条消息时生成，发送后刷新一次列表即可拿到新标题
        void loadConversations(false);
        void watch(requestId);
      } catch (err) {
        if (
          !aliveRef.current ||
          operation.view !== viewRef.current ||
          sendRef.current !== operation
        )
          return;
        const message = err instanceof Error ? err.message : String(err);
        setError(message);
        setMessages((prev) => {
          const last = prev[prev.length - 1];
          return last && last.role === 'ASSISTANT' && last.status !== 'COMPLETED'
            ? [...prev.slice(0, -1), { ...last, status: 'FAILED', error: message }]
            : prev;
        });
        setRun({ running: false });
      } finally {
        if (sendRef.current === operation) sendRef.current = null;
        if (aliveRef.current && operation.view === viewRef.current) setLoading(false);
      }
    },
    [
      activeConversationId,
      context,
      loadConversations,
      loading,
      newConversation,
      run.running,
      watch,
    ],
  );

  const feedback = useCallback(
    async (messageId: number, feedbackType: string, comment?: string) => {
      await aiChatApi.feedback(messageId, { feedbackType, comment });
    },
    [],
  );

  const approveTool = useCallback(async (id: number, approved: boolean, admin = false) => {
    if (approvalRef.current.has(id)) return;
    approvalRef.current.add(id);
    const view = viewRef.current;
    setTools((prev) =>
      prev.map((item) => (item.toolCallId === id ? { ...item, submitting: true } : item)),
    );
    try {
      const response = await aiChatApi.approveTool(id, approved, admin);
      if (aliveRef.current && view === viewRef.current) {
        setTools((prev) =>
          prev.map((item) =>
            item.toolCallId === id ? { ...item, ...response.data, submitting: false } : item,
          ),
        );
      }
    } finally {
      approvalRef.current.delete(id);
      if (aliveRef.current && view === viewRef.current) {
        setTools((prev) =>
          prev.map((item) => (item.toolCallId === id ? { ...item, submitting: false } : item)),
        );
      }
    }
  }, []);

  return useMemo(
    () => ({
      card,
      capabilities,
      conversations,
      activeConversationId,
      messages,
      tools,
      approveTool,
      run,
      loading,
      loadingMessages,
      conversationsLoading,
      error,
      newConversation,
      selectConversation,
      renameConversation,
      removeConversation,
      send,
      stop,
      feedback,
      reload,
    }),
    [
      activeConversationId,
      capabilities,
      card,
      conversations,
      conversationsLoading,
      error,
      feedback,
      loading,
      loadingMessages,
      messages,
      tools,
      approveTool,
      newConversation,
      reload,
      removeConversation,
      renameConversation,
      run,
      selectConversation,
      send,
      stop,
    ],
  );
}

export default useAiChat;
