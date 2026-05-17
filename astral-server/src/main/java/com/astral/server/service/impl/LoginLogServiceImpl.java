package com.astral.server.service.impl;

import com.astral.dao.entity.LoginLog;
import com.astral.dao.mapper.LoginLogMapper;
import com.astral.server.service.LoginLogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 登录日志服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供登录日志实体的标准CRUD操作</p>
 */
@Service
public class LoginLogServiceImpl extends ServiceImpl<LoginLogMapper, LoginLog> implements LoginLogService {
}
