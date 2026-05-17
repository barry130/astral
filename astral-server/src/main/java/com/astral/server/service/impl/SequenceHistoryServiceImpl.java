package com.astral.server.service.impl;

import com.astral.dao.entity.SequenceHistory;
import com.astral.dao.mapper.SequenceHistoryMapper;
import com.astral.server.service.SequenceHistoryService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 序列历史服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供序列历史实体的标准CRUD操作</p>
 */
@Service
public class SequenceHistoryServiceImpl extends ServiceImpl<SequenceHistoryMapper, SequenceHistory> implements SequenceHistoryService {
}
