package com.astral.system.mail;

import com.astral.dao.entity.SysMailAccount;
import com.astral.dao.mapper.SysMailAccountMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class SysMailAccountServiceImpl extends ServiceImpl<SysMailAccountMapper, SysMailAccount>
        implements SysMailAccountService {
}
