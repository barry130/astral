package com.astral.ai.service;

import com.astral.ai.provider.AiProviderDtos.Message;
import com.astral.ai.provider.AiProviderDtos.ToolCall;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/**
 * 历史窗口配对修复的回归测试。
 * <p>线上事故：历史按条数截断，把「助手 toolCalls」与紧随其后的「tool 结果」切成两半，
 * 提供方直接回 400（orphaned or duplicate tool result），整轮对话失败。</p>
 */
class AiChatOrchestratorHistoryTest {

    private static final String TOOL = "tool";

    @Test
    void keepsAProperlyPairedToolRound() throws Exception {
        List<Message> input = List.of(
                Message.user("放点爵士"),
                Message.assistantToolCalls("", List.of(new ToolCall("call_1", "search_music", "{}"))),
                Message.tool("call_1", "search_music", "{\"songs\":[]}"),
                Message.assistant("给你找了几首"));

        List<Message> result = invoke(input);

        assertEquals(4, result.size());
        assertEquals("tool", result.get(2).role());
        assertEquals("call_1", result.get(2).toolCallId());
    }

    @Test
    void dropsLeadingToolMessageWhoseAssistantWasTruncatedAway() throws Exception {
        // 窗口正好从 tool 结果开始：助手那条 toolCalls 消息已被截掉
        List<Message> input = List.of(
                Message.tool("call_1", "search_music", "{\"songs\":[]}"),
                Message.assistant("给你找了几首"),
                Message.user("再来点"));

        List<Message> result = invoke(input);

        assertEquals(2, result.size());
        assertTrue(result.stream().noneMatch(m -> TOOL.equals(m.role())));
        assertEquals("给你找了几首", result.get(0).content());
    }

    @Test
    void dropsAssistantToolCallsWhenOnlySomeResultsSurvive() throws Exception {
        // 两个调用只留下一个结果：配对不全，助手消息与结果一起丢
        List<Message> input = List.of(
                Message.user("放点爵士"),
                Message.assistantToolCalls("", List.of(
                        new ToolCall("call_1", "search_music", "{}"),
                        new ToolCall("call_2", "get_song_detail", "{}"))),
                Message.tool("call_1", "search_music", "{\"songs\":[]}"),
                Message.assistant("给你找了几首"));

        List<Message> result = invoke(input);

        assertEquals(2, result.size());
        assertEquals("放点爵士", result.get(0).content());
        assertEquals("给你找了几首", result.get(1).content());
        assertTrue(result.stream().noneMatch(m -> TOOL.equals(m.role())));
        assertTrue(result.stream().noneMatch(m -> m.toolCalls() != null));
    }

    @Test
    void dropsToolResultThatWasNeverDeclared() throws Exception {
        List<Message> input = List.of(
                Message.assistantToolCalls("", List.of(new ToolCall("call_1", "search_music", "{}"))),
                Message.tool("call_9", "search_music", "{\"songs\":[]}"),
                Message.assistant("给你找了几首"));

        List<Message> result = invoke(input);

        assertEquals(1, result.size());
        assertEquals("给你找了几首", result.get(0).content());
    }

    @Test
    void dropsDuplicateToolResults() throws Exception {
        List<Message> input = List.of(
                Message.assistantToolCalls("", List.of(new ToolCall("call_1", "search_music", "{}"))),
                Message.tool("call_1", "search_music", "{}"),
                Message.tool("call_1", "search_music", "{}"),
                Message.assistant("给你找了几首"));

        List<Message> result = invoke(input);

        assertEquals(1, result.size());
        assertEquals("给你找了几首", result.get(0).content());
    }

    @Test
    void leavesPlainConversationUntouched() throws Exception {
        List<Message> input = List.of(
                Message.system("你是音乐助手"),
                Message.user("你好"),
                Message.assistant("你好，想听点什么？"));

        List<Message> result = invoke(input);

        assertEquals(3, result.size());
        assertEquals(input, result);
    }

    @Test
    void keepsConsecutiveToolRounds() throws Exception {
        List<Message> input = List.of(
                Message.assistantToolCalls("", List.of(new ToolCall("call_1", "search_music", "{}"))),
                Message.tool("call_1", "search_music", "{}"),
                Message.assistantToolCalls("", List.of(new ToolCall("call_2", "get_song_detail", "{}"))),
                Message.tool("call_2", "get_song_detail", "{}"),
                Message.assistant("好了"));

        List<Message> result = invoke(input);

        assertEquals(5, result.size());
        assertEquals("call_1", result.get(1).toolCallId());
        assertEquals("call_2", result.get(3).toolCallId());
    }

    @SuppressWarnings("unchecked")
    private static List<Message> invoke(List<Message> input) throws Exception {
        // 该方法是纯函数（只读入参），用真实方法调用的 mock 实例反射调用即可，
        // 不必装配 15 个构造依赖。
        AiChatOrchestrator orchestrator = mock(AiChatOrchestrator.class, CALLS_REAL_METHODS);
        Method method = AiChatOrchestrator.class.getDeclaredMethod("dropOrphanToolMessages", List.class);
        method.setAccessible(true);
        return new ArrayList<>((List<Message>) method.invoke(orchestrator, input));
    }
}
