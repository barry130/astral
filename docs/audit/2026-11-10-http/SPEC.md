# 完整应用 HTTP 联调补充

用户在第五轮完成后询问剩余问题，并授权“那你继续吧”。固定起点为 `dbb3e27ab4b35e30f58528dd26ff72f27afbdcf6`；沿用原全项目 SPEC 的本地修复、本地提交及隔离环境边界。

本轮补齐此前 Standalone MockMvc 没有覆盖的组合：实际应用入口、Tomcat、过滤器/拦截器、权限切面、全局异常处理、Service/MyBatis/PostgreSQL 与 Redis。测试通过真实 loopback HTTP 请求、Cookie、响应体和公开读取观察结果；数据库 SQL 仅用于建立和清理隔离初态，不作为业务断言出口。凭据只用于本轮夹具。

依照用户持续授权直接确定以下测试边界，无需重复确认：

- 登录与会话：从公钥接口取得公钥后加密登录，校验认证 Cookie、CSRF、会话恢复、登出失效。
- 管理写入：通过公开 API 创建和读取角色、用户、短信供应商；校验部分更新、拒绝后存量保留和凭据出参保护。
- 权限：无登录、APP 身份、无权限管理员、已授权管理员、授权撤回后的同一会话分别观察实际响应；权限与用户均在独立夹具内创建。
- 错误响应：无效 JSON、字段长度/必填、参数类型、404/405，通过真实 DispatcherServlet 和全局 advice 断言 HTTP 状态与业务码。

如验证发现缺陷，先保留公开接口失败证据，再最小修复、完整复测，并对固定增量执行 Standards/Spec 两轴独立复审。未失败的现有行为作为补充覆盖，不伪造 RED。

数据库使用独占 PostgreSQL 服务的随机 schema；Redis 使用本轮独占实例的 DB 15，启动前要求为空，测试结束后清理本测试写入的状态。应用在 target 下独立目录运行，不加载主仓库 data/schemas 或密钥；子进程环境仅继承操作系统必需字段，等待 readiness 就绪后开始业务请求。测试子进程不包含 test-classes，避免扫描测试 Fixture 配置而影响真实应用装配。测试与服务结束后核实归属再停止；上一轮 PostgreSQL 目录、隔离工作区及日志全部保留。

浏览器配置复核为 Chrome、extension_confirmed=false、auto_approve=false；依原 SPEC 不运行替代 CDP/Playwright/bootstrap/浏览器诊断。真实浏览器、外部投递、生产数据与压力测试不算本轮 HTTP GREEN。主仓库两处 QtPackHeaderProbe 并行修改保持排除。
