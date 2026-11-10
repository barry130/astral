# Spec 独立复审

审阅者 `/root/round6_spec` 只读执行，与 Standards 独立。最终固定范围 `dbb3e27ab4b35e30f58528dd26ff72f27afbdcf6...76ca550ffba652716321902777d0f6a5a31f8aae`，由首次 5 文件完整审阅与后继客户端头增量复核组成。

规格来源：[原全项目授权与边界](../2026-11-07/SPEC.md)、[本轮 HTTP SPEC](SPEC.md)。

**PASS，0 项发现。**

- 本轮 SPEC 第 5、9–12 行的真实应用组合与公开 HTTP 行为已覆盖：加密登录、Cookie/CSRF、登出、角色/用户/SMS 部分更新、拒绝后保留、凭据隐藏、权限授予撤回、APP 门禁及错误响应。撤回前后复用原会话，并确认会话仍有效，排除了将会话失效误当作权限拒绝的情况。
- 第 14 行最小修复成立：产品只为 `WebhookUrlPolicy` 标明注入构造器，未放宽策略。已核对源码构建 RED 中的 `NoSuchMethodException: WebhookUrlPolicy.<init>()`，及修复后 7 项 HTTP、10 项地址策略 GREEN。
- 第 16 行的隔离落实：随机 PostgreSQL schema、空 Redis DB 15 守卫、只清理本测试数据库、独立目录、环境白名单、排除 test-classes、等待 readiness，以及启动失败后的清理。
- Redis 版本说明有实际兼容失败依据，属于合理的运行文档修正；没有额外功能扩张。浏览器、外部投递与生产边界按第 18 行保持。
- 后继 `faa780bd...76ca550f` 只将 HTTP helper 调整为既有四头契约，没有改变产品行为、断言、权限、隔离与清理。

总计：Spec 0 项。复审未修改文件、运行构建或提交；最终全量执行和服务停止由 Lead 独立核验归档。
