package com.astral.server.service.impl;

import com.astral.dao.entity.OperateLog;
import com.astral.dao.mapper.OperateLogMapper;
import com.astral.server.service.OperateLogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 操作日志服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供操作日志实体的标准CRUD操作</p>
 */
@Service
public class OperateLogServiceImpl extends ServiceImpl<OperateLogMapper, OperateLog> implements OperateLogService {
}
