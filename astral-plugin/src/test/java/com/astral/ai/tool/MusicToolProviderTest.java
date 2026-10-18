package com.astral.ai.tool;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 音乐助手工具的搜索计划与候选处理。
 *
 * <p>关键业务约束（设计文档 §6.1）：真实音源搜索只能在客户端完成，服务端工具<b>不能</b>
 * 自己去搜索，只能产出计划或整理客户端回传的候选。这里锁定三条不变量：</p>
 * <ul>
 *   <li>没有候选时返回 NEED_CLIENT 并附带完整搜索计划；</li>
 *   <li>计划里的音源必须来自客户端上报的注册表，模型瞎编的音源要被剔除；</li>
 *   <li>有候选时把候选整理成模型可读素材，禁止模型「另外编歌」。</li>
 * </ul>
 */
class MusicToolProviderTest {

    private final MusicToolProvider provider = new MusicToolProvider();

    private AiToolTypes.AiToolHandler tool(String code) {
        return provider.handlers().stream()
                .filter(h -> code.equals(h.definition().toolCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("工具未注册：" + code));
    }

    private static AiToolTypes.AiToolContext context(ObjectNode arguments, ObjectNode context) {
        return new AiToolTypes.AiToolContext(
                1L, 2L, 3L, 4L, "music_assistant", AiConstants.AgentType.MUSIC,
                AiConstants.ClientType.QT_PC, arguments, context, false, null);
    }

    @Test
    void registersExactlyNineReadOnlyToolsForBothQtClients() {
        List<AiToolTypes.AiToolHandler> handlers = provider.handlers();
        assertEquals(9, handlers.size());

        for (AiToolTypes.AiToolHandler handler : handlers) {
            AiToolTypes.AiToolDef def = handler.definition();
            assertEquals(AiConstants.RiskLevel.READ, def.riskLevel(), def.toolCode());
            assertEquals(AiConstants.ApprovalPolicy.NONE, def.approvalPolicy(), def.toolCode());
            assertEquals(List.of(AiConstants.ClientType.QT_UNIAPPX, AiConstants.ClientType.QT_PC),
                    def.clientTypes(), def.toolCode());
            assertTrue(def.permissionCodes().isEmpty(), def.toolCode());
            assertNotNull(def.argumentsSchema(), def.toolCode());
        }
    }

    @Test
    void searchWithoutCandidatesAsksTheClientToSearch() {
        ObjectNode args = AiJson.readObject("{\"keywords\":[\"周杰伦\",\"晴天\"],\"limit\":10}");
        ObjectNode ctx = AiJson.readObject(
                "{\"installedSourceRegistry\":[\"netease\",\"qq\"]}");

        AiToolTypes.AiToolResult result = tool("search_music").execute(context(args, ctx));

        assertEquals(AiToolTypes.AiToolResult.NEED_CLIENT, result.status());
        assertTrue(result.isOk());
        ObjectNode payload = result.payload();
        assertEquals("recommend_music", payload.get("intent").asString());
        assertEquals(10, payload.get("limit").asInt());
        assertEquals(List.of("周杰伦", "晴天"), AiJson.stringList(payload, "keywords"));
        assertEquals(List.of("netease", "qq"), AiJson.stringList(payload, "sources"));
        assertNull(payload.get("candidates"));
    }

    @Test
    void eachSearchToolProducesItsOwnIntent() {
        ObjectNode ctx = AiJson.readObject("{}");
        assertEquals("recommend_music", intentOf("search_music", ctx));
        assertEquals("find_artist", intentOf("search_artist", ctx));
        assertEquals("find_album", intentOf("search_album", ctx));
        assertEquals("find_playlist", intentOf("search_playlist", ctx));
    }

    @Test
    void requestedSourcesOutsideTheClientRegistryAreDropped() {
        ObjectNode args = AiJson.readObject("{\"sources\":[\"netease\",\"made_up_source\"]}");
        ObjectNode ctx = AiJson.readObject("{\"installedSourceRegistry\":[\"netease\",\"qq\"]}");

        AiToolTypes.AiToolResult result = tool("search_music").execute(context(args, ctx));

        assertEquals(List.of("netease"), AiJson.stringList(result.payload(), "sources"));
    }

    @Test
    void whenEveryRequestedSourceIsUnknownItFallsBackToInstalledOnes() {
        ObjectNode args = AiJson.readObject("{\"sources\":[\"made_up_source\"]}");
        ObjectNode ctx = AiJson.readObject("{\"installedSourceRegistry\":[\"netease\",\"qq\"]}");

        AiToolTypes.AiToolResult result = tool("search_music").execute(context(args, ctx));

        assertEquals(List.of("netease", "qq"), AiJson.stringList(result.payload(), "sources"));
    }

    @Test
    void sourceRegistryAcceptsObjectsAsWellAsPlainStrings() {
        ObjectNode args = AiJson.readObject("{\"sources\":[\"qq\"]}");
        ObjectNode ctx = AiJson.readObject(
                "{\"installedSourceRegistry\":[{\"id\":\"qq\"},{\"sourceId\":\"netease\"}]}");

        AiToolTypes.AiToolResult result = tool("search_music").execute(context(args, ctx));

        assertEquals(List.of("qq"), AiJson.stringList(result.payload(), "sources"));
    }

    @Test
    void limitIsClampedIntoTheSupportedRange() {
        ObjectNode ctx = AiJson.readObject("{}");
        assertEquals(100, limitOf("{\"limit\":9999}", ctx));
        assertEquals(1, limitOf("{\"limit\":0}", ctx));
        assertEquals(1, limitOf("{\"limit\":-5}", ctx));
        assertEquals(30, limitOf("{}", ctx));
    }

    @Test
    void candidatesFromTheClientAreHandedToTheModelInstead() {
        ObjectNode args = AiJson.readObject("{\"keywords\":[\"晴天\"]}");
        ObjectNode ctx = AiJson.readObject("""
                {"installedSourceRegistry":["netease"],
                 "musicCandidates":[
                   {"sourceId":"netease","songId":"186016","name":"晴天","artist":"周杰伦",
                    "album":"叶惠美","duration":269000,"quality":"320k"},
                   {"sourceId":"qq","songId":"0039MnYb0qxYhV","name":"晴天","artist":"周杰伦"}
                 ]}""");

        AiToolTypes.AiToolResult result = tool("search_music").execute(context(args, ctx));

        assertEquals(AiToolTypes.AiToolResult.SUCCEEDED, result.status());
        ObjectNode payload = result.payload();
        var candidates = payload.get("candidates");
        assertEquals(2, candidates.size());
        assertEquals("186016", candidates.get(0).get("songId").asString());
        assertEquals(269000, candidates.get(0).get("duration").asInt());
        // 缺省字段不应写成 null 键
        assertFalse(candidates.get(1).has("duration"));

        // 摘要必须带上「只从候选里挑、不要编造」的约束与可核对的标识
        String summary = result.summary();
        assertTrue(summary.contains("不要编造"), summary);
        assertTrue(summary.contains("晴天 - 周杰伦"), summary);
        assertTrue(summary.contains("sourceId=netease"), summary);
        assertTrue(summary.contains("songId=186016"), summary);
    }

    @Test
    void candidateListIsCappedSoThePromptCannotExplode() {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"sourceId\":\"netease\",\"songId\":\"").append(i).append("\",\"name\":\"n").append(i).append("\"}");
        }
        ObjectNode ctx = AiJson.readObject("{\"musicCandidates\":[" + items + "]}");

        AiToolTypes.AiToolResult result = tool("search_music").execute(context(AiJson.readObject("{}"), ctx));

        assertEquals(30, result.payload().get("candidates").size());
    }

    @Test
    void songDetailReadsOnlyFromTheCandidateSet() {
        ObjectNode ctx = AiJson.readObject("""
                {"musicCandidates":[{"sourceId":"netease","songId":"186016","name":"晴天",
                  "artist":"周杰伦","album":"叶惠美","duration":269000,"quality":"320k"}]}""");

        AiToolTypes.AiToolResult hit = tool("get_song_detail")
                .execute(context(AiJson.readObject("{\"songId\":\"186016\"}"), ctx));
        assertEquals(AiToolTypes.AiToolResult.SUCCEEDED, hit.status());
        assertEquals("晴天", hit.payload().get("name").asString());
        assertEquals("歌曲：晴天 - 周杰伦", hit.summary());

        AiToolTypes.AiToolResult wrongSource = tool("get_song_detail")
                .execute(context(AiJson.readObject("{\"songId\":\"186016\",\"sourceId\":\"qq\"}"), ctx));
        assertEquals(AiToolTypes.AiToolResult.FAILED, wrongSource.status());

        AiToolTypes.AiToolResult miss = tool("get_song_detail")
                .execute(context(AiJson.readObject("{\"songId\":\"nope\"}"), ctx));
        assertEquals(AiToolTypes.AiToolResult.FAILED, miss.status());
        assertTrue(miss.summary().contains("search_music"));
    }

    @Test
    void songDetailRejectsMissingSongId() {
        AiToolTypes.AiToolResult result = tool("get_song_detail")
                .execute(context(AiJson.readObject("{}"), AiJson.readObject("{}")));

        assertEquals(AiToolTypes.AiToolResult.REJECTED, result.status());
        assertTrue(result.summary().contains("songId"));
    }

    @Test
    void absentContextIsReportedAsEmptyRatherThanFailure() {
        ObjectNode noContext = AiJson.readObject("{}");
        ObjectNode noArgs = AiJson.readObject("{}");

        for (String code : List.of("get_current_song", "get_current_playlist", "get_user_likes", "get_play_history")) {
            AiToolTypes.AiToolResult result = tool(code).execute(context(noArgs, noContext));
            // 上下文缺失属于「客户端没上报」，不是工具故障，模型仍应继续回答
            assertEquals(AiToolTypes.AiToolResult.SUCCEEDED, result.status(), code);
            assertTrue(result.payload().isEmpty(), code);
        }
    }

    @Test
    void currentPlaylistCountsTheReportedQueue() {
        ObjectNode ctx = AiJson.readObject(
                "{\"currentQueue\":[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"}]}");

        AiToolTypes.AiToolResult result = tool("get_current_playlist")
                .execute(context(AiJson.readObject("{}"), ctx));

        assertEquals(AiToolTypes.AiToolResult.SUCCEEDED, result.status());
        assertTrue(result.summary().contains("3"), result.summary());
        assertEquals(3, result.payload().get("queue").size());
    }

    @Test
    void nullContextNodeDoesNotBlowUp() {
        AiToolTypes.AiToolContext nullContext = new AiToolTypes.AiToolContext(
                1L, 2L, 3L, 4L, "music_assistant", AiConstants.AgentType.MUSIC,
                AiConstants.ClientType.QT_UNIAPPX, null, null, false, null);

        for (AiToolTypes.AiToolHandler handler : provider.handlers()) {
            AiToolTypes.AiToolResult result = handler.execute(nullContext);
            assertNotNull(result, handler.definition().toolCode());
        }
    }

    private String intentOf(String toolCode, ObjectNode ctx) {
        AiToolTypes.AiToolResult result = tool(toolCode).execute(context(AiJson.readObject("{}"), ctx));
        return result.payload().get("intent").asString();
    }

    private int limitOf(String argsJson, ObjectNode ctx) {
        AiToolTypes.AiToolResult result = tool("search_music")
                .execute(context(AiJson.readObject(argsJson), ctx));
        return result.payload().get("limit").asInt();
    }
}
