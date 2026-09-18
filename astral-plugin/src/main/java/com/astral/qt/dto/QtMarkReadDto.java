package com.astral.qt.dto;

import java.util.List;

import lombok.Data;

@Data
public class QtMarkReadDto {
    /** 要标记为已读的公告 id 列表 */
    private List<Long> ids;
}
