# syntax=docker/dockerfile:1
# 多阶段构建: astral-blog（公开博客 + Wiki 前台）Next.js 16 standalone
#
# 【Node 版本，与 astral-front 保持一致】三个阶段统一 node:24-alpine。
#   - Node 20 已于 2026-04-30 EOL，不再有安全修复，故不用 node:20-alpine；
#   - 不要用 node:25-alpine：25 是奇数号非 LTS 线，2026-06-01 已 EOL。
#   - 三阶段版本必须一致：deps 装的 node_modules 会被后两阶段直接复用。
#
# 【包管理器】本项目用 pnpm（有 pnpm-lock.yaml，lockfileVersion 9），
#   不要换成 npm ci —— 仓库里没有 package-lock.json。
#
# 【构建期不访问后端】所有页面 force-dynamic，后端不可达也能 build（见 next.config.ts）。

# 第一阶段: 依赖安装
FROM node:24-alpine AS deps
WORKDIR /app
# corepack 随 Node 发行，用它锁定 pnpm 版本，避免不同机器装到不同 major
RUN corepack enable && corepack prepare pnpm@11.7.0 --activate
COPY package.json pnpm-lock.yaml ./
# npmmirror 加速国内 VPS；BuildKit cache 让二次构建不再重下 node_modules。
# 海外机器若镜像更慢：把 --registry 去掉即可回退 registry.npmjs.org。
RUN --mount=type=cache,id=pfs-blog-pnpm,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile --registry=https://registry.npmmirror.com

# 第二阶段: 构建
FROM node:24-alpine AS builder
WORKDIR /app
RUN corepack enable && corepack prepare pnpm@11.7.0 --activate
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# 项目当前无 public 目录, 但保证其存在, 供 runner 阶段 COPY 使用
RUN mkdir -p public
# 后端公开 CMS API 地址。构建期不请求它（页面全 force-dynamic），
# 但 standalone 产物仍需在运行时能读到该环境变量，故 runner 阶段会再声明一次。
ARG CMS_API_BASE=http://backend:27000
ENV CMS_API_BASE=$CMS_API_BASE
# 不设 NEXT_TELEMETRY_DISABLED 会在构建时上报匿名统计（部分网络会卡住）
ENV NEXT_TELEMETRY_DISABLED=1
RUN pnpm run build

# 第三阶段: 运行 (standalone 产物)
FROM node:24-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
# 运行时环境变量: 后端公开 CMS API 地址（服务端 RSC fetch 的目标）
# 默认指向 compose 服务名 backend；独立运行时显式覆盖为可达的后端地址
ENV CMS_API_BASE=http://backend:27000

RUN addgroup -S nodejs && adduser -S nextjs -G nodejs

# 复制 standalone 服务（server.js + 裁剪后的 node_modules）
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
# 复制静态资源
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
# 复制 public (builder 阶段已保证存在, 当前为空目录)
COPY --from=builder --chown=nextjs:nodejs /app/public ./public

USER nextjs
EXPOSE 3000
ENV PORT=3000
# 注意：package.json 的 start 脚本写死了 -p 3001（本地避开管理端 3000），
# 容器里走 standalone 的 server.js，端口由上面的 PORT 决定，不经过该脚本。
CMD ["node", "server.js"]
