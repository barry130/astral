package com.astral.storage.http;

import java.util.List;
import java.util.Map;

/**
 * 第三方 HTTP 图床渠道的上传画像（{@code CUSTOM_HTTP} 的 provider_options 有效载荷）。
 *
 * <p>画像只描述协议，不含任何明文凭据：模板里用 {@code ${secret:name}} 引用命名凭据，
 * 凭据本身由 {@code HttpSecretEnvelope} 单独存放并从不出参。
 * 所有字段都在 {@link HttpProfileValidator} 里做严格校验；本类型只作为反序列化载体，
 * 不要绕过校验直接用请求体构造它。</p>
 *
 * @param schemaVersion 协议版本，目前只接受 1
 * @param presetId      预设来源标识（可空，与 presetVersion 成对）
 * @param presetVersion 预设版本（可空）
 * @param endpoint      上传地址，仅 origin + path，禁止 Query/userinfo/fragment
 * @param method        固定 POST
 * @param query         独立 Query 参数（≤16 项）
 * @param headers       附加请求头（≤16 项，大小写不敏感去重）
 * @param auth          认证方式
 * @param body          multipart 正文
 * @param response      响应解析与成功判定
 * @param limits        渠道级限额（只能收紧应用硬上限）
 * @param timeouts      单次尝试预算
 */
public record HttpUploadProfile(
        Integer schemaVersion,
        String presetId,
        Integer presetVersion,
        String endpoint,
        String method,
        Map<String, String> query,
        Map<String, String> headers,
        Auth auth,
        Body body,
        ResponseSpec response,
        Limits limits,
        Timeouts timeouts) {

    /** 唯一支持的协议版本 */
    public static final int CURRENT_SCHEMA_VERSION = 1;
    /** 唯一支持的方法 */
    public static final String METHOD_POST = "POST";
    /** 唯一支持的正文模式 */
    public static final String BODY_MULTIPART = "MULTIPART";

    /** 认证方式 */
    public static final String AUTH_NONE = "NONE";

    public static final String AUTH_HEADER = "HEADER";
    public static final String AUTH_QUERY = "QUERY";
    public static final String AUTH_FORM = "FORM";
    public static final String AUTH_BASIC = "BASIC";

    /** 成功条件运算符 */
    public static final String OP_EXISTS = "EXISTS";

    public static final String OP_EQ = "EQ";

    /** 成功条件值类型 */
    public static final String TYPE_STRING = "STRING";

    public static final String TYPE_NUMBER = "NUMBER";
    public static final String TYPE_BOOLEAN = "BOOLEAN";

    /** 上游大小单位 */
    public static final String SIZE_UNIT_BYTES = "BYTES";

    public static final String SIZE_UNIT_KB = "KB";
    public static final String SIZE_UNIT_KIB = "KIB";

    /** 应用硬上限：单图 5 MiB */
    public static final long HARD_MAX_BYTES = 5242880L;
    /** 默认单次尝试预算 */
    public static final int DEFAULT_ATTEMPT_MS = 30000;
    /** 画像序列化后的上限（16 KiB） */
    public static final int MAX_SERIALIZED_BYTES = 16384;

    /**
     * 认证方式（判别联合）。
     * <p>NONE 无字段；HEADER/QUERY/FORM 需要 name + valueTemplate；
     * BASIC 需要 usernameSecret + passwordSecret。</p>
     */
    public record Auth(String mode, String name, String valueTemplate, String usernameSecret, String passwordSecret) {}

    /**
     * multipart 正文定义。
     *
     * @param mode             固定 MULTIPART
     * @param fileField        文件字段名（1–64，安全 ASCII）
     * @param fileNameTemplate 文件名模板，${publicId} / ${extension} 等受限变量
     * @param fields           普通表单字段（≤16 项）
     */
    public record Body(String mode, String fileField, String fileNameTemplate, Map<String, String> fields) {}

    /**
     * 响应解析定义。
     *
     * @param success                成功条件（1–8 条，全部满足）
     * @param urlPointer             必填，提取非空直链
     * @param thumbPointer           可空缩略图
     * @param idPointer              可空上游 ID
     * @param sizePointer            可空上游大小
     * @param sizeUnit               BYTES / KB / KIB
     * @param widthPointer           可空宽度
     * @param heightPointer          可空高度
     * @param numericStringPointers  允许以数字字符串形式出现的非成功字段（≤8）
     * @param allowedUrlOrigins      输出 URL 允许的精确 HTTPS origin（≤8）
     */
    public record ResponseSpec(
            List<Condition> success,
            String urlPointer,
            String thumbPointer,
            String idPointer,
            String sizePointer,
            String sizeUnit,
            String widthPointer,
            String heightPointer,
            List<String> numericStringPointers,
            List<String> allowedUrlOrigins) {}

    /**
     * 单条成功条件。
     *
     * @param pointer RFC 6901 JSON Pointer
     * @param op      EQ / EXISTS
     * @param type    STRING / NUMBER / BOOLEAN（EQ 必填）
     * @param value   EQ 的期望值（字符串形式，按 type 严格比较）
     */
    public record Condition(String pointer, String op, String type, String value) {}

    /**
     * 渠道级限额。
     *
     * @param maxBytes     1–5242880，只能收紧
     * @param allowedMimes 精确 MIME 集合，不接受 {@code image/*}
     */
    public record Limits(Long maxBytes, List<String> allowedMimes) {}

    /**
     * 超时预算。
     *
     * @param attemptMs 单次上游尝试预算（1000–60000），还受总剩余时间限制
     */
    public record Timeouts(Integer attemptMs) {}
}
