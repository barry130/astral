package com.astral.system.notify;

import com.astral.dao.entity.SysSmsLog;
import com.astral.dao.mapper.SysSmsLogMapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class SysSmsLogServiceImpl extends ServiceImpl<SysSmsLogMapper, SysSmsLog>
        implements SysSmsLogService {
}
