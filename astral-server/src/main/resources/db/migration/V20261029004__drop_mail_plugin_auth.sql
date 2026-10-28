-- 删除「插件发信授权」整套：表 + 权限码。
--
-- 背景：发信授权（sys_mail_plugin_auth / allowed_scenes + daily_limit）在通知中心上线后已名存实亡：
--   * 唯一会走授权校验的入口 MailService.send(pluginId,...) / SmsService.send(pluginId,...) 全仓零调用，
--     实际发信统一走通知中心（NotifyPublisher → testSendTemplate / sendTemplateRow），授权从未被读取；
--   * 授权模型与「事件 × 渠道」规则模型重复描述同一件事（谁能发哪些事件），两套并存只会让人以为
--     还有一道 fail-closed 闸门，实际没有 —— 这比没有更危险。
-- 因此连表带权限码一起删，权限判定回到菜单/接口权限这一层。
--
-- 幂等性：DROP TABLE IF EXISTS；权限码清理用先删关联再删码的两步 DELETE，重复执行不报错。
-- 权限码兼容处理带端前缀（V20261001001 起为 admin:system:...）与历史无前缀两种写法。

-- 1. 角色-权限关联先清（sys_role_permission.permission_id 引用 sys_permission.id）
DELETE FROM sys_role_permission
WHERE permission_id IN (
    SELECT id FROM sys_permission
    WHERE permission_code IN ('admin:system:mail:plugin-auth:edit', 'system:mail:plugin-auth:edit')
);

-- 2. 权限码本身
DELETE FROM sys_permission
WHERE permission_code IN ('admin:system:mail:plugin-auth:edit', 'system:mail:plugin-auth:edit');

-- 3. 授权表
DROP TABLE IF EXISTS sys_mail_plugin_auth;
