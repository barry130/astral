package com.astral.system.notify;

import com.astral.dao.entity.SysNotifyRule;
import com.astral.dao.mapper.SysNotifyRuleMapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class SysNotifyRuleServiceImpl extends ServiceImpl<SysNotifyRuleMapper, SysNotifyRule>
        implements SysNotifyRuleService {
}
