-- ============================================================================
-- notice_type 字典收敛为「公告 / 通知」
--
-- 【口径】
--   notice_type 的真实语义是「广播 / 点对点」，不是业务分类：
--     公告 announce —— 广播（sys_notice.user_id IS NULL），开屏/通告栏/消息中心，不加标签；
--     通知 notice   —— 点对点站内信（user_id 非空），反馈回复/状态变更等都由它承载。
--   「反馈 / 需求」是点对点通知的**业务来源**，归反馈插件自己的 sys_feedback.type +
--   feedback_type 字典（本迁移不碰 feedback_type，那一字典保持 issue问题/request需求 原样）。
--
-- 【为什么只「加一项 + 停用两项」】
--   * announce 这一项的值与 label（公告）本来就对，一行不动（零改动、零风险）；
--   * 存量 297 条 notice_type=feedback/request 的行**一条都不迁移**：已发布 App 把
--     feedback→「反馈」、request→「需求」、其它→「公告」硬编码在二进制里，改存值会让
--     老包把个人通知退化成灰「公告」标签。历史值保留为历史别名，读侧归并显示为「通知」；
--   * 停用而不删除：删掉后管理端打开历史行，下拉会显示裸 feedback；停用只是不再出现在
--     新建选项里，sys_dict_data 仍有据可查、可回滚。
--
-- 【不做的事】
--   * 不 UPDATE announce 行（id=130 原样：announce/公告/status=1）；
--   * 不 UPDATE sys_notice 任何业务行（存量数据零改动）；
--   * 不碰 feedback_type 字典。
--
-- 【取号规范】
--   禁止硬编码主键 id：种子行写死 id 一旦撞号，ON CONFLICT 会静默跳过且 Flyway 记为
--   success（V20260930002 的 permission_type 事故）。这里用
--   COALESCE(MAX(id),0)+ROW_NUMBER() 取号，守卫条件用业务键（dict_code + dict_value）。
--   sys_dict_data 是序列供号表（IdType=INPUT），显式插 id 后必须同步抬高
--   sequence_segment.max_value，否则发号器爬到该区间会撞主键（V20261001005 事故）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- A. 新增「通知 / notice / sort=2」（幂等：dict_code + dict_value 双守卫）
-- ---------------------------------------------------------------------------
WITH seed(dict_label, dict_value, dict_sort, description) AS (
    VALUES ('通知', 'notice', 2, '点对点站内信（反馈回复、状态变更等），受保留期限制')
), missing AS (
    SELECT t.id AS type_id, s.* FROM seed s JOIN sys_dict_type t ON t.dict_code = 'notice_type'
    WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = t.id AND d.dict_value = s.dict_value)
)
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY dict_sort),
       type_id, dict_label, dict_value, dict_sort, 1, description
FROM missing;

-- ---------------------------------------------------------------------------
-- B. 停用 legacy 两项（不删除、可回滚；只改 status，不动 label/值）
--    只命中 feedback/request，announce 与 feedback_type 字典都不在内。
-- ---------------------------------------------------------------------------
UPDATE sys_dict_data
SET status = 0, update_time = CURRENT_TIMESTAMP
WHERE dict_type_id = (SELECT id FROM sys_dict_type WHERE dict_code = 'notice_type')
  AND dict_value IN ('feedback', 'request');

-- ---------------------------------------------------------------------------
-- C. 序列供号对齐（显式插了 id，必须把号段抬到 MAX(id)+step 以上）
-- ---------------------------------------------------------------------------
UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + step),
    update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_data_id';

-- ---------------------------------------------------------------------------
-- D. 结果核对（期望 4 行：announce=公告/status1、notice=通知/status1、
--    feedback=反馈/status0、request=需求/status0）
-- ---------------------------------------------------------------------------
SELECT t.dict_code, d.dict_value, d.dict_label, d.dict_sort, d.status
FROM sys_dict_type t
JOIN sys_dict_data d ON d.dict_type_id = t.id
WHERE t.dict_code = 'notice_type'
ORDER BY d.dict_sort, d.dict_value;
