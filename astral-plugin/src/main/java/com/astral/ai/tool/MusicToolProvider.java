package com.astral.ai.tool;

import com.astral.ai.common.AiConstants;
import com.astral.ai.dto.AiAppDtos;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolDef;
import com.astral.ai.tool.AiToolTypes.AiToolHandler;
import com.astral.ai.tool.AiToolTypes.AiToolProvider;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 音乐助手工具集（设计文档 §6.3）。
 *
 * <p>这些工具都<b>不访问任何音乐平台接口</b>：真正的搜索由客户端音源包/音源脚本完成。
 * 服务端只做两件事——把模型的自然语言意图翻译成结构化的 {@code MusicSearchPlan}
 * 交给客户端执行，以及把客户端回传的候选集整理成模型可读的排行素材。</p>
 *
 * <p>这样设计的原因：音源逻辑带签名、Cookie 与平台风控，必须留在客户端；
 * 服务端一旦去抓取就既不稳定也不合规。</p>
 */
@Slf4j
@Component
public class MusicToolProvider implements AiToolProvider {

    /** 客户端可用音源注册表在上下文里的键 */
    private static final String CTX_SOURCES = AiConstants.ContextKey.INSTALLED_SOURCE_REGISTRY;

    /** 客户端回传的候选集在上下文里的键 */
    private static final String CTX_CANDIDATES = AiConstants.ContextKey.MUSIC_CANDIDATES;

    /** 推荐给模型看的候选上限（避免 prompt 爆炸） */
    private static final int MAX_CANDIDATES_TO_MODEL = 30;

    @Override
    public List<AiToolHandler> handlers() {
        return List.of(
                new SearchMusicTool(),
                new SearchArtistTool(),
                new SearchAlbumTool(),
                new SearchPlaylistTool(),
                new GetSongDetailTool(),
                new GetCurrentSongTool(),
                new GetCurrentPlaylistTool(),
                new GetUserLikesTool(),
                new GetPlayHistoryTool()
        );
    }

    // ==================== 搜索计划类 ====================

    /**
     * 生成音乐搜索计划。
     *
     * <p>返回 {@link AiToolResult#NEED_CLIENT}：服务端不能搜索，必须由客户端执行后回传候选。
     * 排在首位的工具，模型通常在用户提出找歌/推荐时首先调用。</p>
     */
    private class SearchMusicTool implements AiToolHandler {

        @Override
        public AiToolDef definition() {
            return new AiToolDef(
                    "search_music", "搜索音乐",
                    "根据用户的自然语言描述生成多音源搜索计划。返回计划后由客户端执行实际搜索并回传候选歌曲，你再据此排序推荐。",
                    "music.search", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.QT_UNIAPPX, AiConstants.ClientType.QT_PC),
                    List.of(),
                    AiToolTypes.objectSchema("""
                            {"intent":{"type":"string","description":"搜索意图，如 recommend_music / find_song / find_similar"},
                             "keywords":{"type":"array","items":{"type":"string"},"description":"搜索关键词，2-6 个"},
                             "sources":{"type":"array","items":{"type":"string"},"description":"音源 ID，留空表示使用全部可用音源"},
                             "limit":{"type":"integer","minimum":1,"maximum":100,"description":"期望候选数量"},
                             "filters":{"type":"object","description":"过滤条件，如 language / mood / era / popularity"}}
                            """),
                    null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            return planResult(context, "recommend_music", "已生成搜索计划，请客户端按计划搜索并回传候选歌曲。");
        }
    }

    /** 找歌手 */
    private class SearchArtistTool implements AiToolHandler {

        @Override
        public AiToolDef definition() {
            return new AiToolDef(
                    "search_artist", "搜索歌手",
                    "按歌手名或描述搜索歌手，返回搜索计划交由客户端执行。",
                    "music.searchArtist", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.QT_UNIAPPX, AiConstants.ClientType.QT_PC),
                    List.of(),
                    AiToolTypes.objectSchema("""
                            {"keywords":{"type":"array","items":{"type":"string"},"description":"歌手关键词"},
                             "sources":{"type":"array","items":{"type":"string"},"description":"音源 ID"}}
                            """),
                    null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            return planResult(context, "find_artist", "已生成歌手搜索计划。");
        }
    }

    /** 找专辑 */
    private class SearchAlbumTool implements AiToolHandler {

        @Override
        public AiToolDef definition() {
            return new AiToolDef(
                    "search_album", "搜索专辑",
                    "按专辑名或歌手搜索专辑，返回搜索计划交由客户端执行。",
                    "music.searchAlbum", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.QT_UNIAPPX, AiConstants.ClientType.QT_PC),
                    List.of(),
                    AiToolTypes.objectSchema("""
                            {"keywords":{"type":"array","items":{"type":"string"},"description":"专辑关键词"},
                             "sources":{"type":"array","items":{"type":"string"},"description":"音源 ID"}}
                            """),
                    null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            return planResult(context, "find_album", "已生成专辑搜索计划。");
        }
    }

    /** 找歌单 */
    private class SearchPlaylistTool implements AiToolHandler {

        @Override
        public AiToolDef definition() {
            return new AiToolDef(
                    "search_playlist", "搜索歌单",
                    "按主题或场景搜索歌单，返回搜索计划交由客户端执行。",
                    "music.searchPlaylist", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.QT_UNIAPPX, AiConstants.ClientType.QT_PC),
                    List.of(),
                    AiToolTypes.objectSchema("""
                            {"keywords":{"type":"array","items":{"type":"string"},"description":"歌单关键词"},
                             "sources":{"type":"array","items":{"type":"string"},"description":"音源 ID"}}
                            """),
                    null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            return planResult(context, "find_playlist", "已生成歌单搜索计划。");
        }
    }

    // ==================== 上下文查询类 ====================

    /** 歌曲详情：候选集里查，不访问平台 */
    private class GetSongDetailTool implements AiToolHandler {

        @Override
        public AiToolDef definition() {
            return new AiToolDef(
                    "get_song_detail", "查询歌曲详情",
                    "从客户端已回传的候选歌曲中查询某首歌的详细信息。",
                    "music.songDetail", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.QT_UNIAPPX, AiConstants.ClientType.QT_PC),
                    List.of(),
                    AiToolTypes.objectSchema("""
                            {"songId":{"type":"string","description":"歌曲 ID"},
                             "sourceId":{"type":"string","description":"音源 ID"}}
                            """),
                    null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            String songId = context.arg("songId");
            String sourceId = context.arg("sourceId");
            if (songId == null || songId.isBlank()) {
                return AiToolResult.rejected("缺少参数 songId");
            }
            for (AiAppDtos.MusicCandidate candidate : candidates(context)) {
                boolean idMatch = songId.equals(candidate.songId());
                boolean sourceMatch = sourceId == null || sourceId.isBlank()
                        || sourceId.equals(candidate.sourceId());
                if (idMatch && sourceMatch) {
                    ObjectNode payload = AiJson_mapper().createObjectNode();
                    payload.put("sourceId", candidate.sourceId());
                    payload.put("songId", candidate.songId());
                    payload.put("name", candidate.name());
                    payload.put("artist", candidate.artist());
                    payload.put("album", candidate.album());
                    payload.put("duration", candidate.duration());
                    payload.put("quality", candidate.quality());
                    return AiToolResult.ok("歌曲：" + candidate.name() + " - " + candidate.artist(), payload);
                }
            }
            return AiToolResult.failed("当前候选集中没有该歌曲，请先调用 search_music 让客户端补充候选。");
        }
    }

    /** 当前播放歌曲 */
    private class GetCurrentSongTool implements AiToolHandler {

        @Override
        public AiToolDef definition() {
            return new AiToolDef(
                    "get_current_song", "查询当前播放歌曲",
                    "读取客户端上报的当前播放歌曲信息。",
                    "music.currentSong", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.QT_UNIAPPX, AiConstants.ClientType.QT_PC),
                    List.of(), AiToolTypes.emptySchema(), null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            JsonNode node = ctx(context, AiConstants.ContextKey.CURRENT_SONG);
            if (node == null || node.isNull()) {
                return AiToolResult.ok("当前没有播放中的歌曲。", AiToolTypes.emptyPayload());
            }
            ObjectNode payload = AiJson_mapper().createObjectNode();
            payload.set("currentSong", node);
            String name = AiJson_text(node, "name", "未知歌曲");
            String artist = AiJson_text(node, "artist", "未知歌手");
            return AiToolResult.ok("当前播放：" + name + " - " + artist, payload);
        }
    }

    /** 当前播放列表 */
    private class GetCurrentPlaylistTool implements AiToolHandler {

        @Override
        public AiToolDef definition() {
            return new AiToolDef(
                    "get_current_playlist", "查询当前播放列表",
                    "读取客户端上报的当前播放队列。",
                    "music.currentPlaylist", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.QT_UNIAPPX, AiConstants.ClientType.QT_PC),
                    List.of(), AiToolTypes.emptySchema(), null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            JsonNode queue = ctx(context, AiConstants.ContextKey.CURRENT_QUEUE);
            JsonNode playlist = ctx(context, AiConstants.ContextKey.CURRENT_PLAYLIST);
            ObjectNode payload = AiJson_mapper().createObjectNode();
            int size = 0;
            if (queue != null && queue.isArray()) {
                payload.set("queue", queue);
                size = queue.size();
            }
            if (playlist != null && !playlist.isNull()) {
                payload.set("playlist", playlist);
            }
            return AiToolResult.ok("当前播放队列共 " + size + " 首。", payload);
        }
    }

    /** 用户收藏 */
    private class GetUserLikesTool implements AiToolHandler {

        @Override
        public AiToolDef definition() {
            return new AiToolDef(
                    "get_user_likes", "查询用户收藏",
                    "读取客户端上报的用户收藏摘要（仅在用户已登录且允许用户上下文时可用）。",
                    "music.userLikes", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.QT_UNIAPPX, AiConstants.ClientType.QT_PC),
                    List.of(), AiToolTypes.emptySchema(), null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            JsonNode node = ctx(context, AiConstants.ContextKey.USER_PREFERENCE_SUMMARY);
            if (node == null || node.isNull()) {
                return AiToolResult.ok("客户端未上报收藏摘要。", AiToolTypes.emptyPayload());
            }
            ObjectNode payload = AiJson_mapper().createObjectNode();
            payload.set("userPreferenceSummary", node);
            return AiToolResult.ok("已读取收藏与偏好摘要。", payload);
        }
    }

    /** 播放历史 */
    private class GetPlayHistoryTool implements AiToolHandler {

        @Override
        public AiToolDef definition() {
            return new AiToolDef(
                    "get_play_history", "查询播放历史",
                    "读取客户端上报的最近播放摘要。",
                    "music.playHistory", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.QT_UNIAPPX, AiConstants.ClientType.QT_PC),
                    List.of(), AiToolTypes.emptySchema(), null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            JsonNode node = ctx(context, AiConstants.ContextKey.RECENTLY_PLAYED_SUMMARY);
            if (node == null || node.isNull()) {
                return AiToolResult.ok("客户端未上报播放历史。", AiToolTypes.emptyPayload());
            }
            ObjectNode payload = AiJson_mapper().createObjectNode();
            payload.set("recentlyPlayedSummary", node);
            return AiToolResult.ok("已读取最近播放摘要。", payload);
        }
    }

    // ==================== 共用逻辑 ====================

    /**
     * 构造搜索结果：优先把客户端已回传的候选集交给模型，否则返回 NEED_CLIENT 计划。
     *
     * <p>音源必须来自客户端上报的注册表：模型给出的音源若不在注册表内会被剔除，
     * 保证不会向客户端下发「不存在的音源」。</p>
     *
     * <p>关于两条分支的由来（设计文档 §6.1「候选歌曲标准化、去重并回传 → 模型排序」）：
     * 真实搜索只能在客户端做，客户端因此在<b>发请求之前</b>就用同一句自然语言做了本地多音源搜索，
     * 并把结果放在上下文的 {@code musicCandidates} 里一起上报。所以这里如果能取到候选，
     * 就直接把候选整理成模型可读的素材返回，让模型在这一轮就能排序、写推荐理由；
     * 取不到候选（例如用户没装音源包）才退回 NEED_CLIENT，把搜索计划交还客户端下一轮补数据。</p>
     */
    private AiToolResult planResult(AiToolContext context, String defaultIntent, String summary) {
        List<String> requested = context.argList("sources");
        List<String> available = availableSources(context);
        List<String> sources = new ArrayList<>();
        for (String source : requested) {
            if (available.isEmpty() || available.contains(source)) {
                sources.add(source);
            }
        }
        if (sources.isEmpty()) {
            sources.addAll(available);
        }
        int limit = context.argInt("limit", AiAppDtos.DEFAULT_LIMIT, AiAppDtos.MIN_LIMIT, AiAppDtos.MAX_LIMIT);
        List<String> keywords = context.argList("keywords");
        JsonNode filters = context.arguments() == null ? null : context.arguments().get("filters");

        AiAppDtos.MusicSearchPlan plan = new AiAppDtos.MusicSearchPlan(
                context.arg("intent") == null ? defaultIntent : context.arg("intent"),
                keywords, sources, limit,
                filters == null || !filters.isObject() ? Map.of()
                        : AiJson_mapper().convertValue(filters, Map.class));

        ObjectNode payload = AiJson_mapper().createObjectNode();
        payload.put("intent", plan.intent());
        ArrayNode keywordArray = payload.putArray("keywords");
        keywords.forEach(keywordArray::add);
        ArrayNode sourceArray = payload.putArray("sources");
        sources.forEach(sourceArray::add);
        payload.put("limit", plan.limit());
        payload.set("filters", AiJson_mapper().valueToTree(plan.filters()));

        List<AiAppDtos.MusicCandidate> candidates = candidates(context);
        if (candidates.isEmpty()) {
            return AiToolResult.needClient(summary, payload);
        }
        ArrayNode candidateArray = payload.putArray("candidates");
        StringBuilder readable = new StringBuilder("客户端已回传 ").append(candidates.size())
                .append(" 首候选歌曲（来源：客户端音源包）。")
                .append("候选已经足够，请**立即**从中挑选并按输出格式作答，不要再调用任何搜索工具；")
                .append("重复搜索只会拿到同一批候选。请只从下列候选中挑选，不要编造不存在的歌曲：");
        for (int i = 0; i < candidates.size(); i++) {
            AiAppDtos.MusicCandidate candidate = candidates.get(i);
            ObjectNode item = candidateArray.addObject();
            item.put("sourceId", candidate.sourceId());
            item.put("songId", candidate.songId());
            item.put("name", candidate.name());
            item.put("artist", candidate.artist());
            item.put("album", candidate.album());
            if (candidate.duration() != null) {
                item.put("duration", candidate.duration());
            }
            item.put("coverUrl", candidate.coverUrl());
            item.put("quality", candidate.quality());
            if (candidate.extra() != null && !candidate.extra().isEmpty()) {
                item.set("extra", AiJson_mapper().valueToTree(candidate.extra()));
            }
            readable.append('\n').append(i + 1).append(". ")
                    .append(nullToEmpty(candidate.name())).append(" - ")
                    .append(nullToEmpty(candidate.artist()));
            if (candidate.album() != null && !candidate.album().isBlank()) {
                readable.append("（《").append(candidate.album()).append("》）");
            }
            readable.append(" [sourceId=").append(nullToEmpty(candidate.sourceId()))
                    .append(", songId=").append(nullToEmpty(candidate.songId())).append(']');
        }
        return AiToolResult.ok(readable.toString(), payload);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 客户端上报的可用音源 ID 列表（兼容字符串数组与对象数组两种形态） */
    private List<String> availableSources(AiToolContext context) {
        JsonNode node = ctx(context, CTX_SOURCES);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (item.isString()) {
                ids.add(item.asString());
            } else if (item.isObject()) {
                String id = AiJson_text(item, "id", null);
                if (id == null) {
                    id = AiJson_text(item, "sourceId", null);
                }
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /** 客户端回传的候选歌曲 */
    private List<AiAppDtos.MusicCandidate> candidates(AiToolContext context) {
        JsonNode node = ctx(context, CTX_CANDIDATES);
        List<AiAppDtos.MusicCandidate> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        int count = 0;
        for (JsonNode item : node) {
            if (count++ >= MAX_CANDIDATES_TO_MODEL) {
                break;
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            JsonNode extraNode = item.get("extra");
            if (extraNode != null && extraNode.isObject()) {
                extra = AiJson_mapper().convertValue(extraNode, Map.class);
            }
            result.add(new AiAppDtos.MusicCandidate(
                    AiJson_text(item, "sourceId", null),
                    AiJson_text(item, "songId", null),
                    AiJson_text(item, "name", null),
                    AiJson_text(item, "artist", null),
                    AiJson_text(item, "album", null),
                    item.has("duration") && item.get("duration").isNumber()
                            ? item.get("duration").asInt() : null,
                    AiJson_text(item, "coverUrl", null),
                    AiJson_text(item, "quality", null),
                    extra));
        }
        return result;
    }

    /** 安全读取上下文字段 */
    private static JsonNode ctx(AiToolContext context, String key) {
        if (context == null || context.context() == null) {
            return null;
        }
        return context.context().get(key);
    }

    private static tools.jackson.databind.ObjectMapper AiJson_mapper() {
        return com.astral.ai.common.AiJson.mapper();
    }

    private static String AiJson_text(JsonNode node, String field, String def) {
        return com.astral.ai.common.AiJson.text(node, field, def);
    }
}
