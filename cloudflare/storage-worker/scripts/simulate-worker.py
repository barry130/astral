# Worker → Astral 存储契约本地模拟脚本
#
# 在不部署 Cloudflare Worker 的情况下，模拟 Worker 的两条 HMAC 链路验证后端契约：
#   1. 上传回调：伪造一个 Redis 上传凭证上下文（模拟 Astral 签发），按 Worker 规范
#      调 POST /api/v1/all/storage/worker/upload-callback，期望登记成功并返回 publicId；
#   2. 下载回源：GET /api/v1/all/storage/origin/files/{publicId}/{contentVersion}，
#      期望返回 telegramFileId；并验证 Nonce 重放被拒绝（STORAGE017）。
#
# 前置条件（需先手工准备，见同目录 DEPLOYMENT.md）：
#   - V4__storage_plugin.sql 已应用；
#   - sys_storage_config / sys_storage_folder 已有测试数据（可通过管理端 API 或 SQL 插入）；
#   - 后端环境变量已配置三个 HMAC 密钥（与 --ticket-key/--origin-secret 一致）。
#
# 用法：
#   python scripts/simulate-worker.py --base http://localhost:27000 \
#       --redis-host <host> --redis-port 6379 --redis-password <pw> --redis-db 7 \
#       --config-id <sys_storage_config.id> --folder-id <sys_storage_folder.id> \
#       --chat-id <telegram-chat-id> \
#       --ticket-key <STORAGE_UPLOAD_TICKET_KEY> --origin-secret <STORAGE_ORIGIN_SHARED_SECRET>
#
# 注意：命令行参数会进入 shell 历史，仅在本地开发使用；脚本不含任何硬编码凭据。

import argparse
import hashlib
import hmac
import json
import socket
import time
import urllib.error
import urllib.request
import uuid


def b64url(data: bytes) -> str:
    import base64
    return base64.urlsafe_b64encode(data).decode().rstrip('=')


def redis_command(host, port, password, db, *args):
    with socket.create_connection((host, port), timeout=10) as sock:
        def send(*cmd):
            sock.sendall(encode_command(*cmd))
            return sock.recv(65536)

        if password:
            send('AUTH', password)
        send('SELECT', str(db))
        return send(*args)


def encode_command(*args):
    out = b'*%d\r\n' % len(args)
    for a in args:
        a = a.encode() if isinstance(a, str) else a
        out += b'$%d\r\n%s\r\n' % (len(a), a)
    return out


def sign_origin(secret: str, method: str, path: str) -> dict:
    ts = str(int(time.time()))
    nonce = uuid.uuid4().hex
    canonical = f"{method}\n{path}\n{ts}\n{nonce}"
    sig = b64url(hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).digest())
    return {
        'X-Storage-Origin-Timestamp': ts,
        'X-Storage-Origin-Nonce': nonce,
        'X-Storage-Origin-Signature': sig,
    }


def http(method: str, url: str, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or '{}')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', required=True)
    parser.add_argument('--redis-host', required=True)
    parser.add_argument('--redis-port', type=int, default=6379)
    parser.add_argument('--redis-password', default='')
    parser.add_argument('--redis-db', type=int, default=7)
    parser.add_argument('--config-id', required=True, type=int)
    parser.add_argument('--folder-id', required=True, type=int)
    parser.add_argument('--chat-id', required=True)
    parser.add_argument('--ticket-key', required=True)
    parser.add_argument('--origin-secret', required=True)
    args = parser.parse_args()

    base = args.base.rstrip('/')

    # 1. 模拟 Astral 签发的凭证上下文写入 Redis（与 UploadTicketService 负载结构一致）
    upload_id = 'up_' + uuid.uuid4().hex
    payload = json.dumps({
        'v': 1, 'uploadId': upload_id, 'configId': args.config_id, 'folderId': args.folder_id,
        'chatId': args.chat_id, 'maxSize': 20971520, 'mime': 'image/png', 'uploaderId': '1',
        'iat': int(time.time()), 'exp': int(time.time()) + 600, 'nonce': uuid.uuid4().hex,
    }, separators=(',', ':'))
    reply = redis_command(args.redis_host, args.redis_port, args.redis_password, args.redis_db,
                          'SET', f'storage:ticket:{upload_id}', payload, 'EX', '1200')
    assert b'+OK' in reply or b'OK' in reply, f'Redis SET 失败: {reply!r}'
    print(f'[1] Redis 凭证上下文已写入: {upload_id}')

    # 2. 模拟 Worker 上传回调（HMAC 服务身份）
    path = '/api/v1/all/storage/worker/upload-callback'
    body = {
        'uploadId': upload_id, 'messageId': '999', 'telegramFileId': 'sim-file-id',
        'fileUniqueId': 'sim-unique', 'sizeBytes': 12345,
        'contentType': 'image/png', 'fileName': 'sim.png',
    }
    status, resp = http('POST', base + path, body, sign_origin(args.origin_secret, 'POST', path))
    assert status == 200 and resp.get('code') == 200, f'回调失败: {status} {resp}'
    public_id = resp['data']['publicId']
    print(f'[2] 上传回调成功: publicId={public_id}')

    # 3. 幂等验证：同一 uploadId 再次回调返回相同 publicId
    headers = sign_origin(args.origin_secret, 'POST', path)
    status, resp2 = http('POST', base + path, body, headers)
    assert status == 200 and resp2['data']['publicId'] == public_id, f'幂等失败: {resp2}'
    print('[3] 回调幂等验证通过')

    # 4. 模拟下载回源（新 Nonce）
    origin_path = f'/api/v1/all/storage/origin/files/{public_id}/1'
    status, resp = http('GET', base + origin_path, None, sign_origin(args.origin_secret, 'GET', origin_path))
    assert status == 200 and resp.get('code') == 200, f'回源失败: {status} {resp}'
    assert resp['data']['telegramFileId'] == 'sim-file-id', f'locator 不匹配: {resp}'
    print(f"[4] 下载回源成功: telegramFileId={resp['data']['telegramFileId']}")

    # 5. Nonce 重放应被拒绝（STORAGE017）
    replay = sign_origin(args.origin_secret, 'GET', origin_path)
    status, resp = http('GET', base + origin_path, None, replay)
    status2, resp2 = http('GET', base + origin_path, None, replay)
    assert resp2.get('errorCode') == 'STORAGE017', f'重放未被拒绝: {resp2}'
    print('[5] Nonce 重放防护验证通过（STORAGE017）')

    # 6. 签名错误应被拒绝
    bad = sign_origin(args.origin_secret, 'GET', origin_path)
    bad['X-Storage-Origin-Signature'] = 'AAAA' + bad['X-Storage-Origin-Signature'][4:]
    status, resp = http('GET', base + origin_path, None, bad)
    assert resp.get('errorCode') == 'STORAGE017', f'错误签名未被拒绝: {resp}'
    print('[6] 错误签名防护验证通过（STORAGE017）')

    print('\n全部模拟通过：Worker → Astral 上传回调与下载回源契约正常。')


if __name__ == '__main__':
    main()
