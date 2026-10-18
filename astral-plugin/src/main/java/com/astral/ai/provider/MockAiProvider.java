package com.astral.ai.provider;

import com.astral.ai.provider.AiProviderDtos.ChatRequest;
import com.astral.ai.provider.AiProviderDtos.ChatResult;
import com.astral.ai.provider.AiProviderDtos.HealthResult;
import com.astral.ai.provider.AiProviderDtos.Message;
import com.astral.ai.provider.AiProviderDtos.StreamListener;
import com.astral.ai.provider.AiProviderDtos.ToolSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地模拟提供方（{@code provider_type = MOCK}）。
 *
 * <p>用途有两个，都不涉及外部网络：</p>
 * <ul>
 *   <li><b>开箱验证</b>：种子数据会创建一套 MOCK 提供方 + 模型 + 路由，让三个智能体在没有任何
 *       真实 API Key 的环境里也能跑通「发消息 → 流式事件 → 结构化回答」的完整链路；</li>
 *   <li><b>自动化测试</b>：单测与联调不想依赖外网与配额，用 MOCK 得到确定性的输出。</li>
 * </ul>
 *
 * <p>它不做任何「智能」的事：仅根据是否声明了工具、以及最后一条用户消息，返回固定结构的内容。
 * 不允许把它用于生产业务判断——后台随时可以把智能体版本切到真实提供方。</p>
 */
@Slf4j
@Component
public class MockAiProvider implements AiProvider {

    /** 提供方类型编码，需与字典 {@code ai_provider_type} 一致 */
    public static final String TYPE = "MOCK";

    /** 流式输出切分长度（模拟逐字下发） */
    private static final int CHUNK = 18;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        String content = compose(request);
        int promptTokens = estimateTokens(request);
        int completionTokens = Math.max(1, content.length() / 3);
        log.debug("[Mock] chat traceId={} model={} tokens={}/{}",
                request.traceId(), request.modelCode(), promptTokens, completionTokens);
        return new ChatResult(content, "stop", promptTokens, completionTokens, List.of());
    }

    @Override
    public void stream(ChatRequest request, StreamListener listener) {
        String content = compose(request);
        int promptTokens = estimateTokens(request);
        try {
            for (int i = 0; i < content.length(); i += CHUNK) {
                int end = Math.min(content.length(), i + CHUNK);
                listener.onDelta(content.substring(i, end));
            }
            listener.onCompleted(new ChatResult(content, "stop", promptTokens,
                    Math.max(1, content.length() / 3), List.of()));
        } catch (RuntimeException e) {
            listener.onError(e);
        }
    }

    @Override
    public HealthResult testConnection(ChatRequest request) {
        return HealthResult.ok(1L);
    }

    /**
     * 生成回答正文。
     *
     * <p>带音乐工具时返回 {@code answer + blocks + actions} 的结构化 JSON；
     * 带知识/管理工具时返回带引用占位与纯文本答案的 JSON；都没有时返回普通文本。</p>
     */
    private String compose(ChatRequest request) {
        String userText = lastUserText(request);
        boolean music = hasTool(request, "search_music");
        boolean qa = hasTool(request, "search_project_document");
        if (music) {
            return musicAnswer(userText);
        }
        if (qa) {
            return qaAnswer(userText);
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            return adminAnswer(userText);
        }
        return "（MOCK 提供方）已收到：" + shorten(userText, 200);
    }

    private String musicAnswer(String userText) {
        String keyword = shorten(userText, 40);
        return "{"
                + "\"answer\":\"我先把「" + escape(keyword) + "」拆成搜索关键词，"
                + "候选歌曲由客户端多音源搜索后回传，再据此排序推荐。"
                + "我不会生成播放地址，点击卡片后由客户端自行取流播放。\","
                + "\"blocks\":[{\"type\":\"song_list\",\"title\":\"为你准备的候选\",\"items\":[]}],"
                + "\"actions\":[{\"type\":\"SEARCH_MUSIC\",\"keyword\":\"" + escape(keyword) + "\"}]"
                + "}";
    }

    private String qaAnswer(String userText) {
        return "{"
                + "\"answer\":\"（MOCK）这是基于项目文档的示例回答：关于「" + escape(shorten(userText, 60))
                + "」，请以引用片段为准。MOCK 提供方不产生真实推理，接入真实模型后此处会给出完整说明。\","
                + "\"blocks\":[],"
                + "\"actions\":[]"
                + "}";
    }

    private String adminAnswer(String userText) {
        return "{"
                + "\"answer\":\"（MOCK）已解析你的管理请求：「" + escape(shorten(userText, 60))
                + "」。写操作会先给出计划与确认卡片，确认后由服务端重新校验权限并执行。\","
                + "\"blocks\":[],"
                + "\"actions\":[]"
                + "}";
    }

    private boolean hasTool(ChatRequest request, String toolCode) {
        if (request.tools() == null) {
            return false;
        }
        for (ToolSpec spec : request.tools()) {
            if (spec != null && toolCode.equals(spec.name())) {
                return true;
            }
        }
        return false;
    }

    private String lastUserText(ChatRequest request) {
        List<Message> messages = request.messages() == null ? new ArrayList<>() : request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message != null && "user".equals(message.role()) && message.content() != null) {
                return message.content();
            }
        }
        return "";
    }

    private int estimateTokens(ChatRequest request) {
        if (request.messages() == null) {
            return 0;
        }
        int total = 0;
        for (Message message : request.messages()) {
            if (message != null && message.content() != null) {
                total += Math.max(1, message.content().length() / 3);
            }
        }
        return total;
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim().replaceAll("\\s+", " ");
        return trimmed.length() > max ? trimmed.substring(0, max) + "…" : trimmed;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /** 供后台/诊断使用：说明 MOCK 不会外发请求 */
    public static String describe() {
        return "本地模拟提供方，不发起任何外部请求，仅用于验证链路与自动化测试".toUpperCase(Locale.ROOT);
    }
}
