package com.astral.qt.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * 包头解析单测：与 qt-pc source_install.rs / qt-uniappx source-update.uts /
 * qt-sources build-sources.mjs 同款口径——找 /*__QT_PACK__ 标记，取到最近 *&#47; 之间的 JSON。
 * <p>重点回归 2026-10-26 事故：cnb.cool 短链 302 跳 HTML 页时探测拿到的是 HTML，
 * 解析必须返回 null（客户端/服务端都不得把半截文本当有效版本号）。</p>
 */
class QtPackHeaderProbeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseValidHeaderLine() {
        String text = "/*__QT_PACK__{\"kind\":\"meta\",\"id\":\"meta-official\",\"name\":\"官方元数据包\","
                + "\"versionCode\":2026102601,\"versionName\":\"2026.10.26.01\",\"updateUrl\":\"\","
                + "\"notes\":\"test\"}*/\n"
                + "// ===== 后面是几 MB 的包体，Range 只取了首段 =====\n"
                + "(function(){})();\n";
        QtPackHeaderProbe.PackHeader header = QtPackHeaderProbe.parsePackHeader(text, mapper);
        assertNotNull(header);
        assertEquals("meta", header.kind());
        assertEquals("meta-official", header.id());
        assertEquals("官方元数据包", header.name());
        assertEquals(2026102601L, header.versionCode());
        assertEquals("2026.10.26.01", header.versionName());
    }

    @Test
    void parseWithLeadingBytesBeforeMark() {
        String text = "  \n/*__QT_PACK__{\"kind\":\"play\",\"id\":\"play-official\",\"versionCode\":2026102501}*/\nbody";
        QtPackHeaderProbe.PackHeader header = QtPackHeaderProbe.parsePackHeader(text, mapper);
        assertNotNull(header);
        assertEquals("play", header.kind());
        assertEquals("play-official", header.id());
        assertEquals(2026102501L, header.versionCode());
        assertEquals("", header.versionName());
    }

    /** 事故回归：302 跳转拿到 HTML 跳转页 / 错误页 → 无标记 → null，不得误判 */
    @Test
    void parseHtmlRedirectPageReturnsNull() {
        String html = "<!DOCTYPE html><html><head><title>302 Found</title></head>"
                + "<body>Redirecting to OSS...</body></html>";
        assertNull(QtPackHeaderProbe.parsePackHeader(html, mapper));
    }

    @Test
    void parseTruncatedHeaderReturnsNull() {
        // Range 截断把包头 JSON 切了一半（连 *&#47; 都没取到）
        String truncated = "/*__QT_PACK__{\"kind\":\"meta\",\"id\":\"meta-offi";
        assertNull(QtPackHeaderProbe.parsePackHeader(truncated, mapper));
    }

    @Test
    void parseMalformedJsonReturnsNull() {
        assertNull(QtPackHeaderProbe.parsePackHeader("/*__QT_PACK__{kind:meta}*/\nbody", mapper));
    }

    @Test
    void parseMissingRequiredFieldsReturnsNull() {
        // 缺 versionCode（=0）→ null：版本号是比版唯一依据，缺了绝不能当 0 下发
        assertNull(QtPackHeaderProbe.parsePackHeader(
                "/*__QT_PACK__{\"kind\":\"meta\",\"id\":\"meta-official\"}*/\nbody", mapper));
        // 缺 id → null
        assertNull(QtPackHeaderProbe.parsePackHeader(
                "/*__QT_PACK__{\"kind\":\"meta\",\"versionCode\":2026102601}*/\nbody", mapper));
        // 缺 kind → null
        assertNull(QtPackHeaderProbe.parsePackHeader(
                "/*__QT_PACK__{\"id\":\"meta-official\",\"versionCode\":2026102601}*/\nbody", mapper));
    }

    @Test
    void parseBlankOrMissingTextReturnsNull() {
        assertNull(QtPackHeaderProbe.parsePackHeader(null, mapper));
        assertNull(QtPackHeaderProbe.parsePackHeader("", mapper));
        assertNull(QtPackHeaderProbe.parsePackHeader("   \n  ", mapper));
    }
}
