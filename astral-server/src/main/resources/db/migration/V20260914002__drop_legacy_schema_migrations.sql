-- ============================================================
-- 删除旧的手工版本跟踪表 schema_migrations
--
-- 历史上 V 系列脚本由人工执行并用该表登记版本；迁移机制切换到
-- Flyway 后由 flyway_schema_history 取代。存量库上本脚本会真正
-- 删表；全新库上 schema_migrations 不存在，DROP IF EXISTS 为无操作。
-- 幂等，可重复执行。
-- ============================================================
DROP TABLE IF EXISTS schema_migrations;
