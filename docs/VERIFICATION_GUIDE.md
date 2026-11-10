# Astral 验证、启动与交付规范

本规范由 [2026-11 全项目修复复盘](audit/2026-11-10-http/RETROSPECTIVE.md) 固化，适用于后续开发、缺陷修复、评审和本地测试交付。代码规则见 [CODING_GUIDE.md §11](CODING_GUIDE.md#11-2026-11-全项目修复后的强制规则)，界面行为见 [UI_GUIDE.md](UI_GUIDE.md)。

本文中的“必须”是开发和评审验收要求。现有 Maven 静态工具没有全部绑定生命周期，本文也没有新增 CI 配置；执行者必须保存实际结果，不能假定构建命令已自动执行所有检查。

## 1. 交付结论的五个层次

| 结论 | 必需证据 | 不能据此推导 |
| --- | --- | --- |
| 编译/构建通过 | 当前输入对应的完整命令、退出码和产物 | Spring 全部 Bean 可装配、依赖运行版本兼容 |
| 回归通过 | 本次测试名称、数量、失败/错误/跳过数、日志及源码版本 | H2 已证明 PG 锁、替身已证明真实第三方服务 |
| 完整后端可用 | 真实入口/产物启动、readiness UP、公开 HTTP 业务链通过 | 两个前端已启动、浏览器行为已通过 |
| 本地页面可交付测试 | 管理台/博客实际 HTTP 响应、同源 API 转发及明确账号/地址，服务持续运行 | 人工浏览器测试已经完成 |
| 浏览器/部署验收通过 | 实际目标环境中的独立结果和覆盖范围 | 未运行的平台、设备或生产服务也已验证 |

每一层单独记录 PASS、FAIL、NOT_RUN/BLOCKED 或有依据的 INHERITED。端口监听、`Started` 日志、Redis PING、`HTTP 200` 都不足以单独确认业务成功；接口还需按契约判断 `body.code` 和可观察结果。

## 2. 按改动选择必须执行的检查

| 触发改动 | 最低验证要求 | 已有回归入口 |
| --- | --- | --- |
| Java 业务行为 | 所在模块及必要依赖编译/回归，Spotless；确认缺陷先复现后修复，不删既有断言 | 各模块 `src/test`；全项目收口另跑完整 reactor |
| schema / 实体约束 / 生成器 | 实际运行生成器；核对最终 DDL、JSON、生成注解；长度上界接受与越界/必填拒绝 | `AdminWriteValidationTest`、`MessageTemplatePersistenceTest` |
| 局部修改 / 列表开关 / 完整表单 | 分别验证省略、空白、null、清空、不可变字段及拒绝后原值保留；无效请求不得部分写入 | `AdminWriteValidationTest`、`MailTemplatePatchPersistenceTest`、`SmsTemplatePatchPersistenceTest`、`AdminHttpAcceptanceTest` |
| 角色权限 / 最后管理员 / 关联删除 | 实际 PG 两连接与受控交错；两种先后提交；同一会话即时授权/撤权；回滚不失效缓存 | `RoleAuthorizationPersistenceTest`、`AdminToolRoleConcurrencyTest`、`AdminToolUserStatusConcurrencyTest`、`AdminHttpAcceptanceTest` |
| 通知 / 模板 / 供应商 | 合并/单渠道入口、启停、渠道选择、持久化后读取/投递、缓存热读与回滚；外部投递用边界替身 | `NotifyRuleConcurrencyTest`、`MessageTemplatePersistenceTest`、`SmsDeliveryTest`、`AdminWriteValidationTest` |
| AI 审批 / 幂等 / 外部状态恢复 | 权限改变后拒绝旧计划、取消/超时/UNKNOWN、重复操作身份与账本、恢复后公开结果 | `AiApprovalLifecycleTest`、`AiAgentConcurrencyTest`、`AiKnowledgePersistenceTest`、`StoragePersistenceTest` |
| Bean 构造器 / 自动配置 / 启动 / 认证 / 运行依赖版本 | 实际完整应用和兼容 PG/Redis；readiness、加密登录、Cookie/CSRF、权限与登出；保留相关策略回归 | `AdminHttpAcceptanceTest`、`WebhookUrlPolicyTest`、`PluginStartupContextTest` |
| Flyway / 初始化顺序 | 按涉及范围验证空库、旧库升级和并发启动；保留历史 checksum；确认生成与启动两条链 | `FreshDatabaseMigrationTest`、`NoticeMigrationCompatibilityTest`、`PluginStartupContextTest` |
| 管理前端 TS/TSX / 共享组件 | ESLint、Prettier、tsc、相关真实 React/请求行为测试；页面/路由/共享行为变更及全项目交付跑生产构建 | `astral-front/tests/*.test.cjs`，条件表单重点看 `form-lifecycle`、`message-admin-contracts` |
| Blog | 按锁安装、类型、server/React 回归与隔离构建；公开 URL/canonical/SSR 检查按变更选择 | `test:server`、`test:react`、`build:isolated`，见 [博客说明](../astral-blog/README.md) |
| 浏览器相关交互 | 环境就绪且已有授权时检查实际 Cookie/CORS、焦点/滚轮/IME、窄屏、下载等涉及行为；不可执行则明确待验 | 合成 DOM/HTTP 只作为补充，不填充浏览器 PASS |
| 打包 / CI / nginx / Worker | 临时仓库归档、fake Docker 标签顺序、两种 nginx 布局语法、Worker/gateway 边界，按变更执行 | `scripts/test_pack.py`、`scripts/test_release.py`、`scripts/verify-nginx.py` 及各服务测试 |
| 仅 Markdown/说明性文档 | 相对链接/锚点、命令与现有脚本/配置一致、证据来源、`git diff --check` | 不因纯文档改动重复启动服务或全量构建 |

必须核对同一规则的关联写入口：创建、完整保存、局部修改、开关、批量、删除、AI 工具和初始化。修复一个入口后检索调用链，并记录其余入口已受同一约束或另有回归的依据。

## 3. 后端与前端检查命令

### 3.1 后端完整验收

使用 JDK 25、Maven 3.9+。准备专用 loopback PostgreSQL 与 Redis，检查当前实际版本。Redis 指令兼容最低要求为 6.0，部署使用仍受维护版本；2026-11 最终验收实际使用 Redis 8.10.3，不能据此声称所有兼容版本均已测试。

现有 HTTP 夹具固定连接 `127.0.0.1`，PostgreSQL 数据库 `postgres`、用户 `astral_audit`、空密码；用户须有建/删本测试 schema 的权限。Redis 无密码，DB 15 必须为空且由测试独占。该配置只用于隔离本机夹具，不得指向共享或生产服务。人工预览数据库 `astral_preview` 与该夹具不同，不直接混用。

PowerShell 示例（端口只是示例，先确认归属并准备相应专用实例）：

```powershell
$auditPgPort = 9685
$auditRedisPort = 8550

# 聚焦完整应用 HTTP：缺少两个属性会条件跳过，不能算验收成功。
mvn -pl astral-server -am '-Dtest=AdminHttpAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-Daudit.postgres.port=$auditPgPort" "-Daudit.redis.port=$auditRedisPort" test

# 全项目产品交付：包括实际 schema 生成和集成测试。
mvn install "-Daudit.postgres.port=$auditPgPort" "-Daudit.redis.port=$auditRedisPort"

mvn spotless:check
mvn checkstyle:checkstyle-aggregate
mvn spotbugs:spotbugs
```

每条命令检查退出码后再继续；自动化脚本必须在非零时停止。禁止带 `skipTests`/跳过代码生成参数后宣称完整验收。该固定版本的 `AdminHttpAcceptanceTest` 有 7 项，完整 Java 共 922 项；后续数量随有效测试变化，不把这两个历史数字硬编码为永久门槛。必须确认必要用例实际运行且未跳过。

HTTP 夹具会启动真实 `AstralApplication.main` 子 JVM，排除 `test-classes`，使用随机 schema、独立运行目录和受控配置。局部 `@SpringBootTest`、MockMvc 或手工 `new` 不能替代它。修改构建/打包/运行入口时还须验证实际交付 JAR 或容器，而非只验证测试 classpath。

### 3.2 管理前端与博客

管理端使用 `package-lock.json` / npm，博客使用 `pnpm-lock.yaml` / pnpm；统一使用 Node 24 LTS。干净依赖安装分别使用 `npm ci`、`pnpm install --frozen-lockfile`，不生成另一套锁，不为绕过镜像失败修改 manifest/锁文件。

在 `astral-front` 执行：

```text
npm run lint
npm run format:check
npx tsc --noEmit
node --test --test-concurrency=1 tests/*.test.cjs
npm run build
```

React 合成 DOM 测试需要 jsdom；可用 `FRONT_TEST_JSDOM_PATH` 指向隔离安装的模块。必须记录实际依赖来源，缺依赖时不能删除相关用例。当前包中没有统一 `npm test` 脚本，不使用不存在的脚本作为验收步骤。

博客使用 `pnpm typecheck`、`pnpm test:server`、`pnpm test:react`、`pnpm build:isolated`；jsdom 来源与隔离构建要求见 [博客验证说明](../astral-blog/README.md)。普通 `build` 不是零外网访问证明，构建前必须核对配置来源和后端目标。

## 4. 本地启动到交付测试

1. **固定版本与产物。** 记录工作区、HEAD、未提交变更及是否包含这些变更。复用 JAR 时核对源码/构建输入与产物 SHA-256；不能只看文件名或时间称为“最新”。
2. **明确配置来源。** 专用端口、数据库/schema、Redis DB、RSA/上传目录和日志目录独立登记。禁止隐式加载生产 `.env`、部署凭据或主工作区数据。构建完成后的隔离启动关闭运行时源码同步：`-Dastral.schema.sync-on-startup=false`。
3. **按依赖顺序启动。** PG/Redis → 后端 → 管理端/博客。每步检查进程没有提前退出、监听归属正确、日志和健康结果正常。需要 readiness 时显式启用 `--management.endpoint.health.probes.enabled=true`，等待 `/actuator/health/readiness` 为 UP。
4. **先验证后端业务。** 公钥 → 加密登录 → Cookie 会话恢复 → CSRF 写保护 → 授权读取/相关操作 → 登出；按变更验证普通 ADMIN、APP 身份门禁和同一会话撤权。
5. **分别检查两个前端。** 管理端 `/login`、博客 `/` 均有实际 HTTP 响应；同源 `/api` 指向本次后端，确认未出现编译/代理错误。启动日志或依赖安装完成不能代替页面检查。
6. **明确交付状态。** 给出实际可访问地址、测试账号、源码版本、日志位置和未配置的外部能力。用户接手测试期间保持服务运行。若只有后端可用，就只报告后端可用，页面记作未就绪。

启动开发服务前核对继承的冲突环境值，明确 `NODE_ENV=development`、本地 `BACKEND_URL` / `CMS_API_BASE` 与站点 URL。环境覆盖的作用域、持续时间要可追踪。复用依赖时核对 manifest、锁、运行时、平台及安装完整性；外部 junction 无法通过 Turbopack 时不能把失败记作构建成功。

自动审批/执行策略拒绝须保留原消息和失败阶段；没有明确原因就不猜测。不得用替代 shell、脚本包装或其他工具绕过拒绝，也不能把未运行步骤写成通过。浏览器缺少所需环境或授权时标为未执行，继续可执行的独立检查。

Redis 本地持久化失败要检查工作目录与文件权限；若专用临时测试实例采用无持久化，须注明重启后会话丢失。持久化环境不能直接套用该临时设置。

## 5. 停止服务与数据保留

- 停止前核对 PID、可执行文件、命令行/数据目录及监听端口，防止 PID 复用或误停同名无关服务。
- 先停前端和业务应用，再停缓存/数据库；数据库使用其正常关闭命令。不得因为端口占用就按进程名批量结束服务。
- 等待退出后再次检查本次 PID、子进程和端口；有残留就如实报告。服务停止成功不等于数据已删除。
- 自动测试仅清理确认属于自己的随机 schema/键；HTTP 测试的 Redis DB 15 必须事先独占且为空。不得清空共享库。
- 人工测试数据、日志与失败证据默认保留；清理遵守当前授权和路径归属，已被拒绝的删除不得改方式重试。

## 6. 验证证据与收口清单

- [ ] 每个确认缺陷有触发条件、根因、修复提交与公开行为回归；正常路径、拒绝路径及失败后原数据保留均按风险覆盖。
- [ ] 同一业务的所有入口已核对；创建/完整保存/局部修改/清空语义明确。
- [ ] 必要检查均实际执行，退出码、失败/错误/跳过数可查；环境限制单独列明。
- [ ] 结果绑定固定提交及源码/构建输入指纹；复用历史结果记录输入一致证据、原日志与版本，缺少依据则重跑。
- [ ] Surefire XML 与本次日志中的测试类和模块汇总交叉核对；不直接累计旧目录，不把聚焦与全量重复相加。
- [ ] 会被覆盖的 XML/日志及时快照或记录哈希与原始完整日志；后继结果不能冒充早期结果。
- [ ] Checkstyle/SpotBugs 实际数量、严重度与新增项逐条评估；不为通过修改门槛/删断言，也不把退出码 0 写成零告警。
- [ ] 全项目审阅按任务规范分别保留 Standards 与 Spec 结论；无效、过时或其他项目报告不计入当前结论。
- [ ] 服务交付/停止状态、数据保留和未执行的浏览器/外部验证准确登记；提交和整合保留其他工作区改动。

每次问题登记至少包括：基线、修复提交、触发条件、根因、受影响入口、回归命令/结果、证据、未验证范围、关闭依据。现有 [最终验证收据](audit/2026-11-10-http/verification.json) 是格式参考，历史测试数量和机器端口不构成永久要求。
