#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Typecho -> astral CMS 内容迁移（ETL）

输入：data-migration/typecho-dump.json（由 dump_typecho.py 从源站 MySQL 导出）
输出：直接写入目标 PostgreSQL 的 cms_post / cms_page / cms_category / cms_post_category / cms_setting

迁移口径（已与需求确认）：
  - 只迁已发布内容：24 篇文章 + 3 个页面
  - 不迁评论、不迁用户（源站评论与用户信息全部丢弃）
  - 主键 id 一律保留源 cid（公开接口「纯数字按 ID 解析」，源 slug 多为数字串，保留后可原样访问）
  - 分类树完整迁移；文章支持多分类，源站的多个分类全部写入 cms_post_category，
    源 order 最小的分类同时写入 cms_post.category_id 作为主分类
  - 源站无 tag 数据，因此 cms_tag / cms_post_tag 不写入
  - 站点设置：site_title / site_description 取自 typecho_options

正文清洗：
  - 去掉 Typecho Markdown 标记 <!--markdown-->
  - CRLF -> LF
  - 修掉 CDN 双斜杠 canace.cn//imagehost -> canace.cn/imagehost（不换域名，保留用户自有 CDN）
  - 「关于」页面的 Joe 主题私有标签 <joe-message> 转为 Markdown 引用块，废弃的注释块移除
"""

import io
import json
import os
import re
import sys
from datetime import datetime

import psycopg2

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

DUMP_PATH = 'typecho-dump.json'

# 连接参数从环境变量读取（不写进仓库，避免生产库口令进入公开仓库）：
#   PGHOST / PGPORT / PGDATABASE / PGUSER / PGPASSWORD
# 未设置时回退到本地默认值 localhost:5432/astral。
PG = dict(host=os.environ.get('PGHOST', 'localhost'),
          port=int(os.environ.get('PGPORT', '5432')),
          dbname=os.environ.get('PGDATABASE', 'astral'),
          user=os.environ.get('PGUSER', 'astral'),
          password=os.environ.get('PGPASSWORD', ''),
          options='-c search_path=astral')

# 源站所有内容的 authorId 均为 1，目标库 sys_user id=1（canace/轻听）已存在
AUTHOR_ID = 1

# 源站分类 parent=0 表示根节点；目标库用 NULL 表示根
ROOT_PARENT = 0


# ============================================================
# 正文清洗
# ============================================================

def clean_body(text: str) -> str:
    """通用正文清洗"""
    if not text:
        return ''
    s = text
    # Typecho 的 Markdown 开关标记（每条正文都以它开头）
    if s.startswith('<!--markdown-->'):
        s = s[len('<!--markdown-->'):]
    s = s.replace('\r\n', '\n').replace('\r', '\n')
    # CDN 双斜杠规范化（只修路径分隔，不改域名）
    s = s.replace('canace.cn//imagehost', 'canace.cn/imagehost')
    # 去掉行尾多余空白与首尾空行
    s = '\n'.join(line.rstrip() for line in s.split('\n'))
    return s.strip() + '\n'


def clean_about_page(text: str) -> str:
    """
    「关于」页面（cid=2）是 Joe 主题的私有 HTML，改为等价 Markdown。
    原内容：基本信息 joe-message 提示块 + 一段被 HTML 注释掉的「兴趣爱好」旧内容。
    """
    # 提取 joe-message 里的 content 属性文本
    msg = re.search(r'<joe-message[^>]*content="([^"]*)"', text)
    body_lines = []
    if msg:
        raw = msg.group(1)
        # <br> 转 Markdown 硬换行
        raw = re.sub(r'<br\s*/?>', '  \n', raw)
        for line in raw.split('\n'):
            line = line.strip()
            if not line:
                continue
            body_lines.append('> ' + line)

    out = ['#### 基本信息', '']
    out.extend(body_lines)
    out.append('')
    # 原被注释掉的「兴趣爱好」块已废弃，不迁移
    return clean_body('\n'.join(out))


# ============================================================
# 摘要 / 封面提取
# ============================================================

def extract_cover(content: str):
    """取正文中第一张图片作为封面"""
    if not content:
        return None
    m = re.search(r'!\[[^\]]*\]\(([^)\s]+)', content)
    if not m:
        return None
    url = m.group(1).strip()
    return url if url.startswith('http') else None


def build_summary(content: str, limit: int = 140):
    """
    从正文提取纯文本摘要：
    去掉代码块、图片、HTML 标签、Markdown 语法符号，压缩空白后截断。
    """
    if not content:
        return None
    s = content
    # 围栏代码块（含语言标记）
    s = re.sub(r'```[\s\S]*?```', ' ', s)
    # 行内代码
    s = re.sub(r'`[^`]*`', ' ', s)
    # 图片
    s = re.sub(r'!\[[^\]]*\]\([^)]*\)', ' ', s)
    # 链接保留文字
    s = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', s)
    # HTML 标签
    s = re.sub(r'<[^>]+>', ' ', s)
    # 标题/引用/列表/强调标记
    s = re.sub(r'^\s{0,3}(#{1,6}|>|[-*+]|\d+\.)\s+', '', s, flags=re.MULTILINE)
    s = re.sub(r'[*_~]{1,3}', '', s)
    # 表格分隔行
    s = re.sub(r'^\s*\|?[\s:|-]{4,}\|?\s*$', ' ', s, flags=re.MULTILINE)
    s = re.sub(r'\|', ' ', s)
    # 压缩空白
    s = re.sub(r'\s+', ' ', s).strip()
    if not s:
        return None
    if len(s) <= limit:
        return s
    cut = s[:limit]
    # 尽量在标点处断开
    for mark in ('。', '！', '？', '；', '. ', '! ', '? ', ' '):
        idx = cut.rfind(mark)
        if idx >= limit * 0.6:
            return cut[:idx].strip() + '…'
    return cut.strip() + '…'


# ============================================================
# 主流程
# ============================================================

def main():
    with open(DUMP_PATH, encoding='utf-8') as f:
        dump = json.load(f)

    contents = dump['contents']
    categories = dump['categories']
    options = {o['name']: o['value'] for o in dump.get('options', [])}

    posts = [c for c in contents if c['type'] == 'post']
    pages = [c for c in contents if c['type'] == 'page']

    # ---------- 分类：mid 直接作为新表 id（远小于号段起点 1000000，不会与运行时发号冲突） ----------
    cat_rows = []
    for cat in sorted(categories, key=lambda x: (x['ord'], x['mid'])):
        parent = cat['parent']
        cat_rows.append(dict(
            id=cat['mid'],
            name=cat['name'],
            slug=(cat['slug'] or None),
            parent_id=None if parent == ROOT_PARENT else parent,
            sort=cat['ord'] or 0,
            description=(cat['description'] or None),
        ))

    # ---------- 文章主分类：同一篇文章多个分类时取源 order 最小的一个 ----------
    cat_by_mid = {c['mid']: c for c in categories}
    rels = {}
    for r in dump['relationships']:
        if r['mtype'] != 'category':
            continue
        rels.setdefault(r['cid'], []).append(r['mid'])
    primary_category = {}
    all_categories = {}
    dropped = []
    for cid, mids in rels.items():
        ordered = sorted(mids, key=lambda mid: (cat_by_mid[mid]['ord'], mid))
        # 目标 cms_category.id 与源 mid 一一对应
        all_categories[cid] = ordered
        primary_category[cid] = ordered[0]
        if len(ordered) > 1:
            dropped.append((cid, [cat_by_mid[m]['name'] for m in ordered]))

    # ---------- 文章 ----------
    post_rows = []
    for c in posts:
        content = clean_body(c['text'])
        published = datetime.fromtimestamp(c['created'])
        updated = datetime.fromtimestamp(c['modified'])
        post_rows.append(dict(
            id=c['cid'],
            title=c['title'].strip(),
            slug=(c['slug'] or None),
            summary=build_summary(content),
            content=content,
            cover=extract_cover(content),
            category_id=primary_category.get(c['cid']),
            status='published',
            is_top=False,
            views=int(c['views'] or 0),
            author_id=AUTHOR_ID,
            published_at=published,
            create_time=published,
            update_time=updated,
        ))

    # ---------- 页面（Wiki） ----------
    page_rows = []
    for c in pages:
        content = clean_about_page(c['text']) if c['cid'] == 2 else clean_body(c['text'])
        published = datetime.fromtimestamp(c['created'])
        updated = datetime.fromtimestamp(c['modified'])
        page_rows.append(dict(
            id=c['cid'],
            title=c['title'].strip(),
            slug=(c['slug'] or None),
            content=content,
            parent_id=None,
            sort=c['ord'] or 0,
            status='published',
            views=int(c['views'] or 0),
            author_id=AUTHOR_ID,
            published_at=published,
            create_time=published,
            update_time=updated,
        ))

    # ---------- 站点设置 ----------
    setting_rows = [
        ('site_title', options.get('title', ''), '站点名称（博客标题）'),
        ('site_description', options.get('description', ''), '站点描述（SEO 用）'),
        # 源站 typecho_options 中无页脚配置项，留空由用户在后台填写
        ('footer_text', '', '页脚文字'),
        ('comment_enabled', 'true', '是否开启评论（true/false）'),
        ('comment_need_review', 'true', '评论是否需要审核后展示（true/false）'),
    ]

    # ---------- 写库 ----------
    conn = psycopg2.connect(**PG)
    conn.autocommit = False
    try:
        cur = conn.cursor()

        # 幂等：先清空内容（目标库当前为空，重复执行也安全）
        for table in ('cms_post_category', 'cms_post_tag', 'cms_post', 'cms_page',
                      'cms_category', 'cms_setting'):
            cur.execute('DELETE FROM astral.' + table)

        for row in cat_rows:
            cur.execute(
                '''INSERT INTO astral.cms_category
                   (id, name, slug, parent_id, sort, description, create_time, update_time)
                   VALUES (%(id)s, %(name)s, %(slug)s, %(parent_id)s, %(sort)s,
                           %(description)s, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)''', row)

        for row in post_rows:
            cur.execute(
                '''INSERT INTO astral.cms_post
                   (id, title, slug, summary, content, cover, category_id, status, is_top,
                    views, author_id, published_at, create_time, update_time, delete_time)
                   VALUES (%(id)s, %(title)s, %(slug)s, %(summary)s, %(content)s, %(cover)s,
                           %(category_id)s, %(status)s, %(is_top)s, %(views)s, %(author_id)s,
                           %(published_at)s, %(create_time)s, %(update_time)s, NULL)''', row)

        for row in page_rows:
            cur.execute(
                '''INSERT INTO astral.cms_page
                   (id, title, slug, content, parent_id, sort, status, views, author_id,
                    published_at, create_time, update_time, delete_time)
                   VALUES (%(id)s, %(title)s, %(slug)s, %(content)s, %(parent_id)s, %(sort)s,
                           %(status)s, %(views)s, %(author_id)s, %(published_at)s,
                           %(create_time)s, %(update_time)s, NULL)''', row)

        # 多分类关联：一篇文章可属于多个分类（写库顺序即展示顺序，主分类在最前）
        link_seq = 0
        for cid, mids in sorted(all_categories.items()):
            if cid not in {r['id'] for r in post_rows}:
                continue
            for mid in mids:
                link_seq += 1
                cur.execute(
                    '''INSERT INTO astral.cms_post_category
                       (id, post_id, category_id, create_time)
                       VALUES (%s, %s, %s, CURRENT_TIMESTAMP)''',
                    (link_seq, cid, mid))

        # 设置：cms_setting.id 由号段分配（这里按现有最大号段取安全起点，避免与运行时冲突）
        cur.execute('SELECT COALESCE(MAX(id), 0) FROM astral.cms_setting')
        base = cur.fetchone()[0]
        for i, (key, value, remark) in enumerate(setting_rows, start=1):
            cur.execute(
                '''INSERT INTO astral.cms_setting
                   (id, setting_key, setting_value, remark, create_time, update_time)
                   VALUES (%s, %s, %s, %s, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                   ON CONFLICT (setting_key) DO UPDATE
                     SET setting_value = EXCLUDED.setting_value,
                         update_time = CURRENT_TIMESTAMP''',
                (base + i, key, value, remark))

        conn.commit()

        # ---------- 报告 ----------
        print('=' * 64)
        print('迁移完成')
        print('=' * 64)
        print(f'分类     : {len(cat_rows)} 条')
        print(f'文章     : {len(post_rows)} 条')
        print(f'页面     : {len(page_rows)} 条')
        print(f'设置     : {len(setting_rows)} 项')
        print(f'标签     : 0 条（源站无 tag 数据）')
        print(f'评论     : 0 条（按需求不迁移）')
        print(f'多分类关联: {link_seq} 行（cms_post_category）')
        print()
        if dropped:
            print('多分类文章（源站的多个分类全部保留，第一个为主分类）：')
            for cid, names in sorted(dropped):
                print(f'  cid {cid}: 主分类 {names[0]}，同时属于 {", ".join(names[1:])}')
            print()
        no_cat = [r['id'] for r in post_rows if r['category_id'] is None]
        print(f'无分类文章 cid: {no_cat}')
        with_cover = [r['id'] for r in post_rows if r['cover']]
        print(f'有封面文章 cid: {with_cover}')
        print(f'无摘要文章 cid: {[r["id"] for r in post_rows if not r["summary"]]}')
        print()
        html_left = []
        for r in post_rows + page_rows:
            if re.search(r'<joe-', r['content']):
                html_left.append(('joe', r['id']))
            if '//imagehost' in r['content']:
                html_left.append(('dblslash', r['id']))
            if r['content'].lstrip().startswith('<!--markdown-->'):
                html_left.append(('marker', r['id']))
        print(f'清洗残留检查: {html_left if html_left else "无"}')
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


if __name__ == '__main__':
    main()
