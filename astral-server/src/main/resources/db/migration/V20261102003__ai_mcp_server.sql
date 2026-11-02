-- AI 工具声明式执行器（第三阶段：MCP）。
-- 一个 MCP 服务器（Streamable HTTP 传输）承载若干远端工具；后台配置服务器地址与请求头后，
-- 「刷新」会发现 tools/list 并把每个远端工具落成一行 ai_tool：
--   executor_type = 'MCP'，handler_key = 'declarative:mcp'，
--   executor_config = {"serverId":<ai_mcp_server.id>,"toolName":"<远端工具名>"}。
-- 远端工具没有本地代码，参数结构直接取远端 inputSchema；风险由服务器声明的 read_only 决定：
-- 只读服务器的工具默认 READ + NONE，非只读服务器发现的工具一律 HIGH + ADMIN_CONFIRM。
CREATE TABLE IF NOT EXISTS ai_mcp_server (
    id BIGINT PRIMARY KEY,
    server_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    url VARCHAR(512) NOT NULL,
    headers_json TEXT,
    read_only BOOLEAN NOT NULL DEFAULT true,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    tool_count INTEGER NOT NULL DEFAULT 0,
    last_refresh_time TIMESTAMP,
    last_error VARCHAR(512),
    created_by BIGINT,
    updated_by BIGINT,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_mcp_server_code ON ai_mcp_server (server_code);

COMMENT ON TABLE ai_mcp_server IS 'MCP 服务器（Streamable HTTP），其工具在刷新时发现并写入 ai_tool';
COMMENT ON COLUMN ai_mcp_server.url IS 'MCP 端点地址；私网/环回/链路本地/云元数据地址始终禁止';
COMMENT ON COLUMN ai_mcp_server.headers_json IS '附加请求头 JSON（如 Authorization），值可写 ${secret:key} 引用环境变量';
COMMENT ON COLUMN ai_mcp_server.read_only IS '服务器是否只读：true 时发现出来的工具默认 READ + NONE，false 时一律 HIGH + ADMIN_CONFIRM';
COMMENT ON COLUMN ai_mcp_server.status IS '状态：ENABLED / DISABLED';
COMMENT ON COLUMN ai_mcp_server.tool_count IS '最近一次刷新发现的工具数量';

COMMENT ON COLUMN ai_tool.executor_type IS '执行器类型：CODE=代码注册 / HTTP=声明式 HTTP 调用 / SQL=声明式只读查询 / MCP=远端 MCP 工具';
