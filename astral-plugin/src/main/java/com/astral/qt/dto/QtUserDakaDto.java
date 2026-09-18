package com.astral.qt.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class QtUserDakaDto {

    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "日期格式错误,应为 yyyy-MM-dd")
    private String time;

    /** 0表示正常签到，1表示需要扣除积分签到 */
    @Pattern(regexp = "[01]", message = "不支持该类型")
    private String type;
}