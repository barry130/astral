package com.astral.system.notify;

import com.astral.dao.entity.SysSmsTemplate;
import com.baomidou.mybatisplus.spring.service.IService;

public interface SysSmsTemplateService extends IService<SysSmsTemplate> {
    void createTemplate(SysSmsTemplate entity);

    void updateTemplate(Long id, SysSmsTemplate entity);

    void deleteTemplate(Long id);
}
