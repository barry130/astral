# Astral 第五轮续审与修复

本轮承接用户“继续至整个项目全部完成，所有的自行决定，不需要问我”，沿用 [原授权与收口判据](../2026-11-07/SPEC.md)。原始全项目基线为 `a39a3f3039ce7deafc3f547a25cc0dc4635c111a`，增量起点为 `0114de8781bbbf60807d79af9952cbbcfec44812`。工作于独立目录 `F:/qtMusic/astral/target/audit-round5-worktree`，避免与主仓库并行修改和报告互相覆盖。

产品提交为 `9011277c0bbe25a937b9f4c2ac9b3bbff32a88be`、`3cfec3db5e3845823d37774a34cb00e5cd38083f`、`5bb42ffd1ab1ee12cfd1b0ee286219d007d6240c` 及最终补修 `bec670c7966b776609bec738b836edd93a00a085`；合计 31 个产品、测试、schema 与规范文件。确认问题、最终验证和双轴复审已闭环；全项目范围归因见 [coverage.md](coverage.md)，整体收口判断见 [PROJECT-COMPLETION.md](PROJECT-COMPLETION.md)。

## 已修复行为与回归

| 编号 / 级别 | 原行为与影响 | 修复后行为 | 公开证据 |
| --- | --- | --- | --- |
| R5-1 / P2 | 多个管理写入口未触发实体校验，超长/必填缺失到数据库才失败；直接整实体校验又会破坏 partial 表单。 | 完整创建/完整表单补 `@Valid`，partial 对实际可写且提供的非 null 字段复用 Bean Validator，业务码 320；省略密码、角色编码、菜单名称继续允许。 | `round5-role-create-red2/green2.log`、role-patch/dict/user/menu/config/mail-account/sms-provider 的 RED/GREEN；最终 `AdminWriteValidationTest` 16 项全部通过。 |
| R5-2 / P2 | schema 与实际 DDL 的类型、宽度和必填规则不一致，导致合法长值误拒或实际必填漏查。 | 配置值对齐可空 TEXT；字典描述 1024、用户电话 32、权限编码 128；邮件账户六个无默认 NOT NULL 列补 required，并实际生成实体。 | dict-width/phone-width/permission-width 的 RED/GREEN；配置长值与邮件必填公开持久化回归；当前 generator 实际生成 34 个实体。 |
| R5-3 / P2 | 条件分区卸载后仍保留必填规则/错误，阻挡保存；旧异步结果可能影响重新挂载。 | 卸载注销规则、错误和旧验证版本，保留草稿；重挂载及 StrictMode replay 恢复规则，同路径控件替换保持新规则。 | `round5-form-unmount-red/green.log` 与 `round5-form-final-green.log`；3 项新增真实 React/jsdom 生命周期回归。 |
| R5-4 / P2 | 仅选 SMS 时，隐藏邮件字段中的旧变量 JSON 仍被解析，阻止不使用这些字段的保存。 | 变量合并与解析收口到 API payload，只在 EMAIL/INAPP 被选中时执行；SMS-only 省略主题、正文、变量。 | `round5-sms-hidden-red/green.log`，通过公开 axios 请求观察；仍允许未启用渠道预配置，选中渲染渠道时仍严格校验。 |
| R5-S1 / P2（Spec 初审） | 短信供应商列表开关提交 `{enabled}`，后端仍强制要求名称和类型，启用/禁用均失败。 | 创建保持名称/类型必填；更新只对提供的名称/类型做空白、重名与 SPI 检查，其余字段仍按原白名单写入。 | `round5-sms-toggle-red.log` 复现名称缺失拒绝；`round5-sms-toggle-green.log` 12 项通过，含双向启停、单字段编辑、缺失目标和无效字段拒绝。 |
| R5-S2 / P2（Spec 中间复审） | 类型校验接受带空格/不同大小写的类型却保存原值，发送不能匹配；历史小写值还受默认语言影响。 | 新建与修改保存匹配的 SPI 注册类型；历史读取使用相同去空格/忽略大小写匹配，兼容 Unicode 大小写等价值且不依赖默认 Locale。 | `round5-sms-type-red/green.log`、`round5-sms-legacy-type-red/green.log`、`round5-sms-unicode-save-red/green.log`、`round5-sms-unicode-legacy-red/green.log`；公开持久化、内存 SPI 投递及 tr-TR 恢复回归，最终 16 项写入与 1 项短信 failover 测试通过。 |

R5-S1 是增量基线已有、初次产品提交仍遗漏的问题；不是本轮新引入回归。保留独立 [Spec 初审 FAIL](spec-review.md) 及其固定提交，中间提交 `3cfec3d` 关闭 R5-S1 后，独立 [Spec 中间复审](spec-intermediate-review.md) 继续发现 R5-S2；固定 `5bb42ff` 的 [归一化复审](spec-normalization-review.md) 又发现同组 Unicode 等价残余；最终用已注册 SPI 身份统一处理，R5-S1/R5-S2 均关闭。已有断言没有删除，两处旧测试仅适配 Controller 构造参数。

## 最终验证

`mvn install -Daudit.postgres.port=9684 -Daudit.redis.port=8549` 于 **2026-11-10T01:14:17+08:00** 通过：**140 套 / 915 项 Java 测试，0 失败、错误或跳过**。实际安装并执行当前 schema generator，生成 34 个实体；未跳过测试或代码生成。

最终 140 份 Surefire XML、逐套日志、各模块汇总已交叉核对，无旧报告混入。31 个最终源码指纹匹配 reactor 前快照、工作区及固定提交。补修前 910 项与中间 911/913 项日志和收据仍保留；其 XML 后来被最终运行覆盖，不能把当前 915 项 XML 算作早期证据。

前端最终源码自 2026-11-09 23:35 快照后未改变；**127 项测试通过**，ESLint、Prettier、tsc 与 Next.js 16 生产构建通过，47 页生成完成于 **2026-11-09 23:36:55 +08:00**。后续补修只涉及三份 Java 文件，前端门禁未无意义重跑。

补修后的 JDK 25 Spotless、Checkstyle、SpotBugs 均通过；Checkstyle **379 warning / 0 error**，SpotBugs **248 Medium / 0 High / 0 Low**。提示逐条评估、待评估 0；规则与门槛未更改。详细差量见 [quality-review.md](quality-review.md)，初始与最终账本分开保留。

## 两轴独立复审

| 轴 | 初审固定 9011277 | 最终固定 bec670c |
| --- | --- | --- |
| Standards | [PASS，0 规范违例 / 0 Fowler 建议](standards-review.md) | [PASS，0 未关闭确认问题 / 0 新建议](standards-delivery-review.md) |
| Spec | [FAIL，1 项 P2 短信启停遗漏](spec-review.md) | [PASS，R5-S1/R5-S2 关闭，0 剩余确认问题](spec-final-review.md) |

两条初审完整核对初次 30 个增量文件及必要调用方；最终补审核对三份后继变更、实际调用链、全部 31 份最终指纹与新验证证据。两个轴独立写报告，不把其中一轴通过当作另一轴通过。复审连接故障没有有效报告，已由新的独立 reviewer 完成；无效任务结果未计入结论。

## 失败分类与运行边界

- 首次 role GREEN 的夹具缺少 CREATE_TIME 自动填充，修正夹具后的 RED2/GREEN2 才是权威行为证据。
- 首次前端构建无法越过外部 node_modules junction；随后 npm ci 镜像连续 HTTP 500。只停止已确认归属的安装进程，残留依赖目录保留；以应用锁与安装锁指纹完全一致的本地副本建立独立 node_modules，最终前端全部门禁重新通过。未改 manifest、锁文件或全局 npm 设置。
- 本轮 Java 新增回归使用实际 DDL、MockMvc/Bean Validator/Service/MyBatis/H2；Standalone MVC 不含完整应用鉴权与全局异常处理。最终 reactor 另外执行既有 PostgreSQL 并发/启动回归，不把 H2 证据称为 PostgreSQL 锁证明。
- React/jsdom 是合成 DOM。真实浏览器布局、IME、滚轮、下载和跨标签仍未验收；扩展未就绪，遵守原 SPEC 禁止替代 CDP/Playwright/bootstrap/诊断的约束。
- 没有真实 SMTP/SMS/模型/云存储投递、生产数据库操作、性能压测、远程 push/PR 或部署。兄弟项目没有修改。

最终所有的 PostgreSQL 9684 与 Redis 8549 已核实 executable、命令行、PID、配置/数据目录及 loopback 监听归属后停止；**2026-11-10T01:15:17.7670360+08:00** 复核剩余监听和父进程均为 0。既有 PostgreSQL 夹具目录按此前删除拒绝保留，没有重新删除；隔离工作区和日志保留供追溯。

本轮以本地产品提交和单独审计文档提交交付。主仓库两处并行 QtPackHeaderProbe 编辑被排除；最终整合前后检查内容哈希、Git 差异和索引以保持这些编辑。原始日志绝对根目录、文件指纹、两轴报告、清理与本地整合策略保存在 [verification.json](verification.json)。
