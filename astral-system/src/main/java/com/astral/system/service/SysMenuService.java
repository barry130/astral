package com.astral.system.service;

import com.astral.dao.entity.SysMenu;

import java.util.List;

/**
 * 菜单服务
 * <p>菜单是树形结构，删除必须级联子孙；原实现写在 Controller 里且只删一层子菜单，
 * 会留下孤儿节点（parent_id 指向已删除的菜单），前端树渲染时这些节点永远不可见也不可管理。</p>
 */
public interface SysMenuService {

    /**
     * 按父节点排序查询全部菜单
     *
     * @return 菜单列表
     */
    List<SysMenu> listAll();

    /**
     * 创建菜单
     * <p>校验父菜单存在，并拒绝把顶级菜单（parent_id = 0）之外的非法父级写入。</p>
     *
     * @param menu 菜单实体
     * @return 保存后的菜单
     */
    SysMenu create(SysMenu menu);

    /**
     * 更新菜单
     * <p>禁止把菜单的父级改成自己或自己的子孙，否则树会成环，
     * 递归删除/渲染时会无限循环直至栈溢出。</p>
     *
     * @param id   菜单ID
     * @param menu 待更新的菜单
     * @return 保存后的菜单
     */
    SysMenu update(Long id, SysMenu menu);

    /**
     * 级联删除菜单及其所有子孙（单事务）
     *
     * @param id 菜单ID
     * @return 实际删除的菜单数量
     */
    int deleteWithChildren(Long id);
}
