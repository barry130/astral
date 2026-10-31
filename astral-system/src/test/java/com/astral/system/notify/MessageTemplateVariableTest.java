package com.astral.system.notify;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.system.dto.MessageTemplateSaveDto;
import com.astral.system.mail.MailService;
import com.astral.system.mail.SysMailTemplateService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 模板变量校验的回归测试。
 *
 * <p>历史缺陷：校验规则要求模板 {@code variables} 覆盖事件 payload 的**全部**字段，
 * 于是「正文只用 ${code}」的密码重置模板在加入站内信渠道后保存报
 * 「模板缺少场景必需变量: email」（MAIL010）。正确规则是「用到的变量必须是事件声明
 * 变量的子集」，未声明的变量（含大小写不符）才拒绝（MAIL022）。</p>
 */
class MessageTemplateVariableTest {

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "mail-template-test"),
                SysMailTemplate.class);
    }

    /** 正文只用 ${code}、双渠道（EMAIL+INAPP）→ 必须能保存 */
    @Test
    void savesTemplateWhenPayloadFieldUnused() {
        Fixture f = new Fixture();
        MessageTemplateSaveDto dto = f.dto("changePasswordByEmail", List.of("EMAIL", "INAPP"),
                "您的验证码是 ${code}", "[\"code\"]");
        assertDoesNotThrow(() -> f.service.save(dto));

        ArgumentCaptor<SysMailTemplate> saved = ArgumentCaptor.forClass(SysMailTemplate.class);
        verify(f.mailTemplates, times(2)).save(saved.capture());
        assertTrue(saved.getAllValues().stream().allMatch(r -> "[\"code\"]".equals(r.getVariables())),
                "落库的 variables 应为实际用到的变量");
    }

    /** 正文使用了事件未声明的变量 → MAIL022；大小写不符同样视为未声明 */
    @Test
    void rejectsUnknownVariable() {
        Fixture f = new Fixture();
        MessageTemplateSaveDto typed = f.dto("changePasswordByEmail", List.of("EMAIL"),
                "验证码 ${token}", "[\"code\"]");
        BusinessException ex = assertThrows(BusinessException.class, () -> f.service.save(typed));
        assertEquals("MAIL022", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("token"), "错误信息应点出未声明的变量");

        MessageTemplateSaveDto upper = f.dto("changePasswordByEmail", List.of("EMAIL"),
                "验证码 ${CODE}", "[\"code\"]");
        assertEquals("MAIL022", assertThrows(BusinessException.class, () -> f.service.save(upper)).getErrorCode());
    }

    /** 显式声明与正文用到的变量取并集，主题里的变量也要算 */
    @Test
    void mergesDeclaredAndUsedVariablesIncludingSubject() {
        Fixture f = new Fixture();
        MessageTemplateSaveDto dto = f.dto("changePasswordByEmail", List.of("EMAIL"),
                "验证码 ${code}", "[\"email\"]");
        assertDoesNotThrow(() -> f.service.save(dto));

        ArgumentCaptor<SysMailTemplate> saved = ArgumentCaptor.forClass(SysMailTemplate.class);
        verify(f.mailTemplates, atLeastOnce()).save(saved.capture());
        assertEquals("[\"email\",\"code\"]", saved.getValue().getVariables());
    }

    /** 抽取规则：多变量、重复、未闭合、非法字符 */
    @Test
    void extractVariablesByPattern() {
        assertEquals(List.of("code", "email"), NotifyEventRegistry.extractVariables("${code}-${email}-${code}"));
        assertEquals(List.of(), NotifyEventRegistry.extractVariables("未闭合 ${code"));
        assertEquals(List.of(), NotifyEventRegistry.extractVariables(null));
        assertEquals(List.of("a.b-c_d"), NotifyEventRegistry.extractVariables("${a.b-c_d}"));
        assertEquals("[\"code\",\"email\"]", NotifyEventRegistry.toJsonArray(List.of("code", "email")));
    }

    /** 最小可用的 service 装置：只有保存路径需要的依赖被 mock */
    private static final class Fixture {
        private final SysMailTemplateService mailTemplates = mock(SysMailTemplateService.class);
        private final SysSmsTemplateService smsTemplates = mock(SysSmsTemplateService.class);
        private final MailService mailService = mock(MailService.class);
        private final SmsService smsService = mock(SmsService.class);
        private final MessageTemplateService service =
                new MessageTemplateService(mailTemplates, smsTemplates, mailService, smsService);

        Fixture() {
            SysMailTemplateMapper mapper = mock(SysMailTemplateMapper.class);
            when(mapper.selectCount(any())).thenReturn(0L);
            when(mailTemplates.lambdaQuery()).thenAnswer(call ->
                    new com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<>(mapper));
            when(mailTemplates.list()).thenReturn(List.of());
            when(mailTemplates.save(any(SysMailTemplate.class))).thenReturn(true);

            SysSmsTemplateMapper smsMapper = mock(SysSmsTemplateMapper.class);
            when(smsMapper.selectCount(any())).thenReturn(0L);
            when(smsTemplates.lambdaQuery()).thenAnswer(call ->
                    new com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<>(smsMapper));
            when(smsTemplates.list()).thenReturn(List.of());
        }

        MessageTemplateSaveDto dto(String scene, List<String> channels, String content, String variables) {
            MessageTemplateSaveDto dto = new MessageTemplateSaveDto();
            dto.setTemplateCode(scene);
            dto.setScene(scene);
            dto.setTemplateName("测试模板");
            dto.setChannels(channels);
            dto.setSubject("轻听音乐 - 获取验证码");
            dto.setContent(content);
            dto.setVariables(variables);
            return dto;
        }
    }
}