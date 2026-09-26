package com.astral.log.service;

import com.astral.dao.entity.LoginLog;
import com.astral.dao.entity.OperateLog;

/**
 * 日志服务接口
 * <p>
 * 提供操作日志和登录日志的保存功能。
 * 由 {@link com.astral.log.aspect.OperateLogAspect} 和 {@link com.astral.log.aspect.LoginLogAspect} 调用。
 * </p>
 */
public interface LogService {
    /**
     * 保存操作日志
     *
     * @param log 操作日志实体，包含操作人、操作类型、请求信息、响应结果等
     */
    void saveOperateLog(OperateLog log);

    /**
     * 保存登录日志
     *
     * @param loginLog 登录日志实体，包含用户名、登录方式、IP地址、登录状态等
     */
    void saveLoginLog(LoginLog loginLog);
}