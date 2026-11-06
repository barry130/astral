-- ============================================================
-- sys_storage_upload_operation.expected_sha256 可空化
--
-- 两步上传的期望 SHA-256 属于客户端可选声明：qt 域 upload-plan
-- 与管理端创建入口均不携带该值，服务端在收到正文后按需计算
-- （actual_sha256）。原建表脚本将其约束为 NOT NULL，而插入路径
-- 从不填充该列，导致所有两步上传创建请求在 PostgreSQL 上必然
-- 违反非空约束（生产实测 2026-11-06 报错）。此处放开非空约束，
-- 与实体语义（可选声明）对齐。
-- ============================================================

ALTER TABLE sys_storage_upload_operation
  ALTER COLUMN expected_sha256 DROP NOT NULL;

COMMENT ON COLUMN sys_storage_upload_operation.expected_sha256
  IS '期望SHA-256(可选,客户端声明,服务端不强制)';
