#!/usr/bin/env bash
# ============================================
# 服务器侧一键更新：拉取 CI 构建好的镜像并重建容器
#
# 前提：
#   1) 仓库 canace/astral 当前为公开仓，镜像可匿名拉取，无需登录。
#      若日后改回私有仓，登录一次即可（凭证持久化到 /root/.docker/config.json，
#      之后 ./update.sh 自动复用。CNB 访问令牌需勾选 registry-package 读权限）：
#        echo '<CNB访问令牌>' | docker login docker.cnb.cool -u cnb --password-stdin
#   2) deploy/.env 已设置 REGISTRY（如 docker.cnb.cool/canace/astral）
#
# 用法：
#   ./update.sh                      # 用 deploy/.env 里的 TAG（默认 latest）
#   ./update.sh 20260926-a1b2c3d     # 指定 tag 精确发布
#   ./update.sh 20260919-ab12cd3     # 回滚到旧版本（注意：数据库迁移不会跟着回滚）
#
# 端口在 docker-compose.yml 里就写成 127.0.0.1:<port>，只绑回环 ——
# 线上前面总有反代（宝塔面板 nginx 或 deploy/nginx/ 那套容器 nginx）。
# 以前用 overlay 追加回环地址的做法是错的：Compose 对 ports 按
# {ip, target, published, protocol} 合并，两条 IP 不同会被**同时保留**，
# 容器自己绑两次同一端口，启动时报 `address already in use`。
# 要直接对外就改 docker-compose.yml 里那三行，不要加 overlay。
#
# 不要用 `docker compose up -d --build` 走这条路：
# 服务器只 pull 不构建，构建统一由 CNB 云原生构建（cnb.cool）完成。
# ============================================
set -euo pipefail

cd "$(dirname "$0")"

COMPOSE=(-f docker-compose.yml -f docker-compose.registry.yml)
echo "==> 端口仅绑回环（127.0.0.1），供前面 nginx 反代"

if [ "$#" -ge 1 ]; then
  export TAG="$1"
fi

echo "==> 拉取镜像 (TAG=${TAG:-latest})"
docker compose "${COMPOSE[@]}" pull

echo "==> 重建容器（--no-build：镜像一律来自仓库，不在服务器上编译）"
docker compose "${COMPOSE[@]}" up -d --no-build

echo "==> 当前状态"
docker compose "${COMPOSE[@]}" ps

echo "==> 清理 7 天前的旧镜像（1核2G 学生机磁盘有限，必须定期回收）"
docker image prune -af --filter "until=168h" >/dev/null || true

echo "==> 完成。查看日志： docker compose ${COMPOSE[*]} logs -f backend"