-- 号段表：biz_key 唯一约束 + 历史重复数据清理
--
-- 背景：
--   原表只有普通索引 idx_segment_biz_key（非唯一）。SegmentGenerator 在号段未初始化时
--   会并发执行 insertSegment，两个线程同时看到 selectByBizKey 返回 null 就会各插一行。
--   之后 selectByBizKey 取回哪一行是不确定的，乐观锁的 version 也随之失效——
--   表现为「号段跳号」甚至「两个实例各自推进自己的号段」。
--
-- 处理：
--   1) 先去重：同一 biz_key 只保留 max_value 最大的那行。
--      被删掉的号段要么从未分配出去，要么其值域已被保留行覆盖，不存在外部引用。
--   2) 再建唯一索引，使 insertSegment 可以用 ON CONFLICT (biz_key) DO NOTHING 做幂等插入。

DELETE FROM sequence_segment s
USING sequence_segment s2
WHERE s.biz_key = s2.biz_key
  AND (s.max_value < s2.max_value
       OR (s.max_value = s2.max_value AND s.id < s2.id));

CREATE UNIQUE INDEX IF NOT EXISTS uk_segment_biz_key ON sequence_segment(biz_key);
