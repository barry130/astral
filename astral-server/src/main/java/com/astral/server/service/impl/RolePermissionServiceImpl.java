package com.astral.server.service.impl;

import com.astral.dao.entity.RolePermission;
import com.astral.dao.mapper.RolePermissionMapper;
import com.astral.server.service.RolePermissionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 角色权限关联服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供角色权限关联实体的标准CRUD操作</p>
 */
@Service
public class RolePermissionServiceImpl extends ServiceImpl<RolePermissionMapper, RolePermission> implements RolePermissionService {
}
