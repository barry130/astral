package com.astral.server.controller;

import com.astral.dao.entity.SysConfig;
import com.astral.server.service.SysConfigService;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 系统配置控制器
 * <p>提供系统配置项的CRUD操作</p>
 */
@Tag(name = "系统配置")
@RestController
@RequestMapping("/api/v1/system/config")
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
    public Result<Page<SysConfig>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "10") Integer pageSize) {
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
    @PostMapping
    public Result<Void> create(@RequestBody SysConfig entity) {
        sysConfigService.save(entity);
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
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysConfig entity) {
        entity.setId(id);
        sysConfigService.updateById(entity);
        return Result.success();
    }

    /**
     * 删除系统配置
     *
     * @param id 配置ID
     * @return 操作结果
     */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysConfigService.removeById(id);
        return Result.success();
    }
}
