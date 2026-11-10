# 完整应用 HTTP 联调与启动修复

本轮承接用户“那你继续吧”的授权，固定起点为 `dbb3e27ab4b35e30f58528dd26ff72f27afbdcf6`。第五轮的全项目源码审阅与局部测试已有结论，本轮补齐实际 `AstralApplication.main`、Tomcat、拦截器、权限切面、全局异常处理、Service/MyBatis、PostgreSQL 和 Redis 的组合运行证据。范围见 [本轮 SPEC](SPEC.md) 和 [原授权](../2026-11-07/SPEC.md)。

本地产品提交为 `faa780bdffa71b0538b19aa14a63a318ec4df07f`（启动修复与 HTTP 覆盖）及 `76ca550ffba652716321902777d0f6a5a31f8aae`（复审后补齐客户端请求头）；最终复审固定于后者。

## 确认问题及修复

| 编号 | 影响与原因 | 修复与证据 |
| --- | --- | --- |
| HTTP-1 / P1 | 完整应用启动失败。`WebhookUrlPolicy` 同时有生产构造器和测试解析器构造器，生产构造器未明确标注注入，Spring 尝试寻找无参构造器，抛出 `NoSuchMethodException: WebhookUrlPolicy.<init>()`。已有单元/局部上下文测试没有扫描完整应用图。 | 给现有生产构造器加 `@Autowired`，不更改地址校验策略。保留已安装依赖的初次失败和本轮源码 reactor 重现的失败证据；修复后完整应用启动、登录及现有 10 项地址策略测试通过。 |
| HTTP-2 / P2 文档 | 原运行文档只要求 Redis，未说明版本。Redis 5.0.14.1 能连接、应用能启动，但 Sa-Token 1.46 更新会话时使用 `SET KEEPTTL`，Redis 5 返回 `ERR syntax error`，登录成为 HTTP 500。 | README 与部署指南补 Redis 6.0+ 的指令兼容要求，建议选择仍受维护版本。本轮改用本机已有 Redis 8.10.3 独占实例完成登录验证；未修改依赖、放宽认证或宣称测试了所有 Redis 6+ 版本。 |

角色局部更新的首次测试曾把显式 `BusinessException.of(320, msg)` 错误预期为 `COMMON002`。现有契约允许显式业务码不带 errorCode，因此校正为断言 320、字段提示与原值保留；这不是产品缺陷，也不作为产品 RED。完整创建的 Bean Validation、JSON 解析和参数类型错误仍断言 `COMMON002`。

## 公开接口覆盖

新增 `AdminHttpAcceptanceTest`，启动独立真实应用 JVM，7 个场景均以 HTTP 响应和公开读取判断业务结果。

| 场景 | 观察结果 |
| --- | --- |
| 加密登录与会话 | 公钥接口、RSA OAEP SHA-256 登录、HttpOnly/SameSite Cookie、CSRF、会话恢复、info 不含 token、登出清 Cookie 与原会话失效。 |
| 角色部分更新 | 无 CSRF 拒绝且未创建；合法写入可读；省略字段保留；roleCode/isSuper 不可借更新改写；超长拒绝后原值保留。 |
| 短信供应商 | ` mock ` 规范化为 MOCK；只改 enabled 保留名称、类型、签名和权重；详情/列表隐藏密钥；空密钥和未知类型拒绝后数据保留。未调用任何发送接口。 |
| 权限即时变化 | 匿名与无权限 ADMIN 被拒；同一会话在授权后成功，撤回后立即拒绝且会话仍有效；普通角色无法创建角色或自行分配权限。 |
| APP 身份隔离 | API 创建的 APP 用户通过 App 登录；即使夹具授予管理角色，也被管理接口身份门禁拒绝，拒绝写入没有落库。 |
| 错误响应 | 无效 JSON、必填、长度、参数类型、缺参符合 HTTP 200 + 320；路由不存在与方法不支持分别为 404/405；无效创建没有残留。 |
| 用户部分更新 | 昵称可改；用户名等省略字段保留；password/userType/status 的越权字段无效；超长手机号拒绝并保留原值；原密码仍可登录。 |

## 夹具与复现

- Java 25 / Maven 3.9.6；PostgreSQL 使用 `astral_audit` 测试用户、空密码、`postgres` 数据库和每轮随机 schema。SQL 只建立/删除 schema，不用于业务断言。
- Redis 要求独占的 loopback 测试实例，且 DB 15 为空；已有内容时直接失败，不清空既有内容。应用退出后清理本测试 DB 15。其它已有 Redis 集成测试仍用各自的随机键。
- 子进程只继承操作系统必需环境变量；配置来自 classpath 应用配置和显式测试参数，工作目录在 `astral-server/target/http-acceptance-*`。RSA 密钥和日志均留在该目录，不加载主工作区 data 或部署凭据。
- 从应用 classpath 排除全部 `test-classes`，避免测试配置被组件扫描；实际生成实体，运行时禁止再次同步源码；等待 readiness 为 UP 后开始业务请求。
- 测试关闭子 JVM、HTTP 客户端、Redis 连接并删除自己的 schema。服务整体由本轮启动/停止收据核验归属；保留此前 PostgreSQL 目录及全部原始日志。
- 正常请求按既有客户端契约携带四个头，Web 平台值为 `web`；这是 Standards 复审发现并关闭的 P3 测试夹具问题，详见 [独立报告](standards-review.md)。

先在专用测试实例准备上述用户及空 DB 15，再从仓库根目录执行：

```text
mvn -pl astral-server -am -Dtest=AdminHttpAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false -Daudit.postgres.port=<专用PG端口> -Daudit.redis.port=<专用Redis端口> test
mvn install -Daudit.postgres.port=<专用PG端口> -Daudit.redis.port=<专用Redis端口>
```

未提供两个 fixture 属性时，新测试按条件跳过；因此普通无服务测试不能算本轮 HTTP 验收通过。本轮最终执行显式提供两项属性，实际数量、源码指纹和退出状态见 [verification.json](verification.json)。

## 验证边界

真实浏览器扩展仍未就绪。依据 [浏览器技能](C:/Users/canace/.agents/skills/autoglm-browser-agent/SKILL.md) 的“扩展未安装时浏览器任务一定会失败”，以及 [原 SPEC](../2026-11-07/SPEC.md) 的“扩展就绪前禁止任何后继手动 Edge/Chrome CDP、bootstrap、Playwright 或浏览器诊断操作、重试”，本轮没有执行浏览器操作。HTTP 请求不会证明浏览器的实际 Cookie 执行、CORS、焦点、弹层和视口行为。

没有访问生产数据或调用真实邮件、短信、模型、云存储服务；没有远程推送、PR 或部署。本轮前端源码未变，230 个受控文件与第五轮最终验证工作区逐字节一致，沿用其 127 项测试、类型检查和生产构建证据；提交前 ESLint/Prettier 另行重跑。主工作区两处 QtPackHeaderProbe 修改不纳入本轮结果。
