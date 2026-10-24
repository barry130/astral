# Typecho → CMS 内容迁移

把 `www.canace.cn`（Typecho 站点）的文章与页面迁移到 astral 的 cms 插件（PostgreSQL）。
**只迁内容与页面**，不迁评论、不迁用户。

## 产物

| 文件 | 作用 |
| --- | --- |
| `dump_typecho.py` | 从源 MySQL（Typecho）导出内容为 JSON |
| `typecho-dump.json` | 导出的中间数据（约 397 KB，27 条内容） |
| `etl_to_cms.py` | 清洗 + 映射 + 灌入 astral 库的 `cms_*` 表（单事务、幂等） |
| `backfill_post_category.py` | 为已迁移过的库补齐 `cms_post_category` 多分类关联行并校准主分类（幂等） |
| `dump.log` | 导出过程日志 |

## 用法

```bash
cd astral/data-migration
python dump_typecho.py              # 源库导出 → typecho-dump.json
python etl_to_cms.py                # 清洗入库 → astral.cms_*
python backfill_post_category.py --dry-run   # 可选：预览多分类关联补写
python backfill_post_category.py             # 可选：补写多分类关联
```

两个脚本都可重复执行：`etl_to_cms.py` 会先 `DELETE` 目标表再写入（`cms_setting` 走 `ON CONFLICT (setting_key) DO UPDATE`）。

连接参数：

- 源：通过 DBX MCP（`~/.dbx/bin/dbx-mcp.exe`）读取「腾讯云Mysql」连接，脚本内不保存明文密码。
- 目标：`psycopg2` 直连 `astral` 库，`options='-c search_path=astral'`。**连接参数从环境变量读取，脚本内不写死主机与口令**：

```bash
export PGHOST=...        # 默认 localhost
export PGPORT=5432       # 默认 5432
export PGDATABASE=astral # 默认 astral
export PGUSER=astral     # 默认 astral
export PGPASSWORD=...    # 默认空，生产环境必填
```

## 迁移口径（重要）

### 迁移范围

- 只迁 `status='publish'` 的内容：**24 篇文章 + 3 个页面**（草稿、隐藏内容不迁）。
- 评论表 `typecho_comments` 与用户表 `typecho_users` 完全不读。

### 主键与 slug

- **保留源 `cid` 作为新表主键 `id`**。原因：公开侧 slug 解析规则是「纯数字按 ID，其余按 slug」，而源站文章 slug 大多就是 `cid` 的字符串形式（如 `slug='3'` ↔ `id=3`），保留 cid 后 `/post/3` 直接命中，无需改写任何 URL。
- 非数字 slug 原样保留：文章 `cid=1` → `start`；页面 `cid=2` → `start-page`、`18` → `qt`、`31` → `qtfaq-1`。
- **号段安全**：`cms_*` 七条号段（`sequence_segment`）的 `max_value` 均为 1000000，运行时首次发号从 1000001 起；显式插入的 id ≤ 68 不会与运行期发号冲突，因此**不需要手动抬高号段**。

### 分类

10 个分类全部迁移，`parent_id` 保留源层级（源 `parent=0` 映射为 `NULL`）：

```
Git / Java / SQL / 区块链 / 网站搭建技术 / GO / 实用软件   （根）
                    └── SpringBoot / thymeleaf / Vue      （挂在「网站搭建技术」下）
```

**多分类**：CMS 支持一篇文章属于多个分类（`cms_post_category` 关联表）。源站的多分类关系**全部保留**，其中源 `order` 最小的分类同时写入 `cms_post.category_id` 作为主分类：

| 文章 cid | 源分类（按 order） | 主分类 | 同时属于 |
| --- | --- | --- | --- |
| 45 | SpringBoot + Java | SpringBoot | Java |
| 49 | SpringBoot + Java + SQL | SpringBoot | Java、SQL |
| 53 | SpringBoot + Java | SpringBoot | Java |
| 55 | SpringBoot + Java | SpringBoot | Java |

关联行由 `etl_to_cms.py` 直接生成；对已迁移过的库可用 `backfill_post_category.py` 补齐（幂等，先清空关联表再按源数据重建，并校准主分类）。

`cid=1`、`cid=11` 源站无分类 → `category_id` 为 `NULL`。

### 标签

源站 `typecho_metas` 中**没有任何 `type='tag'` 记录**，因此 `cms_tag` / `cms_post_tag` 迁移后为空表。不把分类伪造成标签。

### 正文处理

| 处理 | 说明 |
| --- | --- |
| 剥离 `<!--markdown-->` | 27 条正文全部带该前缀（Joe 主题标记），已去除 |
| 图片双斜杠 | `canace.cn//imagehost/...` → `canace.cn/imagehost/...`（共 103 处，涉及 cid 43/44/49/68） |
| 图片域名 | `cdn.canace.cn` / `file.canace.cn` 为用户自有 CDN，**保留原样不改** |
| 图床防盗链 | 源图床按 Referer 白名单防盗链（非白名单域返回 403）。前台图片统一加 `referrerPolicy="no-referrer"`，靠「不带 Referer」放行；跨域图片显示异常时先查这一项 |
| 「关于」页面（cid=2） | Joe 主题私有标签 `<joe-message>` 转成 Markdown 引用块（`#### 基本信息` + `>`），被注释掉的旧内容不迁 |
| 换行 | CRLF → LF，逐行去尾随空格，首尾空行去除 |
| 代码块内 HTML | 不作处理（`<script>`、`<div>`、`<!--` 等均为代码示例文本） |

### 摘要与封面

- `summary`：正文自动生成，去代码块/图片/链接标记/HTML，压空白，上限 140 字，优先在句读处截断。24 篇全部有摘要。
- `cover`：取正文第一张图片 URL；有封面的文章：cid 36、39、43、44、49、55、68，其余为 `NULL`。

### 时间

- `published_at` / `create_time` ← 源 `created`（Unix 秒，本地时区）
- `update_time` ← 源 `modified`
- 迁移后时间跨度：2021-01-06 ~ 2025-11-16
- `views` 保留源站阅读数（合计 18908）

### 作者

源 `authorId=1`，目标库 `sys_user.id=1`（username=`canace`，昵称「轻听」）存在，`author_id` 直接用 1。

### 站点设置

写入 `cms_setting` 5 个键（`footer_text` 源站无对应项，留空）：

| 键 | 值 |
| --- | --- |
| site_title | 风之子博客-测试源码分享 |
| site_description | 个人学习分享记录博客 |
| footer_text | （空） |
| comment_enabled | true |
| comment_need_review | true |

源站的 `keywords` 未迁移（cms_setting 无对应键）；`blog_url` 由管理端「站点设置」自行填写。

## 未迁移项

- 评论（`typecho_comments`）— 用户明确不要
- 用户（`typecho_users`）— 用户明确不要
- 草稿/隐藏内容（7 篇草稿 + 2 个页面草稿）

## 迁移后核对

```sql
SELECT count(*) FROM astral.cms_post;      -- 24
SELECT count(*) FROM astral.cms_page;      -- 3
SELECT count(*) FROM astral.cms_category;  -- 10
SELECT count(*) FROM astral.cms_setting;   -- 5
```

博客前台（astral-blog :3001）与管理端（astral-front :3000 `/dashboard/cms`）均正常展示。
