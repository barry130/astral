-- 声明式工具的前端枚举字典 + MCP 服务器主键号段。
--
-- 1) ai_tool_executor_type：工具的执行方式，后台只允许手工创建 HTTP / SQL 两种，
--    CODE 由代码登记、MCP 由「刷新 MCP 服务器」自动发现。
-- 2) ai_mcp_server_status：MCP 服务器启用状态。
-- 3) ai_mcp_server 的 ID 同样走 sequence_segment 号段（biz_key = 'ai_mcp_server_id'）。

INSERT INTO sequence_segment (biz_key, min_value, max_value, current_max_value, step)
VALUES ('ai_mcp_server_id', 1, 1000, 1000, 1000)
ON CONFLICT (biz_key) DO NOTHING;

WITH seed(dict_code, dict_name, description) AS (
    VALUES ('ai_tool_executor_type', 'AI 工具执行方式', 'CODE 代码实现 / HTTP 外部接口 / SQL 只读查询 / MCP 远端 MCP 工具'),
           ('ai_mcp_server_status', 'MCP 服务器状态', 'ENABLED 启用 / DISABLED 停用')
), missing AS (
    SELECT * FROM seed s WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_code = s.dict_code)
)
INSERT INTO sys_dict_type (id, dict_code, dict_name, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + ROW_NUMBER() OVER (ORDER BY dict_code),
       dict_code, dict_name, 1, description
FROM missing;

WITH seed(dict_code, dict_value, dict_label, dict_sort) AS (
    VALUES ('ai_tool_executor_type', 'CODE', '代码实现', 1),
           ('ai_tool_executor_type', 'HTTP', '外部接口', 2),
           ('ai_tool_executor_type', 'SQL', '只读查询', 3),
           ('ai_tool_executor_type', 'MCP', 'MCP 远端工具', 4),

           ('ai_mcp_server_status', 'ENABLED', '启用', 1),
           ('ai_mcp_server_status', 'DISABLED', '停用', 2)
), missing AS (
    SELECT t.id AS type_id, s.* FROM seed s JOIN sys_dict_type t ON t.dict_code = s.dict_code
    WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = t.id AND d.dict_value = s.dict_value)
)
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY dict_code, dict_sort),
       type_id, dict_label, dict_value, dict_sort, 1 FROM missing;

UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_type_id';
UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_data_id';
