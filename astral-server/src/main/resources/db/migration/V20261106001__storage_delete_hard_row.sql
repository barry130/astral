-- 删除语义改为「立即硬删库行 + 远端异步清理」后的历史数据收敛：
-- 1) 清理卡在 DELETING 的历史行：CUSTOM_HTTP 不属于 server-managed 家族，旧实现会落入
--    「DELETING + 任务表」分支，而 Worker 只处理 TELEGRAM 任务 → 永久滞留「删除中」。
--    其滞留任务（payload 自带 locator 但无人消费）一并清理。
-- 2) 历史软删行（DELETED，各查询已过滤、界面上不可见）转为硬删，与新语义对齐。
-- 3) 状态字典收敛到可达值：AVAILABLE / FAILED / ORPHAN_POSSIBLE。
DELETE FROM sys_storage_task
 WHERE task_type = 'DELETE_REMOTE'
   AND file_id IN (SELECT id FROM sys_storage_file WHERE status IN ('DELETING', 'DELETED'));

DELETE FROM sys_storage_file WHERE status IN ('DELETING', 'DELETED');

DELETE FROM sys_dict_data
 WHERE dict_type_id = (SELECT id FROM sys_dict_type WHERE dict_code = 'storage_file_status')
   AND dict_value IN ('DELETING', 'DELETE_FAILED', 'DELETED');
