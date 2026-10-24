package com.astral.cms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CMS 站点设置（KV）
 * <p>键位见 {@link com.astral.cms.service.CmsSettingService} 的 DEFAULTS：
 * site_title / site_description / footer_text / comment_enabled / comment_need_review。</p>
 */
@Data
@TableName("cms_setting")
@JsonIgnoreProperties(ignoreUnknown = true)
public class CmsSetting {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 配置键 */
    private String settingKey;

    /** 配置值 */
    private String settingValue;

    /** 说明 */
    private String remark;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
