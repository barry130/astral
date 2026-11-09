# 第五轮覆盖与全项目清单核对

原始全项目基线：`a39a3f3039ce7deafc3f547a25cc0dc4635c111a`。本轮增量起点：`0114de8781bbbf60807d79af9952cbbcfec44812`；最终产品提交：`bec670c7966b776609bec738b836edd93a00a085`（含初次产品 `9011277`），31 个产品、测试与规范文件。

本轮完整阅读增量文件和必要调用方，核对管理写入口、实际 Flyway DDL、生成规则与条件表单链路。全项目其余语义覆盖沿用各领域报告和逐轮固定提交复审；清单与哈希核对不等于重新逐行阅读全仓。

## 本轮定向覆盖

| 区域 | 核对内容 | 修复或证据边界 |
| --- | --- | --- |
| 角色、用户、菜单、配置、字典写入口 | 完整创建、完整表单更新和 partial 更新的差异；实际可写字段、必填及列宽；权限和缓存调用顺序 | 完整请求补 `@Valid`；partial 使用实际 Bean Validator 校验白名单中非 null 字段，保留省略密码、角色编码、拖拽名称的语义 |
| 邮件账户、短信供应商入口 | 创建必填、编辑保留凭据、元数据长度、启停、类型归一化、实际投递选择、凭据脱敏与缓存方法 | 校验发生在写入前，独立 Spec 发现的仅 enabled 启停已补修；未真实调用 SMTP/SMS，已有凭据与状态行为由原回归保留 |
| `EntityPatchValidator.java` | 生成实体约束复用、字段白名单、业务码 320、验证异常 | 不以字符串截断或放宽数据库限制代替校验；依赖公开 Controller/持久化回归验证 |
| 五组 schema / 生成实体 | 对照 `V20260914001__init.sql` 和后续迁移，核对 TEXT、列宽与 NOT NULL/default | 配置值可空 TEXT；字典描述 1024、电话 32、权限编码 128；邮件六个无默认必填列；实际 generator 生成 34 个实体，历史 DDL 未改 |
| Form 条件分区生命周期 | 实际 React 挂载、卸载、StrictMode effect 重放、同路径控件替换、异步验证返回 | 卸载清除规则/错误/旧验证身份，保留草稿；3 个新增 jsdom 用例。未进行真实浏览器布局或 IME 验收 |
| 合并通知模板页面及 API | 页面保存调用、所选渠道、变量归一化、隐藏邮件字段、axios 实际请求 | 仅 SMS 时忽略隐藏邮件字段；选择 EMAIL/INAPP 才处理其变量，仍允许独立预配置渠道 |
| 新增与适配回归 | `AdminWriteValidationTest` 16 项；两处旧测试仅适配 Controller 构造参数；前端 4 项新增 | Java 使用真实 MockMvc/Validator/Service/MyBatis/H2 与实际 CREATE TABLE；原有断言保留。Standalone MVC 不代表全应用鉴权或全局异常响应验收 |
| 规范 | `docs/CODING_GUIDE.md`、`docs/UI_GUIDE.md` | 明确 partial 校验和条件分区卸载合同，与实际公开行为保持一致 |

31 个路径及固定 SHA256 完整列在 [verification.json](verification.json) 的 `sourceSnapshot.files` 中；两条独立初审及后继补审分别归因到各自固定增量。

## 全项目范围与继承归因

[最终清单核对](inventory-delivery-reconciliation.json) 包含 **1,430 个自有文件路径**，对比首轮 1,421 个新增 9 个、移除 0 个。9 个新增路径中，6 个来自第二至第四轮回归，3 个来自本轮。31 个本轮路径、47 个其他增量路径、1,352 个继承路径分别标记归因。`deploy/prod.env` 继续只记录路径，不读取或归档真实配置内容。

| 全项目领域 | 语义覆盖记录 | 当前验证归因 |
| --- | --- | --- |
| common / dao / schema / log / monitor / sequence / server | [全项目领域矩阵](../2026-11-08/TAKEOVER.md)所链接的 platform/core 全读与补读 | 本轮完整 11 模块 reactor 及静态检查；生成器实际运行 |
| auth / system | [全项目领域矩阵](../2026-11-08/TAKEOVER.md)、[第二轮](../2026-11-09/CONTINUATION.md)、[第三轮](../2026-11-09-round3/CONTINUATION.md)、[第四轮](../2026-11-09-round4/CONTINUATION.md)及本轮增量 | 权限/角色锁、通知规则、模板、写入校验的既有与新增测试均进入本轮 reactor |
| AI / storage / qt / feedback / cms / plugin | [全项目领域矩阵与关闭账本](../2026-11-08/TAKEOVER.md)，第二、三轮权限与工具补充 | 本轮插件模块 631 项测试；未重复声称所有域逐行重读或真实外部服务验收 |
| 管理前端 | 首轮 Form/调用方/存储/AI 报告及本轮 Form/模板增量 | 本轮 127 项 Node/React/jsdom 回归、ESLint、Prettier、tsc 与生产构建全部执行 |
| Blog | [基线功能核对](../2026-11-07/blog-baseline-parity.md)、首轮两轴最终归因及 [交接](../2026-11-08/TAKEOVER.md) | 源码未改；沿用 84 项测试、隔离生产构建与公开 URL 核对。本轮未重跑 |
| Cloudflare / Telegram / deploy / CI / scripts | 首轮领域记录及 [交接](../2026-11-08/TAKEOVER.md) | 源码未改；沿用 15 项 Worker/Telegram、2 项打包发布测试、两种 nginx 布局检查。本轮未重跑 |
| 同类项目参考 | [原参考](../2026-11-07/mature-project-comparison-current.md)、[补充参考](../2026-11-07/mature-project-comparison-supplement-current.md) | 继承已记录的授权边界、恢复状态和表单原则；本轮无新增外部取材 |

历史报告中的中间失败、待修状态保留原记录时点；当前是否关闭以各轮最终结论、固定产品与证据为准。两项 Blog P3 重复代码建议仍是非阻断维护判断，没有确认为行为缺陷。

## 范围边界

原仓库两处并行 `QtPackHeaderProbe` 修改没有进入隔离工作区、产品提交或本轮验证。兄弟项目、生产数据、真实云端投递和远程发布均不在本轮操作范围。浏览器扩展未就绪，原 SPEC 禁止替代 CDP/Playwright/bootstrap/诊断；未进行这些操作，也未把合成 DOM 标为 browser GREEN。

本轮原始日志、XML 与运行收据保留在 `F:/qtMusic/astral/target/audit-round5-worktree/target/` 及对应模块的 `target/` 中。该隔离工作区保留，避免与主仓库并行构建互相覆盖证据。
