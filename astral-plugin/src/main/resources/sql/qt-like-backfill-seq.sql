-- =====================================================================
-- 存量数据补号：qt_like_song / qt_like_playlist 的 updated_seq 为空的行
--
-- 背景：likeSeq 是「该条在服务器上的版本号」（LIKE_SYNC_DESIGN.md D2），
-- 增量拉取按 updated_seq > since 过滤，NULL 行永远不会进增量流；
-- 全量分页按 updated_seq DESC 排序，NULL 在 PG 默认排最前，挤占第一页。
-- 本脚本给所有 NULL 行补上「该用户维度单调递增」的 seq：
--   基线 = 该用户两表现有最大 seq（跨表取 GREATEST，与运行时取号器一致），
--   之后再按 uid 分组从基线+1 递增，保证：
--   1) 补的号一定大于该用户任何已发出的 seq，不会与 changes 游标冲突；
--   2) 补号后这些行会以 add 事件出现在 since 之后的增量流里，
--      客户端按「已存在且本地 seq 不小于事件 seq 则忽略」处理，幂等无害；
--   3) 服务端下次取号（selectUserMaxSeq + 1）在补号之后，不受影响。
--
-- 顺序要求：先跑「基线与影响面查询」，再按【步骤1→步骤2→步骤3】顺序执行
-- （步骤2的歌单表基线必须包含步骤1刚给歌曲表补出的号，防止两表出现重复 seq）。
-- 可重复执行（幂等）：补过的行不再满足 updated_seq IS NULL，重跑无操作。
-- =====================================================================

-- ---------------------------------------------------------------
-- 0. 影响面预览（先看再改）
-- ---------------------------------------------------------------
SELECT 'qt_like_song' AS tbl, uid, COUNT(*) AS null_seq_rows
FROM qt_like_song WHERE updated_seq IS NULL GROUP BY uid ORDER BY uid;

SELECT 'qt_like_playlist' AS tbl, uid, COUNT(*) AS null_seq_rows
FROM qt_like_playlist WHERE updated_seq IS NULL GROUP BY uid ORDER BY uid;

-- ---------------------------------------------------------------
-- 1. 歌曲表补号
--    基线取「补号前」两表该用户的 max：必须在本步骤内一次算好，
--    否则同一用户逐行 UPDATE 时后面的行会看到前面刚补的号。
-- ---------------------------------------------------------------
WITH base AS (
    SELECT s.uid,
           GREATEST(
               COALESCE((SELECT MAX(x.updated_seq) FROM qt_like_song  x WHERE x.uid = s.uid), 0),
               COALESCE((SELECT MAX(x.updated_seq) FROM qt_like_playlist x WHERE x.uid = s.uid), 0)
           ) AS m
    FROM qt_like_song s
    WHERE s.updated_seq IS NULL
    GROUP BY s.uid
),
numbered AS (
    SELECT s.id,
           s.uid,
           b.m + ROW_NUMBER() OVER (PARTITION BY s.uid ORDER BY s.create_time, s.id) AS new_seq
    FROM qt_like_song s
    JOIN base b ON b.uid = s.uid
    WHERE s.updated_seq IS NULL
)
UPDATE qt_like_song s
SET updated_seq = n.new_seq,
    updated_at = COALESCE(s.updated_at, s.update_time)
FROM numbered n
WHERE s.id = n.id;

-- ---------------------------------------------------------------
-- 2. 歌单表补号
--    基线重新算：此时歌曲表已补完号，MAX 已包含步骤1的结果。
-- ---------------------------------------------------------------
WITH base AS (
    SELECT p.uid,
           GREATEST(
               COALESCE((SELECT MAX(x.updated_seq) FROM qt_like_song  x WHERE x.uid = p.uid), 0),
               COALESCE((SELECT MAX(x.updated_seq) FROM qt_like_playlist x WHERE x.uid = p.uid), 0)
           ) AS m
    FROM qt_like_playlist p
    WHERE p.updated_seq IS NULL
    GROUP BY p.uid
),
numbered AS (
    SELECT p.id,
           p.uid,
           b.m + ROW_NUMBER() OVER (PARTITION BY p.uid ORDER BY p.create_time, p.id) AS new_seq
    FROM qt_like_playlist p
    JOIN base b ON b.uid = p.uid
    WHERE p.updated_seq IS NULL
)
UPDATE qt_like_playlist p
SET updated_seq = n.new_seq,
    updated_at = COALESCE(p.updated_at, p.update_time)
FROM numbered n
WHERE p.id = n.id;

-- ---------------------------------------------------------------
-- 3. 校验
-- ---------------------------------------------------------------
-- 3.1 剩余 NULL 应为 0 行
SELECT 'qt_like_song 剩余NULL' AS chk, COUNT(*) FROM qt_like_song WHERE updated_seq IS NULL
UNION ALL
SELECT 'qt_like_playlist 剩余NULL', COUNT(*) FROM qt_like_playlist WHERE updated_seq IS NULL;

-- 3.2 uid+seq 重复属正常现象，不需要清零：一次操作只取一个号，但会盖到多行上
-- （如 playlist remove 级联软删全部成员歌曲行，共享同一 seq）。只关心「未删除行」
-- 里是否有重复——同键未删除行重复会导致 changes 流语义混乱。应为 0 行：
SELECT 'song 未删除行 uid+seq 重复' AS chk, COUNT(*) FROM (
    SELECT uid, updated_seq FROM qt_like_song
    WHERE deleted_at IS NULL AND updated_seq IS NOT NULL
    GROUP BY uid, updated_seq HAVING COUNT(*) > 1
) a
UNION ALL
SELECT 'playlist 未删除行 uid+seq 重复', COUNT(*) FROM (
    SELECT uid, updated_seq FROM qt_like_playlist
    WHERE deleted_at IS NULL AND updated_seq IS NOT NULL
    GROUP BY uid, updated_seq HAVING COUNT(*) > 1
) b;

-- 3.3 确认存量重复都是级联删除产生的（deleted_rows = 总数、alive_rows = 0）
SELECT uid, updated_seq, COUNT(*) AS total,
       COUNT(*) FILTER (WHERE deleted_at IS NOT NULL) AS deleted_rows
FROM qt_like_song WHERE updated_seq IS NOT NULL
GROUP BY uid, updated_seq HAVING COUNT(*) > 1;
