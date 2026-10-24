"""Export Typecho data (www_canace_cn, MySQL 5.6) into a local JSON dump.

Talks to the DBX MCP stdio server directly (payload never routed through an
agent context). MySQL 5.6 has no JSON functions / CTEs, so every value is
returned as TO_BASE64(...) (safe inside the markdown table) and long bodies are
paged with SUBSTRING + an inline numbers table.
"""

from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
DBX = Path(os.path.expanduser("~")) / ".dbx" / "bin" / "dbx-mcp.exe"

MYSQL_CONN = "fdad6082-a4c4-45c4-934e-231edb2a392e"
MYSQL_DB = "www_canace_cn"

CHUNK = 500     # source chars per base64 chunk (<=2000 b64 chars)
PAGE = 20000    # source chars per MCP call (40 chunks)
NUMS = 40

LOG = open(HERE / "dump.log", "w", encoding="utf-8")


def log(*a):
    msg = " ".join(str(x) for x in a)
    print(msg)
    LOG.write(msg + "\n")
    LOG.flush()


def b64x(expr: str) -> str:
    """TO_BASE64 with newlines stripped (the MCP result renders as a
    markdown table, and raw base64 newlines would break row parsing)."""
    return f"CAST(REPLACE(TO_BASE64({expr}), CHAR(10), '') AS CHAR)"


def b64(s: str | None) -> str:
    if s is None or s == "NULL":
        return ""
    return base64.b64decode(s).decode("utf-8", "replace")


class Mcp:
    def __init__(self, exe: Path):
        self.p = subprocess.Popen(
            [str(exe)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        self._id = 0
        self._init()

    def _send(self, obj):
        self.p.stdin.write(json.dumps(obj, ensure_ascii=False) + "\n")
        self.p.stdin.flush()

    def _read(self):
        while True:
            line = self.p.stdout.readline()
            if not line:
                raise RuntimeError("dbx-mcp closed stdout")
            line = line.strip()
            if not line:
                continue
            try:
                return json.loads(line)
            except json.JSONDecodeError:
                continue

    def request(self, method, params, timeout=300):
        self._id += 1
        rid = self._id
        self._send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        deadline = time.time() + timeout
        while time.time() < deadline:
            msg = self._read()
            if msg.get("id") == rid:
                if "error" in msg:
                    raise RuntimeError(f"MCP error: {msg['error']}")
                return msg["result"]
        raise TimeoutError(f"{method} timed out")

    def _init(self):
        res = self.request(
            "initialize",
            {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "typecho-etl", "version": "1.0"},
            },
        )
        self._send({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})
        log("connected:", res.get("serverInfo"))

    def query(self, sql: str, max_rows: int = 100) -> list[list[str]]:
        res = self.request(
            "tools/call",
            {
                "name": "dbx_execute_query",
                "arguments": {
                    "connection_id": MYSQL_CONN,
                    "database": MYSQL_DB,
                    "sql": sql,
                    "max_rows": max_rows,
                    "cell_char_limit": 4000,
                },
            },
        )
        text = "\n".join(c.get("text", "") for c in res.get("content", []))
        if res.get("isError"):
            raise RuntimeError(f"query failed: {text[:600]}")
        return parse_table(text)

    def close(self):
        try:
            self.p.stdin.close()
            self.p.terminate()
        except Exception:
            pass


def parse_table(text: str) -> list[list[str]]:
    rows: list[list[str]] = []
    header_seen = False
    for line in text.splitlines():
        s = line.strip()
        if not s.startswith("|"):
            continue
        cells = [c.strip() for c in s.strip("|").split("|")]
        if cells and all(set(c) <= set("-: ") and c for c in cells):
            continue  # separator
        if not header_seen:
            header_seen = True
            continue  # header
        rows.append(cells)
    return rows


def nums_table(n: int = NUMS) -> str:
    return " UNION ALL ".join(f"SELECT {i} AS n" for i in range(n))


def fetch_bodies(mcp: Mcp, cid: int, length: int) -> str:
    parts: list[str] = []
    off = 0
    while off < length:
        sql = (
            "SELECT s.n AS seq, CAST(REPLACE(TO_BASE64(SUBSTRING(text, {off} + s.n * {chunk} + 1, {chunk})), CHAR(10), '') AS CHAR) AS chunk "
            "FROM typecho_contents CROSS JOIN ({nums}) s "
            "WHERE cid = {cid} AND {off} + s.n * {chunk} < CHAR_LENGTH(text) ORDER BY s.n"
        ).format(off=off, chunk=CHUNK, nums=nums_table(), cid=cid)
        rows = mcp.query(sql, max_rows=NUMS)
        for r in rows:
            if len(r) >= 2 and r[1] and r[1] != "NULL":
                parts.append(b64(r[1]))
        off += PAGE
    return "".join(parts)


def main():
    mcp = Mcp(DBX)
    dump: dict[str, list[dict]] = {}
    try:
        meta_rows = mcp.query(
            "SELECT cid, CAST(REPLACE(TO_BASE64(title), CHAR(10), '') AS CHAR) AS t, CAST(REPLACE(TO_BASE64(IFNULL(slug, '')), CHAR(10), '') AS CHAR) AS s, "
            "created, modified, type, views, IFNULL(authorId, 0) AS authorId, "
            "IFNULL(`order`, 0) AS ord, IFNULL(parent, 0) AS parent, "
            "CHAR_LENGTH(text) AS len "
            "FROM typecho_contents WHERE status = 'publish' AND type IN ('post', 'page') ORDER BY cid",
            max_rows=100,
        )
        log(f"contents meta rows: {len(meta_rows)}")
        contents = []
        for r in meta_rows:
            cid = int(r[0])
            length = int(r[10] or 0)
            body = fetch_bodies(mcp, cid, length)
            contents.append(
                {
                    "cid": cid,
                    "title": b64(r[1]),
                    "slug": b64(r[2]),
                    "created": int(r[3] or 0),
                    "modified": int(r[4] or 0),
                    "type": r[5],
                    "views": int(r[6] or 0),
                    "authorId": int(r[7] or 0),
                    "ord": int(r[8] or 0),
                    "parent": int(r[9] or 0),
                    "text": body,
                }
            )
            log(f"  cid={cid} len={length} got={len(body)}")
        dump["contents"] = contents

        for label, sql in {
            "categories": (
                "SELECT mid, CAST(REPLACE(TO_BASE64(name), CHAR(10), '') AS CHAR) AS n, CAST(REPLACE(TO_BASE64(IFNULL(slug, '')), CHAR(10), '') AS CHAR) AS s, "
                "IFNULL(parent, 0) AS parent, IFNULL(`order`, 0) AS ord, "
                "CAST(REPLACE(TO_BASE64(IFNULL(description, '')), CHAR(10), '') AS CHAR) AS d FROM typecho_metas WHERE type = 'category' ORDER BY mid"
            ),
            "tags": (
                "SELECT mid, CAST(REPLACE(TO_BASE64(name), CHAR(10), '') AS CHAR) AS n, CAST(REPLACE(TO_BASE64(IFNULL(slug, '')), CHAR(10), '') AS CHAR) AS s, "
                "IFNULL(parent, 0) AS parent, IFNULL(`order`, 0) AS ord, "
                "CAST(REPLACE(TO_BASE64(IFNULL(description, '')), CHAR(10), '') AS CHAR) AS d FROM typecho_metas WHERE type = 'tag' ORDER BY mid"
            ),
        }.items():
            rows = mcp.query(sql, max_rows=100)
            items = []
            for r in rows:
                items.append(
                    {
                        "mid": int(r[0]),
                        "name": b64(r[1]),
                        "slug": b64(r[2]),
                        "parent": int(r[3] or 0),
                        "ord": int(r[4] or 0),
                        "description": b64(r[5]),
                    }
                )
            dump[label] = items
            log(f"{label}: {len(items)}")

        rel_rows = mcp.query(
            "SELECT r.cid, r.mid, m.type FROM typecho_relationships r "
            "JOIN typecho_contents c ON c.cid = r.cid JOIN typecho_metas m ON m.mid = r.mid "
            "WHERE c.status = 'publish' AND c.type IN ('post', 'page') "
            "AND m.type IN ('category', 'tag') ORDER BY r.cid, r.mid",
            max_rows=200,
        )
        dump["relationships"] = [
            {"cid": int(r[0]), "mid": int(r[1]), "mtype": r[2]} for r in rel_rows
        ]
        log(f"relationships: {len(dump['relationships'])}")

        opt_rows = mcp.query(
            "SELECT name, CAST(REPLACE(TO_BASE64(IFNULL(value, '')), CHAR(10), '') AS CHAR) AS v FROM typecho_options "
            "WHERE name IN ('title', 'description', 'keywords', 'footer', 'siteUrl') ORDER BY name",
            max_rows=20,
        )
        dump["options"] = [{"name": r[0], "value": b64(r[1])} for r in opt_rows]
        log(f"options: {len(dump['options'])}")
    finally:
        mcp.close()

    out = HERE / "typecho-dump.json"
    out.write_text(json.dumps(dump, ensure_ascii=False, indent=1), encoding="utf-8")
    log("written:", out, out.stat().st_size, "bytes")
    log(
        "summary:",
        {k: len(v) for k, v in dump.items()},
        "types:",
        sorted({c["type"] for c in dump["contents"]}),
    )


if __name__ == "__main__":
    sys.exit(main())
