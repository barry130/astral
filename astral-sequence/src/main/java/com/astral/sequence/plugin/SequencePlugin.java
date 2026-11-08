package com.astral.sequence.plugin;

import com.astral.dao.entity.SequenceConfig;
import com.astral.dao.entity.SequenceSegment;
import com.astral.dao.mapper.SequenceConfigMapper;
import com.astral.dao.mapper.SequenceSegmentMapper;
import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PluginFrontendExtension;
import com.astral.schema.FieldSchema;
import com.astral.schema.SchemaRegistry;
import com.astral.schema.TableSchema;
import com.astral.sequence.config.EntityIdSequenceProvider;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

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
 *   <li>启动时做高水位对齐：max_value ≥ 表 MAX(id)+step，防迁移显式插主键埋雷（见
 *       {@link #alignHighWatermark(String, String)}）</li>
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
        return List.of("/api/v1/all/sequence");
    }

    @Override
    public boolean isRequired() {
        return true;
    }

    // ==================== 前端导航扩展 ====================

    @Override
    public List<NavItem> getNavItems() {
        return List.of(NavItem.pluginPage("序列管理", "/dashboard/sequence", "ApiOutlined", "admin:sequence:view", 100));
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
     * 未在 SchemaRegistry 中登记的表（无 schema JSON 的遗留表）不会被预置：
     * 首次插入时 SegmentGenerator 自行初始化号段，ID 从 1 开始（无预留段）。
     * 因此插件表应随插件携带 schema JSON（astral-plugin/resources/schema/），
     * 以获得预置预留段与锁定的配置行。
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
        // 插件表的建表由各插件自己的 SchemaInitializer 负责，可能晚于本插件 onEnable；
        // 表还不存在时跳过预置，等首次真正插入时由 SegmentGenerator 按配置初始化，
        // 避免一次性把「表不存在」当成冲突吞掉、留下没有配置的序列。
        if (!tableName.matches("[A-Za-z0-9_]+") || segmentMapper.countTableInCurrentSchema(tableName) == 0) {
            log.debug("[SequencePlugin] 表尚未创建，跳过序列预置：{}", tableName);
            return;
        }

        // 若号段不存在则初始化预留段（insertSegment 用 ON CONFLICT DO NOTHING，并发安全）
        if (segmentMapper.selectByBizKey(bizKey) == null) {
            segmentMapper.insertSegment(bizKey, 1, reserve, 1000);
            log.info("[SequencePlugin] 初始化序列：{} (表={}), 预留段=[1, {}]", bizKey, tableName, reserve);
        }

        // 登记系统内置序列配置（锁定）。只有唯一键并发冲突才允许吞掉并重读；
        // 其他数据库错误必须暴露出来，不能被一行 warn 掩盖。
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
            } catch (DuplicateKeyException e) {
                if (configMapper.selectByBizKey(bizKey) == null) {
                    throw e;
                }
                log.debug("[SequencePlugin] 序列配置已被并发创建：{}", bizKey);
            }
        }

        // 高水位对齐（幂等、只升不降；详见方法 javadoc）
        alignHighWatermark(bizKey, tableName);
    }

    /**
     * 高水位对齐：若表 MAX(id) 已越过号段上限（迁移用 COALESCE(MAX(id),0)+ROW_NUMBER() 显式
     * 插主键、手工修数都可能造成），把 max_value 抬到 MAX(id)+step，确保运行时发号不与存量
     * 主键相撞。这是 V20261001005 撞主键事故（sys_user_role id=2295961 DuplicateKey）的
     * 系统性防御；生产存量已于 2026-10-01 手工对齐修复，此后由本方法每次启动自动维持。
     *
     * <p>调用时机在全部生成器实例化之前（插件启动阶段），无内存缓冲需要失效，故不动 version。
     * 表名来自 SchemaRegistry 的 schema JSON（系统内部元数据），仍做标识符白名单校验防御；
     * 单表失败只告警不阻断启动，下次启动自动重试。</p>
     */
    private void alignHighWatermark(String bizKey, String tableName) {
        try {
            if (!tableName.matches("[A-Za-z0-9_]+") || segmentMapper.countTableInCurrentSchema(tableName) == 0) {
                return;
            }
            SequenceSegment segment = segmentMapper.selectByBizKey(bizKey);
            if (segment == null) {
                return;
            }
            long tableMax = segmentMapper.selectMaxId(tableName);
            int step = segment.getStep() != null && segment.getStep() > 0 ? segment.getStep() : 1000;
            long currentMax = segment.getMaxValue() != null ? segment.getMaxValue() : 0L;
            if (tableMax > Long.MAX_VALUE - step) {
                log.warn("[SequencePlugin] 高水位对齐跳过：{} 表 MAX(id)={} 已接近 long 上限", bizKey, tableMax);
                return;
            }
            long aligned = tableMax + step;
            if (currentMax < aligned && segmentMapper.raiseMaxValue(bizKey, aligned) > 0) {
                log.warn(
                        "[SequencePlugin] 高水位对齐：{} (表={}) max_value {} -> {}（表 MAX(id)={} 已越过号段，" + "多为迁移显式插入主键所致）",
                        bizKey,
                        tableName,
                        currentMax,
                        aligned,
                        tableMax);
            }
        } catch (Exception e) {
            log.warn("[SequencePlugin] 高水位对齐失败（不影响启动，下次启动重试）：{} - {}", bizKey, e.getMessage());
        }
    }
}
