# Astral 第四轮独立 Spec 复审

审阅日期：2026-11-09；仓库：`F:/qtMusic/astral`。本报告仅为 Spec 轴，未读取或合并 Standards 复审报告。

## 固定范围

- 增量基线：`9679cec2082d905df96186748c7413d3b72c4b8b`。
- 产品提交：`bc16a9fe981a346c4a2f57b56b8fbd0d2268c755`，提交说明为 `fix: enforce validation and field clearing in message templates`。
- 原始全项目基线：`a39a3f3039ce7deafc3f547a25cc0dc4635c111a`。
- 三个引用均独立解析；增量三点差量非空：9 个文件，343 行新增、22 行删除。

实际整读全部 9 个增量文件及全部差量，并核对必要的公开 Controller、单渠道服务、短信读取/缓存、事件注册、生成约束、已提交 Flyway DDL、合并表单与 API 字段传输。依据为完整 [原始 SPEC](F:/qtMusic/astral/docs/audit/2026-11-07/SPEC.md)、持续续审授权及 [本轮测试边界](F:/qtMusic/astral/docs/audit/2026-11-09-round4/TEST-SEAMS.md)。其他领域覆盖继承 [首轮记录](F:/qtMusic/astral/docs/audit/2026-11-08/verification.json) 和后续续审记录；本轮没有重新深读原始基线以来的全部项目源码。

## 发现与结论

**Spec PASS：本轮固定增量范围内，0 个剩余确认问题。** 未确认要求缺失、错误实现或无关扩张。

- 所选 SMS 的供应商模板编码缺失/空白返回 `SMS015`，审核状态越过 0–3 返回 `SMS016`。未选渠道的输入不参与服务端校验。渠道预配置保持 [AGENTS.md:162](F:/qtMusic/astral/AGENTS.md:162) 的既有合同；没有将通知规则的必达渠道限制复制到模板保存。
- 合并入口的完整表单采用显式白名单更新，null 清空变量、备注及短信可选字段，改事件后清除旧声明；保留 ID，更新时间由服务端写入。单渠道 partial 更新的省略字段保留语义仍在，事务及提交后缓存失效保留。
- 真实 Validator 在所选渠道行完成变量归一化后检查生成实体约束。邮件名称 64 与已提交 DDL 一致，schema 和实际生成实体同步；单渠道 POST/PUT 的 `@Valid` 提前拦截超长请求，`@Size` 仍允许 partial 省略字段。

公开服务、持久化读取和内存短信 SPI 的新增断言支持三组闭合；既有变量单元断言保留。对应 [SPEC:20](F:/qtMusic/astral/docs/audit/2026-11-07/SPEC.md:20) 的“schema与迁移一致”及 [SPEC:35](F:/qtMusic/astral/docs/audit/2026-11-07/SPEC.md:35) 的“具备对应回归或明确的可核验验证”。通过结论仅覆盖固定增量，不表示全仓或全部用户体验均无问题。

## 独立证据核对

所有运行均由父任务执行；本复审仅读取证据，没有运行构建或测试。

| 证据 | 独立核对结果 |
| --- | --- |
| [构建前源码快照](F:/qtMusic/astral/target/round4-source-before-final.json) | 9/9 SHA256 同时匹配快照、工作区和固定产品 Git blob。 |
| [最终 reactor 原始日志](F:/qtMusic/astral/target/round4-final-reactor.log) | 保存哈希与字节数匹配；独立汇总 139 个不同套名、899 项，0 失败、错误或跳过；`BUILD SUCCESS`，完成于 20:16:07 +08:00。逐模块汇总与逐套统计一致。 |
| [聚焦原始日志](F:/qtMusic/astral/target/round4-focused-green.log) | 保存哈希匹配；4 套 / 52 项，全部在最终 reactor 中，0 失败、错误或跳过；完成于 20:06:35。 |
| 逐行为 RED/GREEN | 实际失败分别为缺失校验、旧可选值未清空或超长字段进入数据库。邮件 `red2` 确认 POST/PUT 均缺校验；首次 POST 的 ID fill 故障不算产品 RED。首次 GREEN 的分页 total 夹具问题保留，`green2` 通过公开 records 及存量读取保留无写入断言。 |
| 生成与静态门禁 | reactor 明确安装当前 generator 并实际生成 34 个实体；生成后的邮件名称约束为 64，未跳过代码生成。安装 JAR、Spotless/Checkstyle/SpotBugs 日志哈希匹配，三个原始日志均成功；最后完成于 20:22:31。 |

测试 XML 的保存边界存在后继覆盖：检查时 139 份中仅 **55 份**保留 [门禁记录](F:/qtMusic/astral/target/round4-final-gates.json) 的原哈希、字节数及逐套计数，包含本轮系统回归。`astral-plugin` 的 **84 份**在 **20:39:40–20:40:13 +08:00** 被后继运行覆盖；其中 8 套当前计数也与原日志不同，包括跳过及 QtPackHeaderProbeHttpTest 项数变化。因此不能独立确认“当前 139 份 XML 全部匹配第四轮”，也不将后继 XML 的跳过计入第四轮。原始完整日志、当时保存的清单和未覆盖报告分别保留其证据价值。

另两份 2026-11-08 的旧 AI XML 哈希未变、当前源文件已缺失且不在本轮套名列表中，排除依据成立。上述覆盖差异已向父任务报告；本复审没有重写测试报告、清单或测试结果。

## 限制与归属

H2/MyBatis/Spring 证明持久化、回滚和提交时序；内存 SMS SPI 证明映射读取与签名回退。邮件 standalone MockMvc 使用默认异常解析器，证明校验在写入前触发，不等于完整应用 HTTP/body-code/鉴权验收。原 reactor 包含既有 PostgreSQL 回归，本轮没有新增性能或真实外部投递验收。

本轮无前端产品差量；ESLint/Prettier 日志哈希已核对，但空 ESLint 日志本身不能独立证明退出码，退出码仍为父任务记录。没有新增类型检查、生产构建或浏览器 GREEN。相邻前端调用方的遗留输入线索已交父任务后续核验，未作为本轮新增缺陷或浏览器结果。

浏览器扩展限制继续遵守 [SPEC:46](F:/qtMusic/astral/docs/audit/2026-11-07/SPEC.md:46)。没有浏览器操作、真实 SMS/SMTP/模型请求、生产库或兄弟项目操作、远程推送、提交或服务操作。父任务 [清理收据](F:/qtMusic/astral/target/round4-service-cleanup.json) 记录本轮服务于 20:38:26 停止，复审只读取该收据。

当前未提交的 QtPackHeaderProbe.java、QtPackHeaderProbeHttpTest.java 不由本复审产生，保留并排除于固定产品差量之外。本复审只新增本报告；保存后读回 UTF-8，并再次核对 9 个源码快照。
