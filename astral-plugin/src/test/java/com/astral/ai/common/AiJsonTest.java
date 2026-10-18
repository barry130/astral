package com.astral.ai.common;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AiJson} 的边界行为。
 *
 * <p>这些方法被几乎所有 AI Service 调用，是「JSON 处理不抛异常」这一约定的唯一实现点：
 * 任何改动都必须保持「非法输入返回 null / 空集合，而不是抛异常」。</p>
 */
class AiJsonTest {

    @Test
    void readReturnsNullForBlankOrBrokenJson() {
        assertNull(AiJson.read(null));
        assertNull(AiJson.read("   "));
        assertNull(AiJson.read("{not json"));
    }

    @Test
    void readObjectFallsBackToEmptyObjectSoCallersCanChain() {
        ObjectNode node = AiJson.readObject("[]");
        assertNotNull(node);
        assertTrue(node.isEmpty());

        ObjectNode parsed = AiJson.readObject("{\"a\":1}");
        assertEquals(1, parsed.get("a").asInt());
    }

    @Test
    void readArrayFallsBackToEmptyArray() {
        assertTrue(AiJson.readArray("{\"a\":1}").isEmpty());
        assertEquals(2, AiJson.readArray("[1,2]").size());
    }

    @Test
    void writePassesStringsThroughAndIgnoresNull() {
        assertNull(AiJson.write(null));
        assertEquals("{\"raw\":1}", AiJson.write("{\"raw\":1}"));
        assertEquals("{\"a\":1}", AiJson.write(AiJson.readObject("{\"a\":1}")));
    }

    @Test
    void textTreatsBlankAndNullNodeAsDefault() {
        ObjectNode node = AiJson.readObject("{\"name\":\"  \",\"title\":\"标题\",\"none\":null}");
        assertEquals("fallback", AiJson.text(node, "name", "fallback"));
        assertEquals("标题", AiJson.text(node, "title", "fallback"));
        assertEquals("fallback", AiJson.text(node, "none", "fallback"));
        assertEquals("fallback", AiJson.text(node, "missing", "fallback"));
        assertEquals("fallback", AiJson.text(null, "title", "fallback"));
    }

    @Test
    void intValueRejectsNonNumericNodes() {
        ObjectNode node = AiJson.readObject("{\"limit\":5,\"bad\":\"5\",\"flag\":true}");
        assertEquals(5, AiJson.intValue(node, "limit", 30));
        assertEquals(30, AiJson.intValue(node, "bad", 30));
        assertEquals(30, AiJson.intValue(node, "flag", 30));
        assertEquals(30, AiJson.intValue(null, "limit", 30));
    }

    @Test
    void boolOnlyAcceptsRealBooleans() {
        ObjectNode node = AiJson.readObject("{\"on\":true,\"text\":\"true\"}");
        assertTrue(AiJson.bool(node, "on", false));
        assertFalse(AiJson.bool(node, "text", false));
        assertFalse(AiJson.bool(null, "on", false));
    }

    @Test
    void stringListSkipsBlankEntriesAndKeepsOrder() {
        ObjectNode node = AiJson.readObject("{\"keys\":[\" a \",\"\",\"  \",\"b\"]}");
        assertEquals(List.of("a", "b"), AiJson.stringList(node, "keys"));
        assertTrue(AiJson.stringList(node, "missing").isEmpty());
        assertTrue(AiJson.stringList(null, "keys").isEmpty());
    }

    @Test
    void splitCsvUppercasesBecauseClientTypesAreComparedAgainstConstants() {
        assertEquals(List.of("QT_PC", "ASTRAL_FRONT"), AiJson.splitCsv(" qt_pc , ASTRAL_FRONT ,"));
        assertTrue(AiJson.splitCsv(null).isEmpty());
        assertTrue(AiJson.splitCsv("  ").isEmpty());
    }

    @Test
    void joinCsvDropsBlanksAndReturnsNullWhenNothingLeft() {
        assertEquals("a,b", AiJson.joinCsv(Arrays.asList(" a ", null, "", " b ")));
        assertNull(AiJson.joinCsv(null));
        assertNull(AiJson.joinCsv(List.of(" ")));
    }

    @Test
    void truncateKeepsShortTextIntact() {
        assertNull(AiJson.truncate(null, 5));
        assertEquals("abc", AiJson.truncate("abc", 5));
        assertEquals("abcde", AiJson.truncate("abcdefg", 5));
    }

    @Test
    void isJsonStructuredOnlyAcceptsObjectOrArray() {
        assertTrue(AiJson.isJsonStructured("{\"a\":1}"));
        assertTrue(AiJson.isJsonStructured("[]"));
        assertFalse(AiJson.isJsonStructured("\"text\""));
        assertFalse(AiJson.isJsonStructured("123"));
        assertFalse(AiJson.isJsonStructured(null));
    }
}
