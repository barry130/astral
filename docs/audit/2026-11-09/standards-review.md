# Standards 续审（2026-11-09）

**最终固定产品提交：`21a24750ea35c276293370bd2b1d7fa510957c06`。Standards 限定范围复核通过：S2-01、S2-02 均已关闭，最后一次窄差量未发现新的必修规范问题。最终验证为 137 套、852 项、0 失败/错误/跳过；Checkstyle 379 warning / 0 error，SpotBugs 248 Medium / 0 High / 0 Low。该结论只覆盖下述既有范围，保留历史缺陷与修复过程，不等于全仓库或生产环境无缺陷。**

## 固定范围与证据边界

- 原始基线：`a39a3f3039ce7deafc3f547a25cc0dc4635c111a`；本次只读起点：`71ad84e416cdaab58cac18c81bfb690174ae0b72`。
- 历史比较：`git diff a39a3f3039ce7deafc3f547a25cc0dc4635c111a...71ad84e416cdaab58cac18c81bfb690174ae0b72`。其间提交为 `e57bb02`、`07a48e8`、`ccae720`、`07c3e40`、`7be9a82`、`a9f0440`、`71ad84e`。
- 本轮最终固定比较：`git diff 71ad84e416cdaab58cac18c81bfb690174ae0b72...21a24750ea35c276293370bd2b1d7fa510957c06`，26 个文件、1730 行新增、188 行删除。其间为 `80601e1 fix: preserve authorization across role approval and notification updates`、`21a2475 fix: reject default role writes after concurrent deletion`；已确认两端可解析且三点 diff 非空，复核时 HEAD 为最终产品 SHA，产品跟踪文件没有工作区差异。
- 上次固定点 `80601e13d87f5bbfeee35ef2040cf4ad22589925` 的 26 个文件、1628 行新增、188 行删除比较保留为历史。最后一轮仅核 `git diff 80601e13d87f5bbfeee35ef2040cf4ad22589925...21a24750ea35c276293370bd2b1d7fa510957c06` 的默认角色锁后存在性补修及相应测试、最终门禁事实，没有重新扩域。
- 任务依据：`docs/audit/2026-11-07/SPEC.md` 及用户继续完整审阅的授权。本次可延伸检查既有入口；以下不声称问题由最近提交新引入。
- 规范依据：根 `AGENTS.md`、`docs/CODING_GUIDE.md`、`docs/UI_GUIDE.md`，并检索相关的 `COMPONENTS_GUIDE.md`、`PLUGIN_GUIDE.md`、`INTEGRATION_GUIDE.md`、`DEPLOY_GUIDE.md`、`AI_MODULE_DESIGN.md`。仓库未发现 `CONTRIBUTING.md` / `CODING_STANDARDS.md`。Fowler 基线仅作启发，本轮不另报维护性 smell。
- 读过菜单、字典、权限及配置部分入口后，依 Lead 收束要求，本轮交付模板缺陷及新增授权父行锁中的同组残余；其他域不作“已通过”结论。不重开 2026-11-08 报告已关闭的 `MessageTemplateService` ID 保留、显式 NULL、提交后缓存失效等触发链。
- 本 reviewer 只读产品代码，仅写本报告；未构建、测试、启动服务、连接数据库、联网或访问浏览器。下面的触发链是源码证据，执行回归由 Lead 完成后另记。

## S2-01 / P2：单行邮件模板局部更新没有校验最终有效状态

**状态：已在固定产品提交 `80601e13d87f5bbfeee35ef2040cf4ad22589925` 关闭。公开 Service 事务版本及原触发链回归均已复核。**

**规则。** `AGENTS.md:157–162` 要求模板按事件 × 渠道绑定，显式变量与正文/主题变量均属于事件 payload；变量不必覆盖全部 payload。`V20261028001__message_template_channel_merge.sql:8,12–13,25–28` 明确同编码、同事件可有 EMAIL/INAPP 两行，编码唯一性为 `(template_code, channel)`。`COMPONENTS_GUIDE.md:287–289` 仍列出单行模板 CRUD，`astral-front/src/api/mail.ts:125–128` 的更新参数为 `Partial<MailTemplate>`，局部更新属于公开合同。

**位置与影响。** `astral-system/src/main/java/com/astral/system/controller/MailTemplateController.java:74–105` 在没有读取存量记录时直接验证局部请求，之后白名单写入。其 `validateTemplate:180–199` 仅当请求 `scene` 非空才解析事件和校验变量：

1. 已有合法 `systemAlert` 模板时，公开 `PUT /api/v1/admin/system/mail/template/{id}` 仅提交 `{"content":"${unknown}"}`，`scenePresent=false`，绕过变量检查并保存未知占位符。只提交未知 `subject` / `variables` 同理；仅换事件时又只检查请求正文，漏检保留的旧正文/声明。发送方按事件 payload 供值，保存成功的模板会含无法由该事件满足的变量。
2. `:167–178` 编码判重与 `:186–193` 事件判重都用 `eq(channelPresent, channel, …)`。请求省略渠道就退化成跨渠道查询。已有同编码、同事件的 EMAIL/INAPP 两行时，对 EMAIL 行只提交原 `templateCode` 加新名称，会错误抛 `MAIL008`；只提交原 `scene` 同样错误抛 `MAIL013`。数据库合法、公开合并模板入口能创建的兄弟行因此阻止正常局部编辑。

`astral-system/src/main/java/com/astral/system/mail/SysMailTemplateServiceImpl.java:8–10` 只是继承 `ServiceImpl`，不存在下层补充校验。此项针对仍可达的单行控制器，与历史合并模板服务的已关闭问题不同。

**修复方向。** 从当前存量及本次提交字段计算完整有效模板，按有效事件/渠道统一验证变量与两组唯一性；写入仍只 SET 允许且提交的字段，避免将存量字段回放覆盖并发修改。归一化变量属于派生状态，须与校验、持久化使用同一有效版本。`AGENTS.md:162` 允许未启用渠道预配置，不应照搬过时注释追加“必须当前启用”限制。

**公开回归方案。** 通过公开 PUT 和真实 mapper 保存→读取：未知正文、主题、声明变量均应拒绝且原行不变；仅换事件须拒绝其保留正文中的不兼容变量；合法内容局部修改须保留省略字段并归一化变量。同编码/同事件不同渠道两行存在时，编码或事件局部更新须成功；真正同渠道冲突仍拒绝。不得仅调用私有验证方法或仅断言 wrapper 参数代替持久化结果。

本轮 Standards 首轮交付 1 组确认缺陷，最高 P2；下文保留后续复核及证据范围，不改写上述原始触发链。

## 首版修复后的窄复核（历史记录）

以下保留首轮修复复核时的状态。比较为 `git diff --ignore-space-at-eol 71ad84e` 的当时工作区，尚未绑定最终提交 SHA。范围限定 S2-01 与本轮新增角色快照/锁协议，不重新扩展其他管理域。

**S2-01 首版执行证据。** 已核对 Lead 执行的 `target/continue-mail-patch-green.log:44–55`：`MailTemplatePatchPersistenceTest` 3 项，0 失败/错误/跳过，`BUILD SUCCESS`。夹具使用真实 MyBatis/H2 持久化、Spring 事务代理和公开控制器更新/读取；覆盖未知正文、主题、显式声明拒绝，换事件检查保留内容，同码/同事件不同渠道局部更新成功，真实同渠道冲突拒绝，合法正文归一化变量。此日志生成于 10:35:14，早于后来的事务下沉调整，不能直接冒充调整后的最终 GREEN。

**S2-01 源码复核。** 修复先锁定当前模板行，复制有效值并合并请求，再统一校验事件、变量和渠道内唯一性；仅提交的白名单字段进入 SET，归一化变量显式写入（可为 NULL）。读行锁覆盖校验及写入，afterCommit 才失效邮件缓存；事件未投递渠道允许预配置。首版将事务放在 Controller，与 `docs/CODING_GUIDE.md:62` 的 public Service 事务边界规则冲突，已通知 Lead；当前 Controller 已委派 `SysMailTemplateService.createTemplate/updateTemplate`，事务与锁、校验、写入及失效整体下沉，规范调整方向正确。待服务层版本回归后完成最终关闭。

**角色快照/锁的必要检查。** `UserRoleServiceImpl` 的快照由服务端生成，稳定排序后涵盖用户类型/状态、当前角色、现有及请求角色的定义、角色权限关联及权限编码/状态。准备钩子清除模型提交的 `_authorizationSnapshot`，审批重放不重建预期值；服务在 users→roles→permissions 锁内比较再替换。上述为源码判断，不将已有其他用例计为这些锁竞争均已实测。

**新增事务缓存边界提醒（待最终调整，不另扩计历史发现）。** 新加 `AppUserRoleService.syncPermissions/assignToUser` 事务后，原 `permissionCache.bumpVersion()` 仍发生在提交之前。其他连接若在 bump 后、commit 前回源，会把旧授权缓存到新版本；服务返回后需要提交后失效。正常启动的 `AppUserRoleInitializer.run:49–56` 已在 sync 返回后再 bump，故不能声称该启动路径必然遗留旧权限。该提醒限定新增事务的服务边界；已建议将 ensureRole/sync/assign 的版本失效统一改到 afterCommit。另提示 Lead 核对 direct 用户角色写入与角色删除共锁、锁后角色存在性，未据此增加本轮缺陷编号或扩大 S2-01 结论。

## 12:42 定向回归后的状态（历史记录）

已只读核验 `target/continue-focused-final.log`。日志 `:221/:229/:234/:338` 分别为单行模板 3、合并模板 6、授权持久化 7、角色通知 10 项，system 小计 26；`:484/:486/:488/:494` 为审批生命周期 36、公告持久化 2、工具权限 4、角色并发 2 项，plugin 小计 44。共 **70 项，0 失败/错误/跳过**，`:513` 为 `BUILD SUCCESS`，`:516` 完成时间为 2026-11-09 12:42:00。这里只核验既有执行日志，reviewer 未运行这些测试。

| 复核对象 | 当前源码与证据 | 本轮结论 |
| --- | --- | --- |
| S2-01 有效模板及渠道内唯一性 | `SysMailTemplateServiceImpl.java:26–79` 的公开事务方法承接 create/update；update 先 `FOR UPDATE`，复制并合并有效值，完整验证后仍只写请求白名单及归一化变量。`MailTemplateController:58–68` 仅委派。`MailTemplatePatchPersistenceTest:60–65` 代理实际 Service，公开 Controller→Service→真实 MyBatis/H2 保存与再读；`:74–128` 原三组断言保留，最新日志 `:221` 全部通过。 | **S2-01 原触发链通过当前工作区定向复核**；首版 Controller 事务边界偏差已纠正。 |
| 提交后权限缓存失效 | `PermissionCache.java:79–91` 提供 `bumpAfterCommit`，事务中注册 afterCommit，无同步时立即 bump。`AppUserRoleService:86,135,173` 的 ensureRole/sync/assign 统一调用；`UserRoleServiceImpl:313–314` 同样复用。`RoleAuthorizationPersistenceTest:162–187` 验证整体替换提交前/回滚版本不变与提交后读取新权限；`:229–237` 通过代理 `assignToUser` 验证未提交版本不变、提交后已有角色缓存回源。最新日志 `:234` 7 项全部通过。 | 先前新增事务缓存提醒已在源码和 assign 分支公开持久化测试中关闭。ensureRole 新建与 sync 分支使用同 helper，但本日志没有两者各自独立的提交交错测试，不扩大实测范围。 |
| direct 关联写与角色锁 | `UserRoleServiceImpl:225–262` 的 save/update 在 users→roles 锁后验证所提交角色存在；`:290–314` 的删除同序持锁及提交后失效。`RolePermissionServiceImpl:36–40,78–89,106–116` 在 role 锁后验证新增或更新目标角色。角色删除也先按序取角色锁。`RoleAuthorizationPersistenceTest:241–245` 公开 save 缺失角色被拒绝且原关联保留。 | 先前提醒的 direct 保存与角色删除之间的父行锁缺口、RolePermission 锁前仅由 Controller 查存在的缺口已在源码修正；没有用这一缺失角色用例冒充真实删除竞争测试。 |

角色快照仍按服务端准备、持久化预期值、审批时共同锁内重算后才替换。正在补充的“公开 RolePermission 写入口与审批快照锁竞争”尚未包含于上述 70 项，待单独日志后补证。本轮没有扩大检查其他域；在已检查的调整中未确认新的必修规范问题。

**当时冻结边界：** 本节是工作区与 12:42 日志的对应复核，当时产品最终 SHA 尚未收到；最终固定提交证据及残余问题见下节。

## 固定提交 `80601e13` 的窄复核（历史记录）

**S2-01 关闭。** `SysMailTemplateServiceImpl.java:26–79` 的 public Service 事务包含锁行、合并有效值、完整校验、白名单局部写入与提交后缓存失效；`:85–149` 的渠道内判重及变量归一化与 `AGENTS.md:157–162` 一致。Controller 事务边界偏差已纠正。最终 reactor 日志 `target/continue-final-reactor.log:393` 中 `MailTemplatePatchPersistenceTest` 3 项全过，`:424` 的合并模板回归 6 项全过；未知正文/主题/声明、换事件保留内容、跨渠道兄弟行及真实同渠道冲突的原触发链均有公开持久化回归。

**快照及公开角色写入锁。** 已复核服务端准备移除模型快照字段、稳定序列化所需授权状态，以及 users→roles→permissions 锁内重算和替换。`UserRoleServiceImpl.java:237–239,261–267` 在用户/角色锁后校验目标存在，`RolePermissionServiceImpl.java:39–40,81–82,110–111` 在角色锁后校验目标角色。`RoleAuthorizationPersistenceTest` 已由 7 项增至 8 项，最终日志 `:639` 全过。`AdminToolRoleConcurrencyTest.java:236–260` 新增公开 `assignPermissions` 与审批替换的事务竞争，通过 `:275` 的 PostgreSQL `wait_event_type='Lock'` 观察公开写入口确实等待审批事务提交；最终日志 `:1350` 为实际 PostgreSQL 3 项全过，0 跳过。该证据不覆盖下面新增发现的默认角色删除交错。

**提交后缓存。** `PermissionCache.java:80–92` 的 helper 事务内登记 afterCommit，回滚不失效；`AppUserRoleService.java:87,136,178` 及 `UserRoleServiceImpl` 新增写入口均使用它。公开代理 `assignToUser` 的提交前版本稳定、提交后回源及整体替换回滚测试仍保留并在最终授权 8 项中通过。对 ensureRole/sync 分支只确认源码共用同一 helper，不声称它们各有独立提交交错用例。

**最终验证记录。** 本 reviewer 逐套汇总 `target/continue-final-reactor.log` 的 `Tests run … -- in`，得到 **137 套、850 项、0 失败/错误/跳过**；`:143,178` 确认 34 个实体生成，system/plugin install 均在日志中，`:6377` 为 `BUILD SUCCESS`，`:6380` 完成于 13:12:21。相关套件另含角色通知 10（`:745`）、审批生命周期 36（`:1120`）、内建公告持久化 2（`:1341`）。这是 Lead 的执行证据，reviewer 未运行构建或测试。

**静态门禁记录。** 只读解析 `target/checkstyle-result.xml` 得 **379 warning / 0 error**；11 份 `spotbugsXml.xml` 合计 **248 Medium / 0 High / 0 Low**。`target/continue-final-quality.log:648,651` 显示成功，完成于 13:17:14。Checkstyle 新增四个报告位置：模板 update NPath32768（`:209`）、validate NPath384（`:210`）、protectRemoval ReturnCount2（`:230`）、updateAssignment NPath512（`:231`）；移出 Controller 的两处旧报告抵扣后较前轮净增 2，不能写成警告清零或数量不变。Lead 已在本轮静态账本逐项评估且 unassessed=0，并完成 SpotBugs 与旧账本逐条匹配；本 reviewer 仅独立核对数量和上述新位置，不重复冒充逐条核对。依 code-review 技能，这些工具已报告规则不另计人工缺陷。

## S2-02 / P2：默认角色写入忽略锁定父行已被删除

**状态：已在最终固定产品提交 `21a24750ea35c276293370bd2b1d7fa510957c06` 关闭；两条公开写路径的真实 PostgreSQL 删除交错回归通过。** 以下保留 `80601e13` 时发现的原触发链，修复及最终执行证据见下一节。本项是已有授权父行锁范围内的残余检查，不扩展其他域，也不声称该竞态由本次提交首次引入。

**规则与位置。** `docs/CODING_GUIDE.md:63,84` 要求关联写入事务完整及角色删除引用校验、关联清理。`AppUserRoleService.java:160–161` 在 `ensureRole()` 无锁读取旧角色 ID 后调用 `FOR UPDATE`，但丢弃查询结果；`:170–178` 随后仍写入关联。同形的 `syncPermissions:104–105` 也未校验锁后角色存在，后续可写入角色权限。`RoleServiceImpl.java:35–49` 已有按序角色锁、引用检查、关联清理和删除；模板及 direct 用户角色/角色权限入口已加的存在性检查没有覆盖此默认角色服务。初始化 SQL `V20260914001__init.sql:145–162` 的两张关联表仅有非空及唯一约束，没有外键，其他迁移也未补这些外键。

**确定交错与影响。** APP_USER 角色仍存在但暂时无人引用时，新用户注册保存用户后进入公开 `assignToUser`。T1 的 `ensureRole` 读出旧角色 ID；T2 通过公开角色删除服务先取得父行锁、确认用户引用为 0、删角色权限和父角色并提交；T1 的 `SELECT … FOR UPDATE` 返回空集，却继续以旧 ID 插入 `sys_user_role`。注册成功但默认角色悬空，用户没有预期 App 权限。启动同步在相同读/锁间隙遇到删除时，可在删除清理完成后重新插入悬空 `sys_role_permission`。事务和 afterCommit 失效均不能补回已经缺失的父行。

**修复与公开回归。** 默认角色的读取与锁定应得到仍存在的同一父行；空锁后须以明确业务结果拒绝，或在锁内重新定位/创建并使用新角色。真实 PostgreSQL 回归可在 `assignToUser` 的 ensureRole 读取后暂停，执行 `deleteRoleWithRelations` 到提交，再恢复赋角色，断言不能成功写入已删除角色 ID；同步分支应同样保证没有悬空角色权限。沿用公开 Service 代理、真实 mapper 和两事务协调，不以 mocked 空结果或 wrapper 断言替代交错证据。

**当时固定 SHA 结论（保留历史）：** 在 `80601e13`，S2-01 已关闭；已检查的快照、direct 关联锁和提交后缓存修复未发现其他新必修问题。S2-02 当时为 1 项尚待关闭的 P2，因此该 SHA 当时没有记为无残余通过。

## 最终固定提交 `21a24750`：S2-02 关闭与门禁证据

**补修核对。** `AppUserRoleService.java:105,161` 的 `syncPermissions/assignToUser` 都在 `ensureRole` 后调用同一 `requireLockedRole`。该 helper `:183–189` 执行 `SELECT … FOR UPDATE` 并检查空集；父行已被删除时在所有关联写入前抛出既有业务码 `SYS003`（角色不存在）。两个 public Service 入口保留 `@Transactional(rollbackFor = Exception.class)`，父行存在时锁持续到关联写入事务结束；父行不存在时业务拒绝使事务回滚。本次窄差量仅改该 Service 和 `AdminToolRoleConcurrencyTest`，没有触及已关闭的模板或其他授权实现。

**公开 PostgreSQL 回归。** `AdminToolRoleConcurrencyTest.java:297–338` 用参数 `assign/sync` 调用真实事务代理的公开 `assignToUser/syncPermissions`。JDBC 包装只调度时序：`:402,414–424` 在真实无锁角色查询已经执行后、调用方进入后续锁查询之前暂停；另一线程调用公开 `RoleService.deleteRoleWithRelations(3L)` 并等待其事务提交，再恢复写入口。两例均断言 `BusinessException` 的错误码为 `SYS003`，且经公开角色、用户角色及角色权限 Service 查询，父行与两类悬空关联均不存在。夹具连接真实 PostgreSQL（`:97–102`），使用真实 mapper 和 Spring 事务代理，未用 mock 结果替代持久化交错。

**RED/GREEN 区分。** `target/continue-app-role-red.log:266,268,276` 是有效 RED：两参数均因旧实现没有抛出预期异常而失败，0 error/skip；`:305,308` 为失败及 14:28:19 完成时间。`target/continue-app-role-red2.log:265,284,287` 虽文件名含 red2，实际是修复后两项通过的 GREEN，完成于 14:47:34。`target/continue-app-role-redgreen.log:259,282` 是夹具编译失败，不能作为缺陷已复现的 RED。

**最终完整门禁。** 逐套只读汇总 `target/continue-final2-reactor.log`：**137 套、852 项、0 失败/错误/跳过**。`:142,177` 明确执行 34 个实体生成；auth/system/plugin 的本地 install 分别见 `:342–343,750–751,2003–2004`。PostgreSQL 角色并发套件由 3 项增至 **5 项**，`:1339` 全部通过；模板局部更新 3 项（`:387`）与授权持久化 8 项（`:633`）保留通过。`:6366,6369` 为 `BUILD SUCCESS` 及 15:20:52 完成时间。reviewer 只读日志和代码，没有运行这些命令、连接数据库或启动服务。

**质量与内容绑定。** `target/continue-final2-quality.log:648,651` 显示门禁成功，完成于 15:24:20。重新只读解析 Checkstyle XML 为 **379 warning / 0 error**；11 份 SpotBugs XML 为 **248 Medium / 0 High / 0 Low**，与上一固定点的计数相同；Lead 逐项账本结论为本次补修无新增差量。`target/continue-final2-content-check.json` 记录门禁后 26 个产品/测试文件的 SHA-256，reviewer 已逐文件重算比对，**26/26 一致**；同时确认 HEAD 为 `21a24750` 且跟踪文件无差异，因此这些门禁后内容与最终固定提交一致。文档封版前已只读确认收据 `reviewedHead` 更新为最终 SHA `21a24750ea35c276293370bd2b1d7fa510957c06`，与上述独立哈希及 Git 核验结果一致。

**最终限定结论。** S2-01 和 S2-02 均已关闭，已检查的补修没有剩余必修规范问题。该结论覆盖既有模板有效状态、授权快照/父行锁及提交后缓存边界；未延伸成所有并发交错、未审领域、生产外部依赖或浏览器均已验证。
