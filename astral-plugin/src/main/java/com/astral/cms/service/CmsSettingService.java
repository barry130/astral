package com.astral.cms.service;

import com.astral.cms.common.CmsException;
import com.astral.cms.entity.CmsSetting;
import com.astral.cms.mapper.CmsSettingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CMS 站点设置服务（KV，含默认值兜底）
 */
@Slf4j
@Service
public class CmsSettingService {

    public static final String KEY_SITE_TITLE = "site_title";
    public static final String KEY_SITE_DESCRIPTION = "site_description";
    public static final String KEY_FOOTER_TEXT = "footer_text";
    public static final String KEY_COMMENT_ENABLED = "comment_enabled";
    public static final String KEY_COMMENT_NEED_REVIEW = "comment_need_review";
    public static final String KEY_BLOG_URL = "blog_url";

    private static final Map<String, String> DEFAULTS = createDefaults();
    private static final Map<String, String> REMARKS = createRemarks();

    @Resource
    private CmsSettingMapper settingMapper;

    /**
     * 全量设置（默认值兜底 + DB 覆盖）
     */
    public Map<String, String> getAll() {
        Map<String, String> result = new LinkedHashMap<>(DEFAULTS);
        settingMapper.selectList(null)
                .forEach(s -> result.put(s.getSettingKey(), s.getSettingValue()));
        return result;
    }

    /**
     * 全量保存（校验键位，逐键 upsert）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateAll(Map<String, String> settings) {
        for (String key : settings.keySet()) {
            if (!DEFAULTS.containsKey(key)) {
                throw new CmsException("不支持的配置项：" + key);
            }
        }
        settings.forEach(this::upsert);
    }

    /**
     * 布尔型设置读取（"true" 视为开，其余视为关）
     */
    public boolean boolValue(String key) {
        return "true".equalsIgnoreCase(getAll().get(key));
    }

    // ==================== 私有辅助 ====================

    private void upsert(String key, String value) {
        CmsSetting exist = settingMapper.selectOne(new LambdaQueryWrapper<CmsSetting>()
                .eq(CmsSetting::getSettingKey, key).last("LIMIT 1"));
        String val = value == null ? "" : value;
        if (exist == null) {
            CmsSetting setting = new CmsSetting();
            setting.setSettingKey(key);
            setting.setSettingValue(val);
            setting.setRemark(REMARKS.get(key));
            settingMapper.insert(setting);
        } else {
            exist.setSettingValue(val);
            settingMapper.updateById(exist);
        }
    }

    private static Map<String, String> createDefaults() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(KEY_SITE_TITLE, "Astral");
        m.put(KEY_SITE_DESCRIPTION, "");
        m.put(KEY_FOOTER_TEXT, "");
        m.put(KEY_COMMENT_ENABLED, "true");
        m.put(KEY_COMMENT_NEED_REVIEW, "true");
        m.put(KEY_BLOG_URL, "");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, String> createRemarks() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(KEY_SITE_TITLE, "站点名称（博客标题）");
        m.put(KEY_SITE_DESCRIPTION, "站点描述（SEO 用）");
        m.put(KEY_FOOTER_TEXT, "页脚文字");
        m.put(KEY_COMMENT_ENABLED, "是否开启评论（true/false）");
        m.put(KEY_COMMENT_NEED_REVIEW, "评论是否需要审核后展示（true/false）");
        m.put(KEY_BLOG_URL, "博客前台地址（含协议，如 https://blog.example.com）");
        return Collections.unmodifiableMap(m);
    }

    /**
     * 前台公开设置（站点标题/描述/页脚 + 评论开关）
     * <p>blog_url 属管理端内部外链地址，不在公开范围内。</p>
     */
    public Map<String, String> getPublic() {
        Map<String, String> all = getAll();
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : new String[]{KEY_SITE_TITLE, KEY_SITE_DESCRIPTION, KEY_FOOTER_TEXT,
                KEY_COMMENT_ENABLED, KEY_COMMENT_NEED_REVIEW}) {
            result.put(key, all.getOrDefault(key, ""));
        }
        return result;
    }
}
