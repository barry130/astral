package com.astral.system.service.impl;

import com.astral.common.error.ErrorCodes;
import com.astral.common.exception.BusinessException;
import com.astral.common.util.PatchValues;
import com.astral.dao.entity.SysMenu;
import com.astral.dao.mapper.SysMenuMapper;
import com.astral.system.service.SysMenuService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 菜单服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysMenuServiceImpl implements SysMenuService {

    /** 顶级菜单的父 ID */
    private static final long ROOT_PARENT_ID = 0L;

    private final SysMenuMapper sysMenuMapper;

    @Override
    public List<SysMenu> listAll() {
        return sysMenuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .orderByAsc(SysMenu::getSort)
                .orderByAsc(SysMenu::getId));
    }

    @Override
    public SysMenu create(SysMenu menu) {
        if (menu == null) {
            throw new BusinessException("COMMON004");
        }
        Long parentId = menu.getParentId() == null ? ROOT_PARENT_ID : menu.getParentId();
        menu.setParentId(parentId);
        if (parentId != ROOT_PARENT_ID && sysMenuMapper.selectById(parentId) == null) {
            throw new BusinessException("SYS014", parentId);
        }
        menu.setId(null);
        sysMenuMapper.insert(menu);
        return menu;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysMenu update(Long id, SysMenu menu) {
        SysMenu existing = sysMenuMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("SYS013");
        }
        // 成环防护：新父级不能是自己，也不能是自己的任意子孙
        Long newParentId = menu.getParentId() == null ? existing.getParentId() : menu.getParentId();
        if (newParentId != ROOT_PARENT_ID) {
            if (newParentId.equals(id)) {
                throw new BusinessException("SYS015", "不能是菜单自身");
            }
            if (descendantIds(id).contains(newParentId)) {
                throw new BusinessException("SYS015", "不能是自己的子菜单");
            }
            if (sysMenuMapper.selectById(newParentId) == null) {
                throw new BusinessException("SYS014", newParentId);
            }
        }
        // 不用 updateById：MP 默认 updateStrategy=NOT_NULL 会跳过 null 字段，
        // 编辑弹窗里「清空图标」保存后不会生效。白名单逐列显式 set。
        // 注意本入口既是整表单提交，也被拖拽排序用局部 payload（只带 sort/parentId）调用，
        // 因此未提交（null）的列一律回落 existing（保持原值），只有空串才算「清空」。
        LambdaUpdateWrapper<SysMenu> update = new LambdaUpdateWrapper<SysMenu>()
                .eq(SysMenu::getId, id)
                .set(SysMenu::getParentId, newParentId)
                .set(SysMenu::getName, PatchValues.orDefault(menu.getName(), existing.getName()))
                .set(SysMenu::getIcon, PatchValues.orCurrent(menu.getIcon(), existing.getIcon()))
                .set(SysMenu::getPath, PatchValues.orCurrent(menu.getPath(), existing.getPath()))
                .set(SysMenu::getPermission, PatchValues.orCurrent(menu.getPermission(), existing.getPermission()))
                .set(SysMenu::getSort, PatchValues.orDefault(menu.getSort(), existing.getSort()))
                .set(SysMenu::getVisible, PatchValues.orDefault(menu.getVisible(), existing.getVisible()))
                .set(SysMenu::getType, PatchValues.orDefault(menu.getType(), existing.getType()))
                .set(SysMenu::getUpdateTime, LocalDateTime.now());
        sysMenuMapper.update(null, update);
        // 回读库中最新行：入参可能是局部 payload（拖拽排序只带 sort/parentId），原样回显会缺字段
        return sysMenuMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteWithChildren(Long id) {
        if (id == null) {
            throw new BusinessException("COMMON004");
        }
        if (sysMenuMapper.selectById(id) == null) {
            throw new BusinessException("SYS013");
        }
        // 先收集全部待删 ID（含自身与所有子孙），再一次删除：
        // 逐层 delete 会让中间态出现 parent_id 悬空的孤儿行。
        Set<Long> ids = descendantIds(id);
        ids.add(id);
        int deleted = sysMenuMapper.deleteBatchIds(ids);
        log.info("[菜单] 级联删除 id={} 共 {} 条（含自身）", id, deleted);
        return deleted;
    }

    /**
     * 广度优先收集指定菜单的所有子孙 ID（不含自身）
     * <p>用显式栈而非递归：菜单层级不可控，递归在深树上有栈溢出风险。
     * 同时用 visited 兜底 —— 万一库里已有环（历史脏数据），也不会死循环。</p>
     *
     * @param rootId 根菜单ID
     * @return 子孙ID集合
     */
    private Set<Long> descendantIds(Long rootId) {
        Set<Long> result = new HashSet<>();
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(rootId);
        visited.add(rootId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            List<SysMenu> children = sysMenuMapper.selectList(
                    new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getParentId, current));
            List<Long> nextLevel = new ArrayList<>(children.size());
            for (SysMenu child : children) {
                if (child.getId() == null || !visited.add(child.getId())) {
                    continue;
                }
                result.add(child.getId());
                nextLevel.add(child.getId());
            }
            queue.addAll(nextLevel);
        }
        return result;
    }
}
