-- ============================================
-- 接口统计补齐客户端维度（平台 + 版本）
--
-- 背景：stat_api_hourly 原先只记录 uri/method/status。
-- 接口调用是服务端自动测量的，拿不到客户端信息，导致「接口统计」无法像设备统计、
-- 错误统计那样按平台与版本筛选（后两者的数据来自 App 主动上报，事件里天然带这两个维度）。
--
-- 现约定：客户端在每个请求携带两个头 ——
--   X-App-Ut      平台（如 app-android / app-ios / app-windows / web）
--   X-App-Version 客户端版本（如 1.2.0）
-- 头名可通过 astral.stat.client-ut-header / client-version-header 覆盖。
-- 未携带时落库为空串，不影响「全部平台 / 全部版本」的查询口径。
--
-- 存量数据两列取默认空串；唯一键加入新维度后，同小时同接口下不同平台/版本分别建桶。
-- ============================================

ALTER TABLE stat_api_hourly ADD COLUMN IF NOT EXISTS ut VARCHAR(16) NOT NULL DEFAULT '';
ALTER TABLE stat_api_hourly ADD COLUMN IF NOT EXISTS app_version VARCHAR(32) NOT NULL DEFAULT '';

-- 唯一键加入客户端维度（PG 中 UNIQUE 约束自带同名索引，DROP CONSTRAINT 会一并移除）
ALTER TABLE stat_api_hourly DROP CONSTRAINT IF EXISTS uk_stat_api_hourly;
ALTER TABLE stat_api_hourly ADD CONSTRAINT uk_stat_api_hourly
    UNIQUE (bucket_hour, uri, method, status, ut, app_version);

-- 报表固定按「日期区间 (+ 平台 + 版本)」过滤，补一条组合索引
CREATE INDEX IF NOT EXISTS idx_stat_api_hourly_bucket_ut
    ON stat_api_hourly(bucket_hour, ut, app_version);
