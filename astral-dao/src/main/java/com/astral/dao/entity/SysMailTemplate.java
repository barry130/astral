package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sys_mail_template")
public class SysMailTemplate {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("template_code")
    @Size(max = 64)
    private String templateCode;

    @TableField("template_name")
    @Size(max = 128)
    private String templateName;

    @Size(max = 256)
    private String subject;

    private String content;

    @Size(max = 512)
    private String variables;

    @Size(max = 64)
    private String scene;

    @Size(max = 16)
    private String channel;

    @Size(max = 256)
    private String remark;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;
}
