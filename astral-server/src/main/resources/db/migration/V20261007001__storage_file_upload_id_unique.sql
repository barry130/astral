-- ============================================================
-- sys_storage_file.upload_id 唯一约束（消除重复登记）
--
-- 背景：两条登记路径（StorageFileService.registerFromCallback / registerFromBrowser）
-- 都是「先查后插」的幂等实现，但原索引 idx_storage_file_upload 只是普通索引，
-- 数据库层不设防：并发（Worker 回调重试、客户端重复回执）时两个事务都能读到
-- 「不存在」，于是同一个 uploadId 插入两行文件记录——表现为文件列表重复、
-- 上传配额重复计数、删除补偿对同一对象触发两次。
--
-- 处理：
--   1. 清理存量重复：同一 upload_id 保留 id 最大的一行（最后登记的那次，
--      与之对应的 provider_locator / public_id 才是客户端最终拿到的），
--      其余行标记 DELETED 并置空 upload_id。**不产生远端删除任务**——
--      重复行指向的是同一个远端对象（同一张凭证签发的 objectKey / Telegram message），
--      该对象仍被保留行使用，误删会让保留行变成死链。
--   2. 建唯一索引：PostgreSQL 唯一索引允许多个 NULL，被置空的软删除行不冲突；
--      查询路径（getByUploadId）继续走该索引。
-- 代码侧配套（同一次部署）：登记前取 pg_advisory_xact_lock 串行化同一 uploadId 的
-- 幂等检查，见 StorageFileMapper#lockUploadKey。
-- ============================================================

UPDATE sys_storage_file f
SET upload_id    = NULL,
    status       = 'DELETED',
    deleted_time = COALESCE(f.deleted_time, CURRENT_TIMESTAMP),
    update_time  = CURRENT_TIMESTAMP
WHERE f.upload_id IS NOT NULL
  AND EXISTS (
      SELECT 1
      FROM sys_storage_file g
      WHERE g.upload_id = f.upload_id
        AND g.id > f.id
  );

DROP INDEX IF EXISTS idx_storage_file_upload;
CREATE UNIQUE INDEX IF NOT EXISTS uq_storage_file_upload ON sys_storage_file (upload_id);