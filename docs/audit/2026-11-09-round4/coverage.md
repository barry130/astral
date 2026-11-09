# 第四轮定向覆盖记录

本轮固定起点 `9679cec2082d905df96186748c7413d3b72c4b8b`，产品提交 `bc16a9fe981a346c4a2f57b56b8fbd0d2268c755`。9 个改动文件均已整读并经过对应构建或回归。前几轮全项目覆盖见 [首轮验证](../2026-11-08/verification.json)、[第二轮续审](../2026-11-09/CONTINUATION.md) 和 [第三轮续审](../2026-11-09-round3/CONTINUATION.md)；本轮是有证据的定向深入检查。

| 区域与文件 | 本轮检查与观察 | 结论或边界 |
| --- | --- | --- |
| `MessageTemplateService.java`、`MessageTemplateSaveDto.java`、公开 `MessageTemplateController.java` | 所选渠道、短信有效状态、完整表单清空、变量归一化、白名单更新、事务及缓存提交时序 | 三组确认问题已修复；公开 Service 回归覆盖，未声称完整 HTTP 验收 |
| `SysMailTemplate.java`、`sys_mail_template.json`、生成器及实际 Flyway 定义 | 名称 64 字符、各 VARCHAR 约束、实际代码生成后规则保留 | 修正邮件 schema 的 128→64；数据库 DDL 未改，旧迁移和校验和保持 |
| `SysSmsTemplate.java`、`sys_sms_template.json`、`V20261002003__sms_channel.sql`、审核状态字典 | 供应商模板编码必需、状态 0–3、实际列宽及缺省值 | 合并入口补齐已有合同；事件尚未使用的渠道仍允许预配置 |
| `MailTemplateController.java`、`SysMailTemplateServiceImpl.java` | 单渠道 POST/PUT 校验、局部更新有效状态、变量、真实删除调用方与缓存 | POST/PUT 补 `@Valid`；不把通用 CRUD 或假设的外层事务直接记为缺陷 |
| `SmsTemplateController.java`、`SysSmsTemplateServiceImpl.java`、`SmsServiceImpl.java` | 保留既有 partial 契约、映射读取、签名回退、提交后缓存 | 既有 21 项 partial/缓存回归仍执行；内存供应商观察不等于真实短信 |
| `NotifyEventRegistry.java`、合并表单及 API 类型调用方 | 事件 payload、渠道预配置、全量保存字段来源 | 模板变量按事件子集校验；未选短信字段不阻止 EMAIL/INAPP 保存 |
| 权限定义 Controller/Service、注册器、目录和权限缓存的实际调用链 | 已有定义写入与 cache bump 的提交顺序、继承 CRUD 的实际使用 | 没有建立新的确认缺陷；不把相邻源码检查扩称为全入口并发证明 |
| 四个改动测试类 | 实际公开边界、真实 Validator、MyBatis/H2/Spring、独立期望值、资源关闭 | 4 套 / 52 项聚焦回归，全部包含在最终 reactor，原有断言保留 |

## 产品与测试文件清单

- `astral-dao/src/main/java/com/astral/dao/entity/SysMailTemplate.java`（由 generator 生成）。
- `astral-schema/src/main/resources/schema/sys_mail_template.json`。
- `astral-system/src/main/java/com/astral/system/controller/MailTemplateController.java`。
- `astral-system/src/main/java/com/astral/system/dto/MessageTemplateSaveDto.java`。
- `astral-system/src/main/java/com/astral/system/notify/MessageTemplateService.java`。
- `astral-system/src/test/java/com/astral/system/controller/MailTemplatePatchPersistenceTest.java`。
- `astral-system/src/test/java/com/astral/system/controller/SmsTemplatePatchPersistenceTest.java`。
- `astral-system/src/test/java/com/astral/system/notify/MessageTemplatePersistenceTest.java`。
- `astral-system/src/test/java/com/astral/system/notify/MessageTemplateVariableTest.java`。

本轮未改前端、博客、Cloudflare、Telegram、部署、CI 或兄弟项目。未执行高并发压测、真实 SMTP/SMS/模型请求、生产库操作或浏览器诊断。浏览器扩展未就绪，原授权禁止替代 CDP/Playwright/bootstrap；合成与构建证据不升格为浏览器 GREEN。更细的公开边界见 [TEST-SEAMS.md](TEST-SEAMS.md)。
