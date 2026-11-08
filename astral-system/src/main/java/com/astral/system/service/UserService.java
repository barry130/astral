package com.astral.system.service;

import com.astral.dao.entity.User;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 用户服务接口
 * <p>继承MyBatis-Plus的IService，提供用户实体的标准CRUD操作</p>
 */
public interface UserService extends IService<User> {
    /** 白名单修改账号状态，并在并发封禁下保留至少一个可用管理员。 */
    void changeStatus(Long userId, int status, String reason);
}
