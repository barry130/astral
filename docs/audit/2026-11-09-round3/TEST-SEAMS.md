# 第三轮公开回归边界

用户在 `587b4ae215375dd745dde0630ddbd2de5c94efbf` 交付后要求“还有没有其他问题，有的话继续”。沿用 [原授权](../2026-11-07/SPEC.md) 的直接修复、仅本地提交及外部副作用隔离约束；历史报告保持原样。

- 权限关联完整性：公开 `RolePermissionController` / `RoleController` / `PermissionController` → 实际 Service、MyBatis 与 Spring 事务；通过公开查询观察缺失权限拒绝、已关联权限不能删除。真实 PostgreSQL 独占 schema 与 JDBC 时序屏障验证分配/删除交错；不调用私有方法或断言 SQL wrapper 形状。
- 默认 App 权限同步：公开 `AppUserRoleService.syncPermissions` 与权限删除入口，真实事务和数据库；检查提交后的角色权限关联，不连接真实账号或授权库。
- 通知配置：公开通知规则与短信模板 Controller/Service → 真实 MyBatis 与 Spring 事务，通过公开详情验证有效配置、失败回滚及更新/删除竞争后的终态。外部投递不执行。
- 通知规则的并发删除以公开规则查询及渠道/收件人 Mapper 的只读查询观察三表终态；父规则删除后无法从详情接口看见孤儿收件人，因此持久层查询也是本次明确的观察边界。JDBC 仅为两个真实事务设置时序屏障和观察 PostgreSQL 锁等待，不伪造查询结果。
- 短信模板省略/空白字段通过公开 POST/PUT/GET 验证；公开发送服务的缓存语义通过内存 SMS SPI 记录实际收到的供应商模板编码，不执行真实短信或网络请求。

这些接缝延续上一轮约定的公开服务/控制器与数据库边界，已由用户持续直接修复授权覆盖。使用 tdd 技能的行为 RED → 最小修复 → GREEN；编译或夹具错误不计行为 RED。H2 用于持久化语义，真实 PostgreSQL 用于并发锁证据，均不称 HTTP、浏览器或生产验收。
