-- 通知子系统阶段⑤：站内通知统一存储。sys_notice（feedback 插件的统一通知表）升级为
-- 平台一等能力：实体/mapper 下沉 astral-dao，消息中心 INAPP 渠道投递改落本表
-- （点对点 + 消息中心位 + 服务端已读 read_time），并接管原 sys_announcement 的公告职责。
-- sys_announcement / sys_notify_inapp 两张过渡表废弃。

-- 1) sys_notice 增列：服务端已读时间（仅点对点行有意义）+ 投递来源事件码
ALTER TABLE sys_notice ADD COLUMN IF NOT EXISTS read_time TIMESTAMP;
ALTER TABLE sys_notice ADD COLUMN IF NOT EXISTS scene VARCHAR(64);

-- 已读相关查询索引（点对点收件箱/未读数）
CREATE INDEX IF NOT EXISTS idx_sys_notice_user_read ON sys_notice(user_id, read_time);

-- 2) 过渡表退场（阶段③的 sys_announcement：能力已被 sys_notice 覆盖）
DROP TABLE IF EXISTS sys_announcement;
DELETE FROM sequence_segment WHERE biz_key = 'sys_announcement_id';

-- 3) 公告级别/状态字典随 sys_announcement 退场（920/9201+ 段位释放回预留区，
--    后续字典迁移可复用；数据行无业务外键，直接清）
DELETE FROM sys_dict_data WHERE dict_type_id IN (
    SELECT id FROM sys_dict_type WHERE dict_code IN ('notify_notice_level', 'notify_notice_status'));
DELETE FROM sys_dict_type WHERE dict_code IN ('notify_notice_level', 'notify_notice_status');

-- 4) 菜单更名：该页已覆盖邮箱/短信/站内信/公告/订阅规则全渠道，不再是「邮箱管理」
UPDATE sys_menu SET name = '消息中心', update_time = CURRENT_TIMESTAMP
WHERE path = '/dashboard/system/mail' AND name = '邮箱管理';
