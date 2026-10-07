package com.astral.system.notify;

import com.astral.dao.entity.SysSmsTemplate;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class SysSmsTemplateServiceImpl extends ServiceImpl<SysSmsTemplateMapper, SysSmsTemplate>
        implements SysSmsTemplateService {
}
