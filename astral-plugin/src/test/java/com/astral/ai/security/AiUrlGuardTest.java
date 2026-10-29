package com.astral.ai.security;

import com.astral.ai.common.AiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 提供方 Base URL 的 SSRF 防护。
 *
 * <p>{@code base_url} 由后台填写，因此这里是「后台可配置」与「不得打到内网」之间唯一的边界。
 * 测试覆盖：协议白名单、云元数据地址、各类私网地址、白名单放行、返回值规范化。</p>
 */
class AiUrlGuardTest {

    @Test
    void keepsPublicHttpsUrlAndStripsTrailingSlashes() {
        // 用 IP 字面量而不是域名：域名版本要靠 DNS 解析（AIR guard 会做 rebinding 检查），
        // 在无外网/DNS 受限的构建机（如 CNB 流水线）会解析失败被误判为内网地址。
        // 校验目标与「去尾部斜杠」无关，换成公网 IP 字面量同样成立。
        assertEquals("https://11.22.33.44/v1",
                AiUrlGuard.validateBaseUrl("https://11.22.33.44/v1///", null));
    }

    @Test
    void allowsPlainHttp() {
        assertEquals("http://api.example.com", AiUrlGuard.validateBaseUrl("http://api.example.com", null));
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertAi006("ftp://api.example.com");
        assertAi006("file:///etc/passwd");
        assertAi006("gopher://api.example.com");
    }

    @Test
    void rejectsBlankOrBrokenUrls() {
        assertAi006(null);
        assertAi006("   ");
        assertAi006("not a url");
        assertAi006("https://");
    }

    @Test
    void rejectsCloudMetadataAddresses() {
        assertAi006("http://169.254.169.254/latest/meta-data");
        assertAi006("http://metadata.google.internal/computeMetadata/v1");
        assertAi006("http://100.100.100.200/latest/meta-data");
    }

    @Test
    void rejectsLoopbackAndPrivateHosts() {
        assertAi006("http://localhost:11434/v1");
        assertAi006("http://127.0.0.1:8080");
        assertAi006("http://10.0.0.5/v1");
        assertAi006("http://192.168.1.117:27000/api");
        assertAi006("http://172.16.0.1");
        assertAi006("http://172.31.255.255");
        assertAi006("http://169.254.1.1");
        assertAi006("http://[::1]:8080");
        assertAi006("http://[fd00::1]");
        assertAi006("http://router.local");
        assertAi006("http://service.internal");
    }

    @Test
    void allowsBoundaryPublicAddressesThatLookPrivate() {
        // 172.32 已不在 172.16/12 内，198.20 已不在 198.18/15 内
        assertEquals("http://172.32.0.1", AiUrlGuard.validateBaseUrl("http://172.32.0.1", null));
        assertEquals("http://198.20.0.1", AiUrlGuard.validateBaseUrl("http://198.20.0.1", null));
        assertEquals("http://100.128.0.1", AiUrlGuard.validateBaseUrl("http://100.128.0.1", null));
    }

    @Test
    void whitelistCanAuthorizeASpecificPrivateHost() {
        // 本地 Ollama 是受控例外：必须显式逐条授权
        assertEquals("http://localhost:11434/v1",
                AiUrlGuard.validateBaseUrl("http://localhost:11434/v1", "localhost"));
        assertEquals("http://10.0.0.5/v1",
                AiUrlGuard.validateBaseUrl("http://10.0.0.5/v1", " 10.0.0.5 , other "));
    }

    @Test
    void whitelistIsCaseInsensitive() {
        // 白名单匹配忽略大小写；返回值保持用户填写的大小写，不做改写
        assertEquals("http://LOCALHOST:11434", AiUrlGuard.validateBaseUrl("http://LOCALHOST:11434", "LocalHost"));
    }

    @Test
    void wildcardWhitelistDisablesTheGuard() {
        assertEquals("http://127.0.0.1:8080", AiUrlGuard.validateBaseUrl("http://127.0.0.1:8080", "*"));
        assertEquals("http://169.254.169.254", AiUrlGuard.validateBaseUrl("http://169.254.169.254", "*"));
    }

    @Test
    void emptyWhitelistEntriesAreIgnored() {
        assertAi006("http://10.0.0.5", " , , ");
    }

    @Test
    void unrelatedWhitelistEntryDoesNotAuthorize() {
        assertAi006("http://10.0.0.5", "localhost,192.168.0.1");
    }

    private static void assertAi006(String url) {
        AiException error = assertThrows(AiException.class, () -> AiUrlGuard.validateBaseUrl(url, null));
        assertEquals("AI006", error.getErrorCode());
    }

    private static void assertAi006(String url, String allowPrivateHosts) {
        AiException error = assertThrows(AiException.class,
                () -> AiUrlGuard.validateBaseUrl(url, allowPrivateHosts));
        assertEquals("AI006", error.getErrorCode());
    }
}
