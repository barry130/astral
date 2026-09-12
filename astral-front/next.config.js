/** @type {import('next').NextConfig} */
// BACKEND_URL: 后端地址, 容器内指向 backend 容器 (由 docker-compose 注入), 本地开发默认 localhost:27000
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:27000';

const nextConfig = {
  reactStrictMode: true,
  output: 'standalone',
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
  async headers() {
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
