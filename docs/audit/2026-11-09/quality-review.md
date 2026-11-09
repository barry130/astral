# 继续审阅：最终静态分析评估

产品提交：`21a24750ea35c276293370bd2b1d7fa510957c06`。增量起点：`71ad84e416cdaab58cac18c81bfb690174ae0b72`。该提交补齐 `80601e13` 复核发现的 S2-02 锁后存在性残余。

JDK 25 下运行 `mvn spotless:check checkstyle:checkstyle-aggregate spotbugs:spotbugs`，2026-11-09 15:24:20 完成，`BUILD SUCCESS`（`target/continue-final2-quality.log`）。没有修改规则、排除文件、告警门槛或编译目标。Spotless 通过；Checkstyle **379 warning / 0 error**；SpotBugs **248 Medium / 0 High / 0 Low**。最新窄修补未产生新的静态分析条目；下列差量以 2026-11-08 审计为基准。

## Checkstyle 差量评估

上一轮 377，本轮 379；按路径、规则及消息匹配，有 4 条新增/迁移告警和 2 条旧 Controller 告警消失，净增 2。逐条原始位置及处理结论见 [checkstyle-assessment.json](checkstyle-assessment.json)。

| 位置 | 告警 | 评估 |
| --- | --- | --- |
| `SysMailTemplateServiceImpl.updateTemplate:34` | NPath 32768 | 局部更新对每个可编辑字段独立判断。先合并有效值统一校验，再只 SET 提交白名单；NPath 将独立字段组合相乘。真实持久化回归覆盖未知变量、保留字段、有效状态及渠道冲突。保留显式白名单以便核对，不为降低指标改成无边界反射复制。 |
| `SysMailTemplateServiceImpl.validateTemplate:85` | NPath 384 | 从 Controller 下沉的必填、注册事件、渠道内唯一性及变量校验；目前存在可进一步简化的分支结构，但未发现允许非法状态写入的路径。作为维护提示保留。 |
| `UserRoleServiceImpl.protectRemoval:152` | void 早返回 2 次 | 关联已经不存在或对应用户已经删除时，无需执行最后可用超管计算；正常存量用户仍走统一保护。两处早返回明确界定无目标，不绕过当前有效管理员的检查。 |
| `UserRoleServiceImpl.updateAssignment:254` | NPath 512 | 缺失关联/空补丁、提交字段存在性、最后超管、成功通知与提交后缓存分支叠加。保留白名单部分写语义；实际服务、锁和回归覆盖当前更改。没有用拆空转发方法隐藏该复杂度。 |

其余 375 条匹配上一轮已评估告警，来源及规则保留在逐条账本。它们包含存量复杂度、声明顺序和编码风格提示，不等于本轮新增缺陷，也没有因构建成功被当作零告警。上一轮具体反证见 [既有质量评估](../2026-11-08/quality-review.md)。

## SpotBugs 差量评估

本轮 248 条按类型、类、字段及具体消息全部匹配上一轮逐条评估，无新增或未归因条目。最新 XML 的行号与指纹写入 [spotbugs-assessment.json](spotbugs-assessment.json)，同时保留对应旧项编号及处理理由。主要是 Spring 注入/请求 DTO 的 `EI_EXPOSE_REP*`、装箱性能提示和已核过上下文的框架误报；不把全部提示笼统称为误报。既有高风险行为问题及修复依据沿用上一轮评估，本轮没有修改这些来源。

## 验证及限度

静态分析没有覆盖角色审批等待窗口和事务缓存问题。它们由本轮公开服务/工具、真实持久层及 PostgreSQL 并发回归补足。最终 reactor 于 2026-11-09 15:20:52 完成（`target/continue-final2-reactor.log`），实际生成 34 个实体，137 套 / 852 项测试均通过，无失败、错误或跳过；包含 Spring 启动上下文测试，验证新增依赖装配没有形成循环。角色并发组共有 5 项真实 PostgreSQL 测试，含 S2-02 用户分配和权限同步两项；审批生命周期组仍是 H2 PostgreSQL mode，不以其替代真实 PostgreSQL 证据。

角色管理写操作采用有序集合锁，锁的覆盖范围比单行更新更大；这是本轮的正确性取舍，未声称完成高并发性能评估。UI 没有改动，浏览器限制仍按原授权保留。完整日志和收据见 [verification.json](verification.json)。
