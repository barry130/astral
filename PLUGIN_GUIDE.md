# Astral 插件化开发指南

本文档介绍如何在统一的 `astral-plugin` 模块中增加业务插件。后续插件不再创建独立 Maven 模块，也不需要修改根 POM、`astral-server/pom.xml` 或 Dockerfile。

## 插件架构

```text
astral-plugin-api                         # 稳定 SPI，仅定义插件契约
astral-plugin/
├── src/main/java/com/astral/plugin/core # 注册中心与生命周期管理
├── src/main/java/com/astral/qt          # 轻听音乐插件
├── src/main/java/com/astral/feedback    # 反馈与统一通知插件
└── src/main/resources                   # 各插件的 schema、SQL 等资源
astral-sequence                           # 系统必需插件及统一序列服务
```

`astral-plugin-api` 保持独立，是为了让 `astral-sequence` 使用插件 SPI，同时避免统一插件模块与序列模块形成 Maven 循环依赖。所有业务插件实现都放入 `astral-plugin`。

插件实现被 Spring 扫描为 Bean 后，由 `PluginRegistry` 自动发现并注册。后台插件管理页可控制运行时启停状态，状态持久化在 `sys_plugin`。

## 新增插件（以 uniappx 为例）

### 1. 建立业务目录

直接在统一模块中创建目录，不新增 POM：

```text
astral-plugin/src/main/java/com/astral/uniappx/
├── UniappxPlugin.java
├── controller/
├── service/
├── mapper/
├── entity/
├── dto/
└── config/
```

资源统一放在 `astral-plugin/src/main/resources` 下，并使用插件专属名称避免冲突，例如：

```text
sql/uniappx-schema.sql
schema/uniappx_order.json
```

### 2. 实现插件接口

```java
package com.astral.uniappx;

import com.astral.plugin.api.AstralPlugin;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UniappxPlugin implements AstralPlugin {
    @Override
    public String getPluginId() {
        return "uniappx";
    }

    @Override
    public String getPluginName() {
        return "UniAppX 业务";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "处理 UniAppX 移动端业务请求";
    }

    @Override
    public List<String> getApiPrefixes() {
        return List.of("/api/v1/app/uniappx", "/api/v1/admin/uniappx");
    }
}
```

API 前缀必须精确到插件拥有的 Controller 根路径，不要声明 `/api/v1/app` 或 `/api/v1/admin` 这类宽泛前缀，否则禁用插件时会误拦截其他业务。

### 3. 增加启动配置

在 `astral-server/src/main/resources/application.yml` 中增加插件启动配置：

```yaml
astral:
  plugins:
    uniappx:
      enabled: ${UNIAPPX_PLUGIN_ENABLED:true}
```

需要按配置决定是否创建的 Web 配置、初始化器或任务，可使用：

```java
@ConditionalOnProperty(
    name = "astral.plugins.uniappx.enabled",
    havingValue = "true",
    matchIfMissing = true
)
```

配置开关在应用启动时生效；后台插件管理页操作的是 `sys_plugin` 中的运行时状态，两者职责不同。

### 4. 编写业务代码

Controller、Service、Mapper、Entity 和配置都放在 `com.astral.uniappx` 下。主应用通过 `@ComponentScan("com.astral")` 自动扫描组件。

如果使用独立 Mapper 包，应提供配置：

```java
@Configuration
@MapperScan("com.astral.uniappx.mapper")
public class UniappxMapperConfig {
}
```

数据库结构变更应遵循 `astral-server/src/main/resources/db/migration/README.md` 的 Flyway 增量迁移规范（新增 `V{yyyyMMddNNN}__xxx.sql`，启动时自动应用；已发布脚本禁止修改）。插件自建 `*SchemaInitializer` 仅限幂等建表（`CREATE TABLE IF NOT EXISTS`）。

### 5. 注册前端导航（可选）

```java
@Component
public class UniappxFrontendExtension implements PluginFrontendExtension {
    @Override
    public String getPluginId() {
        return "uniappx";
    }

    @Override
    public List<NavItem> getNavItems() {
        return List.of(new NavItem(
            "UniAppX 业务", "/dashboard/uniappx", "AppstoreOutlined", 90
        ));
    }
}
```

然后在 `astral-front/src/app/dashboard/uniappx/page.tsx` 创建页面。导航项的 `pluginId` 必须与 `AstralPlugin#getPluginId()` 一致。

## 系统必需插件

实现 `isRequired()` 并返回 `true`，可禁止后台关闭该插件：

```java
@Override
public boolean isRequired() {
    return true;
}
```

`SequencePlugin` 是现有的系统必需插件，负责统一 ID/序列能力。

## 完整参考

统一模块中的 Qt 插件展示了完整业务域的实现方式：

| 关注点 | 实现位置 |
|---|---|
| 插件定义与导航 | `com.astral.qt.QtPlugin` |
| App 用户与认证 | `com.astral.qt.service.QtUserService`、`QtWebConfig` |
| Controller | `com.astral.qt.controller` |
| Mapper 注册 | `com.astral.qt.config.QtMapperConfig` |
| 数据库资源 | `resources/sql/qt-schema.sql`、`resources/schema/qt_*.json` |

反馈与统一通知实现位于 `com.astral.feedback`，其资源为 `resources/sql/feedback-schema.sql`。

## 开发检查清单

- 插件 ID 唯一，且插件、导航扩展、配置中的 ID 一致。
- API 前缀精确，不覆盖其他插件或宿主 API。
- 新代码全部位于 `astral-plugin` 的独立业务 package。
- 资源文件使用插件专属前缀，避免 classpath 同名冲突。
- 数据库变更以只增不改的版本化迁移脚本提交。
- Mapper 包已注册，Controller 的认证范围已明确。
- 实体主键统一 `@TableId(type = IdType.INPUT)`，并保证实体至少有一个字段带 `@TableField(fill=...)`
  （通常为 `createTime`=INSERT / `updateTime`=INSERT_UPDATE；表无 create_time 列时在该表时间字段上挂 INSERT fill 触发），
  ID 由宿主 `SequenceMetaObjectHandler` 按业务键 `{表名}_id` 从全局序列自动填充，**不要写显式取号服务**。
- 分别验证插件启用、运行时禁用和启动配置关闭场景。
- 构建验证：后端 `mvn compile`（当前仓库无测试目录，`mvn test` 暂为空转项；一旦补测试，此条改为 `mvn -pl astral-server -am test`）；前端 `npm run build`。
