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
@TableName("sys_notify_rule")
public class SysNotifyRule {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("rule_name")
    @Size(max = 64)
    private String ruleName;

    @TableField("event_code")
    @Size(max = 64)
    private String eventCode;

    @Size(max = 32)
    private String sender;

    private Integer enabled;

    @Size(max = 256)
    private String remark;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;
}
