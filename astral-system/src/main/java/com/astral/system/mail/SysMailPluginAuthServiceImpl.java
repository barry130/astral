package com.astral.system.mail;

import com.astral.dao.entity.SysMailPluginAuth;
import com.astral.dao.mapper.SysMailPluginAuthMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class SysMailPluginAuthServiceImpl extends ServiceImpl<SysMailPluginAuthMapper, SysMailPluginAuth>
        implements SysMailPluginAuthService {
}
