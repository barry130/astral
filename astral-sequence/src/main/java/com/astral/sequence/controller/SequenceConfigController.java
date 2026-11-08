package com.astral.sequence.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.common.util.PageQuery;
import com.astral.common.util.PatchValues;
import com.astral.dao.entity.SequenceConfig;
import com.astral.dao.entity.SequenceStatistics;
import com.astral.dao.mapper.SequenceConfigMapper;
import com.astral.dao.mapper.SequenceSegmentMapper;
import com.astral.dao.mapper.SequenceStatisticsMapper;
import com.astral.log.annotation.OperateLog;
import com.astral.sequence.config.EntityIdSequenceProvider;
import com.astral.sequence.controller.dto.SequenceConfigUpdate;
import com.astral.sequence.service.GeneratorFactory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 序列配置管理控制器
 * <p>
 * 提供序列号配置的 CRUD 操作接口，包括创建、更新、删除、启用/禁用配置，
 * 以及分页查询和统计信息查询。配置决定了每个业务键使用哪种序列生成策略。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/admin/sequence/configs")
@RequiresPermission("admin:sequence:view")
@RequiredArgsConstructor
public class SequenceConfigController {
    /** 允许创建的序列类型，与 GeneratorFactory 的实现保持一致 */
    private static final Set<String> SUPPORTED_SEQUENCE_TYPES =
            Set.of("SEGMENT", "SNOWFLAKE", "REDIS", "DATABASE", "SIMPLE");

    /** 序列配置数据访问接口 */
    private final SequenceConfigMapper sequenceConfigMapper;
    /** 序列统计数据访问接口 */
    private final SequenceStatisticsMapper statisticsMapper;
    /** 号段表访问接口：修改配置步长时必须同步已存在的号段记录 */
    private final SequenceSegmentMapper sequenceSegmentMapper;
    /** 实体 ID 全局序列提供者（用于内置序列保护） */
    private final EntityIdSequenceProvider entityIdSequenceProvider;
    /** 取号路径的配置读取走 60s 进程内缓存，管理端写完必须失效 */
    private final GeneratorFactory generatorFactory;

    /**
     * 校验是否为系统内置序列
     * <p>
     * 所有实体 ID 序列（业务键以 _id 结尾）不允许创建、修改、删除或启停，
     * 保证各表 ID 生成的一致性。
     * </p>
     */
    private void assertNotSystemSequence(SequenceConfig config, String errorCode) {
        if (config != null && entityIdSequenceProvider.isEntityIdBizKey(config.getBizKey())) {
            throw new BusinessException(errorCode, config.getBizKey());
        }
    }

    /**
     * 获取所有序列配置
     *
     * @return 全部配置列表
     */
    @GetMapping
    public Result<List<SequenceConfig>> getAll() {
        List<SequenceConfig> list = sequenceConfigMapper.selectList(null);
        enrichCurrentValues(list);
        return Result.success(list);
    }

    /**
     * 分页查询序列配置
     * <p>
     * 支持按业务键进行模糊搜索，结果按创建时间倒序排列。
     * </p>
     *
     * @param pageNum  页码，从 1 开始，默认为 1
     * @param pageSize 每页大小，默认为 20
     * @param bizKey   可选的业务键过滤条件，支持模糊匹配
     * @return 分页后的配置列表
     */
    @GetMapping("/page")
    public Result<IPage<SequenceConfig>> getPage(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String bizKey) {
        IPage<SequenceConfig> page = new Page<>(PageQuery.pageNum(pageNum), PageQuery.pageSize(pageSize));
        LambdaQueryWrapper<SequenceConfig> wrapper = new LambdaQueryWrapper<>();
        if (bizKey != null && !bizKey.isEmpty()) {
            wrapper.like(SequenceConfig::getBizKey, bizKey);
        }
        wrapper.orderByDesc(SequenceConfig::getCreateTime);
        IPage<SequenceConfig> result = sequenceConfigMapper.selectPage(page, wrapper);
        enrichCurrentValues(result.getRecords());
        return Result.success(result);
    }

    /**
     * 根据业务键获取配置
     *
     * @param bizKey 业务键
     * @return 对应的配置信息，如果不存在则返回错误
     */
    @GetMapping("/{bizKey}")
    public Result<SequenceConfig> getByBizKey(@PathVariable String bizKey) {
        SequenceConfig config = sequenceConfigMapper.selectByBizKey(bizKey);
        if (config == null) {
            throw new BusinessException("SEQ007");
        }
        enrichCurrentValue(config);
        return Result.success(config);
    }

    /**
     * 填充当前值
     * <p>
     * 序列配置表本身不存储当前值，号段模式的实际当前值记录在序列统计表中，
     * 每次生成都会异步更新。此处实时读取统计表的当前值填充到返回对象，
     * 供前端在号段模式下展示。非号段模式该字段无意义，保留为空。
     * </p>
     *
     * @param config 序列配置
     */
    private void enrichCurrentValue(SequenceConfig config) {
        if (config.getSequenceType() == null || !"SEGMENT".equalsIgnoreCase(config.getSequenceType())) {
            return;
        }
        SequenceStatistics stat = statisticsMapper.selectByBizKey(config.getBizKey());
        if (stat != null) {
            config.setCurrentValue(stat.getCurrentValue());
        }
    }

    /**
     * 批量填充当前值，避免配置列表逐行查统计表造成 N+1。
     *
     * @param configs 配置列表
     */
    private void enrichCurrentValues(List<SequenceConfig> configs) {
        List<String> bizKeys = configs.stream()
                .filter(c -> c.getSequenceType() != null && "SEGMENT".equalsIgnoreCase(c.getSequenceType()))
                .map(SequenceConfig::getBizKey)
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .collect(Collectors.toList());
        if (bizKeys.isEmpty()) {
            return;
        }
        Map<String, SequenceStatistics> stats = statisticsMapper
                .selectList(new LambdaQueryWrapper<SequenceStatistics>().in(SequenceStatistics::getBizKey, bizKeys))
                .stream()
                .collect(Collectors.toMap(SequenceStatistics::getBizKey, Function.identity(), (a, b) -> a));
        configs.forEach(config -> {
            SequenceStatistics stat = stats.get(config.getBizKey());
            if (stat != null) {
                config.setCurrentValue(stat.getCurrentValue());
            }
        });
    }

    /**
     * 创建新的序列配置
     * <p>
     * 自动设置创建时间和更新时间，如果未指定启用状态则默认为启用。
     * </p>
     *
     * @param config 配置信息
     * @return 创建后的配置（包含生成的 ID）
     */
    @OperateLog("创建序列配置")
    @RequiresPermission("admin:sequence:edit")
    @PostMapping
    public Result<SequenceConfig> create(@Valid @RequestBody SequenceConfig config) {
        if (config.getBizKey() == null || config.getBizKey().isBlank()) {
            throw BusinessException.of(320, "业务键不能为空");
        }
        assertNotSystemSequence(config, "SEQ008");
        // 主键与时间戳由服务端生成：客户端传 id 会绕过 AUTO 主键策略，可能覆盖已有配置行。
        config.setId(null);
        String type = config.getSequenceType() == null
                ? ""
                : config.getSequenceType().trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_SEQUENCE_TYPES.contains(type)) {
            throw new BusinessException("SEQ002");
        }
        int step = PatchValues.orDefault(config.getStep(), 1000);
        if (step < 1 || step > 1_000_000) {
            throw BusinessException.of(320, "步长必须在 1-1000000 之间");
        }
        long minValue = PatchValues.orDefault(config.getMinValue(), 1L);
        if (minValue < 1) {
            throw BusinessException.of(320, "序列起始值必须大于 0");
        }
        config.setSequenceType(type);
        config.setStep(step);
        config.setMinValue(minValue);
        if (sequenceConfigMapper.selectByBizKey(config.getBizKey()) != null) {
            throw new BusinessException("SEQ003");
        }
        config.setCreateTime(LocalDateTime.now());
        config.setUpdateTime(LocalDateTime.now());
        if (config.getEnabled() == null) {
            config.setEnabled(true);
        }
        try {
            sequenceConfigMapper.insert(config);
        } catch (DuplicateKeyException e) {
            // 并发创建同一 bizKey：唯一索引兜底，转换为明确的业务错误而不是 500。
            throw new BusinessException("SEQ003");
        }
        generatorFactory.evictConfigCache();
        return Result.success(config);
    }

    /**
     * 更新序列配置
     * <p>
     * 通过路径参数指定配置 ID，请求体中包含要更新的字段。
     * 自动更新修改时间。
     * </p>
     *
     * @param id     配置 ID
     * @param config 更新的配置信息
     * @return 更新后的配置
     */
    @OperateLog("更新序列配置")
    @RequiresPermission("admin:sequence:edit")
    @PutMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<SequenceConfig> update(@PathVariable Long id, @Valid @RequestBody SequenceConfigUpdate config) {
        SequenceConfig existing = sequenceConfigMapper.lockById(id);
        if (existing == null) {
            throw new BusinessException("SEQ007");
        }
        assertNotSystemSequence(existing, "SEQ008");
        // 业务键与序列类型是取号空间的标识：允许修改会导致历史号段被另一业务复用/类型漂移。
        if (config.getBizKey() != null && !existing.getBizKey().equals(config.getBizKey())) {
            throw BusinessException.of(320, "业务键创建后不允许修改");
        }
        String existingType = existing.getSequenceType() == null ? "" : existing.getSequenceType();
        // 类型缺省视为「不修改」：管理端老版本/局部更新不传类型时，不能因为 null 被判成类型漂移。
        if (config.getSequenceType() != null && !existingType.equalsIgnoreCase(config.getSequenceType())) {
            throw new BusinessException("SEQ006", existing.getBizKey(), existingType, config.getSequenceType());
        }
        // 持锁读取用于校验组合约束；遗漏字段不进入 SET，空字符串仍表示显式清空。
        int step = PatchValues.orDefault(config.getStep(), existing.getStep() != null ? existing.getStep() : 1000);
        if (step < 1 || step > 1_000_000) {
            throw BusinessException.of(320, "步长必须在 1-1000000 之间");
        }
        String dateFormat = PatchValues.orCurrent(config.getDateFormat(), existing.getDateFormat());
        String prefix = PatchValues.orCurrent(config.getPrefix(), existing.getPrefix());
        String suffix = PatchValues.orCurrent(config.getSuffix(), existing.getSuffix());
        Long minValue = PatchValues.orDefault(
                config.getMinValue(), existing.getMinValue() != null ? existing.getMinValue() : 1L);
        if (minValue < 1) {
            throw BusinessException.of(320, "序列起始值必须大于 0");
        }
        Boolean enabled = PatchValues.orDefault(
                config.getEnabled(), existing.getEnabled() != null ? existing.getEnabled() : Boolean.TRUE);
        String description = PatchValues.orCurrent(config.getDescription(), existing.getDescription());
        // 不用 updateById：MP 默认 updateStrategy=NOT_NULL 会跳过 null 字段，
        // 「清空前缀/后缀/日期格式」保存后仍是旧值。白名单逐列显式 set，可空列原样写入（含 null）。
        int updated = sequenceConfigMapper.update(
                null,
                new LambdaUpdateWrapper<SequenceConfig>()
                        .eq(SequenceConfig::getId, id)
                        .set(config.getStep() != null, SequenceConfig::getStep, step)
                        .set(config.getDateFormat() != null, SequenceConfig::getDateFormat, dateFormat)
                        .set(config.getPrefix() != null, SequenceConfig::getPrefix, prefix)
                        .set(config.getSuffix() != null, SequenceConfig::getSuffix, suffix)
                        .set(config.getMinValue() != null, SequenceConfig::getMinValue, minValue)
                        .set(config.getEnabled() != null, SequenceConfig::getEnabled, enabled)
                        .set(config.getDescription() != null, SequenceConfig::getDescription, description)
                        .set(SequenceConfig::getUpdateTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BusinessException("SEQ007");
        }
        // 号段模式的实际步长存放在 sequence_segment.step，GeneratorFactory 只读该列；
        // 原实现只改 sequence_config.step，管理端改步长完全不生效。同步时 version+1，
        // 使正在进行中的 allocateNextSegment 乐观锁失败后重读新步长。
        // 只有步长真正变化才同步：重复提交相同步长不应无谓推进 version、打断在途号段分配。
        boolean stepChanged = config.getStep() != null && !config.getStep().equals(existing.getStep());
        if ("SEGMENT".equalsIgnoreCase(existing.getSequenceType()) && stepChanged) {
            sequenceSegmentMapper.updateStepByBizKey(existing.getBizKey(), step);
        }
        generatorFactory.evictConfigCache();
        // 返回本次持锁事务中的实际行，避免反射客户端可写但未获准的字段。
        return Result.success(sequenceConfigMapper.selectById(id));
    }

    /**
     * 删除序列配置
     *
     * @param id 配置 ID
     * @return 操作结果
     */
    @OperateLog("删除序列配置")
    @RequiresPermission("admin:sequence:edit")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        SequenceConfig existing = sequenceConfigMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("SEQ007");
        }
        assertNotSystemSequence(existing, "SEQ008");
        // 删除后可以用同一个 bizKey 重新创建任意类型的配置；若该业务键已经发过号，
        // 换一种类型会从新的起点重新发号，和历史值撞车。已有号段/统计时只允许禁用。
        boolean hasSegment = sequenceSegmentMapper.selectByBizKey(existing.getBizKey()) != null;
        boolean hasStatistics = statisticsMapper.selectByBizKey(existing.getBizKey()) != null;
        if (hasSegment || hasStatistics) {
            throw BusinessException.of(320, "该业务键已产生序列数据，不能删除，请改为禁用");
        }
        sequenceConfigMapper.deleteById(id);
        generatorFactory.evictConfigCache();
        return Result.success();
    }

    /**
     * 启用或禁用序列配置
     * <p>
     * 只更新 enabled 字段和更新时间，其他字段保持不变。
     * 禁用的配置将不会被用于生成序列号。
     * </p>
     *
     * @param id      配置 ID
     * @param enabled 是否启用
     * @return 操作结果
     */
    @OperateLog("启用/禁用序列配置")
    @RequiresPermission("admin:sequence:edit")
    @PutMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id, @RequestParam Boolean enabled) {
        SequenceConfig existing = sequenceConfigMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("SEQ007");
        }
        assertNotSystemSequence(existing, "SEQ008");
        SequenceConfig config = new SequenceConfig();
        config.setId(id);
        config.setEnabled(enabled);
        config.setUpdateTime(LocalDateTime.now());
        if (sequenceConfigMapper.updateById(config) == 0) {
            throw new BusinessException("SEQ007");
        }
        generatorFactory.evictConfigCache();
        return Result.success();
    }
}
