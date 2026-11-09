package com.astral.system.notify;

import com.astral.dao.entity.SysNotifyRule;
import com.astral.system.dto.NotifyRuleSaveDto;
import com.baomidou.mybatisplus.spring.service.IService;

public interface SysNotifyRuleService extends IService<SysNotifyRule> {
    Long createRule(NotifyRuleSaveDto dto);

    void updateRule(Long id, NotifyRuleSaveDto dto);

    void deleteRule(Long id);
}
