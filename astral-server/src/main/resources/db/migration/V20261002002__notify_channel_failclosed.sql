-- 通知子系统阶段①：渠道维度 + 事件注册表更名 + 发信授权 fail-closed。
-- 事件（原「场景」）注册表从 MailSceneRegistry 演化为 NotifyEventRegistry（代码注册，含 payload 字段声明），
-- 模板绑定升级为 (scene=事件码) × channel 二元组，为 SMS/站内信渠道预留维度。

-- 1) 模板表加渠道列：存量行全部归入 EMAIL 渠道（当前唯一已实现渠道）
ALTER TABLE sys_mail_template ADD COLUMN IF NOT EXISTS channel VARCHAR(16) NOT NULL DEFAULT 'EMAIL';

-- 2) 唯一绑定从 (scene) 升级为 (scene, channel)：同事件可按渠道各绑一个模板
DROP INDEX IF EXISTS uk_sys_mail_template_scene;
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_mail_template_scene_channel ON sys_mail_template(scene, channel);

-- 3) 发信授权 fail-closed：allowed_scenes 留空 = 一律拒绝（原语义为全部允许）。
--    给 qt 插件的种子授权显式补上它实际使用的事件码，行为不变；其余留空授权从此默认拒绝。
UPDATE sys_mail_plugin_auth
SET allowed_scenes = 'changePasswordByEmail', update_time = CURRENT_TIMESTAMP
WHERE plugin_id = 'qt' AND (allowed_scenes IS NULL OR allowed_scenes = '');
