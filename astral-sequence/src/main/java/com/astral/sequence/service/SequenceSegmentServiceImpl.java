package com.astral.sequence.service.impl;

import com.astral.dao.entity.SequenceSegment;
import com.astral.dao.mapper.SequenceSegmentMapper;
import com.astral.sequence.service.SequenceSegmentService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 号段分配服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供号段分配实体的标准CRUD操作</p>
 */
@Service
public class SequenceSegmentServiceImpl extends ServiceImpl<SequenceSegmentMapper, SequenceSegment> implements SequenceSegmentService {
}
