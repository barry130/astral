package com.astral.sequence.controller;

import com.astral.common.error.ErrorCodes;
import com.astral.common.result.Result;
import com.astral.dao.entity.SequenceConfig;
import com.astral.dao.entity.SequenceStatistics;
import com.astral.dao.mapper.SequenceConfigMapper;
import com.astral.dao.mapper.SequenceStatisticsMapper;
import com.astral.log.annotation.OperateLog;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 序列配置管理控制器
 * <p>
 * 提供序列号配置的 CRUD 操作接口，包括创建、更新、删除、启用/禁用配置，
 * 以及分页查询和统计信息查询。配置决定了每个业务键使用哪种序列生成策略。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/admin/sequence/configs")
@RequiredArgsConstructor
public class SequenceConfigController {
    /** 序列配置数据访问接口 */
    private final SequenceConfigMapper sequenceConfigMapper;
    /** 序列统计数据访问接口 */
    private final SequenceStatisticsMapper statisticsMapper;
    /** 实体 ID 全局序列提供者（用于内置序列保护） */
    private final com.astral.sequence.config.EntityIdSequenceProvider entityIdSequenceProvider;

    /**
     * 校验是否为系统内置序列
     * <p>
     * 所有实体 ID 序列（业务键以 _id 结尾）不允许创建、修改、删除或启停，
     * 保证各表 ID 生成的一致性。
     * </p>
     */
    private void assertNotSystemSequence(SequenceConfig config, String errorCode) {
        if (config != null && entityIdSequenceProvider.isEntityIdBizKey(config.getBizKey())) {
            throw new com.astral.common.exception.BusinessException(errorCode, config.getBizKey());
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
        list.forEach(this::enrichCurrentValue);
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
        IPage<SequenceConfig> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SequenceConfig> wrapper = new LambdaQueryWrapper<>();
        if (bizKey != null && !bizKey.isEmpty()) {
            wrapper.like(com.astral.dao.entity.SequenceConfig::getBizKey, bizKey);
        }
        wrapper.orderByDesc(SequenceConfig::getCreateTime);
        IPage<SequenceConfig> result = sequenceConfigMapper.selectPage(page, wrapper);
        result.getRecords().forEach(this::enrichCurrentValue);
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
            return Result.error("SEQ007");
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
        if (!"SEGMENT".equals(config.getSequenceType())) {
            return;
        }
        SequenceStatistics stat = statisticsMapper.selectByBizKey(config.getBizKey());
        if (stat != null) {
            config.setCurrentValue(stat.getCurrentValue());
        }
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
    @PostMapping
    public Result<SequenceConfig> create(@RequestBody SequenceConfig config) {
        assertNotSystemSequence(config, "SEQ008");
        config.setCreateTime(java.time.LocalDateTime.now());
        config.setUpdateTime(java.time.LocalDateTime.now());
        if (config.getEnabled() == null) {
            config.setEnabled(true);
        }
        sequenceConfigMapper.insert(config);
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
    @PutMapping("/{id}")
    public Result<SequenceConfig> update(@PathVariable Long id, @RequestBody SequenceConfig config) {
        SequenceConfig existing = sequenceConfigMapper.selectById(id);
        assertNotSystemSequence(existing, "SEQ008");
        config.setId(id);
        config.setUpdateTime(java.time.LocalDateTime.now());
        sequenceConfigMapper.updateById(config);
        return Result.success(config);
    }

    /**
     * 删除序列配置
     *
     * @param id 配置 ID
     * @return 操作结果
     */
    @OperateLog("删除序列配置")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        SequenceConfig existing = sequenceConfigMapper.selectById(id);
        assertNotSystemSequence(existing, "SEQ008");
        sequenceConfigMapper.deleteById(id);
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
    @PutMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id, @RequestParam Boolean enabled) {
        SequenceConfig existing = sequenceConfigMapper.selectById(id);
        assertNotSystemSequence(existing, "SEQ008");
        SequenceConfig config = new SequenceConfig();
        config.setId(id);
        config.setEnabled(enabled);
        config.setUpdateTime(java.time.LocalDateTime.now());
        sequenceConfigMapper.updateById(config);
        return Result.success();
    }
}