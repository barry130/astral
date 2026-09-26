package com.astral.log.service.impl;

import com.astral.dao.entity.LoginLog;
import com.astral.dao.entity.OperateLog;
import com.astral.dao.mapper.LoginLogMapper;
import com.astral.dao.mapper.OperateLogMapper;
import com.astral.log.service.LogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 日志服务实现类
 * <p>
 * 通过 MyBatis-Plus Mapper 将操作日志和登录日志持久化到数据库。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class LogServiceImpl implements LogService {
    /** 操作日志Mapper，用于操作日志的数据库操作 */
    private final OperateLogMapper operateLogMapper;
    /** 登录日志Mapper，用于登录日志的数据库操作 */
    private final LoginLogMapper loginLogMapper;

    /**
     * 保存操作日志到数据库
     *
     * @param log 操作日志实体
     */
    @Override
    public void saveOperateLog(OperateLog log) {
        operateLogMapper.insert(log);
    }

    /**
     * 保存登录日志到数据库
     *
     * @param loginLog 登录日志实体
     */
    @Override
    public void saveLoginLog(LoginLog loginLog) {
        loginLogMapper.insert(loginLog);
    }
}