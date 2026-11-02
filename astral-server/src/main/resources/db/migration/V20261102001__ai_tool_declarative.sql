-- AI 工具声明式执行器（第一阶段：HTTP）。
-- ai_tool 此前只有「代码注册」一种形态：handler_key 指向 Spring 容器里的处理器，
-- 后台只能调整风险/确认/启停，无法新增工具。本次加两列支持声明式 HTTP 工具：
--   executor_type   CODE=代码注册（默认，存量行不受影响） / HTTP=后台配置的声明式 HTTP 调用
--   executor_config executor_type='HTTP' 时的执行配置 JSON（method/url/headers/query/body/超时/取值路径）
-- 声明式工具的 handler_key 固定为 'declarative:http'，与代码处理器命名空间隔离。
-- 安全边界在代码里写死、不随配置走：host 必须字面量、模板只允许出现在 path/query/header/body、
-- 非 GET 一律 HIGH + ADMIN_CONFIRM、禁私网/环回/链路本地/云元数据地址、可选白名单收紧。
ALTER TABLE ai_tool ADD COLUMN executor_type VARCHAR(16) NOT NULL DEFAULT 'CODE';
ALTER TABLE ai_tool ADD COLUMN executor_config TEXT;
COMMENT ON COLUMN ai_tool.executor_type IS '执行器类型：CODE=代码注册 / HTTP=声明式 HTTP 调用';
COMMENT ON COLUMN ai_tool.executor_config IS '声明式执行配置 JSON（executor_type 非 CODE 时使用）';
