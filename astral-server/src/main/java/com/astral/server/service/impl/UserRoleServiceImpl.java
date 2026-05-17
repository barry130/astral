package com.astral.server.service.impl;

import com.astral.dao.entity.UserRole;
import com.astral.dao.mapper.UserRoleMapper;
import com.astral.server.service.UserRoleService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 用户角色关联服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供用户角色关联实体的标准CRUD操作</p>
 */
@Service
public class UserRoleServiceImpl extends ServiceImpl<UserRoleMapper, UserRole> implements UserRoleService {
}
