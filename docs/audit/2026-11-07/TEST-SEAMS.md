# 本轮前端回归边界

用户已授权无需逐项询问的持续审阅与直接修复。本轮采用既有公开接口作为验证边界，不将私有函数重现当作界面验证。

## 首轮公开边界

- `FormInstance`：经`Form.useForm`所提供的公开get/set/reset/validate接口验证真实表单数据形状、可恢复校验及字段选择；使用实际编译后的模块与真实React包，不复制表单实现，不调用私有字段注册接口。
- axios客户端：实际导出的CSRF缓存/读取接口及公开request方法，传输层使用隔离HTTP适配器；验证业务失败、传输失败、请求计数和恢复，不读取真实Cookie或发生产请求。
- 文件下载：公开 `request.getBlob(url, { mediaTypes, ...config })` 使用同一 axios 出口；验证显式媒体类型、跨域 baseURL/凭据/系统头、JSON 业务错误不被保存为文件及请求计数。JSON 错误体最多解码 64 KiB，超过时只拒绝为响应格式异常，不读入或解析超大错误体；这是解码上限，不把它宣称为 axios/XHR 网络接收上限。此边界只验证传输/载荷，不单独证明浏览器下载落盘或对象 URL 生命周期。
- 文件保存：既有公开 `downloadResponse` 配合真实 `Response`/`Blob` 与外部 DOM、对象 URL、时钟适配器，验证文件名/文件内容交给点击目标、URL 不能同步撤销及成功/失败后的资源清理。不复制保存实现；外部平台调用序列只认证 JavaScript 交接与清理，不等同真实浏览器下载文件或落盘证据。
- 主题偏好：公开偏好读取/切换/订阅接口配合可控浏览器环境，覆盖系统默认、显式选择、隐私存储、订阅清理与SSR稳定快照；真实渲染另用隔离浏览器核验，不把这些测试当作浏览器证明。
- 原上传反馈6项处理函数契约回归保持，后续补实际组件/浏览器链路证据，不删除现有有效断言。
- 身份快照：实际 API 公开投影及 AuthProvider 使用的公开会话存储，验证凭据不进入用户上下文、恢复/登录/登出/卸载的竞态以及 Cookie 写请求调用顺序；仅替换认证 HTTP 边界，不替换 React 或会话实现。存储订阅与 HTTP 顺序证据不等于浏览器实际 Set-Cookie/跨标签页证据。

## 编译加载

新增测试以TypeScript编译器加载实际模块的公开exports，保持与Node/React同realm，避免跨VM原型和Error身份造成假失败；不抽取私有实现。测试适配器仅替换外部边界，预期值来自用户可见契约与独立常量。

## 后端 AI 执行公开边界

- `AiChatOrchestrator.invoke`：实际 Java 编译产物的既有公开执行接口，在独立工作线程调用；原请求实体、实际工具处理器 SPI 和真实 `PermissionChecker` 验证请求者权限、审批人不代替请求者、撤权后拒绝及 handler 不执行。只替换外部 `StpInterface` 权限数据源，不反射私有方法、不复制执行/权限逻辑，也不启动后端或数据库。
- 此边界认证工具执行前的权限和身份传递，不单独认证完整模型循环、HTTP 端点注解、真实 MCP/HTTP 传输或审批持久化；这些另列覆盖与门禁。

## 后端 AI 审计查看公开边界

- `AiAdminRequestController.request`：实际控制器、请求/智能体/事件服务及实际 DTO 的 Jackson 序列化；只替换外部数据库 mapper。元数据接口即使收到 `content=true` 也不能返回正文、配置/知识快照、工具参数/结果/原始错误、事件数据或消息块/引用。
- 正文由独立公开路径承载并声明同时要求请求查看和会话正文查看权限；公开方法的注解元数据、真实 `PermissionRegistry.collectDeclarations()` 所登记的 API/DATA 类型与合法内容投影另做回归。注册器使用真实 Spring 控制器上下文/目录/缓存对象，只有外部数据库 mapper 替身；不反射私有扫描器，不把查询参数当作权限，也不把类型登记证明当作实际 HTTP/AOP 授权证明。
- 公开 `requestContent` 经真实 Spring 操作日志 AOP、实际 `OperateLogWriter` 和 `LogServiceImpl`，只替换外部日志数据库 mapper，观察查阅事件的操作者、目标路径和状态；正文仍交还合法调用方，但不能复制到操作日志。该代理用例不认证完整 HTTP 鉴权或 `@Async` 实际线程调度。
- 公开 `requestContent` 另经真实 Spring 权限 AOP与实际 `PermissionChecker`/Sa-Token 登录、权限读取；外部 `StpInterface` 权限数据源与 Sa-Token 自带 mock 请求上下文隔离。验证 API/DATA 各缺一项均拒绝、两项具备可读取；既有工作线程 requester seam 不受替换。仅认证代理调用时声明的授权，不认证真实 HTTP 拦截器/CSRF/Cookie 或生产角色缓存。
- 声明解析后继纵切：真实 Spring 权限代理和注册器，共用实际 Sa-Token 登录/权限读取，仅替换外部 `StpInterface` 和权限数据库 mapper。使用公开、带声明的最小端点夹具验证接口声明、最具体实现方法、方法整体覆盖类组、组内 ANY/ALL 与所有组独立必需；不反射私有解析器、不复制解析逻辑，不将端点夹具描述为真实业务 HTTP/CSRF/数据库证据。
- 同一控制器的公开 `conversationMessages` 查阅面采用上述真实权限代理／操作日志代理边界，数据库仅用合成会话与消息 mapper 替身：会话 API 查看与 DATA 正文查看各自必需，合法正文仍返回，查阅成功／失败记录操作者、目标、结果而不复制正文或私有异常文本。既有请求正文断言保留；此扩展不另造私有解析器或内部服务替身。
- 此边界认证真实返回对象的敏感字段隔离，不宣称已经经过完整 HTTP 身份拦截器、数据库执行或浏览器验收。

## 后端 AI 工具配置公开边界

- 公开 `AiAdminToolController.createHttpTool`、后续同类执行配置操作，使用真实 DTO、`AiToolService`、Spring 权限 AOP 和 `PermissionChecker`；仅替换外部数据库 Mapper、权限数据源和 Sa-Token mock 请求上下文。首先验证仅有可下放的 `admin:ai:tool:edit` 不能创建执行配置，超管可完成同一合法保存；读取操作不因写接口提权而一律要求超管。
- 无真实网络、JDBC/后端启动或浏览器调用；只认证公开代理入口的门禁及真实服务参数/返回投影，不认证 HTTP 拦截器、DNS/实际 socket、生产数据库事务、异步调度或审批持久化。

## 公共日志脱敏边界

- 沿用户已授权的既有公开 API 修复边界，直接调用实际 `LogSanitizer.mask(String)`，使用独立的合成 JSON／表单凭据和非敏感字段；验证包含部分打码标记的凭据不能留存可读尾部，非敏感字段与重复脱敏保持稳定。保留 `CommonUtilsTest` 原有全部断言，不复制正则或调用私有键名处理器。
- 此边界只认证公共纯函数的文本输出，不单独认证操作日志 AOP 覆盖、异步保存、HTTP 接口、真实数据库或所有非 JSON 日志格式。

## 接手后账户、会话与存储持久化边界

- 账户公开改密、重置与 MFA 设置接口：实际 MyBatis/H2 事务，认证/解密边界隔离，验证并发状态保留及旧凭据 CAS；不连接真实账户库。
- TokenService 公开撤销/清理：真实 Sa-Token 逻辑与隔离 DAO，验证未知状态不能误报成功、指纹保密及永久会话保留。
- 存储公开 create/register/delete/recover/quota 接口：真实 MyBatis/Spring 事务和独占 loopback PostgreSQL schema；Redis 票据存取、上游传输使用合成适配器。验证目录与在途操作一致性、持久额度、意图并发上限及失败恢复；不访问真实存储服务或用户文件。
- HttpUploadGateway 公开 send/probe：隔离 DNS 与 socket/HTTP 边界，验证共同截止、迟到解析不发送和诊断不反射任何上游正文；这些证据不等于真实公网/TLS/浏览器验收。

## 接手后通知、插件、Worker 与部署边界

- 通知 publish/publishAsync、规则整体保存和邮件/短信发送：实际服务、Spring 异步/事务代理、隔离 MyBatis 数据库；外部投递 SPI/SMTP 使用合成适配器。验证解析失败隔离、显式清空、渠道匹配、有限资源及故障转移，不向真实收件人发送。
- 插件公开注册/启停与状态仓库：真实生命周期、真实 Flyway/PostgreSQL mapper 和 SequenceMetaObjectHandler，验证持久化失败诚实反馈、启动意图保留、独立表序列；统计异步边界隔离。
- Worker 默认 fetch 入口：实际 Worker 源码与合成多 PoP caches/fetch/时钟，验证重放字节、权限缓存期限、上传前容量和 MIME 拒绝；不运行 Cloudflare 或 Telegram。
- pack.sh/pack.bat、build-release.sh、博客生产构建和 Nginx 示例：临时 Git 仓库/假 Docker 命令/隔离构建/真实 nginx -t；验证当前源码、排除凭据、完整三镜像标签和配置语法，不打包真实凭据、不推送镜像或启动代理。

## 接手后 QT、CMS、监控与序列边界

- QtDakaService 公开签到与积分/连续天数查询：真实 MyBatis/H2、合成用户和可控 Clock，验证包含本次完成签到的 7/30/365 天奖励、跨月年显示、断签及重复请求；不访问真实账号。
- QtPackHeaderProbe.probe：真实共享传输和 loopback HTTP，替换目标 DNS 批准边界，验证忽略 Range、大 Content-Length、慢响应、有限逐跳重定向和有效包头；不访问公网产物。
- CMS 公开页面/分类写入、详情、评论/分享管理列表：独占 PostgreSQL schema、真实 Mapper/Spring 事务、合成身份与时钟，验证可见性、同 ID 异类型标题、深树和并发写入、限流容量与窗口；不将服务回归描述为浏览器验收。
- 监控公开指标/告警/统计和序列配置更新：真实服务、实际 MyBatis 事务、外部系统指标/投递适配器，验证未知采样、渠道停用、筛选一致性、并发局部更新及提交后缓存失效。仪表盘使用真实 React 和合成 DOM/axios，未运行真实浏览器。

## 接手后知识检索边界

- AiKnowledgeService 公开保存、绑定、检索、分页与重建接口使用独占 PostgreSQL schema、实际 Mapper/事务及生产迁移。验证完整正文边界、保存失败保留旧内容、按源阈值、末尾唯一命中、稳定顺序、实际返回大小及 EXPLAIN 索引计划；不复制私有分词/打分函数。
- 管理界面使用真实 React/axios 与合成 DOM，验证翻页、完整正文加载后编辑及过期响应不覆盖当前弹窗。该证据不等于浏览器文件、网络或生产量级性能验收。

## 接手后 AI 用量与收尾边界

- 公开编排 start/approve、请求取消/恢复、配额解析、模型配置和用量预占/结算/核对：实际服务和 Spring 事务代理，独占 H2/PostgreSQL schema；Provider、角色数据和工具外部副作用采用合成适配器。验证完成回调、执行租约预算、取消后消息终态、首次周期行竞争、币种独立、账本核对幂等及汇总口径，不调用真实模型或业务工具。
- OpenAiCompatibleProvider 的公开 chat/stream/testConnection 使用 loopback HTTP/TLS 停滞夹具，验证连接预算、整体预算、路径查询参数和安全错误；无公网模型调用。
- 管理端请求详情和聊天输入使用实际 React/axios 与合成 DOM，验证同请求弹窗重开时旧审批不刷新新弹窗，历史加载时回车保留草稿；无真实浏览器。

## 接手最终并发、启动与任务验收边界

- 智能体公开 Controller 发布/编辑/工具与知识绑定入口：实际 Spring 事务代理、校验服务及 PostgreSQL mapper，通过受控线程交错验证锁内校验、已发布版本冻结、迟到编辑拒绝和省略路由字段不回放旧引用。外部 mapper read barrier 只安排交错，不替代数据库写入或校验结论。
- 人工用量/工具核对公开入口：真实 Sa-Token、权限 AOP、MockMvc DTO 验证，隔离身份与服务边界；实际账本幂等及操作者记录另由生命周期/PG 用例覆盖。React/axios 合成 DOM 验证历史日期范围、必填字段和不重发工具。
- Qt 公开资料、改密与管理员状态接口：真实 MyBatis/H2 事务及合成用户，通过交错证明局部更新不撤销封禁、不恢复旧密码；未连接用户库。
- 监控条件装配与 SSE：实际 ApplicationContextRunner 分别设置统计开/关；真实 MockMvc asyncDispatch 验证一次请求计数；locale 用例恢复 JVM 原默认值。插件禁用通过真实 Spring 路径匹配验证 matrix/context-path，非字符串函数替身。
- 知识任务公开提交、取消、重试、任务读取及 worker tick：真实 PG 事务和游标，验证单源活动任务幂等、终态、有限恢复与新 worker 对象续跑。失败夹具在文档读取后抛出，未把它描述为删除 chunks 后的中途失败；对象重建也不等于进程强杀。UI 使用真实 React/axios 和延迟网络响应，验证忙状态期间刷新不会抢走操作状态；该用例没有等待定时轮询，不声称浏览器验收。
- 旧知识任务升级：从无 cursor 的真实旧表形调用实际 AiSchemaInitializer/ResourceDatabasePopulator，再通过公开任务服务读取/重试；第二次 initializer 和随后新08008迁移保留新任务游标。SQL 查询仅用于数据库 DDL 默认值/索引这一公开管理边界。
- 启动：实际 Boot Flyway/MyBatis/Sequence/PluginRegistry/async 图，独占 PostgreSQL schema、bundled schema catalog，验证完整迁移优先于插件取号、异步记录和第二次 context 恢复。另以实际 Flyway 公共 callback 控制首次验证交错，两个独立启动策略实例必须在空库/baseline 决策前串行，保留每版本一次和校验和检查。没有启动生产完整服务或访问用户数据。
### 最终静态复核补充（2026-11-08）

- 管理助手邮件：公开 `AiToolHandler.execute` → 实际 `NotifyPublisher`、`MailServiceImpl`；替身止于 Mapper/Redis 与 SMTP sender，验证无规则、失败、部分成功与成功的用户可见状态，不发送真实邮件。
- 模型流工具调用：公开 `AiProvider.stream` + loopback SSE，验证缺失上游ID时多次同名调用的身份不冲突，不调用真实模型。
- 管理助手写操作：公开 `AiToolHandler.execute` → 实际 User/Config/Provider service + MyBatis + H2；数据库边界插入一次独立提交，随后通过服务公开读取验证密码、资料、配置及提供方未被旧值覆盖。Sa-Token 会话作为外部边界替换。

### 已提交终审补充（2026-11-09）

- `send_email` 公开工具 → 真实 NotifyPublisher/Mail/Sms/Inapp 服务，混合 EMAIL/SMS/INAPP 与仅非邮件规则；只在数据库、SMTP 与短信供应商 SPI 边界隔离，观察批准的邮件结果及非邮件零投递。不使用真实收件人或第三方服务。
- `change_user_status` 公开工具与管理 Controller → 真实 UserService/MyBatis/Spring 事务、独占 PostgreSQL schema，通过数据库连接边界安排不同账号并发禁用，随后公开读取账号状态；验证至少保留一个可用管理员及业务拒绝。会话撤销边界隔离，真实账号与角色策略不变。用户已授权直接修复，无需新增逐项确认。
