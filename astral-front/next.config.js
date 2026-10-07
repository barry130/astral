/** @type {import('next').NextConfig} */
// BACKEND_URL: 后端地址, 容器内指向 backend 容器 (由 docker-compose 注入), 本地开发默认 localhost:27000
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:27000';
// 管理台自身的版本号, 构建期注入给 src/lib/client-info.ts 当 X-App-Version 头的值
// (统一客户端系统头契约, 见 astral-common 的 com.astral.common.util.ClientHeaders)
const CLIENT_VERSION = require('./package.json').version;

const nextConfig = {
  reactStrictMode: true,
  output: 'standalone',
  // echarts 6 的 ESM lib 内部存在循环依赖 (chart/tree/treeAction.js 等 → helper/roamHelper.js)，
  // Turbopack 对循环依赖中函数声明绑定的初始化不符合 ESM 规范，模块求值期会炸
  // "registerRoamActionSimply is not a function"（feedback/statistics 页白屏，刷新无解）。
  // 整仓没有任何直接 import echarts 子路径的代码，唯一入口 echarts-for-react require 根导出，
  // 把根导出别名到单文件 UMD 产物（dist/echarts.js）即可整图绕开循环，且与全量引入等价。
  turbopack: {
    resolveAlias: {
      // ⚠️ 必须是相对路径：Turbopack 的 resolveAlias 不支持 Windows 绝对路径（"windows imports are not implemented yet"）
      echarts: './node_modules/echarts/dist/echarts.js',
    },
  },
  env: {
    NEXT_PUBLIC_CLIENT_VERSION: CLIENT_VERSION,
  },
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: `${BACKEND_URL}/api/:path*`,
      },
    ];
  },
  // 防止发版后浏览器/中间缓存层(nginx proxy cache 等)继续投递旧 HTML:
  // - HTML(/) 与 RSC/预取请求: 明确禁止缓存, 每次回源拿最新页面
  // - /_next/static/**: 文件名带内容 hash, 内容变即文件名变, 长缓存 + immutable 是安全的
  // - /api/**: 默认 no-store, 双保险
  //
  // ⚠️ 以上「文件名带内容 hash」只在 production 构建成立。
  // next dev(Turbopack) 下 /_next/static/** 的 chunk 名是按模块路径派生的稳定名
  // (如 src_app_globals_162hn9o.css)，改了 globals.css 或任何组件的 Tailwind 类之后
  // 内容变了、文件名不变；此时若照发 max-age=31536000, immutable，浏览器会永久命中
  // 那份旧 CSS —— 表现为「代码明明改了、界面纹丝不动」，且普通刷新也救不回来
  // (immutable 会跳过再验证)。Next 也会就此打印
  // "Custom Cache-Control headers ... can break Next.js development behavior" 警告。
  // 所以 dev 下不接管缓存头，交回 Next 默认策略。
  async headers() {
    if (process.env.NODE_ENV !== 'production') return [];
    return [
      {
        source: '/:path*',
        headers: [
          { key: 'Cache-Control', value: 'no-cache, no-store, must-revalidate' },
        ],
      },
      {
        source: '/_next/static/:path*',
        headers: [
          { key: 'Cache-Control', value: 'public, max-age=31536000, immutable' },
        ],
      },
      {
        source: '/api/:path*',
        headers: [
          { key: 'Cache-Control', value: 'no-store, max-age=0' },
        ],
      },
    ];
  },
};

module.exports = nextConfig;
