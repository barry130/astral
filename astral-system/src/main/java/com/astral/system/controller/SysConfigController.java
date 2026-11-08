package com.astral.system.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysConfig;
import com.astral.system.service.SysConfigService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
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
 * 系统配置控制器
 * <p>提供系统配置项的CRUD操作</p>
 * <p>权限：类级默认要求 {@code admin:system:config:view}（查询类接口），写接口覆盖为 {@code admin:system:config:edit}</p>
 */
@Tag(name = "系统配置")
@RestController
@RequestMapping("/api/v1/admin/system/config")
@RequiresPermission("admin:system:config:view")
@RequiredArgsConstructor
public class SysConfigController {

    /** 系统配置服务 */
    private final SysConfigService sysConfigService;

    /**
     * 分页查询系统配置列表
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @return 分页配置数据
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<SysConfig>> page(
            @RequestParam(defaultValue = "1") Integer pageNum, @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<SysConfig> page = new Page<>(pageNum, pageSize);
        return Result.success(sysConfigService.page(page));
    }

    /**
     * 根据ID查询配置详情
     *
     * @param id 配置ID
     * @return 配置实体
     */
    @Operation(summary = "根据ID查询")
    @GetMapping("/{id}")
    public Result<SysConfig> getById(@PathVariable Long id) {
        return Result.success(sysConfigService.getById(id));
    }

    /**
     * 创建系统配置
     *
     * @param entity 配置实体
     * @return 操作结果
     */
    @Operation(summary = "创建")
    @RequiresPermission("admin:system:config:edit")
    @PostMapping
    @CacheEvict(value = "sysConfig", allEntries = true)
    public Result<Void> create(@RequestBody SysConfig entity) {
        sysConfigService.save(entity);
        sysConfigService.evictCache();
        return Result.success();
    }

    /**
     * 更新系统配置
     *
     * @param id 配置ID
     * @param entity 配置实体（包含更新后的字段）
     * @return 操作结果
     */
    @Operation(summary = "更新")
    @RequiresPermission("admin:system:config:edit")
    @PutMapping("/{id}")
    @CacheEvict(value = "sysConfig", allEntries = true)
    public Result<Void> update(@PathVariable Long id, @RequestBody SysConfig entity) {
        // 白名单更新：id 由路径决定，只允许改「名称/键/值/类型/说明」这几个可编辑列。
        // 不能用 updateById(客户端实体)：MP 会把实体上所有非 null 字段一并写进 SET
        // （mass assignment），客户端可借此改写任意列——包括以后新增的敏感列。
        var update = sysConfigService.lambdaUpdate().eq(SysConfig::getId, id);
        if (entity.getConfigName() != null) {
            update.set(SysConfig::getConfigName, entity.getConfigName());
        }
        if (entity.getConfigKey() != null) {
            update.set(SysConfig::getConfigKey, entity.getConfigKey());
        }
        if (entity.getConfigValue() != null) {
            update.set(SysConfig::getConfigValue, entity.getConfigValue());
        }
        if (entity.getConfigType() != null) {
            update.set(SysConfig::getConfigType, entity.getConfigType());
        }
        if (entity.getDescription() != null) {
            update.set(SysConfig::getDescription, entity.getDescription());
        }
        update.update();
        sysConfigService.evictCache();
        return Result.success();
    }

    /**
     * 删除系统配置
     *
     * @param id 配置ID
     * @return 操作结果
     */
    @Operation(summary = "删除")
    @RequiresPermission("admin:system:config:edit")
    @DeleteMapping("/{id}")
    @CacheEvict(value = "sysConfig", allEntries = true)
    public Result<Void> delete(@PathVariable Long id) {
        sysConfigService.removeById(id);
        sysConfigService.evictCache();
        return Result.success();
    }
}
