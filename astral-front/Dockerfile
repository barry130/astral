# syntax=docker/dockerfile:1
# 多阶段构建: Next.js 14 standalone
# 第一阶段: 依赖安装
FROM node:20-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
# npmmirror 加速国内 VPS；BuildKit cache 让二次构建不再重下 node_modules。
# 海外机器若镜像更慢：把 --registry 去掉即可回退 registry.npmjs.org。
RUN --mount=type=cache,target=/root/.npm \
    npm ci --registry=https://registry.npmmirror.com

# 第二阶段: 构建
FROM node:20-alpine AS builder
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
FROM node:20-alpine AS runner
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
