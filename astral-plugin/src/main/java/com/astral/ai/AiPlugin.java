package com.astral.ai;

import com.astral.ai.common.AiConstants;
import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PermissionProvider;
import com.astral.plugin.api.PermissionProvider.PermissionDef;
import com.astral.plugin.api.PluginFrontendExtension;
import com.astral.plugin.api.PluginFrontendExtension.NavItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Astral AI 插件入口。
 *
 * <p>本插件提供三类能力：</p>
 * <ul>
 *   <li>{@code music_assistant} —— 轻听 App（qt-uniappx）与轻听 PC（qt-pc）共用的音乐助手；</li>
 *   <li>{@code admin_assistant} —— 管理后台（astral-front）的运维管理助手；</li>
 *   <li>{@code admin_qa} —— 管理后台独立的项目答疑助手（只读文档 + 只读实时数据）。</li>
 * </ul>
 *
 * <p>三个编码只是<b>初始配置数据</b>，不是代码分支：智能体的模型、Prompt、工具、知识源、
 * 额度、入口、可用客户端全部由后台配置并落库，运行期只认「已发布版本」。</p>
 */
@Slf4j
@Component
public class AiPlugin implements AstralPlugin, PluginFrontendExtension, PermissionProvider {

    /** 插件 ID */
    public static final String PLUGIN_ID = "ai";

    /** 客户端权限：音乐助手使用 */
    public static final String PERM_MUSIC_USE = "user:ai:music:use";

    /** 客户端权限：会话查看 */
    public static final String PERM_CONVERSATION_VIEW = "user:ai:conversation:view";

    /** 客户端权限：会话编辑（改名/删除） */
    public static final String PERM_CONVERSATION_EDIT = "user:ai:conversation:edit";

    /** 客户端权限：收藏动作 */
    public static final String PERM_MUSIC_FAVORITE = "user:ai:music:favorite";

    /** 客户端权限：歌单动作 */
    public static final String PERM_MUSIC_PLAYLIST = "user:ai:music:playlist";

    /** 管理端权限：配置总览 */
    public static final String PERM_CONFIG_VIEW = "admin:ai:config:view";

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public String getPluginName() {
        return "AI 助手";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "音乐助手（App/PC）、管理助手与项目答疑助手；智能体、模型、Prompt、工具、知识源与额度均可后台配置";
    }

    /**
     * 只声明精确前缀。
     * <p>{@code PluginApiInterceptor} 按前缀连坐拦截，写成 {@code /api/v1/app} 会把轻听音乐接口一起带走，
     * 因此必须精确到 {@code /ai}。</p>
     */
    @Override
    public List<String> getApiPrefixes() {
        return List.of("/api/v1/app/ai", "/api/v1/admin/ai", "/api/v1/all/ai");
    }

    @Override
    public boolean isRequired() {
        return false;
    }

    @Override
    public void onEnable() {
        log.info("[AiPlugin] AI 助手插件已启用");
    }

    @Override
    public void onDisable() {
        log.info("[AiPlugin] AI 助手插件已停用");
    }

    /**
     * 插件业务页统一挂在插件管理目录下。
     *
     * <p>三个入口对应三种完全不同的权限与用途，所以做成三个并列导航项而不是页内 Tabs：
     * 配置台是管理员维护通道与版本的地方（{@code admin:ai:config:view}），
     * 管理助手与答疑是「使用」——它们有各自独立的授权，只被授予 {@code admin:ai:qa:use}
     * 的人不该被迫先拿到配置权限才能进答疑页。</p>
     */
    @Override
    public List<NavItem> getNavItems() {
        return List.of(
                NavItem.pluginPage("AI 助手", "/dashboard/plugin/ai", "RobotOutlined", PERM_CONFIG_VIEW, 230),
                NavItem.pluginPage("管理助手", "/dashboard/plugin/ai/assistant", "MessageOutlined", Perms.ASSISTANT_USE, 231),
                NavItem.pluginPage("答疑", "/dashboard/plugin/ai/qa", "ReadOutlined", Perms.QA_USE, 232)
        );
    }

    @Override
    public List<PermissionDef> getPermissions() {
        String domain = "ai";
        return List.of(
                new PermissionDef(PERM_MUSIC_USE, "音乐助手使用", domain, TYPE_API, "在轻听 App / PC 使用 AI 音乐助手"),
                new PermissionDef(PERM_CONVERSATION_VIEW, "AI 会话查看", domain, TYPE_API, "查看自己的 AI 会话与消息"),
                new PermissionDef(PERM_CONVERSATION_EDIT, "AI 会话编辑", domain, TYPE_API, "重命名、置顶、删除自己的 AI 会话"),
                new PermissionDef(PERM_MUSIC_FAVORITE, "音乐收藏动作", domain, TYPE_DATA, "允许助手触发收藏动作"),
                new PermissionDef(PERM_MUSIC_PLAYLIST, "音乐歌单动作", domain, TYPE_DATA, "允许助手触发歌单相关动作"),

                new PermissionDef("admin:ai:config:view", "AI 配置总览", domain, TYPE_API, "查看 AI 模块配置总览"),
                new PermissionDef("admin:ai:provider:view", "AI 提供方查看", domain, TYPE_API, "查看 AI 提供方配置"),
                new PermissionDef("admin:ai:provider:edit", "AI 提供方编辑", domain, TYPE_API, "新增、修改、删除 AI 提供方"),
                new PermissionDef("admin:ai:model:view", "AI 模型查看", domain, TYPE_API, "查看 AI 模型配置"),
                new PermissionDef("admin:ai:model:edit", "AI 模型编辑", domain, TYPE_API, "新增、修改、删除 AI 模型"),
                new PermissionDef("admin:ai:route:view", "AI 路由查看", domain, TYPE_API, "查看模型路由配置"),
                new PermissionDef("admin:ai:route:edit", "AI 路由编辑", domain, TYPE_API, "新增、修改模型路由"),
                new PermissionDef("admin:ai:agent:view", "AI 智能体查看", domain, TYPE_API, "查看智能体与版本"),
                new PermissionDef("admin:ai:agent:edit", "AI 智能体编辑", domain, TYPE_API, "新增、修改智能体草稿版本"),
                new PermissionDef("admin:ai:agent:publish", "AI 智能体发布", domain, TYPE_API, "发布、回滚、停用智能体版本"),
                new PermissionDef("admin:ai:prompt:view", "AI 提示词查看", domain, TYPE_API, "查看提示词模板与版本"),
                new PermissionDef("admin:ai:prompt:edit", "AI 提示词编辑", domain, TYPE_API, "新增、修改、发布提示词"),
                new PermissionDef("admin:ai:knowledge:view", "AI 知识库查看", domain, TYPE_API, "查看知识源与文档"),
                new PermissionDef("admin:ai:knowledge:edit", "AI 知识库编辑", domain, TYPE_API, "新增、修改知识源与文档"),
                new PermissionDef("admin:ai:knowledge:sync", "AI 知识库同步", domain, TYPE_API, "触发知识库索引同步"),
                new PermissionDef("admin:ai:tool:view", "AI 工具查看", domain, TYPE_API, "查看已登记工具"),
                new PermissionDef("admin:ai:tool:edit", "AI 工具编辑", domain, TYPE_API, "修改工具风险等级与确认策略"),
                new PermissionDef("admin:ai:tool:approve", "AI 工具审批", domain, TYPE_API, "审批高风险工具调用"),
                new PermissionDef("admin:ai:quota:view", "AI 额度查看", domain, TYPE_API, "查看额度策略与用量"),
                new PermissionDef("admin:ai:quota:edit", "AI 额度编辑", domain, TYPE_API, "新增、修改额度策略"),
                new PermissionDef("admin:ai:usage:view", "AI 用量查看", domain, TYPE_API, "查看模型用量与费用"),
                new PermissionDef("admin:ai:request:view", "AI 请求查看", domain, TYPE_API, "查看请求列表与状态"),
                new PermissionDef("admin:ai:request:cancel", "AI 请求取消", domain, TYPE_API, "取消正在运行的 AI 请求"),
                new PermissionDef("admin:ai:conversation:view", "AI 会话审计", domain, TYPE_API, "查看会话元数据"),
                new PermissionDef("admin:ai:conversation:content:view", "AI 会话内容查看", domain, TYPE_DATA, "查看会话完整内容（敏感）"),
                new PermissionDef("admin:ai:audit:view", "AI 审计查看", domain, TYPE_API, "查看 AI 模块审计记录"),
                new PermissionDef("admin:ai:assistant:use", "管理助手使用", domain, TYPE_API, "在管理后台使用管理助手"),
                new PermissionDef("admin:ai:qa:use", "答疑助手使用", domain, TYPE_API, "在管理后台使用项目答疑助手"),
                new PermissionDef("admin:ai:qa:export", "答疑导出", domain, TYPE_API, "导出答疑引用与答案")
        );
    }

    /** 供内部代码引用的默认权限编码（避免散落魔法字符串） */
    public static final class Perms {
        public static final String ASSISTANT_USE = "admin:ai:assistant:use";
        public static final String QA_USE = "admin:ai:qa:use";

        private Perms() {
        }
    }

    /** 种子智能体编码（初始化数据用，不参与运行期分支判断） */
    public static final class SeedAgents {
        public static final String MUSIC = "music_assistant";
        public static final String ADMIN = "admin_assistant";
        public static final String QA = "admin_qa";

        private SeedAgents() {
        }
    }

    /** 该插件支持的语言无关的常量复用 */
    public static List<String> supportedClientTypes() {
        return AiConstants.ClientType.all();
    }
}
