package com.astral.qt.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * GitHub 加速前缀配置（UPDATE_DESIGN.md §1.2）
 * <p>最终下载地址 = prefix_url + 原始 GitHub 链接（直接字符串拼接）。</p>
 */
@Data
@TableName("qt_github_accel")
public class QtGithubAccel {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 节点名称（如 ghfast） */
    private String name;

    /** 加速前缀，如 https://ghfast.top/ */
    private String prefixUrl;

    /** 是否启用：1=启用（参与 App 端探测） 0=停用 */
    private Long isShow;

    /** 排序（探测顺序，小在前） */
    private Long sort;

    /** 备注 */
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
