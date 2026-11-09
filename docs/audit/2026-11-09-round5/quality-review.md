# 第五轮静态分析与代码质量判断

固定产品提交：`bec670c7966b776609bec738b836edd93a00a085`，增量起点：`0114de8781bbbf60807d79af9952cbbcfec44812`。本轮对照 [第四轮评估](../2026-11-09-round4/quality-review.md)，所有继承条目保留具体 disposition、reason 和来源。

## 当前门禁与差量

| 工具 | 补修后最终结果（+08:00） | 原始日志 |
| --- | --- | --- |
| Spotless | 2026-11-10T01:14:47+08:00 PASS | `target/round5-delivery-spotless.log` |
| Checkstyle | 2026-11-10T01:15:02+08:00 PASS；379 warning / 0 error | `target/round5-delivery-checkstyle.log`、`target/checkstyle-result.xml` |
| SpotBugs | 2026-11-10T01:16:41+08:00 PASS；248 Medium / 0 High / 0 Low | `target/round5-delivery-spotbugs.log`、11 份模块 XML |

环境为 JDK 25，SpotBugs 保持 Max effort / Medium threshold。规则、排除项、门槛和编译目标均未改动。Checkstyle 的 379 条按路径、规则、消息匹配第四轮；SpotBugs 的 248 条按类型、类、字段、消息匹配第四轮。**新增提示 0，待人工评估 0**，详细清单见 [Checkstyle 账本](checkstyle-delivery-assessment.json) 与 [SpotBugs 账本](spotbugs-delivery-assessment.json)。

这些提示包括明确保留的复杂度、框架数据契约和维护成本，未统一归类为误报，也没有为了降低数字删规则或增加无业务意义的转发层。此前两项 Blog P3 possible Duplicated Code 仍是非阻断建议，当前行为一致；它们不进入本轮新增问题计数。

## 工程判断

本轮确认的问题主要是约束执行边界不一致：实体已有长度/必填规则，但部分控制器未触发；另一方面，直接对 partial 载荷做整实体校验会要求用户重传密码、不可变编码或未修改的名称。新增共享校验器复用生成实体的规则，仅校验写入白名单中的非 null 字段，完整表单则保留 `@Valid`。这使异常输入在持久化前失败，同时保留既有编辑行为。

规则是否正确也依赖元数据与实际 DDL 一致。配置值的 TEXT/可空语义和三个 VARCHAR 宽度得到纠正，邮件账户的六个实际必填列补齐；真实代码生成和数据库边界回归共同防止以后生成时丢失修复。没有改写历史迁移或截断合法用户输入。

前端条件字段需要独立的校验生命周期。Form 卸载解除规则和错误、作废旧异步结果，同时保留草稿；API 只处理所选渠道的字段，避免隐藏邮件变量阻挡仅短信保存。回归通过真实 React 组件和公开 axios 出口观察结果，未复制业务实现作为测试期望。

整体仍存在复杂服务和兼容表单层的维护成本。当前判断是已确认问题闭环，具备可复核的本地回归和构建证据；不把测试通过或提示已评估解释为绝对无缺陷。

## 证据有效性与限制

最终 reactor 于 **2026-11-10T01:14:17+08:00** 通过：140 套 / 915 项，0 失败、错误或跳过；实际安装并执行 generator，生成 34 个实体。隔离工作区的 140 份 Surefire XML 均与逐套日志、模块汇总一致，没有混入旧报告；初始 910 项及中间 911/913 项的 XML 已被本次运行覆盖，早期日志和收据保留。31 个改动文件同时匹配最终快照和固定 Git blob；Java/schema 与 reactor 前快照一致。

前端全部最终门禁在最后一次源码变更后执行：127 项通过，ESLint、Prettier、tsc、Next.js 生产构建通过，构建于 **23:36:55** 完成。依赖来自与应用锁文件及安装锁文件指纹一致的本地独立副本；外部 junction 构建失败和 npm 镜像 500 单独归类为环境失败，未掩盖或删除日志。

新增 Java 用例采用真实 MockMvc/Validator/Service/MyBatis/H2，证明绑定、校验时机及存量保留；未覆盖全应用鉴权和全局异常处理的 HTTP 组合。React/jsdom 是合成 DOM，不能替代真实浏览器布局、IME、滚轮、下载、跨标签验收。浏览器限制、真实外部系统和性能压测边界见 [续审记录](CONTINUATION.md) 与 [验证收据](verification.json)。

独立 Spec 初审补充的短信供应商启停遗漏已用公开 MockMvc 请求复现并修复。创建保留完整必填；partial 更新只校验提供的名称/类型，空白、未知 SPI、重名、空白密钥仍拒绝。供应商类型保存与历史读取归一化也经公开内存 SPI 回归关闭。最后一次完整 reactor 和静态检查均在补修后执行，前端源码与先前最终门禁指纹一致。
