package com.astral.system.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.dao.entity.DictData;
import com.astral.dao.mapper.DictDataMapper;
import com.astral.system.service.DictDataService;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.*;

/**
 * 字典数据控制器
 * <p>提供字典数据的CRUD操作，支持按字典类型ID筛选分页查询</p>
 * <p>权限：查询类接口要求 {@code admin:system:dict:view}，写接口要求 {@code admin:system:dict:edit}；
 * {@code GET /byCode} 刻意<b>不做权限限制</b>（仅要求登录），原因见该方法注释。</p>
 */
@Tag(name = "字典数据表")
@RestController
@RequestMapping("/api/v1/admin/system/dict/data")
@RequiredArgsConstructor
public class DictDataController {

    /** 字典数据服务 */
    private final DictDataService dictDataService;
    /** 字典数据Mapper，用于自定义查询 */
    private final DictDataMapper dictDataMapper;

    /**
     * 分页查询字典数据列表
     * <p>支持按字典类型ID进行筛选</p>
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @param dictTypeId 字典类型ID（可选）
     * @return 分页字典数据
     */
    @Operation(summary = "分页查询")
    @RequiresPermission("admin:system:dict:view")
    @GetMapping("/page")
    public Result<Page<DictData>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "10") Integer pageSize,
                                        Long dictTypeId) {
        Page<DictData> page = new Page<>(pageNum, pageSize);
        // 如果指定了字典类型ID，则添加筛选条件
        LambdaQueryWrapper<DictData> wrapper = new LambdaQueryWrapper<>();
        if (dictTypeId != null) {
            wrapper.eq(DictData::getDictTypeId, dictTypeId);
        }
        return Result.success(dictDataMapper.selectPage(page, wrapper));
    }

    /**
     * 根据ID查询字典数据详情
     *
     * @param id 字典数据ID
     * @return 字典数据实体
     */
    @Operation(summary = "根据ID查询")
    @RequiresPermission("admin:system:dict:view")
    @GetMapping("/{id}")
    public Result<DictData> getById(@PathVariable Long id) {
        return Result.success(dictDataService.getById(id));
    }

    /**
     * 按字典类型编码(dict_code)查询启用的字典数据
     * <p>用于前端按编码拉取下拉选项，避免硬编码枚举值</p>
     *
     * <p><b>刻意不加权限注解</b>：这是前端 {@code fetchDictOptions} 的唯一数据源，
     * 被插件页、存储页、统计页、反馈页、权限/角色/表结构等多个页面共用，
     * 且前端不做失败降级。若按 {@code admin:system:dict:view} 限制，
     * 只有其它模块权限（如仅 {@code admin:qt:admin}）的管理员会整页下拉为空。
     * 字典内容是全局枚举文案、不含业务数据，登录即可读是既有行为，故此接口保持仅登录校验。
     * 如需收紧，应改为「按模块拆分的枚举读取接口」，而不是给本接口加域权限。</p>
     *
     * @param code 字典类型编码，如 qt_notice_channel
     * @return 字典数据列表（按 dict_sort 升序）
     */
    @Operation(summary = "按字典编码查询字典数据")
    @GetMapping("/byCode")
    @Cacheable(value = "dictData", key = "#code")
    public Result<List<DictData>> byCode(@RequestParam("code") String code) {
        return Result.success(dictDataService.listByCode(code));
    }

    /**
     * 创建字典数据
     *
     * @param entity 字典数据实体
     * @return 操作结果
     */
    @Operation(summary = "创建")
    @RequiresPermission("admin:system:dict:edit")
    @PostMapping
    @CacheEvict(value = "dictData", allEntries = true)
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
    @RequiresPermission("admin:system:dict:edit")
    @PutMapping("/{id}")
    @CacheEvict(value = "dictData", allEntries = true)
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
    @RequiresPermission("admin:system:dict:edit")
    @DeleteMapping("/{id}")
    @CacheEvict(value = "dictData", allEntries = true)
    public Result<Void> delete(@PathVariable Long id) {
        dictDataService.removeById(id);
        return Result.success();
    }
}
