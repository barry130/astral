package com.astral.server.service.impl;

import com.astral.dao.entity.Permission;
import com.astral.dao.mapper.PermissionMapper;
import com.astral.server.service.PermissionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 权限服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供权限实体的标准CRUD操作</p>
 */
@Service
public class PermissionServiceImpl extends ServiceImpl<PermissionMapper, Permission> implements PermissionService {
}
