# Astral 插件化开发指南

本文档介绍如何将新的功能模块（例如 uniappx 业务后端）作为插件集成到 Astral 后台管理系统中。

## 插件架构概览

```
astral-plugin-api    # 插件 SPI 接口定义（必须依赖）
astral-plugin        # 插件注册中心与生命周期管理（核心模块）
astral-plugin-demo   # 示例插件（可复制为模板）
astral-plugin-qt     # 完整业务插件参考：轻听音乐 App 后端（用户/打卡/收藏/公告/更新）
astral-sequence      # 序列服务插件（默认开启，参考实现）
```

插件通过 Spring 的自动配置机制被发现，注册到 `PluginRegistry` 后即可在后台"插件管理"页面中启用/禁用。

### 插件 API 前缀声明（重要）

实现 `getApiPrefixes()` 声明插件暴露的 REST 路径前缀后，插件一旦被禁用，
匹配这些前缀的请求会被 `PluginApiInterceptor` 拦截并返回 `PLUGIN001` 错误：

```java
@Override
public List<String> getApiPrefixes() {
    return List.of("/api/v1/admin/plugin/demo");  // 你的插件接口前缀
}
```

### 系统必需插件（不允许禁用）

实现 `isRequired()` 返回 `true` 可将插件标记为**系统必需**：管理端禁用操作返回
`PLUGIN002` 错误，前端插件管理页的开关被锁定。参考 `SequencePlugin`：

```java
@Override
public boolean isRequired() { return true; }
```

参考实现：`SequencePlugin`（astral-sequence 模块）是系统必需插件，声明 `/api/v1/sequence`
前缀并承载全部业务实体的全局 ID 生成（业务键=数据库名_id，号段模式，内置序列锁定不可改）。

## 创建新插件（以 uniappx 为例）

### 步骤 1：创建 Maven 模块

```xml
<!-- uniappx-plugin/pom.xml -->
<project>
    <parent>
        <groupId>com.astral</groupId>
        <artifactId>astral</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>uniappx-plugin</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.astral</groupId>
            <artifactId>astral-plugin-api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.astral</groupId>
            <artifactId>astral-common</artifactId>
        </dependency>
        <!-- 如需要数据库操作，添加 astral-dao -->
        <dependency>
            <groupId>com.astral</groupId>
            <artifactId>astral-dao</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
    </dependencies>
</project>
```

### 步骤 2：实现 AstralPlugin 接口

```java
@Service
public class UniappxPlugin implements AstralPlugin {

    @Override
    public String getPluginId() { return "uniappx"; }

    @Override
    public String getPluginName() { return "UniAppX 业务后端"; }

    @Override
    public String getVersion() { return "1.0.0"; }

    @Override
    public String getDescription() { return "处理 uniappx 移动端业务请求"; }

    @Override
    public List<String> getApiPrefixes() { return List.of("/api/v1/app"); }

    @Override
    public void onEnable() {
        // 插件启用时的初始化逻辑（如启动定时任务、加载配置）
    }

    @Override
    public void onDisable() {
        // 插件禁用时的清理逻辑
    }
}
```

### 步骤 3：注册插件到根 POM

在根 `pom.xml` 的 `<modules>` 中添加 `uniappx-plugin`，并在 `astral-server/pom.xml` 中引入依赖：

```xml
<dependency>
    <groupId>com.astral</groupId>
    <artifactId>uniappx-plugin</artifactId>
</dependency>
```

### 步骤 4：配置启用开关

在 `application.yml` 中：

```yaml
astral:
  plugins:
    uniappx:
      enabled: ${UNIAPPX_PLUGIN_ENABLED:false}
```

### 步骤 5：编写业务代码

插件内可以任意使用 Controller / Service / Mapper / Entity，包名建议使用 `com.astral.uniappx.*`，会被主应用的 `@ComponentScan("com.astral")` 自动扫描。

### 步骤 6：前端扩展（可选）

实现 `PluginFrontendExtension` 接口，在后台侧边栏动态注册菜单：

```java
@Component
public class UniappxFrontendExtension implements PluginFrontendExtension {

    @Override
    public String getPluginId() { return "uniappx"; }

    @Override
    public List<NavItem> getNavItems() {
        return List.of(
            new NavItem("UniAppX 业务", "/dashboard/uniappx", "AppstoreOutlined", 90)
        );
    }
}
```

然后在 `astral-front/src/app/dashboard/uniappx/page.tsx` 创建对应页面。

## 完整业务插件参考（astral-plugin-qt）

`astral-plugin-qt`（轻听音乐 App 后端）是功能最完整的插件示例，展示了从零打造独立业务域需要覆盖的要点：

| 关注点 | 实现示例 | 说明 |
|--------|----------|------|
| 统一用户体系 | 宿主 `sys_user` 表 + `QtUserService`，通过 `user_type='APP'` 区分 | App 用户并入宿主用户表，与管理端用户按用户类型隔离 |
| 自身认证 | `QtAuthInterceptor`（Sa-Token，`satoken` 请求头） | 复用宿主 Sa-Token 登录态，并校验当前用户为 APP 用户 |
| 独立表结构 | `resources/sql/qt-schema.sql` + `QtSchemaInitializer` | 启动时 `CREATE TABLE IF NOT EXISTS` 幂等建表 |
| 实体 ID | 实体 `@TableId(type=INPUT)` + `@TableField(fill=...)` | 由宿主全局序列（astral_id）自动取号 |
| Mapper 注册 | `@MapperScan("com.astral.qt.mapper")`（QtMapperConfig） | 宿主只扫 `com.astral.dao.mapper`，插件需自行注册 |
| 宿主拦截器放行 | `WebMvcConfig` 排除 `/api/v1/user/**`、`/api/v1/app/**` | App 用户复用宿主 Sa-Token 登录态，由插件拦截器负责 APP 用户身份校验 |
| 后台管理面 | `/api/v1/admin/qt/**` + `/dashboard/qt` | 复用宿主 Sa-Token 管理员认证 |

**关键模式：插件自身的 Controller 路径若需与外部前端（如 uniappx）对接，应保持路径与认证方式与对方约定完全一致。**

## 插件生命周期

| 阶段 | 触发时机 | 说明 |
|------|----------|------|
| 注册 | 应用启动 | 自动发现并注册到 PluginRegistry |
| 启用 | 启动或后台启用 | 调用 `onEnable()` |
| 禁用 | 后台禁用 | 调用 `onDisable()` |
| 卸载 | 移除依赖重启 | 从注册表移除 |

## 权限控制

插件接口默认走 `/api/v1/**` 拦截器，需要认证。如需更细粒度权限：

1. 在 `sys_permission` 表添加权限记录（如 `uniappx:view`）
2. 在 `PluginController` 或插件自己的 Controller 上使用 Sa-Token 注解
3. 前端 `menuConfig` 中配置 `permission: 'uniappx:view'`

## 注意事项

- 插件模块的 Controller 路径建议统一以 `/api/v1/admin/plugin/{pluginId}` 或 `/api/v1/{biz}` 开头
- 需要数据库表时，将建表脚本放在**插件自己的 resources** 下（如 `resources/sql/uniappx-schema.sql`），编写 `XxxSchemaInitializer`（`@Component` + `@ConditionalOnProperty`，启动时用 `ResourceDatabasePopulator` 执行 `CREATE TABLE IF NOT EXISTS` 脚本，幂等）。参考 `QtSchemaInitializer`；**不需要**手工注册到根目录 `sql/` 或 `SchemaRegistry`
- 插件启用/禁用通过 `astral.plugins.{id}.enabled` 配置（`@ConditionalOnProperty` 消费），热切换为前端控制（后台切换时立即生效）

