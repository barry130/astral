# Astral 全项目接口文档

> 整理自 `astral` 仓库全部 Controller。基础路径 `http://localhost:27000`，前缀 `/api/v1`。
> 认证：**管理台（浏览器）** 凭 HttpOnly Cookie `satoken` 自动认证，写请求需回填 `X-CSRF-Token`；
> **轻听 App / 脚本 / Swagger 等非浏览器调用方** 走 `satoken` 请求头（该路径豁免 CSRF 校验）。
> 两种方式都由宿主 Sa-Token 解析，详见 [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md#token-管理httponly-cookie--csrf-双提交)。
> 返回包装：管理端宿主 `Result<T>{code,msg,data}`；反馈/轻听 App 端用各自 `FeedbackRestResp`/`QtRestResp{code,msg,data}`。

---

## 1. 认证鉴权 `astral-auth`

### 1.1 账号认证 `/api/v1/all/auth`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/public-key` | 获取 RSA 公钥（登录密码加密用） |
| POST | `/login` | 用户名密码登录（RSA 密文密码，限流 5次/60s，成功返回 token+用户信息，并 Set-Cookie 下发 HttpOnly `satoken` 与 `astral_csrf`） |
| POST | `/logout` | 登出，使当前 token 失效并清除认证 Cookie |
| GET | `/info` | 获取当前登录用户信息（含角色/权限列表；响应体额外带 `csrfToken`，见下） |

> 浏览器调用方：登录后凭 Cookie 自动认证；`POST`/`PUT`/`DELETE` 需带 `X-CSRF-Token`，缺失或不匹配返回 403 `AUTH013`。
> 令牌取值有两处，按顺序回退：**同源部署**读非 HttpOnly 的 `astral_csrf` Cookie；**跨域直连部署**下该 Cookie
> 属于 API 域、页面读不到，改从登录响应体与 `GET /info` 响应体的 `csrfToken` 字段取（前端缓存在内存即可）。
> 凡走 Cookie 凭据的登录用户都会拿到该值（不限于管理员）——网页端普通用户的 `/files`（兼容入口 `/imgbed`）写操作同样需要它。
> 带了 `satoken` 请求头的请求不参与该校验。

### 1.2 Token 管理 `/api/v1/system/token`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/page?pageNum=&pageSize=&userId=` | 分页查询 Token（可按 userId 筛选） |
| PUT | `/{id}/revoke` | 吊销指定 Token |
| PUT | `/user/{userId}/kick` | 踢出指定用户（使其所有 Token 失效） |
| DELETE | `/expired` | 清理所有过期 Token |

---

## 2. 系统管理 `astral-system`

### 2.1 用户 `/api/v1/system/user`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/page?pageNum=&pageSize=&userType=&username=` | 分页查询（userType=ADMIN/APP 筛选，支持用户名/昵称/邮箱模糊） |
| GET | `/{id}` | 用户详情 |
| POST | `/` | 创建用户 |
| PUT | `/{id}` | 更新用户 |
| DELETE | `/{id}` | 删除用户 |
| GET | `/{id}/roles` | 获取用户角色列表 |
| PUT | `/{id}/roles` | 分配角色（body：`{roleIds:[]}`） |
| PUT | `/{id}/status?status=` | 封禁/解封（status=0 封禁并踢下线） |
| PUT | `/{id}/password` | 重置密码（body：`{password}`，≥6位，重置后踢下线） |
| PUT | `/{id}/kick` | 踢下线 |
| PUT | `/{id}/type` | 修改用户类型（ADMIN/APP） |

### 2.2 角色 `/api/v1/system/role`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/page` | 分页查询 |
| GET | `/{id}` | 详情 |
| POST | `/` | 创建 |
| PUT | `/{id}` | 更新 |
| DELETE | `/{id}` | 删除 |
| GET | `/all` | 查询所有角色（不分页） |
| GET | `/{id}/permissions` | 获取角色权限列表 |
| PUT | `/{id}/permissions` | 分配权限（body：`{permissionIds:[]}`） |

### 2.3 权限 `/api/v1/system/permission`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/page` | 分页查询 |
| GET | `/{id}` | 详情 |
| POST | `/` | 创建 |
| PUT | `/{id}` | 更新 |
| DELETE | `/{id}` | 删除（已被角色引用则报 SYS006） |
| GET | `/tree` | 权限树 |

### 2.4 菜单 `/api/v1/system/menu`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/tree` | 菜单树 |
| GET | `/list` | 菜单列表（扁平） |
| GET | `/{id}` | 菜单详情 |
| POST | `/` | 创建 |
| PUT | `/{id}` | 更新 |
| DELETE | `/{id}` | 删除（连带删除子菜单） |

### 2.5 数据字典
| 模块 | 方法 | URL | 作用 |
|---|---|---|---|
| 字典 | GET | `/api/v1/system/dict/page` | 分页查询字典数据 |
| 字典 | GET | `/api/v1/system/dict/{id}` | 字典数据详情 |
| 字典 | POST | `/api/v1/system/dict` | 创建字典数据 |
| 字典 | PUT | `/api/v1/system/dict/{id}` | 更新字典数据 |
| 字典 | DELETE | `/api/v1/system/dict/{id}` | 删除字典数据 |
| 类型 | GET | `/api/v1/system/dict/type/page` | 分页查询字典类型 |
| 类型 | GET | `/api/v1/system/dict/type/all` | 全部字典类型 |
| 类型 | GET | `/api/v1/system/dict/type/{id}` | 类型详情 |
| 类型 | POST | `/api/v1/system/dict/type` | 创建类型 |
| 类型 | PUT | `/api/v1/system/dict/type/{id}` | 更新类型 |
| 类型 | DELETE | `/api/v1/system/dict/type/{id}` | 删除类型 |
| 数据 | GET | `/api/v1/system/dict/data/page?dictTypeId=` | 分页查询（可按类型筛选） |
| 数据 | GET | `/api/v1/system/dict/data/{id}` | 详情 |
| 数据 | GET | `/api/v1/system/dict/data/byCode?code=` | 按字典类型编码查启用数据（下拉用） |
| 数据 | POST | `/api/v1/system/dict/data` | 创建 |
| 数据 | PUT | `/api/v1/system/dict/data/{id}` | 更新 |
| 数据 | DELETE | `/api/v1/system/dict/data/{id}` | 删除 |

### 2.6 系统配置 `/api/v1/system/config`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/page` | 分页查询 |
| GET | `/{id}` | 详情 |
| POST | `/` | 创建 |
| PUT | `/{id}` | 更新 |
| DELETE | `/{id}` | 删除 |

### 2.7 表结构 `/api/v1/system/table-schema`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/` | 获取所有表结构定义 |
| GET | `/{tableName}` | 按表名获取表结构 |
| GET | `/module/{moduleName}` | 按模块获取表结构 |
| GET | `/names` | 获取所有表名 |
| POST | `/` | 创建表结构（body：`{tableName,tableComment,moduleName,includeCommonFields}`） |
| DELETE | `/{tableName}` | 删除表结构 |
| GET | `/{tableName}/entity` | 生成 Entity 代码 |
| GET | `/{tableName}/mapper` | 生成 Mapper 代码 |
| GET | `/{tableName}/service` | 生成 Service 接口代码 |
| GET | `/{tableName}/service-impl` | 生成 ServiceImpl 代码 |
| GET | `/{tableName}/controller` | 生成 Controller 代码 |
| GET | `/{tableName}/sql` | 生成建表 SQL（推断） |

### 2.8 关联表（简单 CRUD）
| 模块 | 前缀 | 方法 | URL | 作用 |
|---|---|---|---|---|
| 用户角色 | `/api/v1/system/user_role` | GET | `/page` | 分页 |
| | | GET | `/{id}` | 详情 |
| | | POST | `/` | 创建 |
| | | PUT | `/{id}` | 更新 |
| | | DELETE | `/{id}` | 删除 |
| 角色权限 | `/api/v1/system/role_permission` | 同上 | 同上 | 同上（分页/详情/增删改） |

### 2.9 邮件
| 模块 | 前缀 | 方法 | URL | 作用 |
|---|---|---|---|---|
| 账户 | `/api/v1/system/mail/account` | GET | `/page` | 分页 |
| | | GET | `/{id}` | 详情 |
| | | POST | `/` | 新增 |
| | | PUT | `/{id}` | 更新 |
| | | DELETE | `/{id}` | 删除 |
| | | POST | `/{id}/toggle?enabled=` | 启用/停用 |
| | | POST | `/test?accountId=&toEmail=` | 测试发送 |
| 模板 | `/api/v1/system/mail/template` | GET | `/page` | 分页 |
| | | GET | `/{id}` | 详情 |
| | | POST | `/` | 新增 |
| | | PUT | `/{id}` | 更新 |
| | | DELETE | `/{id}` | 删除 |
| | | POST | `/preview` | 模板预览（body：`{templateId,variables}`） |
| 日志 | `/api/v1/system/mail/log` | GET | `/page` | 分页（accountId/pluginId/toEmail/status/时间区间筛选） |
| | | GET | `/statistics` | 发送统计概览 |
| 授权 | `/api/v1/system/mail/plugin-auth` | GET | `/list` | 列表 |
| | | POST | `/` | 新增 |
| | | PUT | `/{id}` | 更新 |
| | | DELETE | `/{id}` | 删除 |

---

## 3. 日志 `astral-log`

### 3.1 操作日志 `/api/v1/log/operate_log`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/page` | 分页 |
| GET | `/{id}` | 详情 |
| POST | `/` | 创建 |
| PUT | `/{id}` | 更新 |
| DELETE | `/{id}` | 删除 |

### 3.2 登录日志 `/api/v1/log/login_log`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/page` | 分页 |
| GET | `/{id}` | 详情 |
| POST | `/` | 创建 |
| PUT | `/{id}` | 更新 |
| DELETE | `/{id}` | 删除 |

---

## 4. 序列生成 `astral-sequence`

### 4.1 生成 `/api/v1/sequence`
| 方法 | URL | 作用 |
|---|---|---|
| POST | `/next` | 获取单个序列号（body：`{bizKey,type}`，限流 100/60s） |
| POST | `/batch` | 批量获取序列（body：`{bizKey,count,type}`，限流 50/60s） |
| GET | `/types` | 所有支持的类型（SNOWFLAKE/SEGMENT/REDIS/DATABASE/SIMPLE） |

### 4.2 配置管理 `/api/v1/sequence/configs`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/` | 全部配置 |
| GET | `/page?bizKey=` | 分页（可按业务键模糊） |
| GET | `/{bizKey}` | 按业务键获取配置 |
| POST | `/` | 创建（系统内置 `_id` 序列禁止创建 SEQ008） |
| PUT | `/{id}` | 更新 |
| DELETE | `/{id}` | 删除 |
| PUT | `/{id}/toggle?enabled=` | 启用/禁用 |

### 4.3 号段 `/api/v1/sequence/segment` 与统计 `/api/v1/sequence/statistics`
均为标准 CRUD（page/getById/create/update/delete）。

### 4.4 历史 `/api/v1/sequence/history`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/page?bizKey=` | 分页（按业务键） |
| GET | `/recent?bizKey=&limit=` | 最近记录（默认100） |

---

## 5. 监控与统计 `astral-monitor`

### 5.1 系统监控 `/api/v1/monitor`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/system` | 系统监控（CPU/内存/磁盘/线程/运行时间） |
| GET | `/jvm` | JVM 监控（堆内存/GC/线程/JDK版本） |
| GET | `/business` | 业务监控（序列总数/QPS/配置数） |

### 5.2 统计报表 `/api/v1/admin/stat`（admin 前端，权限 `admin:statistics:view`）
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/overview?date=&ut=` | 设备统计概览（今日 vs 昨日） |
| GET | `/trend?metric=&date=&ut=&gran=` | 指标24小时趋势（默认 pv/hour） |
| GET | `/api/top?limit=&date=` | 接口调用 Top 榜（默认10） |
| GET | `/api/trend?uri=&method=&date=` | 单接口24小时调用趋势 |
| GET | `/error/page?pageNum=&pageSize=&errorType=&appVersion=&fingerprint=` | 错误明细分页 |
| GET | `/error/summary?date=` | 错误分组汇总（按 fingerprint） |

### 5.3 统计上报 `/api/v1/app/stat`（App 匿名，单批≤200）
| 方法 | URL | 作用 |
|---|---|---|
| POST | `/report` | 匿名批量上报统计事件（恒返回200，失败不影响客户端） |

---

## 6. 插件管理 `astral-plugin` `/api/v1/admin/plugin`

| 方法 | URL | 作用 |
|---|---|---|
| GET | `/list` | 所有插件 |
| GET | `/enabled` | 已启用插件 |
| POST | `/{pluginId}/enable` | 启用插件 |
| POST | `/{pluginId}/disable` | 禁用插件 |
| GET | `/nav-extensions` | 前端导航扩展（仅已启用插件） |
| GET | `/configs` | 插件默认配置 |

---

## 7. 轻听插件 `astral-plugin / qt`

### 8.1 App 用户 `/api/v1/app/user`（App 用户，satoken）
| 方法 | URL | 作用 |
|---|---|---|
| POST | `/login` | 登录（返回 token+用户信息） |
| POST | `/refresh` | 刷新 token |
| GET | `/me` | 根据 token 获取用户信息 |
| POST | `/logout` | 退出登录 |
| POST | `/email` | 发送邮箱验证码 |
| POST | `/register` | 注册（邮箱+验证码） |
| POST | `/update` | 更新用户信息 |
| POST | `/changePass` | 邮箱验证码重置密码 |
| POST | `/daka` | 用户签到 |
| GET | `/dakaInfo` | 连续签到天数+总积分 |
| GET | `/dakaInfoByMonth?time=` | 某年某月签到详情 |
| POST | `/deactivate` | 注销账号 |
| GET | `/like/changes?since=` | 增量拉取收藏变更 |
| GET | `/like/list?page=&size=` | 全量分页拉取收藏 |
| POST | `/like/song` | 收藏/取消收藏单曲 |
| POST | `/like/playlist` | 收藏/取消收藏歌单 |
| POST | `/like/batch` | 批量收藏/取消（单批≤200） |

### 8.2 App 公共 `/api/v1/app`（免认证）
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/update?type=&version=&channel=` | APK 更新信息（type=1101/1102） |
| GET | `/version/check?type=&version=&versionName=` | 校验是否官方版本 |
| GET | `/github/accels` | GitHub 加速节点列表 |
| GET | `/source/manifest?platform=` | 音源包清单 |
| POST | `/source/report` | 音源包可用性上报 |

### 8.3 管理端 `/api/v1/admin/qt`（宿主管理员）
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/overview` | 概览统计（用户/签到/更新数） |
| GET | `/users?pageNum=&pageSize=&keyword=` | App 用户分页 |
| PUT | `/users/{id}/state?state=` | 封禁/解封 |
| GET | `/updates` | 版本更新列表 |
| POST | `/updates` | 新增版本更新 |
| PUT | `/updates/{id}` | 更新版本 |
| DELETE | `/updates/{id}` | 删除版本 |
| GET/POST/PUT/DELETE | `/github-accels[/{id}]` | 加速节点 CRUD |
| POST | `/github-accels/cache/evict` | 失效加速节点缓存 |
| POST | `/github-accels/probe` | 探测节点可用性 |
| GET | `/source-releases` | 音源包列表 |
| GET | `/source-releases/stats` | 音源包统计 |
| POST | `/source-releases` | 新增音源包（artifacts 按 path 增量合并，未提交项继承上一版；已在字典 `qt_source_artifact_path` 停用的 path 不再继承） |
| PUT | `/source-releases/{id}` | 更新音源包（同上；请求体带 `replaceArtifacts=true` 时本次 artifacts 即当前生效全集，未提交的 path 会被删除） |
| POST | `/source-releases/{id}/publish` | 发布 |
| POST | `/source-releases/{id}/unpublish` | 取消发布 |
| POST | `/source-releases/{id}/bad` | 标记异常 |
| DELETE | `/source-releases/{id}` | 删除音源包 |

音源包 artifacts 条目除 `path` / `version` / `url` 外，manifest 还会直发服务端维护的包头字段 `packId` / `packVersionCode` / `packVersionName`（由 `QtPackHeaderProbe` 从产物首行 `/*__QT_PACK__*/` 包头探测回填：建单/编辑后异步补齐，发布前同步补齐；管理端上送的这三个字段一律忽略，url 未变的条目继承旧探测值）。客户端（qt-pc / qt-uniappx）优先用它们直接比版、免 Range 探测；字段缺失（旧服务端发的旧 release）时回退客户端自行探测。

---

## 8. 反馈插件 `astral-plugin / feedback`

### 9.1 App 反馈 `/api/v1/app/feedback`（App 用户，satoken）
| 方法 | URL | 作用 |
|---|---|---|
| POST | `/submit` | 提交反馈（type/title/content/contact；统一客户端系统头 X-App-Ut/X-App-Version/X-Device/X-OS，见 COMPONENTS_GUIDE.md） |
| GET | `/my?pageNum=&pageSize=` | 我的反馈分页 |
| GET | `/public?pageNum=&pageSize=` | 公开列表（status=published AND is_public=true） |
| GET | `/{id}` | 详情（本人 或 published+public 可见） |
| GET | `/{id}/replies` | 回复列表 |
| POST | `/reply` | 用户回复（同时通知管理员） |

### 9.2 App 通知 `/api/v1/app/message`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/active?versionCode=` | 当前生效通知（**公开**，三展示位共用） |
| GET | `/center` | 消息中心（需登录） |
| GET | `/unread-count` | 未读数 |
| POST | `/read-ack` | 已读回执（body：`{ids:[]}`） |

> **投放平台识别（老客户端兼容）**：三个查询接口都按
> `X-App-Ut` 头 → 遗留 `X-Platform` 头 → `channel` 查询参数 → 缺省 App 的顺序解析，
> 详见 `NoticeChannel.resolveTargets`。新客户端只发统一平台头即可；
> 老客户端写死的 `channel=app|pc|web|all` 继续被识别
> （`app`→Android+iOS、`pc`→Windows、`web`→Web、`all`→不限平台），
> 三者皆空时回落 Android+iOS（等价于旧的 `channel=app` 缺省）。

### 9.3 管理端反馈 `/api/v1/admin/feedback`（宿主管理员）
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/page?pageNum=&pageSize=&status=&type=&keyword=&startDate=&endDate=` | 分页（含提交人 username/email） |
| GET | `/{id}` | 详情 |
| PUT | `/{id}/status` | 状态流转（body：`{status}`；仅 published/deprecated 通知提交人） |
| PUT | `/{id}/public` | 公开切换（body：`{isPublic}`） |
| DELETE | `/{id}` | 软删 |
| GET | `/{id}/replies` | 回复列表 |
| POST | `/reply` | 管理端回复（通知提交人） |
| GET | `/stat` | 统计看板 |

### 9.4 管理端通知 `/api/v1/admin/message`（宿主管理员）
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/page?pageNum=&pageSize=&channel=&noticeType=&keyword=&startDate=&endDate=` | 通知分页 |
| POST | `/` | 发布通知 |
| PUT | `/{id}` | 编辑通知 |
| DELETE | `/{id}` | 删除通知 |

> 状态机：`pending提出 → received已接收 → resolved已解决 → published已发布`；任意状态→`deprecated已废弃`；已废弃可转回任意状态。
> 通知类型：`issue` 反馈映射为 `feedback`，`request` 反馈映射为 `request`。
> 通知渠道 `channel`：**逗号分隔的投放平台集合**，取值与 `stat_platform` 同源
> （`app-android` / `app-ios` / `app-windows` / `app-linux` / `app-macos` / `app-harmony` / `web`），另有 `all` = 不限平台。
> 分页的 `channel` 参数支持多选（逗号分隔，逐项「包含」匹配）；
> 存量遗留值 `app` / `pc` 仍可提交，落库时分别归一为 `app-android,app-ios` / `app-windows`。

---

## 10. 文件存储与文件管理 `astral-plugin / storage`

网页入口 `/files`，旧入口 `/imgbed` 继续渲染相同组件；后台入口 `/dashboard/plugin/storage` 的「文件」页签使用同一文件管理组件，配置、授权、任务及审计页签仍独立维护。完整需求规划与本次交付边界见 [STORAGE_REQUIREMENTS.md](STORAGE_REQUIREMENTS.md)。

### 10.1 登录用户通用接口 `/api/v1/all/storage`

由宿主校验登录态；浏览器写请求需携带 CSRF 令牌。以下权限为目录权限，所有者拥有全部权限，其他用户按显式授权校验，不继承祖先权限；`PUBLIC` 可见性不会使目录进入匿名列表。超管（`*:*:*`）可在支持豁免的操作中跳过目录权限。

| 方法 | 相对 URL | 请求与作用 |
|---|---|---|
| GET | `/folders` | 我的目录（所有者或显式 USER 授权）；返回 `parentId`、路径、存储配置及 `myPermissions`（`ALL` 或逗号分隔权限）；超管返回全部 |
| POST | `/folders` | body `{parentId?, folderName}`；根目录采用默认存储，子目录采用父目录存储/默认可见性/上传策略；在父目录下创建需 `MANAGE` |
| PUT | `/folders/{id}` | body `{folderName}`；需 `MANAGE`，仅改名，后代路径同步更新 |
| DELETE | `/folders/{id}` | 需 `MANAGE`，仅可删除无未删除文件且无子目录的空目录 |
| GET | `/files` | 分页搜索，参数见 §10.3；指定目录需 `READ`，省略目录仅查询可读取目录 |
| GET | `/folders/{folderId}/files` | 目录文件兼容查询；`current=1&size=20`，需 `READ` |
| GET | `/files/{publicId}` | 文件详情；上传者本人、超管或目录 `READ`；响应不包含 provider 定位信息 |
| PUT | `/files/{publicId}` | body `{originalName?, folderId?}`；重命名/移动需来源目录 `UPDATE`；移动需目标目录 `UPLOAD`，且目标启用、同一存储配置 |
| PUT | `/files/{publicId}/visibility?value=PUBLIC或PRIVATE` | 改可见性；上传者本人、超管或目录 `UPDATE`，沿用现有版本更新逻辑 |
| DELETE | `/files/{publicId}` | 上传者本人、超管或目录 `DELETE`；沿用现有删除流程 |
| POST | `/files/batch` | 批量删除/移动/改可见性，见 §10.3；每项沿用对应单文件权限 |
| POST | `/upload-ticket` | body `{folderId, fileName, contentType, sizeBytes}`；需 `UPLOAD`，返回短时直传凭证；超管可豁免目录权限与上传策略，存储配置仍须启用 |
| POST | `/files/register` | body `{uploadId}`；对象存储直传后登记并执行既有内容校验；Telegram 由 Worker 回调登记 |
| GET | `/uploads/{uploadId}` | 按凭证查询登记结果，供上传完成流程使用 |
| POST | `/files/{publicId}/download-url` | 返回 `{url, expiresAt}`；需目录 `READ`（超管可豁免），具体链接形态取决于渠道 |
| POST | `/files/{publicId}/permanent-url` | 返回 `{url}`；需目录 `READ`（超管可豁免），仅 `PUBLIC` 且渠道配置支持时签发 |

`FolderMineView` 字段：`id, parentId, folderName, folderPath, visibility, status, storageConfigId, myPermissions`。新建目录的授权不从父目录复制，新目录所有者为创建者。

### 10.2 管理端 `/api/v1/admin/plugin/storage`

读接口（包括签发下载/永久链接）需 `admin:storage:view`，以下写接口需 `admin:storage:edit`；方法级权限覆盖类级权限。通过管理端接口执行的文件操作豁免目录 ACL，但仍检查文件状态、移动的存储配置限制等业务规则。

| 方法 | 相对 URL | 请求与作用 |
|---|---|---|
| GET | `/manager/files` | 本次文件管理查询，参数同 §10.3；默认每页 24 条 |
| GET | `/files` | 既有后台文件查询，参数 `current=1&size=20&folderId=&keyword=`；新 UI 使用 `/manager/files` |
| PUT | `/files/{publicId}` | body `{originalName?, folderId?}`；文件重命名/移动 |
| POST | `/files/batch` | 批量操作，请求和逐项结果同 §10.3 |
| PUT | `/files/{publicId}/visibility?value=PUBLIC或PRIVATE` | 改可见性 |
| DELETE | `/files/{publicId}` | 删除文件，复用原有渠道流程 |
| POST | `/upload-ticket` | 请求/响应同通用上传凭证；后台豁免目录权限与上传策略，存储配置仍须启用 |
| POST | `/files/{publicId}/download-url` | 签发下载链接 |
| POST | `/files/{publicId}/permanent-url` | 获取永久公开链接，仍限 `PUBLIC` 且渠道配置支持 |
| GET | `/folders` | 全部目录 |
| POST | `/folders` | body `{parentId?, folderName, configId?, visibility?, policy?}`；新建目录 |
| PUT | `/folders/{id}` | body `{folderName?, visibility?, status?, configId?, policy?}`；管理目录与策略 |
| DELETE | `/folders/{id}` | 删除空目录 |
| GET | `/folders/{id}/permissions` | 查看目录授权 |
| PUT | `/folders/{id}/permissions` | body `{rows:[{subjectType, subjectId, permissions}]}`；保存目录授权 |

对象存储上传完成仍调用通用 `/files/register`；Telegram 仍由 Worker 回调，不新增浏览器文件正文上传到 Astral 的接口。既有 `/overview`、`/configs`、`/tasks`、`/audit` 接口保持原有用途。

### 10.3 查询、批量操作与数据兼容性

`GET /files`（通用）及 `GET /manager/files`（管理端）参数：

| 参数 | 规则 |
|---|---|
| `current`, `size` | 默认 `1`、`24`；页码最小 1，每页限制 1–100 |
| `folderId` | 可选；省略时跨可读取目录搜索，管理端搜索全部目录 |
| `keyword` | 文件原始名称包含匹配，最长 255 字符 |
| `category` | 可选，按 MIME 匹配 `image` / `audio` / `video` / `document` / `archive`，选项字典 `storage_file_category` |
| `visibility` | 可选 `PUBLIC` / `PRIVATE`，选项字典 `storage_visibility` |
| `sort` | `newest`（默认）/ `oldest` / `name` / `size`，分别为时间倒序/时间正序/名称正序/大小倒序；字典 `storage_file_sort` |

响应为 `Result<Page<StorageFileEntity>>`（`records, total, current, size` 等分页字段）；排除 `DELETED`，其他状态仍返回供展示，provider 定位信息被抹除。文件重命名/移动仅限 `AVAILABLE`，名称不能含路径分隔符、控制字符或仅为 `.`/`..`；文件名最长 255，目录名最长 128，目录路径最长 512 字符。

批量请求为 `{publicIds:[...], action, folderId?, visibility?}`，`publicIds` 需 1–100 项，重复 ID 去重执行。`action` 为 `delete`、`move`（必填 `folderId`）、`visibility`（必填合法 `visibility`）。合法请求的 `data` 为 `{succeeded:[publicId,...], failed:[{publicId,message},...]}`；每个文件独立提交，部分失败不回滚其他成功项，调用方需检查 `failed` 而不能只检查外层成功码。

重命名/移动不修改对象键、provider 定位、publicId 或内容版本，因此既有链接保持有效；跨存储配置移动未实现。删除与可见性变更仍走原有渠道及版本流程，不能用目录移动替代物理对象迁移。

部署需同时更新前后端，并执行 Flyway 迁移 `V20261018003__storage_file_manager_dict.sql`（只新增字典，无表结构变更）。本次没有交付回收站、匿名目录浏览、独立分享、分片/断点续传或自动缩略图。
