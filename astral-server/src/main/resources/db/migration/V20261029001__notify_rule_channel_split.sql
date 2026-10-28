-- ============================================================================
-- 通知规则拆分：事件级规则 + 渠道明细 + 渠道收件人
--
-- 【背景】
--   原 sys_notify_rule 一行 = 一个渠道（channel/template_id/recipient_*），
--   同一事件要配多渠道就得建多条规则，后台呈现为「一个事件 N 条规则」；
--   同时插件发信授权表 sys_mail_plugin_auth 里的 allowed_scenes 与代码重复描述、
--   daily_limit 又是全渠道共用，无法按渠道差异化。
--
-- 【本次结构】
--   sys_notify_rule            事件级策略卡（rule_name/event_code/sender/enabled/remark）
--   sys_notify_rule_channel    渠道明细（channel/template_id/daily_limit/daily_limit_scope/platform）
--   sys_notify_rule_recipient  渠道收件人（每渠道可多个，FIXED/PAYLOAD_FIELD/ROLE）
--
-- 【存量数据】
--   旧规则 6 行按 1:1 迁成「1 规则 + 1 渠道 + 1 收件人」，id 全部保留；
--   新增「重置密码验证码」规则（changePasswordByEmail，EMAIL 渠道，限额 2 条/天/收件人），
--   该事件原先由 qt 插件直调 MailService 发送，本次改由规则驱动。
--
-- 【查询守卫】
--   全部语句带 IF NOT EXISTS / NOT EXISTS 守卫，重复执行不改变结构与数据。
-- ============================================================================

create table if not exists sys_notify_rule_channel (
    id                bigint       not null primary key,
    rule_id           bigint       not null,
    channel           varchar(16)  not null,
    template_id       bigint,
    enabled           smallint     not null default 1,
    daily_limit       integer      not null default 0,
    daily_limit_scope varchar(16)  not null default 'RECIPIENT',
    platform          varchar(96)  not null default 'all',
    remark            varchar(256),
    create_time       timestamp    not null default CURRENT_TIMESTAMP,
    update_time       timestamp    not null default CURRENT_TIMESTAMP
);
create unique index if not exists uk_sys_notify_rule_channel on sys_notify_rule_channel (rule_id, channel);
comment on table sys_notify_rule_channel is '通知规则渠道明细（一个规则下可配多个渠道，每渠道独立模板/限额/平台/收件人）';
comment on column sys_notify_rule_channel.channel is '投递渠道(EMAIL/SMS/INAPP)';
comment on column sys_notify_rule_channel.template_id is '模板ID(空=按事件+渠道自动解析)';
comment on column sys_notify_rule_channel.daily_limit is '每日限额(0=不限)';
comment on column sys_notify_rule_channel.daily_limit_scope is '限额口径(RECIPIENT按收件人/GLOBAL全局)';
comment on column sys_notify_rule_channel.platform is 'INAPP平台定向(逗号分隔,或all=不限平台)';

create table if not exists sys_notify_rule_recipient (
    id              bigint       not null primary key,
    channel_id      bigint       not null,
    recipient_type  varchar(16)  not null default 'FIXED',
    recipient_value varchar(128),
    create_time     timestamp    not null default CURRENT_TIMESTAMP,
    update_time     timestamp    not null default CURRENT_TIMESTAMP
);
create index if not exists idx_sys_notify_rule_recipient_channel on sys_notify_rule_recipient (channel_id);
comment on table sys_notify_rule_recipient is '通知规则渠道收件人（每渠道可配多个）';
comment on column sys_notify_rule_recipient.recipient_type is '收件人类型(FIXED固定值/PAYLOAD_FIELD取载荷字段/ROLE按角色展开)';

-- ---------------------------------------------------------------------------
-- 1) 旧规则行 → 渠道明细（一旧行 = 一渠道；旧表仍有 channel 列时才迁）
-- ---------------------------------------------------------------------------
do $$
begin
    if exists (select 1 from information_schema.columns
               where table_schema = current_schema() and table_name = 'sys_notify_rule'
                 and column_name = 'channel') then
        execute $sql$
            insert into sys_notify_rule_channel
                (id, rule_id, channel, template_id, enabled, daily_limit, daily_limit_scope, platform)
            select (select coalesce(max(id), 0) from sys_notify_rule_channel)
                       + row_number() over (order by r.id),
                   r.id, r.channel, r.template_id, r.enabled, 0, 'RECIPIENT', coalesce(r.platform, 'all')
            from sys_notify_rule r
            where not exists (select 1 from sys_notify_rule_channel c where c.rule_id = r.id)
        $sql$;
    end if;
end $$;

-- ---------------------------------------------------------------------------
-- 2) 旧规则收件人 → 渠道收件人
-- ---------------------------------------------------------------------------
do $$
begin
    if exists (select 1 from information_schema.columns
               where table_schema = current_schema() and table_name = 'sys_notify_rule'
                 and column_name = 'recipient_type') then
        execute $sql$
            insert into sys_notify_rule_recipient (id, channel_id, recipient_type, recipient_value)
            select (select coalesce(max(id), 0) from sys_notify_rule_recipient)
                       + row_number() over (order by c.id),
                   c.id, r.recipient_type, r.recipient_value
            from sys_notify_rule r
            join sys_notify_rule_channel c on c.rule_id = r.id
            where not exists (select 1 from sys_notify_rule_recipient x where x.channel_id = c.id)
        $sql$;
    end if;
end $$;

-- ---------------------------------------------------------------------------
-- 3) 事件级字段：新增 sender；平台定向下放到渠道行，删除旧的渠道/收件人列
-- ---------------------------------------------------------------------------
alter table sys_notify_rule add column if not exists sender varchar(32) not null default 'system';
update sys_notify_rule set sender = 'feedback' where event_code like 'feedback%' and sender = 'system';
comment on column sys_notify_rule.sender is '触发方类型(system/qt/ai/feedback，仅审计用)';

alter table sys_notify_rule drop column if exists channel;
alter table sys_notify_rule drop column if exists template_id;
alter table sys_notify_rule drop column if exists recipient_type;
alter table sys_notify_rule drop column if exists recipient_value;
alter table sys_notify_rule drop column if exists platform;

-- ---------------------------------------------------------------------------
-- 4) 新增「重置密码验证码」规则（必达 EMAIL 渠道，限额 2 条/天/收件人）
-- ---------------------------------------------------------------------------
insert into sys_notify_rule (id, rule_name, event_code, sender, enabled, remark)
select (select coalesce(max(id), 0) from sys_notify_rule) + 1,
       '重置密码验证码', 'changePasswordByEmail', 'qt', 1, '邮箱验证码（必达 EMAIL 渠道，限额 2 条/天/收件人）'
where not exists (select 1 from sys_notify_rule where event_code = 'changePasswordByEmail');

insert into sys_notify_rule_channel
    (id, rule_id, channel, template_id, enabled, daily_limit, daily_limit_scope, platform)
select (select coalesce(max(id), 0) from sys_notify_rule_channel) + 1,
       r.id, 'EMAIL',
       (select t.id from sys_mail_template t
         where t.scene = 'changePasswordByEmail' and t.channel = 'EMAIL' order by t.id limit 1),
       1, 2, 'RECIPIENT', 'all'
from sys_notify_rule r
where r.event_code = 'changePasswordByEmail'
  and not exists (select 1 from sys_notify_rule_channel c where c.rule_id = r.id and c.channel = 'EMAIL');

insert into sys_notify_rule_recipient (id, channel_id, recipient_type, recipient_value)
select (select coalesce(max(id), 0) from sys_notify_rule_recipient) + 1,
       c.id, 'PAYLOAD_FIELD', 'email'
from sys_notify_rule_channel c
join sys_notify_rule r on r.id = c.rule_id
where r.event_code = 'changePasswordByEmail' and c.channel = 'EMAIL'
  and not exists (select 1 from sys_notify_rule_recipient x where x.channel_id = c.id);
