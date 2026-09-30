'use client';

/**
 * 全局错误边界
 * <p>
 * 兜底「根布局自身渲染失败」的场景：此时 layout.tsx 里的 globals.css 不会再被渲染，
 * CSS 变量与 Tailwind 令牌都不可用，因此这里全部用内联样式，保证在无样式环境下依然
 * 是浅色底 + 深色字、可读可用。必须自带 <html>/<body>。
 * </p>
 */
export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <html lang="zh-CN">
      <body
        style={{
          margin: 0,
          minHeight: '100vh',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 16,
          padding: '0 24px',
          textAlign: 'center',
          background: '#ffffff',
          color: '#18181b',
          fontFamily: 'system-ui, -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif',
        }}
      >
        <h1 style={{ margin: 0, fontSize: 18, fontWeight: 600 }}>系统出错了</h1>
        <p style={{ margin: 0, maxWidth: 480, fontSize: 14, color: '#71717a' }}>
          {error.message || '应用初始化时发生异常，请刷新页面重试。'}
        </p>
        {error.digest ? (
          <p style={{ margin: 0, fontSize: 12, color: '#71717a' }}>错误编号：{error.digest}</p>
        ) : null}
        <button
          type="button"
          onClick={() => reset()}
          style={{
            cursor: 'pointer',
            borderRadius: 6,
            border: '1px solid #18181b',
            background: '#18181b',
            color: '#fafafa',
            padding: '8px 16px',
            fontSize: 14,
          }}
        >
          重试
        </button>
      </body>
    </html>
  );
}
