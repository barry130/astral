#!/usr/bin/env bash
# ============================================================
# Astral 打包脚本 (服务器上执行，用于从源码构建 Docker 镜像)
#
#   用法:
#     ./pack.sh                       # 打包到默认目录 /opt/astral-pack
#     ./pack.sh /data/release         # 指定输出目录
#     ./pack.sh -n astral-20260910    # 指定产物名
#     ./pack.sh -o /data/release -n astral-20260910
#
#   产物: <输出目录>/<名称>.tar.gz  (仅源码，不含 node_modules/target/.next)
# ============================================================
set -euo pipefail

# ---------- 参数 ----------
OUT_DIR="/opt/astral-pack"
NAME="astral"
while [ $# -gt 0 ]; do
  case "$1" in
    -o|--out)  OUT_DIR="$2"; shift 2 ;;
    -n|--name) NAME="$2";    shift 2 ;;
    -h|--help)
      sed -n '2,12p' "$0"; exit 0 ;;
    *)
      # 兼容位置参数: ./pack.sh /data/release
      OUT_DIR="$1"; shift ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"     # astral 仓库根
PROJECT_DIR="$(basename "$SRC_DIR")"        # astral
STAMP="$(date +%Y%m%d-%H%M%S)"
[ "$NAME" = "astral" ] && NAME="astral-${STAMP}"
ARCHIVE="$OUT_DIR/${NAME}.tar.gz"

echo "============================================"
echo " 源码目录 : $SRC_DIR"
echo " 输出目录 : $OUT_DIR"
echo " 产物名称 : ${NAME}.tar.gz"
echo "============================================"

# ---------- 前置检查 ----------
command -v tar >/dev/null 2>&1 || { echo "[ERR] 未找到 tar"; exit 1; }

for f in deploy/docker-compose.yml astral-server/Dockerfile astral-front/Dockerfile pom.xml; do
  [ -e "$SRC_DIR/$f" ] || { echo "[ERR] 缺少必要文件: $f"; exit 1; }
done

# ---------- 打包 ----------
mkdir -p "$OUT_DIR"
cd "$(dirname "$SRC_DIR")"

tar \
  --exclude="${PROJECT_DIR}/astral-front/node_modules" \
  --exclude="${PROJECT_DIR}/astral-front/.next" \
  --exclude="${PROJECT_DIR}/astral-front/out" \
  --exclude="${PROJECT_DIR}/**/target" \
  --exclude="${PROJECT_DIR}/data" \
  --exclude="${PROJECT_DIR}/logs" \
  --exclude="${PROJECT_DIR}/.git" \
  --exclude="${PROJECT_DIR}/.idea" \
  --exclude="${PROJECT_DIR}/.vscode" \
  --exclude="${PROJECT_DIR}/.claude" \
  --exclude="${PROJECT_DIR}/.qwen" \
  --exclude="${PROJECT_DIR}/**/*.log" \
  --exclude="${PROJECT_DIR}/**/.DS_Store" \
  --exclude="${PROJECT_DIR}/deploy/.env" \
  --exclude="${PROJECT_DIR}/**/*.local" \
  --exclude="${PROJECT_DIR}/run-backend.local.bat" \
  --exclude="${PROJECT_DIR}/run-frontend.local.bat" \
  --exclude="${PROJECT_DIR}/**/application-local.yml" \
  --exclude="${PROJECT_DIR}/**/application-local.yaml" \
  --exclude="${PROJECT_DIR}/**/*.tsbuildinfo" \
  -czf "$ARCHIVE" "$PROJECT_DIR"

# ---------- 结果 ----------
SIZE="$(du -h "$ARCHIVE" | cut -f1)"
COUNT="$(tar -tzf "$ARCHIVE" | wc -l)"
echo
echo "[OK] 打包完成"
echo "     产物: $ARCHIVE"
echo "     大小: $SIZE"
echo "     文件: $COUNT 个"
echo
echo "下一步（服务器本地部署）:"
echo "  tar -xzf $ARCHIVE -C /opt"
echo "  cd /opt/$PROJECT_DIR/deploy && docker compose up -d --build"
echo
echo "上传到另一台服务器:"
echo "  scp $ARCHIVE root@<服务器IP>:/opt/"