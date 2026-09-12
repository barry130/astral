package com.astral.system.mail;

import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class SysMailTemplateServiceImpl extends ServiceImpl<SysMailTemplateMapper, SysMailTemplate>
        implements SysMailTemplateService {
}
