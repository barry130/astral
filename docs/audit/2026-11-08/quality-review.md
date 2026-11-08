# 最终静态分析评估（接手审阅，2026-11-08 至 2026-11-09）

质量门禁使用 JDK 25，保留仓库既有规则/排除文件。SpotBugs Maven 插件从 4.9.3.0 升级至 4.9.8.2，原因是旧版本不支持 major 69，原失败不能算分析通过。根 POM 的 Spotless 换行固定为 UNIX，与 Checkstyle LF 和 schema generator 一致；生成器去掉最后一处冗余空行，实际生成后检查稳定。没有降低 Java 编译目标或扩大告警排除。

## 实际修复

- SpotBugs 报出的 `AdminToolProvider.SendEmailTool` 未使用投递结果是真实业务错误。无规则返回 REJECTED；无确认或部分确认返回 UNKNOWN 和已确认数量，提示查看通知日志；只有 EMAIL 投递有确认且无失败才报告成功。结果不反射 SMTP 异常原文。使用真实 publisher/mail service，数据库与 SMTP 为边界替身，4 项行为测试通过。
- `OpenAiCompatibleProvider` 用函数名 hash 作缺失ID的回退，使同名调用发生身份碰撞，后续计划校验拒绝。改用独立 UUID；保留提供方给出的ID，loopback公开 stream 回归证实3次调用身份不冲突。原 Math.abs(hashCode) 的溢出问题同时消失。
- 沿助手写操作发现旧实体回写：禁用用户会二次哈希旧密码并覆盖并发资料；配置值更新覆盖描述；停用提供方覆盖名称/凭据轮换。公开工具→实际服务/MyBatis的回归分别RED后GREEN；当前写入只含目标字段，检查写入结果，状态值及封禁理由校验、封禁会话撤销与既有管理入口一致。
- 删除6处不再使用的局部变量（保留锁与授权调用本身）、生成器未调用的私有重载；显式关闭包装 reader；两个 Path 父目录判空；两个业务异常的参数数组防御复制；去掉已由上文证明的冗余状态/空值条件。
- 全部5处 Java 通配导入改显式；两个 sequence service 实现文件移到匹配其既有 package 的 impl 目录，类名/FQCN不变。
- 最终复核去掉 BusinessException 非空 varargs 在长度读取之后的冗余判空，保留原有参数契约和防御复制。此前报告中的250项实际为249 Medium和1 High；该 High 随此修改消除，最终数量以收据为准。
- 已提交差量复核补齐两个公开行为边界：用户状态服务在同一事务内锁定按ID排序的未删管理员集合，再统计可用人数并白名单写入，助手与后台状态接口共用；邮件专用投递在收件人解析/额度预占/副作用之前限于 EMAIL，普通事件发布仍支持多渠道。两处均有业务断言 RED→GREEN，数据库锁使用真实 PostgreSQL 两请求交错验证。

## 剩余告警的评估原则与具体反证

完整逐条账本见 [spotbugs-assessment.json](spotbugs-assessment.json)，每条保留类型、位置、方法/字段、处理结论与理由。工具执行成功不等同于零告警。Checkstyle 数量及每条源码位置见 [checkstyle-assessment.json](checkstyle-assessment.json)。

- `BX_UNBOXING_IMMEDIATELY_REBOXED`：逐项核过条件操作数，均为先判空后给默认值，或数据库主键非空/排序默认值；是装箱成本提示，无NPE路径。未为消除告警把易读默认值表达式批量改写。
- `VO_VOLATILE_INCREMENT`：LoginFailureStore 的写操作处于 ConcurrentHashMap.compute 内，同key串行，volatile保留读侧可见性，不是裸并发自增。
- `HRS_REQUEST_PARAMETER_TO_HTTP_HEADER`：RequestIdFilter 写头前用完整正则 `[A-Za-z0-9_-]{1,64}` 验证，否则替换为UUID，CR/LF和超长值不能透传。
- `REC_CATCH_EXCEPTION`：LoginLogAspect 获取用户名使用反射 getMethod/invoke，反射确实可能失败；降级为空用户名并有说明，不影响登录业务。
- 两处 `DE_MIGHT_IGNORE`：GlobalExceptionHandler 的统计登记不能阻断500响应，主异常已记录；StorageConfigService 对损坏旧JSON只放弃合并，后续必填/凭据与provider options校验仍执行，不能因此把缺凭据配置当有效。
- `NP_BOOLEAN_RETURN_NULL`：MCP readOnlyHint 明确是三态；readOnlyOf 使用 `serverReadOnly && !Boolean.FALSE.equals(hint)`，null 表示远端未否定只读性，结合服务器只读配置判定，不拆箱。这与 AI_MODULE_DESIGN 的现有审批契约一致，不能误写成 null 一律进入高风险。
- 两处 `CT_CONSTRUCTOR_THROW`：McpToolExecutor 的构造器是private，创建失败不返回；Snowflake构造器主动拒绝越界节点ID，没有finalize实现、资源释放或权限检查被绕过的调用链。保留启动失败契约。
- `SF_SWITCH_NO_DEFAULT`：UploadOperationService 在switch前单独处理 REMOTE_CREATED；switch覆盖其余3个实际enum值（DEFINITE_REJECTED/UNKNOWN/NOT_SENT）。不通过空default掩盖未来新增状态。
- `RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE`：StorageFileService 在签发凭证读取后校验过userId，锁内幂等返回仍做所有权检查；重复检查无业务错误，保留可读的安全边界。
- `MS_EXPOSE_REP`：AiJson共享的是 Jackson 3 ObjectMapper。配置采用不可变mapper/builder约定，调用端用其读写/建节点；没有暴露可修改的Jackson2配置入口，不逐次复制昂贵mapper。
- `EI_EXPOSE_REP/2` 分别核对请求DTO、响应DTO、Spring依赖注入、内部传输对象。JSON输入每次反序列化新建；DTO响应不是持久缓存；服务/mapper/config注入是框架生命周期，不能靠clone替代；路由快照、CompiledTemplate、usage summary、NotifyEventRegistry实际生产构造参数已为 List.of/copyOf/toList；其余请求级集合/JSON节点与上传bytes用于同一受控调用，无已证实外部修改共享状态的链路。数据库状态以持久快照/锁为准。具体类别逐条记录，未宣称所有record深度不可变。

## 维护判断

Checkstyle 主要剩余为复杂度、声明顺序、早返回次数以及命名/表达式提示。HTTP地址策略、SQL AST、上传状态机与AI编排有较高维护成本；本轮已用公开回归保护关键契约，未通过拆空方法或关闭指标制造零告警。后续拆分应按业务职责与有价值测试推进。

最终原始XML为377条Checkstyle warning/0 error和248条SpotBugs Medium/0 High。最后两处修复新增两条NPath维护提示：NotifyPublisher筛选分支266、UserService.changeStatus输入验证与事务不变量1080。已逐段核对并以公开回归保护实际合同，不单为降低指标拆出空转发方法。剩余SpotBugs每条均匹配既有评估，未发现新的未归因条目。

构建与测试的最终数量、原始日志指纹、独占服务清理状态见 [verification.json](verification.json)。浏览器验证仍受原授权/扩展约束，DOM与HTTP夹具不计入真实浏览器验收。
