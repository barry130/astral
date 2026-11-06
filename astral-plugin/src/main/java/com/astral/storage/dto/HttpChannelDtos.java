package com.astral.storage.dto;

import com.astral.storage.http.HttpSecretEnvelope;
import com.astral.storage.http.HttpUploadProfile;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * CUSTOM_HTTP 渠道管理 DTO 集合（与旧 {@link StorageDtos} 完全分离，旧配置契约不变）。
 *
 * <p>凭据只以 {@link HttpSecretEnvelope.SecretView}（name/configured/updatedAt）出现在读出侧；
 * 请求侧凭据使用判别联合 {@link HttpSecretEnvelope.SecretChange}（KEEP/SET/CLEAR），
 * 不接受"看起来已配置"的掩码。</p>
 */
public final class HttpChannelDtos {

    private HttpChannelDtos() {}

    /** 新建渠道：profile 与 secretChanges 同版本保存；status 缺省 DISABLED */
    public record ChannelCreateReq(
            String name,
            HttpUploadProfile profile,
            Map<String, HttpSecretEnvelope.SecretChange> secretChanges,
            String status,
            String remark) {}

    /** 更新渠道：必须带 expectedVersion（手工 CAS），冲突返回 STORAGE038 */
    public record ChannelUpdateReq(
            Long expectedVersion,
            String name,
            HttpUploadProfile profile,
            Map<String, HttpSecretEnvelope.SecretChange> secretChanges,
            String status,
            String remark) {}

    /** 列表摘要（不含 profile / 凭据） */
    public record ChannelSummary(
            Long id,
            String name,
            String providerType,
            String status,
            Long configVersion,
            Integer isDefault,
            String healthStatus,
            LocalDateTime lastTestTime,
            String lastTestMessage,
            String remark,
            String createBy,
            LocalDateTime createTime,
            String updateBy,
            LocalDateTime updateTime) {}

    /** 安全详情：profile 可见（不含凭据明文），凭据只列是否已配置 */
    public record ChannelView(
            Long id,
            String name,
            String providerType,
            String status,
            Long configVersion,
            Integer isDefault,
            String healthStatus,
            LocalDateTime lastTestTime,
            String lastTestMessage,
            String remark,
            HttpUploadProfile profile,
            List<HttpSecretEnvelope.SecretView> configuredSecrets,
            List<String> missingSecrets,
            String configError,
            String createBy,
            LocalDateTime createTime,
            String updateBy,
            LocalDateTime updateTime) {}

    /** 离线校验入参：不访问数据库、不联网 */
    public record ChannelValidateReq(
            HttpUploadProfile profile, Map<String, HttpSecretEnvelope.SecretChange> secretChanges, String status) {}

    /** 离线校验结果：valid=false 时按 missingSecrets 提示补齐 */
    public record ChannelValidationView(
            boolean valid,
            HttpUploadProfile profile,
            List<HttpSecretEnvelope.SecretView> configuredSecrets,
            List<String> missingSecrets) {}

    /** 真实测试结果：只回安全摘要与实际渠道/操作 ID */
    public record ChannelTestView(
            boolean success,
            String safeCode,
            String safeMessage,
            Integer httpStatus,
            Long remoteSizeBytes,
            Long channelId,
            Long configVersion,
            String requestId,
            LocalDateTime testedAt) {}

    /** 部署侧批准目标（只读；管理接口不能扩大） */
    public record ApprovedTargetView(String origin, String path, List<String> urlOrigins) {}
}
