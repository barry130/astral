package com.astral.system.controller;

import com.astral.dao.entity.DictType;
import com.astral.system.service.DictTypeService;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 字典类型控制器
 * <p>提供字典类型的CRUD操作及全量查询功能</p>
 */
@Tag(name = "字典类型表")
@RestController
@RequestMapping("/api/v1/admin/system/dict/type")
@RequiredArgsConstructor
public class DictTypeController {

    /** 字典类型服务 */
    private final DictTypeService dictTypeService;

    /**
     * 分页查询字典类型列表
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @return 分页字典类型数据
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<DictType>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<DictType> page = new Page<>(pageNum, pageSize);
        return Result.success(dictTypeService.page(page));
    }

    /**
     * 获取所有字典类型（不分页）
     *
     * @return 全部字典类型列表
     */
    @Operation(summary = "获取所有字典类型")
    @GetMapping("/all")
    public Result<List<DictType>> getAll() {
        return Result.success(dictTypeService.list());
    }

    /**
     * 根据ID查询字典类型详情
     *
     * @param id 字典类型ID
     * @return 字典类型实体
     */
    @Operation(summary = "根据ID查询")
    @GetMapping("/{id}")
    public Result<DictType> getById(@PathVariable Long id) {
        return Result.success(dictTypeService.getById(id));
    }

    /**
     * 创建字典类型
     *
     * @param entity 字典类型实体
     * @return 操作结果
     */
    @Operation(summary = "创建")
    @PostMapping
    @CacheEvict(value = "dictType", allEntries = true)
    public Result<Void> create(@RequestBody DictType entity) {
        dictTypeService.save(entity);
        dictTypeService.evictCache();
        return Result.success();
    }

    /**
     * 更新字典类型
     *
     * @param id 字典类型ID
     * @param entity 字典类型实体（包含更新后的字段）
     * @return 操作结果
     */
    @Operation(summary = "更新")
    @PutMapping("/{id}")
    @CacheEvict(value = "dictType", allEntries = true)
    public Result<Void> update(@PathVariable Long id, @RequestBody DictType entity) {
        entity.setId(id);
        dictTypeService.updateById(entity);
        dictTypeService.evictCache();
        return Result.success();
    }

    /**
     * 删除字典类型
     *
     * @param id 字典类型ID
     * @return 操作结果
     */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    @CacheEvict(value = "dictType", allEntries = true)
    public Result<Void> delete(@PathVariable Long id) {
        dictTypeService.removeById(id);
        dictTypeService.evictCache();
        return Result.success();
    }
}
