/**
 * 全局请求计数
 *
 * 由 src/api/client.ts 的拦截器驱动：请求发出 +1，响应或失败 -1。
 * 用「计数」而不是「布尔值」是关键——表格页常常同时并发 3~5 个接口，
 * 布尔值会在第一个接口返回时就隐藏指示条，剩下的请求变成无反馈状态。
 */

type Listener = (active: number) => void;
type Unsubscribe = () => void;

let activeCount = 0;
const listeners = new Set<Listener>();

const notify = (): void => {
  listeners.forEach((listener) => listener(activeCount));
};

/** 请求开始：活跃数 +1 */
export const beginRequest = (): void => {
  activeCount += 1;
  notify();
};

/**
 * 请求结束：活跃数 -1
 * 成功与失败都必须调用，否则计数泄漏会让指示条永久常驻
 */
export const endRequest = (): void => {
  activeCount = Math.max(0, activeCount - 1);
  notify();
};

/** 订阅活跃请求数；订阅时立即回推当前值，避免首帧状态不一致 */
export const subscribeRequestCount = (listener: Listener): Unsubscribe => {
  listeners.add(listener);
  listener(activeCount);
  return () => {
    listeners.delete(listener);
  };
};
