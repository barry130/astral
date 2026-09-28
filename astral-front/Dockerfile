# syntax=docker/dockerfile:1
# 多阶段构建: Next.js 16 standalone
#
# 【前端版本】Next.js 16.3.6 + React 19 + Tailwind CSS v4 + shadcn/ui（已移除 antd）。
#   Next 16 默认使用 Turbopack 构建；`next lint` 已在 16 中移除，故 package.json 无 lint 脚本。
#
# 【Node 版本，勿随意改】三个阶段统一 node:24-alpine（Active LTS，支持到 2028-04）。
#   版本依据 https://github.com/nodejs/Release/blob/main/schedule.json
#   - 原来用的 node:20-alpine，但 Node 20 已于 2026-04-30 EOL，不再有安全修复，故升级；
#   - 不要改成 node:25-alpine：25 是奇数号非 LTS 线，2026-06-01 已 EOL，
#     等于用一个停维版本换掉另一个停维版本；
#   - 想跟进 26 也可以（2026-10-28 转 LTS），但请三个阶段一起改。
#   - 三阶段版本必须一致：deps 阶段装的 node_modules 会被后两阶段直接复用，
#     跨版本可能带来 native 模块 ABI 不一致。本项目当前无 native 依赖
#     （package.json 里没有 sharp / @img 等），但保持一致仍是必要的纪律。
#   - 本地开发机若跑 Node 25，只影响本机，与本镜像无关（但 25 本身也是 EOL 版本）。
#
# 第一阶段: 依赖安装
FROM node:24-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
# npmmirror 加速国内 VPS；BuildKit cache 让二次构建不再重下 node_modules。
# 海外机器若镜像更慢：把 --registry 去掉即可回退 registry.npmjs.org。
RUN --mount=type=cache,target=/root/.npm \
    npm ci --registry=https://registry.npmmirror.com

# 第二阶段: 构建
FROM node:24-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# 项目当前无 public 目录, 但保证其存在, 供 runner 阶段 COPY 使用
RUN mkdir -p public
# next.config.js 的 rewrites 在构建时读取该地址
ARG BACKEND_URL=http://backend:27000
ENV BACKEND_URL=$BACKEND_URL
# NEXT_PUBLIC_API_URL 构建时内联, 默认空串 -> 走同源相对路径 (/api), 由 Next rewrites 转发
ARG NEXT_PUBLIC_API_URL=
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL
RUN npm run build

# 第三阶段: 运行 (standalone 产物)
FROM node:24-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
# 运行时环境变量: 后端地址 (供 next.config.js rewrites 使用)
# 默认指向 compose 服务名 backend；独立运行时需显式覆盖为可达的后端地址
ENV BACKEND_URL=http://backend:27000

RUN addgroup -S nodejs && adduser -S nextjs -G nodejs

# 复制 standalone 服务
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
# 复制静态资源
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static

# 复制 public (builder 阶段已保证存在, 当前为空目录)
COPY --from=builder --chown=nextjs:nodejs /app/public ./public

USER nextjs
EXPOSE 3000
ENV PORT=3000
CMD ["node", "server.js"]
