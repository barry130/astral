package com.astral.ai.service;

import com.astral.ai.entity.AiAgentVersionEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 响应格式与工具轮数的回归测试。
 * <p>线上事故：版本上的 responseSchema 是裸 JSON Schema，被原样塞进 {@code response_format}，
 * 提供方按非法枚举静默忽略，模型因此从未被要求输出 JSON，音乐助手只回一句散文。</p>
 */
class AiChatOrchestratorFormatTest {

    private static final String BARE_SCHEMA =
            "{\"type\":\"object\",\"required\":[\"answer\"],\"properties\":{\"answer\":{\"type\":\"string\"}}}";

    @Test
    void wrapsBareSchemaIntoTheOpenAiResponseFormatEnvelope() {
        String format = AiChatOrchestrator.responseFormat(BARE_SCHEMA);

        assertTrue(format.contains("\"type\":\"json_schema\""), format);
        assertTrue(format.contains("\"json_schema\""), format);
        assertTrue(format.contains("\"schema\""), format);
        // 裸 schema 的 properties 必须原样保留在信封里
        assertTrue(format.contains("\"properties\""), format);
        assertTrue(format.contains("\"name\":\"agent_response\""), format);
    }

    @Test
    void keepsAnAlreadyWrappedFormatUntouched() {
        String wrapped = "{\"type\":\"json_object\"}";
        assertEquals(wrapped, AiChatOrchestrator.responseFormat(wrapped));

        String strict = "{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"x\",\"schema\":{}}}";
        assertEquals(strict, AiChatOrchestrator.responseFormat(strict));
    }

    @Test
    void blankOrEmptySchemaYieldsNoResponseFormat() {
        assertNull(AiChatOrchestrator.responseFormat(null));
        assertNull(AiChatOrchestrator.responseFormat("  "));
        assertNull(AiChatOrchestrator.responseFormat("{}"));
        assertNull(AiChatOrchestrator.responseFormat("not json"));
    }

    @Test
    void configuredMaxRoundsOverridesTheConstant() {
        assertEquals(3, AiChatOrchestrator.resolveMaxToolRounds(version("{\"maxRounds\":3}")));
        assertEquals(6, AiChatOrchestrator.resolveMaxToolRounds(version("{\"maxRounds\":6}")));
    }

    @Test
    void maxRoundsFallsBackAndIsClamped() {
        // 没配 → 默认 4
        assertEquals(4, AiChatOrchestrator.resolveMaxToolRounds(version(null)));
        assertEquals(4, AiChatOrchestrator.resolveMaxToolRounds(version("{}")));
        // 填 0 或负数 → 夹到 1，避免一轮都不跑
        assertEquals(1, AiChatOrchestrator.resolveMaxToolRounds(version("{\"maxRounds\":0}")));
        // 填离谱的大数 → 夹到硬上限，避免请求被拖死
        assertEquals(8, AiChatOrchestrator.resolveMaxToolRounds(version("{\"maxRounds\":999}")));
        // 版本为 null 也不能炸
        assertEquals(4, AiChatOrchestrator.resolveMaxToolRounds(null));
    }

    @Test
    void transitionSentenceWithoutBlocksIsNotAStructuredAnswer() {
        // 事故原句：非空但没有 blocks，旧逻辑只看 isBlank() 所以兜底从未触发
        assertFalse(AiChatOrchestrator.hasStructuredBlocks("我会为你制定一个适合跑步的快歌搜索计划。"));
        assertFalse(AiChatOrchestrator.hasStructuredBlocks("{\"answer\":\"好的\"}"));
        assertFalse(AiChatOrchestrator.hasStructuredBlocks("{\"answer\":\"好的\",\"blocks\":[]}"));
        assertFalse(AiChatOrchestrator.hasStructuredBlocks(null));
        assertFalse(AiChatOrchestrator.hasStructuredBlocks("  "));
    }

    @Test
    void answerWithSongBlocksCountsAsStructured() {
        String json = "{\"answer\":\"挑了几首\",\"blocks\":[{\"type\":\"song_list\",\"title\":\"跑步\","
                + "\"items\":[{\"songId\":\"a\",\"sourceId\":\"qq\",\"name\":\"x\",\"artist\":\"y\",\"reason\":\"r\"}]}]}";
        assertTrue(AiChatOrchestrator.hasStructuredBlocks(json));
    }

    @Test
    void fencedJsonIsStillRecognised() {
        String fenced = "```json\n{\"answer\":\"挑了几首\",\"blocks\":[{\"type\":\"song_list\"}]}\n```";
        assertTrue(AiChatOrchestrator.hasStructuredBlocks(fenced));
    }

    private static AiAgentVersionEntity version(String toolPolicyJson) {
        AiAgentVersionEntity version = new AiAgentVersionEntity();
        version.setToolPolicyJson(toolPolicyJson);
        return version;
    }
}
