-- V20261106001 任务清理补丁：该迁移把 task_type 写成了 'DELETE_REMOTE'，
-- 实际存储值为 'DELETE_REMOTE_OBJECT'（见 StorageTaskEntity.TYPE_DELETE_REMOTE），条件未命中。
-- 补删 CUSTOM_HTTP 滞留删除任务：孤儿行（文件行已删）中 payload 不含 messageId 的
-- 即为 CUSTOM_HTTP 滞留任务（Worker 只消费 TELEGRAM 任务，CUSTOM_HTTP 任务无人执行）。
-- TELEGRAM 合法清理任务的 payload 为 chatId/messageId 结构（见 StorageFileService.buildLocator），
-- 文件行删除后任务仍有效（Worker 按 payload 执行远端删除），必须保留。
DELETE FROM sys_storage_task
 WHERE task_type = 'DELETE_REMOTE_OBJECT'
   AND file_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sys_storage_file f WHERE f.id = sys_storage_task.file_id)
   AND payload_json NOT LIKE '%"messageId"%';
