# syntax=docker/dockerfile:1
# 多阶段构建: Next.js 16 standalone
#
# 【前端版本】Next.js 16.3.8 + React 19 + Tailwind CSS v4 + shadcn/ui（已移除 antd）。
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
# BACKEND_URL 仅供构建期 next.config.js rewrites 求值（dev 形态回退）；
# 运行期真正生效的是 runner 阶段 proxy.js 启动时读取的同名环境变量。
ARG BACKEND_URL=http://backend:27000
ENV BACKEND_URL=$BACKEND_URL
# 注意：不要给构建传 NEXT_PUBLIC_API_URL。bundle 必须保持空串（同源相对路径 /api），
# 运行期全靠容器内 proxy.js 转发；烤入域名会让浏览器绕过代理直连，镜像通用化即失效。
RUN npm run build

# 第三阶段: 运行 (standalone 产物 + 前置同源代理)
FROM node:24-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
#
# 【进程模型，勿改单进程】镜像对外只暴露一个端口，里面是两个进程:
#   proxy.js (:${PORT:-3000}) —— 对外唯一入口
#     · /api/**        → BACKEND_URL (默认 http://backend:27000，后端路由自带 /api 前缀)
#     · 其余 + WS 升级  → 127.0.0.1:${NEXT_PORT:-3001} 的 Next standalone
#   这样 API_BASE_URL 保持空串（同源相对路径 /api）烤进 bundle，
#   镜像不再绑定任何后端域名，换后端只需换 BACKEND_URL 环境变量，无需重新 build。
# BACKEND_URL 启动时读取（proxy.js 里 process.env），改环境变量即生效。
ENV BACKEND_URL=http://backend:27000
ENV NEXT_PORT=3001

RUN addgroup -S nodejs && adduser -S nextjs -G nodejs

# 复制 standalone 服务
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
# 复制静态资源（必须在 server.js 启动前就位: Next 启动时会缓存 static 目录状态）
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static

# 复制 public (builder 阶段已保证存在, 当前为空目录)
COPY --from=builder --chown=nextjs:nodejs /app/public ./public

# 前置代理（零依赖 node:http，含 WebSocket/SSE 升级透传）
COPY --from=builder --chown=nextjs:nodejs /app/proxy.js ./proxy.js

USER nextjs
EXPOSE 3000
ENV PORT=3000
# proxy.js 是 1 号进程，负责转发并转发信号给 Next 子进程；server.js 由它拉起
CMD ["node", "proxy.js"]
