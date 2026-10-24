import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  reactStrictMode: true,
  // 说明：所有数据页均为 force-dynamic（请求时服务端拉取后端 CMS 公开 API），
  // 构建期不发起任何必需的数据请求，后端不可达也能正常 build。
  //
  // standalone 产物供 Docker 部署使用：server.js 自带最小依赖树，
  // 镜像里只需复制 .next/standalone + .next/static + public，不必装整份 node_modules。
  output: 'standalone',
};

export default nextConfig;
