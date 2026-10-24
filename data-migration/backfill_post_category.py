"""给已有文章补齐多分类关联行（cms_post_category）。

背景：首轮迁移时 CMS 只支持单分类，cid 45/49/53/55 的第二个及之后的分类被丢弃。
本脚本按源站 typecho_relationships 的原始分类挂载关系重建关联表，并把
cms_post.category_id 校准为主分类（源 order 最小者）。

用法：python backfill_post_category.py [--dry-run]

连接参数从环境变量读取（不写进仓库，避免生产库口令进入公开仓库）：
  PGHOST / PGPORT / PGDATABASE / PGUSER / PGPASSWORD
未设置时回退到本地默认值 localhost:5432/astral。
"""
import io
import json
import os
import sys
from pathlib import Path

import psycopg2

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent
DUMP = ROOT / "typecho-dump.json"

PG = dict(
    host=os.environ.get("PGHOST", "localhost"),
    port=int(os.environ.get("PGPORT", "5432")),
    dbname=os.environ.get("PGDATABASE", "astral"),
    user=os.environ.get("PGUSER", "astral"),
    password=os.environ.get("PGPASSWORD", ""),
    options="-c search_path=astral",
)


def load_links():
    """从 dump 里还原 {cid: [mid, ...]}，按源 order 升序（越小越靠前）"""
    data = json.loads(DUMP.read_text(encoding="utf-8"))
    mids = {m["mid"]: m for m in data["categories"]}
    by_cid = {}
    for rel in data["relationships"]:
        if rel.get("mtype") != "category":
            continue
        by_cid.setdefault(rel["cid"], []).append(rel["mid"])
    for cid, mid_list in by_cid.items():
        mid_list.sort(key=lambda mid: (mids.get(mid, {}).get("ord", 999), mid))
    return by_cid


def main():
    dry_run = "--dry-run" in sys.argv
    by_cid = load_links()
    conn = psycopg2.connect(**PG)
    conn.autocommit = False
    try:
        with conn.cursor() as cur:
            cur.execute('SELECT id FROM cms_post WHERE delete_time IS NULL')
            exist_ids = {r[0] for r in cur.fetchall()}

            plan = []
            for cid in sorted(by_cid):
                if cid not in exist_ids:
                    continue
                mid_list = by_cid[cid]
                if len(mid_list) < 2:
                    continue
                # 源站 mid 与目标 cms_category.id 一一对应（ETL 保留了源 mid）
                cur.execute('SELECT id FROM cms_category WHERE id = ANY(%s)', (mid_list,))
                valid = {r[0] for r in cur.fetchall()}
                ordered = [m for m in mid_list if m in valid]
                if ordered:
                    plan.append((cid, ordered))

            print(f"需要补关联的文章：{len(plan)} 篇")
            for cid, ordered in plan:
                cur.execute('SELECT id, name FROM cms_category WHERE id = ANY(%s)', (ordered,))
                names = {r[0]: r[1] for r in cur.fetchall()}
                print(f"  cid={cid} → " + " + ".join(f"{m}:{names.get(m, '?')}" for m in ordered))

            if dry_run:
                print("\n--dry-run：未写库")
                conn.rollback()
                return

            # 清空后按源顺序重建（order 最小者在最前，即主分类）
            cur.execute('DELETE FROM cms_post_category')
            for cid, ordered in plan:
                for mid in ordered:
                    cur.execute(
                        'INSERT INTO cms_post_category (id, post_id, category_id, create_time) '
                        'VALUES ((SELECT COALESCE(MAX(id), 0) FROM cms_post_category) + 1, %s, %s, NOW())',
                        (cid, mid),
                    )
                # 主分类 = 第一个
                cur.execute('UPDATE cms_post SET category_id = %s WHERE id = %s', (ordered[0], cid))

            cur.execute('SELECT COUNT(*) FROM cms_post_category')
            total = cur.fetchone()[0]
            print(f"\n已写入 cms_post_category {total} 行，并校准 {len(plan)} 篇文章的主分类")
            conn.commit()
            print("提交完成")
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


if __name__ == "__main__":
    main()
