# Astral 权限体系设计（重构版）

> 本文档对应本次权限重构（迁移 `V20260930001` / `V20260930002` / `V20260930003`），
> 用于说明**重构前的乱象、新的权限模型、以及「怎么加一条权限」的操作手册**。
> 规范条款已同步进 `AGENTS.md` 约束 5，插件侧用法见 `PLUGIN_GUIDE.md`。

---

## 一、重构前的问题（为什么「乱且不好用」）

| 问题 | 具体表现 |
|---|---|
| **码风格不统一** | 分层码（`admin:system:user:view`）与扁平码（`qt_admin`、`qt_tester`）混用；只有分层码能表达「域」，无从按模块分组展示 |
| **超管靠一条假权限** | `*:*:*` 作为一行普通权限记录存在 `sys_permission`，绑到管理员角色上；它出现在权限树里，能被误删、误取消勾选，删掉即全站失权 |
| **权限粒度只有「能不能调」** | 无法表达「同一个接口，不同人群拿到不同结果」——例如「测试版只推给有测试权限的用户，但正式版版本号更高时这些人也该收到正式版」 |
| **校验写法不统一** | 控制器里手写 `permissionChecker.require("...")` / `requireSuper()`，**漏写即静默放行** |
| **大量管理端接口裸奔** | 字典、系统配置、用户角色关联、角色权限关联、日志、监控、序列、反馈、通知、对象存储、轻听后台等一批 `/api/v1/admin/**` 接口**完全没有权限校验**（任何登录管理员都能改） |
| **`qt_admin` 只存在于线上库** | 没有任何迁移脚本创建它，全新库与存量库行为不一致 |
| **类型语义自相矛盾** | 后端 `type` 是 1-目录/2-菜单/3-按钮，前端却把 1/2/3 渲染成「菜单/按钮/接口」 |

## 二、权限到底在哪里配置（先看这一节）

很多人第一次看会问「权限管理是怎么配置的、库里的算不算最终版」。答案分三层：

```
第 1 层：代码里「有哪些权限」—— 唯一事实来源
    @RequiresPermission("admin:feedback:edit")   接口要不要权限、要哪个码
    PermissionDef(...)                     插件声明结果级权限
        ↓ 启动时 PermissionAutoRegistrar 自动 INSERT 进 sys_permission（只增不改）
第 2 层：库里「权限长什么样」—— 名称/域/类型/层级/排序
    sys_permission 表（+ 迁移脚本给中文名与层级）
        ↑ 后台「权限管理」页可以改名称、改排序；但 AutoRegistrar 永不覆盖已存在的行
第 3 层：库里「谁有什么权限」—— 角色授权
    sys_role_permission（角色 ←→ 权限）
        ↑ 唯一授权入口：后台「角色管理 → 权限」，按域分组的复选树
        ⚠ 没有「直接给用户授权」这条路（严格 RBAC）：用户 → 角色 → 权限
```

**所以要改权限，只有两个地方**：
- 新增/改动**接口校验** → 改 Java 注解（第 1 层），重启后自动登记，再去角色管理页勾选；
- 调整**某个角色能干什么** → 后台角色管理页勾选（第 3 层），无需改代码。

**超管是例外**：`sys_role.is_super=1` 的角色不查授权表，登录时直接合成 `*:*:*`。
因此超管角色在 `sys_role_permission` 里的绑定行**没有任何作用**，V20260930005 已把它们清空——
看到超管 0 条绑定是正常的，不是配置丢了。

**默认拒绝**：接口一旦标了 `@RequiresPermission`，除了超管，别的角色**必须显式勾选**才有权限。
所以「重构后某个页面突然 403」几乎总是因为该角色还没勾新码，而不是权限系统坏了。

## 三、新模型总览

权限被明确切成**三类**，语义不再混在一起：

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. 菜单型权限  type=1/2/3（目录/菜单/按钮）                      │
│    作用：前端菜单树渲染 + 页面可见性                             │
│    判定：前端 hasPermissionIn()，后端一般不校验                  │
├─────────────────────────────────────────────────────────────────┤
│ 2. 接口型权限  type=4（接口）                                    │
│    作用：某个/某类后端接口是否可达                               │
│    判定：@RequiresPermission("端:域:资源:操作") / @RequiresSuper   │
│    语义：二元——有就放行，没有抛 COMMON005                        │
├─────────────────────────────────────────────────────────────────┤
│ 3. 结果级权限  type=5（数据）                                    │
│    作用：接口可达，但**结果里能看见哪一部分**由权限决定          │
│    判定：DataScopeResolver.resolveVisibleValues(...) → 可见集合  │
│    语义：集合——权限只回答「能看见哪些」，选哪个由业务决定        │
└─────────────────────────────────────────────────────────────────┘
```

**关键设计约束（本次重构的核心）**：
结果级权限**只决定可见集合，不决定「必须返回哪一个」**。
业务必须在可见集合内按自己的业务规则择优。反例（本次被明确禁止的写法）：

```java
// ✗ 错误：有测试权限就发测试版 —— 正式版版本号更高时，这些人反而收到更低的测试版
if (hasPermission("user:qt:update:channel:beta")) return findLatest(type, "beta");
```

## 四、权限编码规范

```
端:域:资源:操作[:范围]        全小写，冒号分隔
```

| 段 | 含义 | 例 |
|---|---|---|
| 端 | 与接口路径三层前缀一一对应；**App 用户默认权限即「持有全部 `user:` 前缀权限」** | `admin`（`/api/v1/admin/**`）/ `user`（`/api/v1/app/**`）/ `all`（`/api/v1/all/**`） |
| 域 | `sys_permission.domain`，**跳过「端」段后取第 1 段**（`PermissionChecker.domainOf`） | `system` / `qt` / `storage` / `log` / `sequence` / `feedback` |
| 资源 | 被操作的对象 | `user` / `role` / `update` / `source` |
| 操作 | 动作 | `view` / `edit` / `assign` / `admin` |
| 范围 | **仅结果级权限使用**，取值本身是被授予的可见值 | `user:qt:update:channel:beta` → 可见值 `beta` |

- 层级通配：持有 `admin:system:user:*` 即拥有该前缀下全部权限（`PermissionChecker.matches`）；
  通配必须带上「端」段，`system:user:*` 不是合法通配（`matches` 不做剥端段比较，
  否则 `admin:feedback:view` 会错误地匹配上 `user:feedback:view`）。
- 超管：`sys_role.is_super = 1` 的角色在 `StpInterfaceImpl` 里合成 `*:*:*`，不再落库。
- 类型**由声明显式给出**，不按段数推断：注解 `@RequiresPermission.type()` 默认 `PermissionType.API`，
  结果级权限显式声明 `PermissionType.DATA`（插件 `PermissionDef.getType()` 同理）。
  反例：`admin:system:mail:account:edit` 去掉端段后也是 4 段，但它是接口权限——
  早期按「段数 ≥ 4 即结果级」推断的 `PermissionChecker.inferType()` 已删除。

## 五、数据模型变更

| 变更 | 说明 |
|---|---|
| `sys_role.is_super`（TINYINT，默认 0） | 超管标记。原本绑定 `*:*:*` 的角色在迁移中自动置 1；`*:*:*` 权限行与其绑定被删除 |
| `sys_permission.domain`（VARCHAR 32，默认 `system`） | 权限域，管理端按域分组展示；迁移按 `split_part(code,':',2)` 回填（**跳过「端」段**，否则 `admin:system:user:view` 的域会变成 `admin`） |
| `sys_permission.type` 语义扩展 | 1-目录 2-菜单 3-按钮 **4-接口 5-数据**；4/5 不参与前端菜单渲染 |
| 权限类型字典 | `permission_type`（1~5）登记进 `sys_dict_type`/`sys_dict_data`，前端类型列/下拉按字典渲染 |

## 六、校验链路

```
Controller 方法/类
  └─ @RequiresPermission("admin:system:dict:edit") / @RequiresSuper
       └─ PermissionAspect (@Around，方法注解优先，回退类注解；未登录放行)
            └─ PermissionChecker.hasAnyPermission / isSuperUser
                 └─ StpUtil.getPermissionList() → StpInterfaceImpl
                      └─ PermissionCache（会话级缓存 + 全局版本号失效）
                           └─ sys_user_role → sys_role → sys_role_permission → sys_permission
```

要点：

- **未登录不拦截**：认证由 `AuthInterceptor` 负责。这保证了 App 端/免认证接口上的注解不会因「没登录」被误拒，与历史 `require()` 语义一致。
- **失败即 `BusinessException("COMMON005")`**，由 `GlobalExceptionHandler` 统一转错误响应，对外行为与手写校验完全一致。
- **提权类接口用 `@RequiresSuper`**：改角色/权限、分配用户角色、重置他人密码、Token 吊销/踢人。
  这类接口一旦被非超管调用即可自我提权，因此要求最严格的权限。
- **缓存失效**：任何改动「用户→权限」映射的写操作（角色权限分配、用户角色变更、权限定义增删改）
  必须调用 `PermissionCache.bumpVersion()`；缓存另有 5 分钟最长存活兜底（漏调也不会永久脏）。

## 七、结果级权限：以轻听测试版投放为例

**需求**：轻听客户端检查更新时，测试版（beta）只投放给拥有测试权限的用户；
但**正式版版本号更高时，拥有测试权限的用户也必须收到正式版**（版本号大者为新）。

**实现**（`QtAppController#getUpdate` → `QtAppService#getUpdate`）：

```
1. 取可见渠道集合：
   channels = DataScopeResolver.resolveChannelsByToken(satoken, "user:qt:update:channel")
      · 无权限/未登录/无 token → [stable]
      · 持有 user:qt:update:channel:beta → [stable, beta]
      · 超管 → [stable, 全部已登记渠道]
2. 在集合内按业务规则择优（版本号最大者）：
   for (channel : channels) picked = max(picked, findLatest(type, versionCode, channel))
```

**beta 资格的授予来源**：范围权限（`type=DATA`）是投放资格，**只能由管理员按人/按角色授予**
（如 `TESTER` 角色）；`APP_USER` 默认角色只批发 `type=API` 的接口权限，`syncPermissions()`
每次启动还会主动收回 APP_USER 名下的 DATA 权限（V20261001009 修复的正是按 `user:` 前缀
全量授权时把 beta 资一并批发给全部 App 用户的缺陷）。

正确性：

| 场景 | 无权限用户 | 有测试权限用户 |
|---|---|---|
| beta 3 > stable 2 | 收到 stable 2 | 收到 beta 3 |
| stable 3 > beta 2 | 收到 stable 3 | 收到 **stable 3**（不是更低的 beta 2） |
| 只有 stable | 收到 stable | 收到 stable |

音源包 manifest 同理（`QtSourceService#buildManifest`）：不可见渠道的 release **视同不存在**，
包括其坏包回退信号也不可见；ETag 计算混入可见集合，登录态变化不会命中错误的 304 缓存。

**扩展性**：新增一个渠道（如 `rc`）只需登记一条权限 `qt:update:channel:rc`，
框架与业务代码都不用改（可见集合自动包含它，超管自动获得）。

## 八、超管语义变更

| | 重构前 | 重构后 |
|---|---|---|
| 表达方式 | `sys_permission` 里一条 `*:*:*` 记录 + `sys_role_permission` 绑定 | `sys_role.is_super = 1` |
| 前端可见性 | 权限列表含 `*:*:*` | 权限列表仍含 `*:*:*`（由 `StpInterfaceImpl` 合成），前端代码无需改动 |
| 风险 | 权限行可被误删/误取消勾选 → 全站失权 | 角色标记，不出现在权限树里，不可被误操作 |

迁移会扫描所有绑定了 `*:*:*` 的角色并置 `is_super = 1`，随后删除该权限行与其绑定。

## 九、权限自动登记（不再漏登记）

`PermissionRegistry` 在启动时（`ApplicationRunner`，最低优先级，**Flyway 之后**）扫描：

1. 所有 `@RestController`/`@Controller` 上类级/方法级的 `@RequiresPermission`；
2. 所有实现 `PermissionProvider` 的插件（`getPermissions()`，可声明结果级权限）。

**只补齐缺失项，绝不覆盖已有行**——后台改过的权限名称/排序/状态是运维数据。
注解上的 `name`/`description`/`domain` 会被用上；没写 `name` 时名称为权限码本身。
新增权限因此不再需要「改代码 + 手写 Flyway INSERT + 后台点选」三处同步。

插件的两个 beta 渠道权限由 `QtPlugin.getPermissions()` 声明（SPI 常量 `TYPE_API=4` / `TYPE_DATA=5`）。

## 十、前端改动

| 位置 | 改动 |
|---|---|
| `src/lib/perm.ts` | 新增 `matchPermission` / `hasPermissionIn`（层级通配 + 超管通配），导出 `QT_PERMISSIONS`；`usePerm` 复用它 |
| `dashboard/layout.tsx` | 菜单过滤统一走 `hasPermissionIn`；**插件导航项按 `NavItem.permission` 过滤**，避免「菜单可见、点进去全 403」 |
| `system/permission/page.tsx` | 增加「权限域」列与筛选、按「域→类型→权限码」排序分组；类型文案改由字典 `permission_type` 驱动（修正原 1/2/3 文案错位）；表单补充规范提示 |
| `system/role/page.tsx` | 角色列表显示「超管」标记；角色表单可设超管；权限分配改用 `GET /permission/tree?groupBy=domain`，**按域分组的复选树**（域节点无 ID，勾选即全选该域，不会提交非法 ID）；超管角色的「权限」按钮禁用并提示 |
| `api/plugin.ts` | `NavExtension` 增加可选 `permission` |

后端 `GET /api/v1/admin/system/permission/tree?groupBy=domain` 返回两级结构：
域分组节点（`id = null`）→ 该域下全部权限（域内按 类型→排序→权限码 排序）。
不传 `groupBy` 时保持原菜单树行为。

## 十一、迁移与兼容

| 脚本 | 内容 |
|---|---|
| `V20260930001__permission_model_refactor.sql` | 加 `is_super`/`domain`；回填；超管角色标记；旧码搬迁（`qt_admin`→`admin:qt:admin`，`qt_tester`→两个 beta 结果级权限）；删除 `*:*:*` 与旧码 |
| `V20260930002__permission_type_dict.sql` | `permission_type` 数据字典（1~5），修正前端类型文案错位 |
| `V20260930003__permission_seed_new_codes.sql` | 本次新增的 11 个接口权限码登记（带中文名/域/类型） |
| `V20260930004__permission_granular_write_and_tree.sql` | 修正 `admin:system:menu:view` 非法 `type=0`；补 4 个可委派写码（`admin:system:user:edit`/`admin:system:role:edit`/`admin:system:menu:edit`/`admin:system:token:edit`）；建立 `parent_id` 层级（19 条动作码挂到同资源 `:view` 下）；仪表盘菜单接线 `admin:monitor:view` |
| `V20260930005__role_permission_baseline.sql` | 清空超管角色冗余绑定；USER 角色重置为 `admin:monitor:view`+`admin:statistics:view`+`admin:log:view` 只读基线并移除历史越权视图权限 |
| `V20261001001__permission_code_side_prefix.sql` | 权限码加「端」段（原地改写，id 不变）；`sys_menu.permission` 同步加前缀 |
| `V20261001003__menu_type_dict.sql` | `menu_type` 数据字典（0目录/1菜单/2按钮），与 `permission_type` 相互独立 |
| `V20261001004__permission_cleanup_legacy_codes.sql` | 清理混合态部署期间被注册器补插的无前缀孤儿码（无前缀 + 无角色引用） |
| `V20261001005__app_user_role_and_permissions.sql` | 新增 `APP_USER` 角色（持有全部 `user:` 权限）并回填 `user_type='APP'` 存量用户。⚠️ 本脚本按前缀全量授权未排除 `type=DATA`，端前缀重命名后把两个 beta 资格误批发给全体 App 用户，由 `V20261001009` 修复 |
| `V20261001006__cleanup_orphan_unprefixed_permissions.sql` | 与 20261001004 同条件的幂等收尾清理 |
| `V20261001009__app_user_revoke_data_permissions.sql` | **修复 APP_USER 误持范围权限**：收回其名下全部 `type=DATA` 授权（beta 资格回归管理员按人授予）；配套 `syncPermissions()` 改为只授 `type=API` 并每次启动收回 DATA。**与代码同一次部署** |

**升级须知（重要）**：
1. `V20260930003` **不向任何角色授权**。这些接口在重构前完全没有校验（任何登录管理员可操作），
   重构后按「默认拒绝」处理。超管角色（`is_super=1`）不受影响；
   其他角色请到「系统管理 → 角色管理 → 权限」里按需勾选。**这是刻意的收紧**。
2. 旧码别名表 `PermissionChecker.LEGACY_ALIASES` 在过渡期保留双向映射
   （`qt_admin` ↔ `admin:qt:admin`，`qt_tester` ↔ 两个 beta 码），所有调用方迁移完成后应删除该表。
3. `GET /dict/data/byCode` **刻意不加权限**（仅登录）：它是前端 `fetchDictOptions` 的唯一数据源，
   被多个页面共用且无失败降级；若按 `admin:system:dict:view` 限制，只有窄权限（如仅 `admin:qt:admin`）的管理员会整页下拉为空。
   字典内容是全局枚举文案、不含业务数据，登录即可读是既有行为。

## 十二、操作手册

### 加一条「接口权限」

```java
@Operation(summary = "创建")
@RequiresPermission(value = "admin:feedback:edit", name = "反馈编辑", description = "反馈状态流转/删除/回复")
@PostMapping
public Result<Void> create(...) { ... }
```

启动后自动登记进 `sys_permission`（`domain` 取「端」段之后的第 1 段 `feedback`，`type=4`）；
到角色管理页勾选即可授权。

### 加一条「结果级权限」

1. 插件在 `getPermissions()` 里声明：`new PermissionDef("qt:update:channel:rc", "轻听RC渠道资格", "qt", TYPE_DATA, "...")`；
2. 业务侧解析可见集合，并在集合内择优：

```java
Set<String> channels = dataScopeResolver.resolveChannelsByToken(satoken, "user:qt:update:channel");
return appService.getUpdate(type, version, channels);   // 内部取集合内版本号最大者
```

**不要**写成布尔分支；**不要**让「有权限」直接等于「返回该渠道的值」。

### 提权类接口

```java
@RequiresSuper
@PutMapping("/{id}/permissions")
public Result<Void> assignPermissions(...) { ... }
```

## 十三、写权限粒度：哪些下放、哪些必须超管

「权限很细」不等于「所有写操作都建一个码」。判断标准只有一条：
**持有该码的人能否借此把自己或他人变成超管、或执行代码。**

| 操作 | 编码 / 门禁 | 理由 |
|---|---|---|
| 用户改资料、改状态、踢下线 | `admin:system:user:edit` | 影响他人会话/状态，但不改变权限集合 |
| 用户新增/删除 | `@RequiresSuper` | 删除+新增可绕过引用校验重建同权限账号；新增时可挂任意角色 |
| 用户分配角色 | `@RequiresSuper` | 直接改「用户→权限」映射＝提权 |
| 用户重置密码 | `@RequiresSuper` | 可接管他人账号 |
| 用户改 `user_type` | `@RequiresSuper` | `ADMIN`/`APP` 决定可访问哪套接口 |
| 角色改名/描述/排序/状态、删除角色 | `admin:system:role:edit` | 不触碰权限集合（删除有引用校验） |
| 角色新增 | `@RequiresSuper` | 请求体是实体，客户端可传 `is_super=1` 造超管 |
| 角色分配权限 | `@RequiresSuper` | 可给自己挂 `*:*:*` |
| 角色 `is_super` 变更 | **无入口** | `update` 里显式 `setIsSuper(null)` 丢弃客户端值；只能在库里设置 |
| 菜单增删改 | `admin:system:menu:edit` | 只影响导航可见性 |
| 字典 / 系统配置增删改 | `admin:system:dict:edit` / `admin:system:config:edit` | 影响业务文案与开关，不含鉴权定义 |
| Token 吊销 / 踢人 / 清理过期 | `admin:system:token:edit` | 会话失效≠提权 |
| 权限定义增删改 | `@RequiresSuper` | 可自造 `*:*:*` 权限行 |
| 表结构写（建表/删表/改 schema/生成代码） | `@RequiresSuper` | 落盘 schema JSON 并生成 Java 源码＝代码执行面 |
| 插件启停 | `admin:plugin:edit` | 影响接口可达性，但插件清单是构建期固定的 |
| 反馈/通知/存储/序列配置 | `admin:feedback:edit` / `admin:message:edit` / `admin:storage:edit` / `admin:sequence:edit` | 纯业务数据 |

**下放一条写权限前必须检查的事**：该接口的请求体里有没有能改写「权限相关字段」的入口。
`RoleController.update` 就是反例——原本 `@RequiresSuper` 掩盖了它不校验 `is_super` 的事实，
一旦下放给 `admin:system:role:edit`，持有者 `PUT {"isSuper":1}` 即可自封超管。下放与字段剥离必须同一次改动完成。

## 十四、验证情况

**已验证（本机 PostgreSQL 16.10 + Redis 7.0.11 + JDK 25 实测）**

- 后端全模块 `mvn -DskipTests compile` **BUILD SUCCESS**。
- **全新空库 15 个迁移全部应用成功**（`Successfully applied 15 migrations … now at version v20260930005`），
  应用正常启动。这一步抓出并修复了两个真实缺陷：
  1. `V20260930001` 的 `SELECT COALESCE(MAX(id),0)+1 … WHERE NOT EXISTS` 聚合子查询恒返回一行，
     破坏幂等，已改为 `base` 聚合 CTE + 逐行 `NOT EXISTS`；
  2. `V20260930005` 里把 `baseline` 的别名取成 `b`，与同一查询中 `CROSS JOIN` 的 `base` CTE 混淆，
     导致 `b.max_id` 解析失败（`column b.max_id does not exist`），已重命名别名。
- 全新库终态核对：ADMIN 绑定 0 条、USER 绑定 `admin:log:view,admin:monitor:view,admin:statistics:view` 3 条、
  4 个新写码存在且父级正确、非法 `type` 行 0 条、仪表盘菜单 `permission='admin:monitor:view'`。
- **端到端 10/10 通过**（真实 PG + Redis，RSA 登录）：
  - 结果级权限：无 Token → stable/versionCode 100；持 `user:qt:update:channel:beta` → beta/200；超管 → beta/200；
  - 接口权限：持 `admin:system:user:view` → 用户页 200；缺 `admin:system:dict:edit` → HTTP 403 `COMMON005`；
  - 非超管踢人 → 403 `COMMON005`；超管踢人 → 200；
  - 权限树：`groupBy=domain` 返回 10 个分组（分组节点 `id=null`），qt 组 3 个子节点。
- 前端 `npx tsc --noEmit` **通过**。

**已知限制**

- `admin:sequence:generate` / `admin:sequence:batch` 在 `sys_permission` 里有行、前端也按它们门控，
  但**后端未强制**：`/api/v1/all/sequence/next` 是 `INTEGRATION_GUIDE` 里公开的通用取号接口
  （App 与外部集成共用），加权限会破坏既有调用方。因此这两个码当前是**纯客户端便利门控**，
  不构成安全边界；真正的边界是 `@RateLimit`。若将来要收紧，应新建 `/api/v1/admin/sequence/**`
  管理端接口并只对它加注解，而不是给通用取号口加权限。
- `/dashboard/config` 与 `/dashboard/message` 两个路由没有任何菜单入口（`sys_menu`、`menuConfig`、
  插件 `NavItem` 都没有），只能直接输 URL 访问。前者是序列配置页、与 `/dashboard/sequence` 部分重复，
  建议后续合并删除。
