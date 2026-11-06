package com.astral.storage.http;

import com.astral.storage.config.StorageProperties;
import java.util.Map;

/**
 * CUSTOM_HTTP 渠道真实上传测试的执行缝（由执行链实现，本类只定义契约）。
 *
 * <p>设计约束（v1.1 §12 / §13.1）：真实测试属于有副作用的独立操作，必须由写权限触发；
 * 使用合成内容，不读取用户文件；应用侧开关关闭或执行器未装配时，测试<b>必须</b>返回
 * {@code STORAGE050} 失败，不得伪造成功。返回结果只含固定安全码与安全摘要，
 * 上游正文、认证 URL 与凭据一律不返回。</p>
 */
public interface HttpChannelTestTransport {

    /**
     * 测试请求。
     *
     * @param channelId     渠道 id（结果里回显，便于核对实际渠道）
     * @param configVersion 配置版本（测试针对的具体版本）
     * @param requestId     本次测试的操作 ID（审计键，不含凭据）
     * @param profile       有效画像
     * @param secrets       已配置凭据（仅内存使用；toString 已脱敏）
     * @param target        命中的批准目标
     * @param attemptMs     单次尝试预算（毫秒）
     */
    record TestRequest(
            Long channelId,
            Long configVersion,
            String requestId,
            HttpUploadProfile profile,
            Map<String, String> secrets,
            StorageProperties.ApprovedTarget target,
            int attemptMs) {

        @Override
        public String toString() {
            return "TestRequest[channelId=" + channelId + ", configVersion=" + configVersion + ", requestId="
                    + requestId + ", secrets=" + (secrets == null ? 0 : secrets.size()) + ", attemptMs=" + attemptMs
                    + "]";
        }
    }

    /**
     * 测试结论。
     *
     * @param success         是否通过（仅当上游按画像成功接收合成内容）
     * @param safeCode        固定安全码（如 OK / UNAVAILABLE / AUTH_REJECTED / UPSTREAM_HTTP_STATUS）
     * @param safeMessage     可展示的安全摘要，不得包含凭据、认证 URL、上游正文
     * @param httpStatus      上游 HTTP 状态（可为空）
     * @param remoteSizeBytes 上游报告的字节数（可为空）
     */
    record TestOutcome(boolean success, String safeCode, String safeMessage, Integer httpStatus, Long remoteSizeBytes) {

        /** 执行器不可用：明确失败，绝不伪造成功 */
        public static TestOutcome unavailable(String safeCode, String safeMessage) {
            return new TestOutcome(false, safeCode, safeMessage, null, null);
        }
    }

    /**
     * 执行一次真实测试。
     *
     * @param request 测试请求
     * @return 测试结论
     */
    TestOutcome probe(TestRequest request);
}
