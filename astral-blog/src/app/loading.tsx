/**
 * 路由级加载骨架：动态页面回源较慢时给出结构提示，避免白屏。
 * 使用 aria-busy + role=status，让读屏用户知道内容正在加载。
 */
export default function Loading() {
  return (
    <div
      className="page-shell py-8 sm:py-12"
      role="status"
      aria-busy="true"
      aria-label="页面加载中"
    >
      <div className="bg-line-soft h-3 w-28 animate-pulse" />
      <div className="bg-line-soft mt-5 h-10 w-2/3 max-w-[32rem] animate-pulse" />
      <div className="mt-10 space-y-4">
        <div className="border-line bg-surface-muted h-24 animate-pulse border" />
        <div className="border-line bg-surface-muted h-24 animate-pulse border" />
        <div className="border-line bg-surface-muted h-24 animate-pulse border" />
      </div>
      <span className="sr-only">页面加载中，请稍候。</span>
    </div>
  );
}
