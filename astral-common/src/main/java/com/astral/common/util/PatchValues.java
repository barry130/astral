package com.astral.common.util;

/**
 * 管理端「整实体更新」的取值归一工具。
 *
 * <p>背景：MyBatis-Plus 默认 {@code updateStrategy = FieldStrategy.NOT_NULL}，
 * {@code updateById(entity)} 会跳过所有 null 字段，也没有 UPDATE 策略注释可用；
 * 于是「把某个可清空字段清空」保存后库里仍是旧值 —— 前端提示保存成功，实际什么都没改。
 * 需要「可清空」语义的更新入口应改用 {@code LambdaUpdateWrapper} 逐列显式 {@code set}
 * （{@code set} 是无条件写入，不受 FieldStrategy 影响），取值口径由本类统一：</p>
 *
 * <ul>
 *   <li>{@link #blankToNull(String)}：文本控件清空时前端提交的是 {@code ""}，
 *       归一成 null 才是「清空」；否则库里会留下空串而不是 NULL。</li>
 *   <li>{@link #orDefault(Object, Object)}：非空列（NOT NULL DEFAULT）被清空时回落默认值，
 *       避免显式写 null 撞非空约束变成 500。</li>
 *   <li>{@link #orCurrent(Object, Object)}：区分「客户端没提交」（null = 保持原值）
 *       与「客户端清空」（{@code ""} = 写 NULL）。整实体表单和局部补丁共用一个入口时用它，
 *       局部调用方不回传的字段不会被清成 null。</li>
 * </ul>
 */
public final class PatchValues {

    private PatchValues() {
    }

    /** 空白字符串归一为 null：文本控件清空提交 ""，语义上等于「清空」 */
    public static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /** 值为 null 时回落默认值：用于 NOT NULL DEFAULT 列，避免显式写 null 撞约束 */
    public static <T> T orDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }

    /**
     * 区分「未提交」与「清空」：null 表示本次不改该字段（保持 current），
     * 空串/空白表示显式清空（写 NULL），其余原样写入。
     */
    public static String orCurrent(String value, String current) {
        return value == null ? current : blankToNull(value);
    }
}
