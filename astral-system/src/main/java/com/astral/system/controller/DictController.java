package com.astral.system.controller;

import com.astral.dao.entity.DictData;
import com.astral.system.service.DictDataService;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 数据字典控制器
 * <p>提供字典数据的基础CRUD操作</p>
 */
@Tag(name = "数据字典")
@RestController
@RequestMapping("/api/v1/admin/system/dict")
@RequiredArgsConstructor
public class DictController {

    /** 字典数据服务 */
    private final DictDataService dictDataService;

    /**
     * 分页查询字典数据列表
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @return 分页字典数据
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<DictData>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                       @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<DictData> page = new Page<>(pageNum, pageSize);
        return Result.success(dictDataService.page(page));
    }

    /**
     * 根据ID查询字典数据详情
     *
     * @param id 字典数据ID
     * @return 字典数据实体
     */
    @Operation(summary = "根据ID查询")
    @GetMapping("/{id}")
    public Result<DictData> getById(@PathVariable Long id) {
        return Result.success(dictDataService.getById(id));
    }

    /**
     * 创建字典数据
     *
     * @param entity 字典数据实体
     * @return 操作结果
     */
    @Operation(summary = "创建")
    @PostMapping
    public Result<Void> create(@RequestBody DictData entity) {
        dictDataService.save(entity);
        return Result.success();
    }

    /**
     * 更新字典数据
     *
     * @param id 字典数据ID
     * @param entity 字典数据实体（包含更新后的字段）
     * @return 操作结果
     */
    @Operation(summary = "更新")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody DictData entity) {
        entity.setId(id);
        dictDataService.updateById(entity);
        return Result.success();
    }

    /**
     * 删除字典数据
     *
     * @param id 字典数据ID
     * @return 操作结果
     */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dictDataService.removeById(id);
        return Result.success();
    }
}
