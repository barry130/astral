-- 「用户角色变更 / 系统告警 / 找回密码验证码」发出来的站内信是**个人通知**，不是运营公告。
-- 事件定义（NotifyEventRegistry）原先给这三类事件的 noticeType 传了 announce，导致
-- sys_notice.notice_type='announce'，在管理端与客户端的「公告」分类里出现。
-- 这里把历史行一次性改回 notice；手工新建的公告不受影响（它们没有这几个 scene）。
UPDATE sys_notice
SET notice_type = 'notice',
    update_time = CURRENT_TIMESTAMP
WHERE notice_type = 'announce'
  AND scene IN ('userRolesChanged', 'systemAlert', 'changePasswordByEmail');
