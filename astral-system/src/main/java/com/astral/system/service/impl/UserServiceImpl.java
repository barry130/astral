package com.astral.system.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.astral.common.exception.BusinessException;
import com.astral.common.util.PasswordPolicy;
import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import com.astral.system.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供用户实体的CRUD操作</p>
 * <p>在保存和更新用户时，自动对密码进行BCrypt加密处理</p>
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long userId, int status, String reason) {
        if (status != 0 && status != 1) throw BusinessException.of("status 只允许 0 或 1");
        if (status == 0 && !StringUtils.hasText(reason)) throw BusinessException.of("封禁时必须填写理由");
        if (status == 0) {
            // Lock the same ordered administrator set, including disabled rows, before counting.
            // Separate row locks for the two target accounts would permit both to disable the last pair.
            baseMapper.selectList(new LambdaQueryWrapper<User>()
                    .eq(User::getUserType, "ADMIN")
                    .eq(User::getDeleted, 0)
                    .orderByAsc(User::getId)
                    .last("FOR UPDATE"));
        }
        User user = getById(userId);
        if (user == null || Integer.valueOf(1).equals(user.getDeleted())) throw BusinessException.of("用户不存在");
        if (status == 0
                && "ADMIN".equals(user.getUserType())
                && Integer.valueOf(1).equals(user.getStatus())
                && count(new LambdaQueryWrapper<User>()
                                .eq(User::getUserType, "ADMIN")
                                .eq(User::getDeleted, 0)
                                .eq(User::getStatus, 1))
                        <= 1) {
            throw BusinessException.of("系统中仅剩一个可用管理员，禁止禁用该账号");
        }
        if (!update(new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .eq(User::getDeleted, 0)
                .set(User::getStatus, status)
                .set(User::getStatusReason, status == 0 ? reason.trim() : "")
                .set(User::getUpdateTime, LocalDateTime.now()))) {
            throw BusinessException.of("用户状态未更新，请刷新后核对");
        }
    }

    /**
     * 保存用户
     * <p>如果用户设置了密码，则先进行BCrypt加密再保存</p>
     *
     * @param entity 用户实体
     * @return 是否保存成功
     */
    @Override
    public boolean save(User entity) {
        // 密码强度策略 + BCrypt 加密（策略失败抛 IllegalArgumentException，由全局异常处理器返回 400 文案）
        if (StringUtils.hasText(entity.getPassword())) {
            PasswordPolicy.validateOrThrow(entity.getPassword(), entity.getUsername());
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
        // 仅当提供了新密码时才加密（避免覆盖已有密码）；策略同 save
        if (StringUtils.hasText(entity.getPassword())) {
            PasswordPolicy.validateOrThrow(entity.getPassword(), entity.getUsername());
            entity.setPassword(BCrypt.hashpw(entity.getPassword()));
        }
        return super.updateById(entity);
    }
}
