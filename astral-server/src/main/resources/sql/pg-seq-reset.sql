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
