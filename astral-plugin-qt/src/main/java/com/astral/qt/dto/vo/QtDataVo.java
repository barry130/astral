package com.astral.qt.dto.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 通用数据包裹层：{data: xxx} */
@Data
@AllArgsConstructor
public class QtDataVo<T> {

    private T data;
}