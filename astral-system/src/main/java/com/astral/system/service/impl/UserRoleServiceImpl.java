package com.astral.system.service.impl;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.Role;
import com.astral.dao.entity.UserRole;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.UserRoleMapper;
import com.astral.system.service.UserRoleService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 用户角色关联服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供用户角色关联实体的标准CRUD操作</p>
 *
 * <p>这里直接注入 {@link RoleMapper} 而不是 {@code RoleService}：{@code RoleServiceImpl}
 * 需要反向统计用户角色引用数，互相注入服务会形成 Bean 循环依赖。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRoleServiceImpl extends ServiceImpl<UserRoleMapper, UserRole> implements UserRoleService {

    private final RoleMapper roleMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        List<Long> distinct = (roleIds == null || roleIds.isEmpty())
                ? List.of()
                : roleIds.stream().filter(Objects::nonNull).distinct().toList();

        // 1) 先校验角色ID都真实存在：脏ID直接拒绝。
        //    原实现静默入库，后续权限查询查不到角色，表现为「分配成功但没有权限」，很难排查。
        if (!distinct.isEmpty()) {
            Long found = roleMapper.selectCount(
                    new LambdaQueryWrapper<Role>().in(Role::getId, distinct));
            long foundCount = found == null ? 0L : found;
            if (foundCount != distinct.size()) {
                log.warn("分配角色失败：存在不存在的角色ID, userId={}, 期望={}, 实际={}",
                        userId, distinct.size(), foundCount);
                throw new BusinessException("SYS012", distinct.size() - foundCount);
            }
        }

        // 2) 清空 + 写入必须同一事务：中途失败会让用户角色被清空且无法自愈
        remove(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));

        if (!distinct.isEmpty()) {
            List<UserRole> rows = new ArrayList<>(distinct.size());
            for (Long roleId : distinct) {
                UserRole ur = new UserRole();
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                rows.add(ur);
            }
            saveBatch(rows);
        }
    }
}
