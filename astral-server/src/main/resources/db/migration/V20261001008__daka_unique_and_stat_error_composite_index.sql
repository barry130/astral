-- ============================================================================
-- 1) qt_user_daka：同一用户同一天只能签到一次（幂等性由数据库兜底）
-- ============================================================================
-- 背景：QtDakaService.daka() 是「先查后插」——先 getDayByMonth 判重、再 save()。
-- 两个并发请求（双击、网络重放、脚本并发）可以同时通过判重，各写一行同日记录，
-- 结果是积分与连续天数翻倍，且无法回滚。应用层已改为捕获唯一键冲突后返回幂等提示，
-- 这里把该不变式真正落到数据库。
--
-- ⚠️ 建唯一索引前必须先清理存量重复行：保留每组 (uid, data) 中 id 最小的一行
--    （即最早的合法签到），删除其余重复行。
--    删除会同步减少这些用户被重复计发的积分（getAllIntegral 按行求和），
--    这正是本次要修正的脏数据；不会有用户的「首次签到」被删除。
--
-- ⚠️ 必须先判表存在：qt_* 插件表不是本迁移链建的——它们由 QtSchemaInitializer
--    （@PostConstruct，晚于 Flyway；astral.plugins.qt.enabled=false 时不注册）建表。
--    全新空库走 init.sql 全量建库流程时，本迁移执行时 qt_user_daka 还不存在，
--    不判表存在直接清重/建索引会让迁移失败、应用起不来。表缺失时跳过：
--    空库没有存量重复，唯一索引随后由 qt-schema.sql 的
--    CREATE UNIQUE INDEX IF NOT EXISTS 兜底建上。
DO $$
DECLARE
    dup_rows INTEGER;
BEGIN
    IF to_regclass('qt_user_daka') IS NULL THEN
        RAISE NOTICE '[migration] qt_user_daka 尚未创建（qt 插件表由 SchemaInitializer 建表），唯一索引由 qt-schema.sql 兜底';
        RETURN;
    END IF;

    SELECT COUNT(*) INTO dup_rows
    FROM qt_user_daka d
    WHERE d.id > (SELECT MIN(x.id) FROM qt_user_daka x WHERE x.uid = d.uid AND x.data = d.data);

    IF dup_rows > 0 THEN
        RAISE NOTICE '[migration] qt_user_daka 清理同日重复签到 % 行（每组保留最早一条）', dup_rows;
        DELETE FROM qt_user_daka d
        WHERE d.id > (SELECT MIN(x.id) FROM qt_user_daka x WHERE x.uid = d.uid AND x.data = d.data);
    END IF;

    -- 原 idx_qt_daka_uid_data 与唯一索引列完全相同，被后者取代（避免重复索引的写放大）
    DROP INDEX IF EXISTS idx_qt_daka_uid_data;
    CREATE UNIQUE INDEX IF NOT EXISTS uk_qt_daka_uid_data ON qt_user_daka(uid, data);
    COMMENT ON INDEX uk_qt_daka_uid_data IS '同一用户同一天只能签到一次（幂等约束）';
END $$;

-- ============================================================================
-- 2) stat_error_log：错误汇总的 (fingerprint, occur_time) 复合索引
-- ============================================================================
-- 背景：错误汇总 SQL 对每个 fingerprint 分组都带 sampleMessage / topAppVersion
-- 两个相关子查询，它们各自再按 fingerprint + occur_time 过滤。原先只有
-- idx_stat_error_fingerprint 单列索引，子查询仍需回表按时间过滤，
-- 当日 fingerprint 较多时接近 N+1，是统计页最慢的一环。
CREATE INDEX IF NOT EXISTS idx_stat_error_fp_time ON stat_error_log(fingerprint, occur_time);

-- 旧单列指纹索引与复合索引的左前缀完全重叠，纯写放大，随复合索引上线一并退役
DROP INDEX IF EXISTS idx_stat_error_fingerprint;

COMMENT ON INDEX idx_stat_error_fp_time IS '错误汇总：按指纹取样本/代表版本时的时间过滤';
