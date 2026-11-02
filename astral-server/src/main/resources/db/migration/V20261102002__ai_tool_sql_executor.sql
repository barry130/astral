-- AI 工具声明式执行器（第二阶段：SQL 只读查询）。
-- 无结构变更：V20261102001 已加 executor_type / executor_config 两列，本阶段只扩展取值与注释。
--   executor_type = SQL：后台配置一条带具名参数的 SELECT，模型只负责填参数、拿结果。
-- 只读护栏全部在代码里（com.astral.ai.tool.declarative.SqlToolExecutor），不随配置放开：
--   只允许单条 SELECT / WITH … SELECT（写关键字、数据修改型 CTE、多语句一律拒绝）；
--   参数只走 #{name} → PreparedStatement 绑定，不存在把入参拼进 SQL 的路径；
--   连接 setReadOnly(true) + 事务末尾 rollback 双保险；
--   maxRows 默认 50、硬上限 200，timeoutSeconds 默认 5、上限 15，columns 必须显式声明。
COMMENT ON COLUMN ai_tool.executor_type IS '执行器类型：CODE=代码注册 / HTTP=声明式 HTTP 调用 / SQL=声明式只读查询';
COMMENT ON COLUMN ai_tool.executor_config IS '声明式执行配置 JSON（executor_type 非 CODE 时使用）';
