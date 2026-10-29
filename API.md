# Astral 全项目接口文档

> 整理自 `astral` 仓库全部 Controller。基础路径 `http://localhost:27000`，前缀 `/api/v1`。
> 认证：**管理台（浏览器）** 凭 HttpOnly Cookie `satoken` 自动认证，写请求需回填 `X-CSRF-Token`；
> **轻听 App / 脚本 / Swagger 等非浏览器调用方** 走 `satoken` 请求头（该路径豁免 CSRF 校验）。
> 两种方式都由宿主 Sa-Token 解析，详见 [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md#token-管理httponly-cookie--csrf-双提交)。
> 返回包装：全端统一 `Result<T>{code,errorCode,msg,data,timestamp}`（管理端与 App 端共用同一个类）；
> **两层语义**：HTTP 状态码只表达传输层（`200`=服务端已处理，业务失败也是 `200`；`404/405/413/500/503` 才是传输错误），
> 业务结论看 body 的 `code`（`200` 成功 / `300` 业务失败 / `320` 参数错误 / `401` 未登录 / `403` 无权限 / `429` 限流 / `500` 系统错误）。

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

### 1.2 Token 管理 `/api/v1/admin/system/token`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/page` | 会话分页（含 device 列） |
| PUT | `/revoke` | 吊销指定 token |
| PUT | `/kick` | 踢指定用户下线 |
| DELETE | `/expired` | 清理过期会话 |
| PUT | `/revoke-all` | 全端会话重置（超管） |

---

## 2. 系统管理 `astral-system`

### 2.1 用户 `/api/v1/admin/system/user`
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

### 2.2 角色 `/api/v1/admin/system/role`
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

### 2.3 权限 `/api/v1/admin/system/permission`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/page` | 分页查询 |
| GET | `/{id}` | 详情 |
| POST | `/` | 创建 |
| PUT | `/{id}` | 更新 |
| DELETE | `/{id}` | 删除（已被角色引用则报 SYS006） |
| GET | `/tree` | 权限树 |

### 2.4 菜单 `/api/v1/admin/system/menu`
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
| 字典 | GET | `/api/v1/admin/system/dict/page` | 分页查询字典数据 |
| 字典 | GET | `/api/v1/admin/system/dict/{id}` | 字典数据详情 |
| 字典 | POST | `/api/v1/admin/system/dict` | 创建字典数据 |
| 字典 | PUT | `/api/v1/admin/system/dict/{id}` | 更新字典数据 |
| 字典 | DELETE | `/api/v1/admin/system/dict/{id}` | 删除字典数据 |
| 类型 | GET | `/api/v1/admin/system/dict/type/page` | 分页查询字典类型 |
| 类型 | GET | `/api/v1/admin/system/dict/type/all` | 全部字典类型 |
| 类型 | GET | `/api/v1/admin/system/dict/type/{id}` | 类型详情 |
| 类型 | POST | `/api/v1/admin/system/dict/type` | 创建类型 |
| 类型 | PUT | `/api/v1/admin/system/dict/type/{id}` | 更新类型 |
| 类型 | DELETE | `/api/v1/admin/system/dict/type/{id}` | 删除类型 |
| 数据 | GET | `/api/v1/admin/system/dict/data/page?dictTypeId=` | 分页查询（可按类型筛选） |
| 数据 | GET | `/api/v1/admin/system/dict/data/{id}` | 详情 |
| 数据 | GET | `/api/v1/admin/system/dict/data/byCode?code=` | 按字典类型编码查启用数据（下拉用） |
| 数据 | POST | `/api/v1/admin/system/dict/data` | 创建 |
| 数据 | PUT | `/api/v1/admin/system/dict/data/{id}` | 更新 |
| 数据 | DELETE | `/api/v1/admin/system/dict/data/{id}` | 删除 |

### 2.6 系统配置 `/api/v1/admin/system/config`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/page` | 分页查询 |
| GET | `/{id}` | 详情 |
| POST | `/` | 创建 |
| PUT | `/{id}` | 更新 |
| DELETE | `/{id}` | 删除 |

### 2.7 表结构 `/api/v1/admin/system/table-schema`
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
| GET | `/{tableName}/sql` | 生成建表 SQL（`?dialect=mysql|postgresql`，默认 postgresql） |

### 2.8 关联表（简单 CRUD）
| 模块 | 前缀 | 作用 |
|---|---|---|
| 用户角色 | `/api/v1/admin/system/user_role` | 分页/详情/增删改 |
| 角色权限 | `/api/v1/admin/system/role_permission` | 同上 |

### 2.9 邮件 `/api/v1/admin/system/mail`
| 模块 | 方法 | URL | 作用 |
|---|---|---|---|
| 账户 | GET | `/account/page` | 分页 |
| | GET | `/account/{id}` | 详情 |
| | POST | `/account` | 新增 |
| | PUT | `/account/{id}` | 更新 |
| | DELETE | `/account/{id}` | 删除 |
| | POST | `/account/{id}/toggle?enabled=` | 启用/停用 |
| | POST | `/account/test?accountId=&toEmail=` | 测试发送 |
| 模板 | GET | `/template/page` | 分页 |
| | GET | `/template/{id}` | 详情 |
| | POST | `/template` | 新增 |
| | PUT | `/template/{id}` | 更新 |
| | DELETE | `/template/{id}` | 删除 |
| | POST | `/template/preview` | 模板预览（body：`{templateId,variables}`） |
| | POST | `/template/{id}/send` | 模板试发 |
| 日志 | GET | `/log/page` | 分页（accountId/pluginId/toEmail/status/时间区间筛选） |

> 事件注册表 `GET /api/v1/admin/system/notify/event`（见 §2.10）。发信授权不再走独立表——投递权限/收件人/限额统一由事件订阅规则决定。

### 2.10 事件订阅规则 `/api/v1/admin/system/notify`（权限 `admin:system:notify:rule:view/edit`）
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/event` | 全部注册事件（代码注册的只读契约：事件码/payload字段/可用渠道/必达渠道/发送方） |
| GET | `/event/list` | 事件下拉选项 |
| GET | `/channel/list` | 渠道下拉选项 |
| GET | `/template?eventCode=&channel=` | 按事件+渠道查可用模板（渠道下拉数据源） |
| GET | `/rule/page?pageNum=&pageSize=&eventCode=&enabled=` | 规则分页 |
| GET | `/rule/{id}` | 规则详情（含渠道与收件人） |
| POST | `/rule` | 新建规则（同一事件可建多条；每条规则可挂多渠道，每渠道可设日限额/平台） |
| PUT | `/rule/{id}` | 更新规则 |
| DELETE | `/rule/{id}` | 删除规则 |
| POST | `/publish` | 发布测试（按规则投递一条真实消息） |

> 三表模型：`sys_notify_rule`（规则）→ `sys_notify_rule_channel`（渠道：template_id/daily_limit/platform）→ `sys_notify_rule_recipient`（收件人：FIXED/PAYLOAD_FIELD/ROLE）。
> ROLE 按角色展开启用用户；platform 仅 INAPP 生效；每日限额按「规则×渠道×收件人」Redis 计数。

### 2.11 消息模板 `/api/v1/admin/system/message/template`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/list?scene=&channel=` | 模板列表（scene=事件码） |
| GET | `/options?scene=&channel=` | 模板下拉（事件+渠道绑定用） |
| POST | `/save` | 新建/更新（校验 scene∈注册表、channel∈事件声明渠道、variables 覆盖 payload 全字段、(scene,channel) 唯一） |
| DELETE | `?id=` | 删除 |
| POST | `/preview` | 变量渲染预览 |

### 2.12 短信 `/api/v1/admin/system/sms`（权限 `admin:system:notify:sms:view/edit`）
| 模块 | 方法 | URL | 作用 |
|---|---|---|---|
| 供应商 | GET | `/provider/page` | 分页 |
| | GET | `/provider/{id}` | 详情（access_secret 出参永远抹除） |
| | GET | `/provider/types` | 供应商类型列表（MOCK/ALIYUN） |
| | POST | `/provider` | 新增 |
| | PUT | `/provider/{id}` | 更新（access_secret 留空不改） |
| | DELETE | `/provider/{id}` | 删除 |
| 模板 | GET | `/template/page` | 分页 |
| | GET | `/template/{id}` | 详情 |
| | POST | `/template` | 新增（事件 → 供应商模板code + 签名） |
| | PUT | `/template/{id}` | 更新 |
| | DELETE | `/template/{id}` | 删除 |
| | POST | `/template/{id}/send` | 模板试发 |
| 日志 | GET | `/log/page` | 分页 |

> 短信正文在供应商侧备案审核，本地不渲染；`sys_sms_template` 只存「事件 → provider_template_code + 签名 + 审核状态」映射。

### 2.13 消息统计 `/api/v1/admin/system/message/statistics`（权限 `admin:system:mail:statistics:view`）
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/overview?startDate=&endDate=&channel=&scene=&plugin=&status=` | 概览（total/success/fail + channels/scenes/plugins 三个维度 breakdown） |
| GET | `/trend?granularity=&startDate=&endDate=&channel=&scene=&plugin=&status=` | 按天趋势（稠密补 0） |
| GET | `/list?pageNum=&pageSize=&channel=&scene=&plugin=&status=&startDate=&endDate=` | 跨渠道合并明细（按 sendTime 倒序） |

> EMAIL/SMS 日志 + INAPP sys_notice 合并统计；站内信无失败态（status=0 只统计邮件/短信）。

### 2.14 站内信门户 `/api/v1/all/notify/inapp`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/my/page` | 我的站内信分页 |
| GET | `/unread-count` | 未读数 |
| POST | `/{id}/read` | 标记已读 |
| POST | `/read-all` | 全部已读 |

---

## 3. 日志 `astral-log`

### 3.1 操作日志 `/api/v1/admin/log/operate_log`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/page` | 分页 |
| GET | `/{id}` | 详情 |
| POST | `/` | 创建 |
| PUT | `/{id}` | 更新 |
| DELETE | `/{id}` | 删除 |

### 3.2 登录日志 `/api/v1/admin/log/login_log`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/page` | 分页 |
| GET | `/{id}` | 详情 |
| POST | `/` | 创建 |
| PUT | `/{id}` | 更新 |
| DELETE | `/{id}` | 删除 |

---

## 4. 序列生成 `astral-sequence`

### 4.1 生成 `/api/v1/all/sequence`
| 方法 | URL | 作用 |
|---|---|---|
| POST | `/next` | 获取单个序列号（body：`{bizKey,type}`，限流 100/60s） |
| POST | `/batch` | 批量获取序列（body：`{bizKey,count,type}`，限流 50/60s） |
| GET | `/types` | 所有支持的类型（SNOWFLAKE/SEGMENT/REDIS/DATABASE/SIMPLE） |

### 4.2 配置管理 `/api/v1/admin/sequence/configs`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/` | 全部配置 |
| GET | `/page?bizKey=` | 分页（可按业务键模糊） |
| GET | `/{bizKey}` | 按业务键获取配置 |
| POST | `/` | 创建（系统内置 `_id` 序列禁止创建 SEQ008） |
| PUT | `/{id}` | 更新 |
| DELETE | `/{id}` | 删除 |
| PUT | `/{id}/toggle?enabled=` | 启用/禁用 |

### 4.3 号段 `/api/v1/admin/sequence/segment` 与统计 `/api/v1/admin/sequence/statistics`
均为标准 CRUD（page/getById/create/update/delete）。

### 4.4 历史 `/api/v1/admin/sequence/history`
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

### 7.1 App 用户 `/api/v1/app/user`（App 用户，satoken）
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

### 7.2 App 公共 `/api/v1/app`（免认证）
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/update?type=&version=&channel=` | APK 更新信息（type=1101/1102） |
| GET | `/version/check?type=&version=&versionName=` | 校验是否官方版本 |
| GET | `/github/accels` | GitHub 加速节点列表 |
| GET | `/source/manifest?platform=` | 音源包清单 |
| POST | `/source/report` | 音源包可用性上报 |

### 7.3 管理端 `/api/v1/admin/qt`（宿主管理员）
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

### 8.1 App 反馈 `/api/v1/app/feedback`（App 用户，satoken）
| 方法 | URL | 作用 |
|---|---|---|
| POST | `/submit` | 提交反馈（type/title/content/contact；统一客户端系统头 X-App-Ut/X-App-Version/X-Device/X-OS，见 COMPONENTS_GUIDE.md） |
| GET | `/my?pageNum=&pageSize=` | 我的反馈分页 |
| GET | `/public?pageNum=&pageSize=` | 公开列表（status=published AND is_public=true） |
| GET | `/{id}` | 详情（本人 或 published+public 可见） |
| GET | `/{id}/replies` | 回复列表 |
| POST | `/reply` | 用户回复（同时通知管理员） |

### 8.2 管理端反馈 `/api/v1/admin/feedback`（宿主管理员）
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

---

## 9. 站内通知（宿主 `astral-system / notice`）

### 9.1 App 通知 `/api/v1/app/message`
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/active?versionCode=&channel=` | 当前生效通知（**公开**，三展示位共用；`channel=pc|app` 旧值兼容） |
| GET | `/center` | 消息中心（需登录；含 `url` 跳转链接字段，如 `astral://feedback/1000002`） |
| GET | `/unread-count` | 未读数 |
| POST | `/read-ack` | 已读回执（body：`{ids:[]}`） |

> **投放平台识别（老客户端兼容）**：三个查询接口都按
> `X-App-Ut` 头 → 遗留 `X-Platform` 头 → `channel` 查询参数 → 缺省 App 的顺序解析，
> 详见 `NoticeTargets.resolve`。新客户端只发统一平台头即可；
> 老客户端写死的 `channel=app|pc|web|all` 继续被识别
> （`app`→Android+iOS、`pc`→Windows、`web`→Web、`all`→不限平台），
> 三者皆空时回落 Android+iOS（等价于旧的 `channel=app` 缺省）。

### 9.2 管理端通知 `/api/v1/admin/message`（宿主管理员）
| 方法 | URL | 作用 |
|---|---|---|
| GET | `/inbox` | 管理端铃铛收件箱 |
| GET | `/page?pageNum=&pageSize=&channel=&noticeType=&keyword=&startDate=&endDate=` | 通知分页 |
| POST | `/` | 发布通知 |
| PUT | `/{id}` | 编辑通知 |
| DELETE | `/{id}` | 删除通知 |

> 状态机：`pending提出 → received已接收 → resolved已解决 → published已发布`；任意状态→`deprecated已废弃`；已废弃可转回任意状态。
> 通知类型：`notice` 通知（点对点站内信）/ `announce` 公告（广播，`user_id` 为空）。
> 存量行里的 `feedback` / `request` 是历史别名，服务端查询照样命中、渲染归并为「通知」，
> 但新建通知只允许 `notice` / `announce` 两值（`notice_type` 字典里两项已 `status=0`）。
> 「反馈 / 需求」的细分在反馈侧（`sys_feedback.type` + `feedback_type` 字典），不属于通知类型。
> 通知渠道 `channel`：**逗号分隔的投放平台集合**，取值与 `stat_platform` 同源
> （`app-android` / `app-ios` / `app-windows` / `app-linux` / `app-macos` / `app-harmony` / `web`），另有 `all` = 不限平台。
> 分页的 `channel` 参数支持多选（逗号分隔，逐项「包含」匹配）；
> 存量遗留值 `app` / `pc` 仍可提交，落库时分别归一为 `app-android,app-ios` / `app-windows`。
> 「仅首次启动弹出」开关（`termsVersion` 非空 = 客户端首启免责声明）：打开时渠道强制 App 六端、
> `dialog_closable` 强制 1、`read_seconds` 夹 [0,60]；关闭时这些字段写回 NULL。

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

---

## 11. CMS 博客插件 `astral-plugin / cms`

管理端 `/api/v1/admin/cms/{post,page,category,tag,comment,share,setting,export}`、App 端 `/api/v1/app/cms`（10 个公开接口）。
完整接口契约见 [docs/CMS_PLUGIN.md](docs/CMS_PLUGIN.md)。

## 12. AI 助手插件 `astral-plugin / ai`

管理端 `/api/v1/admin/ai`（供应商/智能体/提示词/工具/知识库/用量/请求日志，70+ 端点）、App 端 `/api/v1/app/ai`（SSE 对话/会话/用量，12 端点）。
设计文档见 [AI_MODULE_DESIGN.md](AI_MODULE_DESIGN.md)。
