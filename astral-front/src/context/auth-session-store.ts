'use client';

import {
  sessionInfoFromResponse,
  type LoginRequest,
  type LoginResponse,
  type SessionInfoResponse,
} from '@/api/auth';
import type { ApiRequestConfig, ApiResult } from '@/api/client';

export interface AuthSnapshot {
  readonly user: SessionInfoResponse | null;
  readonly loading: boolean;
}

export interface AuthBackend {
  login: (request: LoginRequest) => Promise<ApiResult<LoginResponse>>;
  logout: () => Promise<ApiResult<void>>;
  getUserInfo: (config?: ApiRequestConfig) => Promise<ApiResult<SessionInfoResponse>>;
  setCsrfToken: (token?: string | null) => void;
  clearCsrfToken: () => void;
}

/** 同一 document 的 Cookie 写请求必须完成后才允许下一次写；拒绝也不能卡住队列。 */
export class AuthMutationQueue {
  private tail: Promise<void> = Promise.resolve();
  private pending = 0;

  run<T>(operation: () => Promise<T>): Promise<T> {
    this.pending += 1;
    const result = this.tail.then(operation);
    this.tail = result.then(
      () => {
        this.pending -= 1;
      },
      () => {
        this.pending -= 1;
      },
    );
    return result;
  }

  isIdle(): boolean {
    return this.pending === 0;
  }

  async idle(): Promise<void> {
    // 等待期间加入的写请求也要完成，不能在另一连接切换 Cookie 时恢复身份。
    while (!this.isIdle()) await this.tail;
  }
}

const sharedMutations = new AuthMutationQueue();
const INITIAL_SNAPSHOT: AuthSnapshot = Object.freeze({ user: null, loading: true });

/** AuthProvider 的实际会话边界；只有当前活动连接与最新用户意图能够发布身份。 */
export class AuthSession {
  private snapshot = INITIAL_SNAPSHOT;
  private readonly listeners = new Set<() => void>();
  private active = false;
  private revision = 0;
  private connectionRevision = 0;
  private restoreAbort: AbortController | null = null;

  constructor(
    private readonly backend: AuthBackend,
    private readonly mutations: AuthMutationQueue = sharedMutations,
  ) {}

  getSnapshot = (): AuthSnapshot => this.snapshot;
  getServerSnapshot = (): AuthSnapshot => INITIAL_SNAPSHOT;
  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  connect(): { ready: Promise<void>; disconnect: () => void } {
    this.active = true;
    const connection = ++this.connectionRevision;
    const ticket = this.beginIntent();
    return {
      ready: this.restore(ticket),
      disconnect: () => {
        if (connection !== this.connectionRevision || !this.active) return;
        this.active = false;
        this.revision += 1;
        this.restoreAbort?.abort();
        this.restoreAbort = null;
        // 不使仍在执行的旧 Cookie 写请求的 CSRF 恢复失效；下一连接等其完成后清理。
        if (this.mutations.isIdle()) this.backend.clearCsrfToken();
        this.publish({ user: null, loading: false });
      },
    };
  }

  private current(ticket: number): boolean {
    return this.active && ticket === this.revision;
  }

  private beginIntent(): number {
    if (!this.active) throw new Error('认证页面已关闭，请重新打开后重试');
    this.restoreAbort?.abort();
    this.restoreAbort = null;
    const ticket = ++this.revision;
    this.publish({ user: null, loading: true });
    return ticket;
  }

  private publish(snapshot: AuthSnapshot): void {
    this.snapshot = Object.freeze(snapshot);
    for (const listener of this.listeners) listener();
  }

  private async restore(ticket: number): Promise<void> {
    const controller = new AbortController();
    this.restoreAbort = controller;
    try {
      await this.mutations.idle();
      if (!this.current(ticket) || controller.signal.aborted) return;
      this.backend.clearCsrfToken();
      const response = await this.backend.getUserInfo({ signal: controller.signal, silent: true });
      if (!this.current(ticket)) return;
      if (response.code !== 200) throw new Error(response.msg);
      const user = sessionInfoFromResponse(response.data);
      this.backend.setCsrfToken(user.csrfToken);
      this.publish({ user, loading: false });
    } catch {
      // 请求被取消、失败或属于旧会话时，不能恢复其身份或 CSRF 值。
      if (this.current(ticket)) {
        this.backend.clearCsrfToken();
        this.publish({ user: null, loading: false });
      }
    } finally {
      if (this.restoreAbort === controller) this.restoreAbort = null;
      if (this.current(ticket)) this.publish({ ...this.snapshot, loading: false });
    }
  }

  async login(request: LoginRequest): Promise<LoginResponse> {
    const ticket = this.beginIntent();
    return this.mutations.run(async () => {
      if (!this.current(ticket)) throw new Error('登录已被新的会话操作取代');
      // API 客户端的会话代次在实际换 Cookie 前推进，而不是在等待中的 UI 意图上推进。
      this.backend.clearCsrfToken();
      try {
        const response = await this.backend.login(request);
        if (!this.current(ticket)) throw new Error('登录已被新的会话操作取代');
        if (response.code !== 200) throw new Error(response.msg);
        const user = sessionInfoFromResponse(response.data);
        this.backend.setCsrfToken(user.csrfToken);
        this.publish({ user, loading: false });
        return response.data;
      } finally {
        if (this.current(ticket)) this.publish({ ...this.snapshot, loading: false });
      }
    });
  }

  async logout(): Promise<void> {
    const ticket = this.beginIntent();
    return this.mutations.run(async () => {
      // 已明确请求的登出仍要执行，包括之后卸载的连接；否则旧在途登录会留下有效 Cookie。
      this.backend.clearCsrfToken();
      try {
        const response = await this.backend.logout();
        if (response.code !== 200) throw new Error(response.msg);
      } finally {
        if (this.current(ticket)) {
          this.backend.clearCsrfToken();
          this.publish({ user: null, loading: false });
        }
      }
    });
  }
}
