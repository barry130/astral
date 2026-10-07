package com.astral.system.notify;

import com.astral.dao.entity.SysSmsProvider;
import com.astral.dao.mapper.SysSmsProviderMapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class SysSmsProviderServiceImpl extends ServiceImpl<SysSmsProviderMapper, SysSmsProvider>
        implements SysSmsProviderService {
}
