# Astral 审阅与修复交接

来源 DSH `session-c9c6458f-4334-477e-a37f-4deb65f66d9f`；固定基线 `a39a3f3039ce7deafc3f547a25cc0dc4635c111a`。沿用[原授权与范围](../2026-11-07/SPEC.md)：完整审阅并直接修复，只本地提交，不推送。继承原会话工作区后继续修复，未撤销已有存储修复或替换脱敏占位符。

## 当前交付状态

本次授权范围内的审阅、修复、本地提交及双轴复核已完成。最终产品提交为 `a9f044067fcf05c5cd914b9202c784a7eb67d587`：828项Java、123项管理前端、84项博客、15项Worker/Telegram、2项打包发布测试通过；生产构建与质量报告完成。Standards 已确认规范问题无未关闭项，保留两项P3重复代码维护判断；Spec 已确认实现问题无未关闭项。旧领域报告保留其记录时点，正文中的“待修/未执行”不能作为当前状态；本文件、两轴顶部最终结论和验证收据共同给出当前交付判断。

工程判断：主要风险集中在权限与敏感信息边界、整行旧值回写、跨请求状态机、失败后恢复和构建/数据库启动一致性。本轮为这些路径加入显式写入范围、持久账本、数据库锁与可恢复的界面状态。前端沿用现有设计令牌和组件，改进表单、错误、加载、弹窗与编辑器行为，没有以重新换 UI 库代替业务修复。大型服务仍有继续拆分的维护空间；没有把审美偏好或未证明的候选风险包装成已发生漏洞。

## 领域覆盖与归因

[当前文件清单](inventory.json)包含1421个源码、配置、文档与测试路径的身份/指纹（不含审阅报告自身）；这是盘点，不是逐文件独立重读声明。真实运行配置只记路径，不读取内容。继承的完整阅读、补读和本次定向因果链复核分开归因：

| 领域 | 原始与补充审阅记录 | 本次收口证据 |
|---|---|---|
| common / dao / schema / log / monitor / sequence / server | [平台域](../2026-11-07/backend-platform.md)、[core首轮67/188](../2026-11-07/core-platform-current.md)与[余121补读](../2026-11-07/core-platform-remaining-current.md) | schema真实生成、序列边界/异步、统计与启动专项、全reactor |
| auth / system | [安全域](../2026-11-07/backend-security.md)、[auth/system恢复](../2026-11-07/auth-system-recovery-current.md)与[余61补读](../2026-11-07/auth-system-remaining-current.md) | 实际权限/日志代理、H2/PG事务、独占Redis投递限额、App已读 |
| storage | [后端](../2026-11-07/backend-storage.md)、[前端](../2026-11-07/frontend-storage.md) | storage实际PG26项、传输夹具、重放/恢复/额度和前端上传回归 |
| qt / feedback / cms / plugin | [业务域](../2026-11-07/backend-domains.md)及最终Spec E/G节 | QT状态竞争/签到/版本、CMS树锁与可见性、插件禁用MockMvc、两次Boot context |
| AI | [初始范围](../2026-11-07/backend-ai-coverage.md)、[读取恢复](../2026-11-07/backend-ai-read-recovery.md)与[分片补读](../2026-11-07/backend-ai-shards/) | 实际请求/审批/计费事务、SQL AST、受控HTTP/TLS、发布并发7、知识PG11、管理DOM |
| 管理前端 | 存储报告、[Form](../2026-11-07/frontend-form-types-current.md)、[调用方](../2026-11-07/frontend-form-callers-current.md)、[ToolPanel](../2026-11-07/frontend-toolpanel-format-current.md)；其余入口按本次双轴定向审阅 | 123项Node/真实React合成DOM，完整ESLint/Prettier/tsc/生产build |
| Blog | [基线功能](../2026-11-07/blog-baseline-parity.md)、[Standards最终归因](../2026-11-07/blog-standards-final-1fa5ace8.md)、[Spec最终归因](../2026-11-07/blog-spec-final-1fa5ace8.md) | 原118源/174hunk组合阅读；115源同hash，Dockerfile与两个驱动变化另核；84测试、隔离生产build、HTTP公开URL |
| Worker / Telegram / deploy / CI / scripts | 最终Spec E/G节及当前源差异 | worker.fetch合成边界+本地gateway15、fake Docker/临时Git2、nginx两布局语法、博客公开域名产物 |
| 同类产品参考 | [原参考](../2026-11-07/mature-project-comparison-current.md)、[补充](../2026-11-07/mature-project-comparison-supplement-current.md) | 保留动作级授权、表单异步状态、内容清洗和可恢复失败原则；不引入不兼容架构 |

未把模式搜索、文件指纹或单项测试升级为全文件语义证明；也没有把旧报告中的NOTREAD自动改写为本轮独立FULL。每个范围领域均有审阅记录，阅读深度以原清单和最终报告明示为准。

## 已确认问题关闭账本

| 问题组（沿用报告ID） | 修复后行为 | 主要回归 |
|---|---|---|
| S1–S6 / AS账户、Token / QT-USER-F1 | Token仅指纹；禁用/删除即时限制会话；资料/改密/状态白名单更新、凭据CAS，不撤销并发封禁或恢复旧密码 | Auth/Profile/Token、QtUserConcurrentUpdate、QtAdminSafety |
| S1–S5 / R9 MCP | headers加密、掩码保留/清空、大小写兼容；日志不复制执行配置凭据；错误不反射原JSON | MCP持久化、真实日志AOP与计划快照 |
| SQL-AST / HTTP-TR | SQL按AST校验来源表列/纯函数，保留安全CTE；HTTP/MCP受审核DNS固定、TLS原主机与总预算、响应大小约束 | SQLReadPolicy42、ToolHttpTransport、Provider loopback/TLS |
| AI审批 A1–A4 / LIFE F1–F8 | 请求租约、状态CAS和工具结果持久身份；审批者/原请求者权限独立；取消终态和实际usage保留，未发送释放 | AiApprovalLifecycle30、AiUsagePersistence |
| AI计费与人工核对 | 分阶段幂等、价格/周期快照、费用按币种汇总、CNY限额；UNKNOWN保留待核，不自动重发工具 | PG账本、真实权限/JSON验证、历史范围/必填DOM |
| AI-REF-F1 / G-N4 | 模型/路由有引用时拒绝删除；草稿更新只SET提供字段；发布/校验/绑定共用事务锁，发布后不能改写 | AiAgentConcurrency7（真实PG公开入口） |
| 知识 C16 / F-N2 / G-N3 / H-N1 | 正文400k拒绝边界、分页按需取正文、索引候选有界；持久任务进度、取消、重试；旧任务仅首次升级转失败 | AiKnowledgePersistence11，含实际initializer两次+08008 |
| H-N2 / F-N1/F-N3前端 | 切源/版本与弹窗旧响应不覆盖当前状态；取消/重试期间刷新不能永久占用busy；聊天加载保留草稿 | React/axios合成DOM，全前端123 |
| 存储 ST-REV-F1–F11 | 远端结果未知保留身份；独占claim、持久计数、旧票据占位/清理、目录锁；3个前端上传worker及服务端大小权威保留 | StoragePersistence26、UploadOperation/Quota/Gateway、前端上传 |
| 模板 / 通知 / App已读 | ID保持与取消渠道、显式NULL、提交后缓存；投递错误逐渠道隔离、Redis原子额度；本人点对点read_time持久化，广播仍客户端已读 | Template/NotifyRule/NotifyPublisher、AppNoticeReadPersistence |
| QT / CMS / plugin E项 | 版本可见性/缓存副本、签到7/30/365、包头8KiB/逐跳策略；CMS祖先可见性、树竞争、限流硬上限；插件状态/ID与路径门禁 | QT、CMS PG、PluginIsolation MockMvc、PluginStartupContext |
| MON-STD / CFG | 统计关闭不再缺Bean；SSE异步dispatch不累积计数；Locale.ROOT；未知指标不伪造健康；日志层级/PG示例纠正 | Monitor4、实际指标/告警测试、配置核对 |
| 历史迁移 / 最终baseline竞态 | 历史校验和保留，精确callback补前置表/条件种子；按schema加锁覆盖空库与baseline决策 | Fresh6、NoticeCompatibility7、真实Boot启动1 |
| 发布/归档/博客 E-D | 排除真实凭据；三镜像完整不可变版本后提升latest；nginx片段正确作用域；构建时校验SITE/ADMIN URL | 临时Git/fake Docker2、nginx -t、生产HTTP URL |
| schema格式输出 | 生成实体主键/填充/枚举与metadata一致；去掉末尾额外空行使生成后Spotless稳定 | 当前codegen实际生成34实体、Spotless |
| 最终静态复核 / 管理助手写入口 | 发信按投递结果报告，无规则拒绝、失败或部分成功为UNKNOWN并提示人工核对；同名无ID模型流调用生成独立身份；用户状态不二次哈希密码，配置值/提供方启停仅写指定字段 | AdminToolEmailOutcome6、OpenAiCompatibleProviderTransport5、AdminToolWritePersistence2、AiProviderPersistence7，含公开入口RED→GREEN |
| 最终已提交复审 I-N1 / I-N2 | 助手和后台状态入口在同一事务内锁定共同管理员集合、重读可用人数再白名单写入；邮件专用动作在解析收件人/预占额度/投递前仅允许EMAIL，普通事件仍支持多渠道 | AdminToolUserStatusConcurrency2（工具vs工具/工具vsController真实PG）；混合三渠道及仅非邮件公开工具回归，24项专项通过 |

各项日志中的测试有重叠，不能逐行相加为总测试数。最初夹具初始化失败、旧mock返回0和中间构建失败保留在target日志，不算业务RED或最终成功；最终门禁须使用最新完整命令结果。

## 验证与运行边界

- JDK25、Maven3.9.6、Node24。Java最终命令执行当前schema generator、完整reactor测试和install；未使用skipCodeGen。PostgreSQL16.4和Redis均为本任务独占loopback实例，真实生产库未接触。
- `target/takeover-final-reactor.log`：828项、133套件，零失败/错误/跳过，2026-11-09 01:11:43完成；替代较早816/824项日志。`takeover-final-quality.log`于01:13:03成功，Spotless通过；Checkstyle377条维护告警/0错误、SpotBugs248条Medium/0条High均有[评估账本](quality-review.md)，不宣称零告警。原250项实为249 Medium+1 High，冗余判空修复后消除High，再由共享状态服务消除1条装箱提示；最终数量以实际XML为准。质量结果与日志指纹写入[验证收据](verification.json)。
- `target/takeover-front-complete-tests.log`：123；`takeover-blog-final-tests.log`：84；`takeover-workers-final.log`：15；`takeover-pack-release-final.log`：2。前端/博客生产构建、博客canonical/sitemap/robots与编译后admin链接检查、两种nginx布局语法检查分别记录。
- 回归使用的公开接口、事务/权限边界与外部替身详见[TEST-SEAMS](../2026-11-07/TEST-SEAMS.md)。知识worker恢复是重建worker对象并继续数据库游标，未运行进程kill；失败夹具在读取文档后抛出，未声称已测试删chunks后失败。DOM任务用例没有等定时轮询。迁移启动是实际关键组件图，未把它称完整生产部署。
- 浏览器扩展未就绪且原会话禁止后备CDP/Playwright，因此没有本轮真实浏览器验收。真实滚轮/IME、物理布局、cookie顺序、下载、跨tab和生产网络仍未核验。合成DOM和loopbackHTTP不替代这些项目。
- 未发送邮件/短信、未调用真实模型或云存储、未推送代码/镜像/PR、未发布或修改生产数据。Worker权限撤销存在文档明确的最多300秒缓存窗口，不能宣称跨PoP秒撤销。
- 本次独占 PostgreSQL PID22308/端口9684 与 Redis PID31896/端口8549 已核实归属并关闭，端口不再监听。包含临时目录删除的组合命令被执行策略拒绝；随后仅停止服务成功，未重试删除。临时PG目录 `C:/Users/canace/AppData/Local/Temp/astral-audit-pg-c6a3db9b135140e3a8952e040591c826` 保留；仓库target内的日志和运行时保留供追溯。未关闭无关服务或浏览器。
- UNKNOWN历史用量缺价格/额度快照时拒绝自动核对。没有编造历史换汇或补写已产生费用；基线成本列仅初始化零，非CNY污染是中间未提交实现问题，不据此伪造生产数据迁移。

## 候选与非阻断维护建议

历史报告中的QT换邮箱二次验证策略、CMS浏览量写放大、异步统计投递语义、Snowflake节点配置/状态保留等依赖产品或部署契约，未确认为本轮可复现缺陷。原“存储完全无超时”等已被实际代码反证，不能继续计入待修。Blog两项P3 possible duplication是维护判断，当前行为一致，不强行抽象。它们和明确环境限制均不冒充已修或已验证。

## 本地提交与双轴最终结论

| 本地提交 | 内容 |
|---|---|
| `e57bb02` | 后端状态、授权、事务与恢复修复，回归及Java规范化 |
| `07a48e8` | 管理前端/博客交互、反馈、会话与编辑器修复 |
| `ccae720` | 打包发布、标签、Worker/Telegram及部署配置 |
| `07c3e40` | 领域审阅记录、覆盖与验证证据归档 |
| `7be9a82` | 异常参数冗余判空清理、CMS/UI文档整理 |
| `a9f0440` | 最后管理员并发状态保护、助手邮件渠道边界及回归 |

以上均只在本地。最终报告/指纹另作docs提交，产品版本固定于a9f0440；报告体积与Java格式化占本轮大量diff，不将所有行变更算作行为修复。

| 复核轴 | 最终判定 | 范围 |
|---|---|---|
| [Standards](standards-review.md) | 已确认规范问题未关闭0；保留2项P3 possible Duplicated Code维护判断 | 既有领域覆盖+固定提交差量+最后8文件窄复核，未宣称本次独立全文重读全仓 |
| [Spec](spec-review.md) | PASS；已确认实现问题未关闭0 | 原范围内缺陷闭环；I-N1/I-N2补充修复及a9f0440完整门禁已独立核验 |

标准与需求两轴独立保留，不以一轴结果掩盖另一轴。真实浏览器及生产外部系统边界如上，不将本地通过扩大成生产验收结论。
