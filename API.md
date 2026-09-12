# Astral 全项目接口文档

> 整理自 `astral` 仓库全部 Controller。基础路径 `http://localhost:27000`，前缀 `/api/v1`。
> 认证：管理端接口走宿主 Sa-Token（`satoken` 头，管理员）；App 接口走插件自身鉴权（`satoken` 头，App 用户）。
> 返回包装：管理端宿主 `Result<T>{code,msg,data}`；反馈/轻听 App 端用各自 `FeedbackRestResp`/`QtRestResp{code,msg,data}`。

---

## 1. 认证鉴权 `astral-auth`

### 1.1 账号认证 `/api/v1/auth`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/public-key` | 获取 RSA 公钥（登录密码加密用） |
| POST | `/login` | 用户名密码登录（RSA 密文密码，限流 5次/60s，成功返回 token+用户信息） |
| POST | `/logout` | 登出，使当前 token 失效 |
| GET | `/info` | 获取当前登录用户信息（含角色/权限列表） |

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

### 5.2 统计报表 `/api/v1/stat`（admin 前端，权限 `statistics:view`）
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/overview?date=&ut=` | 设备统计概览（今日 vs 昨日） |
| GET | `/trend?metric=&date=&ut=&gran=` | 指标24小时趋势（默认 pv/hour） |
| GET | `/api/top?limit=&date=` | 接口调用 Top 榜（默认10） |
| GET | `/api/trend?uri=&method=&date=` | 单接口24小时调用趋势 |
| GET | `/error/page?pageNum=&pageSize=&errorType=&appVersion=&fingerprint=` | 错误明细分页 |
| GET | `/error/summary?date=` | 错误分组汇总（按 fingerprint） |

### 5.3 统计上报 `/api/v1/stat`（App 匿名，单批≤200）
| 方法 | URL | 作用 |
|---|---|---|
| POST | `/report` | 匿名批量上报统计事件（恒返回200，失败不影响客户端） |

---

## 6. 集群 `astral-server` `/api/v1/cluster`（仅管理员）

| 方法 | URL | 作用 |
|---|---|---|
| GET | `/health` | 集群健康检查（返回 ok） |
| GET | `/nodes` | 在线节点列表 |
| GET | `/nodes/all` | 全部节点（含离线） |
| GET | `/status` | 集群整体状态 |
| GET | `/current` | 当前节点信息 |
| GET | `/node/{nodeId}` | 指定节点状态 |
| POST | `/node/{nodeId}/offline` | 节点下线 |

---

## 7. 插件管理 `astral-plugin` `/api/v1/plugin`

| 方法 | URL | 作用 |
|---|---|---|
| GET | `/list` | 所有插件 |
| GET | `/enabled` | 已启用插件 |
| POST | `/{pluginId}/enable` | 启用插件 |
| POST | `/{pluginId}/disable` | 禁用插件 |
| GET | `/nav-extensions` | 前端导航扩展（仅已启用插件） |
| GET | `/configs` | 插件默认配置 |

### 7.1 示例插件 `/api/v1/plugin/demo`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/hello` | 示例接口（返回 message/status） |

---

## 8. 轻听插件 `astral-plugin-qt`

### 8.1 App 用户 `/api/v1/user`（App 用户，satoken）
| 方法 | URL | 作用 |
|---|---|---|
| POST | `/login` | 登录（返回 token+用户信息） |
| POST | `/refresh` | 刷新 token |
| GET | `/me` | 根据 token 获取用户信息 |
| POST | `/logout` | 退出登录 |
| POST | `/email` | 发送邮箱验证码 |
| POST | `/register` | 注册（邮箱+验证码） |
| POST | `/upload` | 上传头像（multipart `avatar`） |
| POST | `/update` | 更新用户信息 |
| POST | `/changePass` | 邮箱验证码重置密码 |
| POST | `/daka` | 用户签到 |
| GET | `/dakaInfo` | 连续签到天数+总积分 |
| GET | `/dakaInfoByMonth?time=` | 某年某月签到详情 |
| GET | `/getLikeList` | 用户收藏歌单+歌曲 |
| POST | `/uploadLikeList` | 同步收藏歌单+歌曲 |

### 8.2 App 公告 `/api/v1/user/notice`（⚠️ 已废弃，迁至反馈插件 `/api/v1/app/message/**`）
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/center` | 消息中心列表 |
| GET | `/unread/count` | 未读数 |
| POST | `/read` | 批量标记已读 |

### 8.3 App 公共 `/api/v1/app`（免认证）
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/notice?version=&satoken=` | 生效中的公告（按版本/登录人群过滤） |
| GET | `/update?type=&version=&channel=` | APK 更新信息（type=1101/1102） |
| GET | `/version/check?type=&version=&versionName=` | 校验是否官方版本 |

### 8.4 管理端 `/api/v1/qingting/admin`（宿主管理员）
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/overview` | 概览统计（用户/签到/公告/更新数） |
| GET | `/users?pageNum=&pageSize=&keyword=` | App 用户分页 |
| PUT | `/users/{id}/state?state=` | 封禁/解封 |
| GET | `/notices` | 公告列表 |
| POST | `/notices` | 新增公告 |
| PUT | `/notices/{id}` | 更新公告 |
| DELETE | `/notices/{id}` | 删除公告 |
| GET | `/updates` | 版本更新列表 |
| POST | `/updates` | 新增版本更新 |
| PUT | `/updates/{id}` | 更新版本 |
| DELETE | `/updates/{id}` | 删除版本 |

---

## 9. 反馈插件 `astral-plugin-feedback`

### 9.1 App 反馈 `/api/v1/app/feedback`（App 用户，satoken）
| 方法 | URL | 作用 |
|---|---|---|
| POST | `/submit` | 提交反馈（type/title/content/contact；Header X-Device/X-OS/X-App-Version/X-Platform） |
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
