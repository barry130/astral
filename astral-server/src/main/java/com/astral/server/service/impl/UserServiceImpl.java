package com.astral.server.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import com.astral.server.service.UserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 用户服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供用户实体的CRUD操作</p>
 * <p>在保存和更新用户时，自动对密码进行BCrypt加密处理</p>
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * 保存用户
     * <p>如果用户设置了密码，则先进行BCrypt加密再保存</p>
     *
     * @param entity 用户实体
     * @return 是否保存成功
     */
    @Override
    public boolean save(User entity) {
        // 对密码进行BCrypt加密，避免明文存储
        if (StringUtils.hasText(entity.getPassword())) {
            entity.setPassword(BCrypt.hashpw(entity.getPassword()));
        }
        return super.save(entity);
    }

    /**
     * 更新用户
     * <p>如果用户提供了新密码，则先进行BCrypt加密再更新</p>
     *
     * @param entity 用户实体
     * @return 是否更新成功
     */
    @Override
    public boolean updateById(User entity) {
        // 仅当提供了新密码时才加密（避免覆盖已有密码）
        if (StringUtils.hasText(entity.getPassword())) {
            entity.setPassword(BCrypt.hashpw(entity.getPassword()));
        }
        return super.updateById(entity);
    }
}
