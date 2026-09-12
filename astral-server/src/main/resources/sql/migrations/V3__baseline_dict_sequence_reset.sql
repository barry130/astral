-- ============================================================
-- 迁移版本：V3（基线脚本，仅用于全新数据库首次部署，执行一次）
-- 命名规范：V{序号}__{描述}.sql，序号递增、只增不改
-- 应用方式：见同目录 README.md
-- 注意：一旦应用过就禁止再修改本文件，后续变更请新增下一个版本号
-- ============================================================

-- ============================================
-- PostgreSQL 序列重置脚本
-- dict-init.sql 使用显式 id 插入字典数据，需将 BIGSERIAL 序列推进到当前最大值，
-- 避免后续运行时自动生成 id 时与种子数据冲突。
-- ============================================

SELECT setval(pg_get_serial_sequence('sys_dict_type', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM sys_dict_type), 1),
              (SELECT COUNT(*) > 0 FROM sys_dict_type));

SELECT setval(pg_get_serial_sequence('sys_dict_data', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM sys_dict_data), 1),
              (SELECT COUNT(*) > 0 FROM sys_dict_data));
