#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
command -v docker >/dev/null || { echo 'Install Docker Engine and Compose v2 first.' >&2; exit 1; }
docker compose version >/dev/null
if [[ ! -f .env ]]; then
  umask 077
  cp .env.example .env
  echo 'Created .env. Fill in the Bot Token, Astral URL and three shared HMAC keys, then rerun.'
  exit 1
fi
chmod 600 .env
if grep -Eq 'REPLACE_ME|astral\.example\.com' .env; then
  echo 'Replace all placeholders in .env before deploying.' >&2
  exit 1
fi
# Cloudflare Tunnel 连接器的启用条件（compose 的两个 profile 互斥）：
#   TUNNEL_TOKEN 非空        → --profile tunnel       （远程管理隧道，README 方案 C2）
#   cloudflared/config.yml   → --profile tunnel-local （本地管理隧道，README 方案 C3）
# 两者都没有就只起网关（用 Cloudflare Origin Rules 或 Nginx，README 方案 C1/A/B）。
# 空数组在 set -u 下的展开要加 ${arr[@]+...} 守卫，否则老版本 bash 会报 unbound variable。
TUNNEL_ARGS=()
TUNNEL_SVC=''
WITH_TUNNEL=0
if grep -Eq '^[[:space:]]*TUNNEL_TOKEN=[^[:space:]]' .env; then
  TUNNEL_ARGS=(--profile tunnel)
  TUNNEL_SVC='cloudflared'
  WITH_TUNNEL=1
  echo 'TUNNEL_TOKEN detected: starting the remotely-managed Cloudflare Tunnel connector too.'
elif [[ -f cloudflared/config.yml ]]; then
  if ! compgen -G 'cloudflared/*.json' >/dev/null; then
    echo 'cloudflared/config.yml found but no tunnel credentials JSON (<UUID>.json) next to it.' >&2
    echo 'Run `cloudflared tunnel create` and put its credentials file into ./cloudflared/ (README C3).' >&2
    exit 1
  fi
  TUNNEL_ARGS=(--profile tunnel-local)
  TUNNEL_SVC='cloudflared-local'
  WITH_TUNNEL=1
  echo 'cloudflared/config.yml detected: starting the locally-managed Cloudflare Tunnel connector too.'
else
  echo 'No tunnel config: starting the gateway only (use Cloudflare Origin Rules or Nginx, README section 2).'
fi
docker compose ${TUNNEL_ARGS[@]+"${TUNNEL_ARGS[@]}"} config --quiet
docker compose ${TUNNEL_ARGS[@]+"${TUNNEL_ARGS[@]}"} up -d --build
echo 'Gateway published on port 38787. Allow TCP 38787 in your firewall for direct IP access.'
echo 'NOTE: the HTTPS admin page cannot upload to a plain-HTTP gateway (browser Mixed Content).'
echo '      Put a domain + TLS in front of this port before using it from the admin UI; see README section 2.'
if [[ $WITH_TUNNEL -eq 1 ]]; then
  echo "Cloudflare Tunnel connector started. Check: docker compose --profile ${TUNNEL_ARGS[1]} logs --tail=50 ${TUNNEL_SVC}"
  echo 'Astral gateway URL (workerBaseUrl): https://YOUR_TUNNEL_HOSTNAME'
else
  echo 'Astral gateway URL (must be an HTTPS origin for browser uploads): https://YOUR_GATEWAY_DOMAIN'
fi
echo 'Check Telegram connectivity: curl --fail http://127.0.0.1:38787/healthz'
