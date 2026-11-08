package com.astral.sequence.service;

/**
 * 序列号生成结果。
 * <p>
 * 同时返回实际使用的序列类型和生成值，供接口层回显真实类型；此前控制器直接回显请求里的
 * type，请求未传时会返回 null，与实际使用的默认类型不一致。
 * </p>
 *
 * @param value  单个生成时为序列号；批量生成时为最后一个有效序列号
 * @param type   实际使用的序列类型（已规范化为大写）
 * @param values 批量生成时为逗号分隔的完整结果；单个生成时为 null
 */
public record SequenceResult(long value, String type, String values) {

    /**
     * 单个序列号结果。
     *
     * @param value 序列号
     * @param type  实际类型
     * @return 结果
     */
    public static SequenceResult single(long value, String type) {
        return new SequenceResult(value, type, null);
    }

    /**
     * 批量序列号结果。
     *
     * @param value  最后一个有效序列号
     * @param type   实际类型
     * @param values 逗号分隔的完整结果
     * @return 结果
     */
    public static SequenceResult batch(long value, String type, String values) {
        return new SequenceResult(value, type, values);
    }
}
