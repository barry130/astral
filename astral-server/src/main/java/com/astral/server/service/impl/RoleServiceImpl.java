package com.astral.server.service.impl;

import com.astral.dao.entity.Role;
import com.astral.dao.mapper.RoleMapper;
import com.astral.server.service.RoleService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 角色服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供角色实体的标准CRUD操作</p>
 */
@Service
public class RoleServiceImpl extends ServiceImpl<RoleMapper, Role> implements RoleService {
}
