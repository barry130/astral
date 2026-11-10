# HTTP 联调补充完成记录

本轮已完成完整应用 HTTP 联调、确认问题修复、最终验证和两轴独立复审。固定产品提交为 `76ca550ffba652716321902777d0f6a5a31f8aae`，承接前五轮结果；本轮没有未关闭的确认缺陷。

发现并修复了会导致整个后端无法启动的 `WebhookUrlPolicy` 构造注入问题，并补齐 Redis 6.0+ 的运行文档要求。新测试实际启动应用入口和 Tomcat，以 PostgreSQL、Redis 以及公开 HTTP 接口验证登录会话、CSRF、部分更新、权限授予撤回、APP 身份隔离和错误响应。详细因果链、失败证据和场景见 [续审记录](CONTINUATION.md)。

## 最终验证

| 检查 | 结果与归因 |
| --- | --- |
| 完整 Maven reactor | 最终提交运行 `mvn install -Daudit.postgres.port=9685 -Daudit.redis.port=8550`，实际执行 schema generator；141 套、922 项测试，失败 0、错误 0、跳过 0。 |
| 新增实际 HTTP 场景 | 7 项全部通过，包含真实应用启动；最终测试日志没有未预期 ERROR。 |
| Webhook 地址策略 | 原有 10 项全部通过，地址校验行为没有放宽。 |
| Spotless / Checkstyle | 最终提交检查通过；Checkstyle 379 warning、0 error，与已评估基线匹配，无新增或待评估项。 |
| SpotBugs | 本轮实际运行，248 Medium、0 High，与已评估基线一致。分析固定于 `faa780b`；后继 `76ca550` 仅改测试 helper 的客户端头，全部生产源码及构建输入指纹一致，因此沿用该生产分析，不宣称重复运行。 |
| 管理前端 | 230 个受控文件与第五轮最终工作区逐字节一致；沿用 127 项测试、类型检查和生产构建，ESLint/Prettier 本轮重跑通过。 |
| Standards | [最终 PASS](standards-review.md)，历史 1 项 P3 客户端头问题已关闭，open 0。 |
| Spec | [最终 PASS](spec-review.md)，0 项发现。 |
| 服务清理 | 12 轮应用子进程均已停止；HTTP 随机 schema 剩余 0，Redis DB 15 剩余键 0；专用 PostgreSQL/Redis 及 PostgreSQL 子进程已停止，两个监听端口剩余 0。夹具目录、日志和工作区保留。 |

[verification.json](verification.json) 保存固定提交、源文件 SHA-256、每套测试 XML 指纹、命令退出状态、前端/SpotBugs 归因、静态检查逐项评估和清理收据。原始证据位于保留工作区 `C:/Users/canace/.codex/worktrees/astral-http-acceptance/astral/target/round6-http`；应用日志在对应 `astral-server/target/http-acceptance-*` 下。

## 交付边界

本轮完成的是可执行的本地 HTTP 补充验证与修复。真实浏览器仍因扩展未就绪而未执行，遵守 [原 SPEC](../2026-11-07/SPEC.md) 的禁止替代浏览器操作决定；不将 HTTP 的 Cookie/CSRF 断言称为浏览器行为验证。未执行生产部署、真实外部投递、远程 push 或 PR。

本地整合使用快进方式，主工作区原有两处 QtPackHeaderProbe 并行修改不纳入本轮。整合脚本在执行前后核对这两处内容、diff、索引和状态；实际整合收据保留于上述证据目录的 `local-integration.json`。
