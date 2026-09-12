package com.astral.sequence.plugin;

import com.astral.dao.entity.SequenceConfig;
import com.astral.dao.mapper.SequenceConfigMapper;
import com.astral.dao.mapper.SequenceSegmentMapper;
import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PluginFrontendExtension;
import com.astral.schema.FieldSchema;
import com.astral.schema.SchemaRegistry;
import com.astral.schema.TableSchema;
import com.astral.sequence.config.EntityIdSequenceProvider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 序列生成服务插件
 * <p>
 * 序列服务承载系统全部业务实体的 ID 生成（SequenceMetaObjectHandler），
 * 属于核心基础设施，属于<b>系统必需插件</b>：不允许在管理端禁用。
 * </p>
 * <ul>
 *   <li>{@code astral.plugins.sequence.enabled} 开关已移除，插件始终注册</li>
 *   <li>管理端禁用操作返回 PLUGIN002 错误</li>
 *   <li>启动时为每张使用 INPUT 主键的业务表初始化独立序列（业务键 = 表名_id，预留段规避种子/历史数据冲突）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SequencePlugin implements AstralPlugin, PluginFrontendExtension {

    private final SequenceConfigMapper configMapper;
    private final SequenceSegmentMapper segmentMapper;
    private final EntityIdSequenceProvider entityIdSequenceProvider;

    @PostConstruct
    public void init() {
        log.info("[SequencePlugin] 序列插件注册，实体ID序列命名规则: {{表名}}_id");
    }

    @Override
    public String getPluginId() {
        return "sequence";
    }

    @Override
    public String getPluginName() {
        return "序列生成服务";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "系统必需插件：为每张业务表提供独立 ID 序列（号段模式，业务键=表名_id），同时支持 REST 序列接口";
    }

    @Override
    public List<String> getApiPrefixes() {
        return List.of("/api/v1/sequence");
    }

    @Override
    public boolean isRequired() {
        return true;
    }

    // ==================== 前端导航扩展 ====================

    @Override
    public List<NavItem> getNavItems() {
        return List.of(
                new NavItem("序列管理", "/dashboard/sequence", "ApiOutlined", 100)
        );
    }

    @Override
    public void onEnable() {
        log.info("[SequencePlugin] 序列服务已启用，开始初始化每表序列");
        ensureEntityIdSequences();
    }

    @Override
    public void onDisable() {
        log.warn("[SequencePlugin] 序列服务被禁用：系统必需插件，禁用将被拒绝；实体 ID 生成继续工作");
    }

    /**
     * 为每张使用 INPUT 主键的业务表初始化独立序列
     * <p>
     * 扫描 SchemaRegistry 中所有表的 Schema 定义，找出主键 mybatisPlusIdType=INPUT 的表，
     * 为每张表创建：
     * <ol>
     *   <li>预留号段：min=1, max=reserve，运行时 ID 从 reserve+1 开始，规避种子数据冲突</li>
     *   <li>系统内置配置：type=SEGMENT，锁定不可改/删</li>
     * </ol>
     * </p>
     * <p>
     * 未在 SchemaRegistry 中登记的表（如插件动态创建的 qt_* 表）会在首次插入时
     * 由 GeneratorFactory 自动创建配置，ID 从 1 开始（无预留段）。
     * </p>
     */
    private void ensureEntityIdSequences() {
        long reserve = entityIdSequenceProvider.getReserve();
        List<TableSchema> inputTables = collectInputTables();

        log.info("[SequencePlugin] 发现 {} 张使用 INPUT 主键的业务表，开始初始化序列", inputTables.size());

        for (TableSchema schema : inputTables) {
            String tableName = schema.getTableName();
            String bizKey = entityIdSequenceProvider.getBizKeyForTable(tableName);
            initTableSequence(bizKey, tableName, reserve);
        }
    }

    /**
     * 扫描 SchemaRegistry，收集所有主键 mybatisPlusIdType=INPUT 的表
     */
    private List<TableSchema> collectInputTables() {
        List<TableSchema> result = new ArrayList<>();
        for (TableSchema schema : SchemaRegistry.getAllSchemas()) {
            if (schema.getFields() == null) continue;
            for (FieldSchema field : schema.getFields()) {
                if (Boolean.TRUE.equals(field.getIsPrimaryKey())
                        && "INPUT".equalsIgnoreCase(field.getMybatisPlusIdType())) {
                    result.add(schema);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 为单张表初始化序列：预留号段 + 系统内置配置
     */
    private void initTableSequence(String bizKey, String tableName, long reserve) {
        // 若号段不存在则初始化预留段
        if (segmentMapper.selectByBizKey(bizKey) == null) {
            try {
                segmentMapper.insertSegment(bizKey, 1, reserve, 1000);
                log.info("[SequencePlugin] 初始化序列：{} (表={}), 预留段=[1, {}]", bizKey, tableName, reserve);
            } catch (Exception e) {
                log.warn("[SequencePlugin] 初始化序列冲突（可能并发/已存在）：{} - {}", bizKey, e.getMessage());
            }
        }

        // 登记系统内置序列配置（锁定）
        if (configMapper.selectByBizKey(bizKey) == null) {
            try {
                SequenceConfig config = new SequenceConfig();
                config.setBizKey(bizKey);
                config.setSequenceType("SEGMENT");
                config.setStep(1000);
                config.setEnabled(true);
                config.setDescription("系统内置：表" + tableName + "的实体ID序列，由系统自动维护");
                config.setCreateTime(LocalDateTime.now());
                config.setUpdateTime(LocalDateTime.now());
                configMapper.insert(config);
                log.info("[SequencePlugin] 登记序列配置：{}", bizKey);
            } catch (Exception e) {
                log.warn("[SequencePlugin] 登记序列配置冲突：{} - {}", bizKey, e.getMessage());
            }
        }
    }
}
