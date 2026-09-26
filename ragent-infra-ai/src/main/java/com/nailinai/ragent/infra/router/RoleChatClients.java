package com.nailinai.ragent.infra.router;

import com.nailinai.ragent.infra.chat.ChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按角色分发的聊天客户端路由。
 *
 * <p>主链路（Planner 决策、最终回答）需要强模型的 function calling 能力，
 * 而查询改写、上下文压缩摘要、意图分类、评估 judge 是典型的小模型任务——
 * 全部走旗舰模型既贵又慢。本类为每个角色维护独立的多候选路由（含熔断），
 * 角色未配置或其候选全部无效时回落到主模型（{@code primary}），
 * 因此只配置主 {@code ai.chat.candidates} 时系统行为与从前完全一致。</p>
 *
 * <p>配置形如：</p>
 * <pre>
 * ai:
 *   chat:
 *     candidates: [ ...主模型... ]
 *     roles:
 *       utility:
 *         - provider: siliconflow
 *           base-url: ...
 *           api-key: ...
 *           model: ...
 * </pre>
 */
public class RoleChatClients {

    public static final String ROLE_UTILITY = "utility";
    public static final String ROLE_JUDGE = "judge";

    private static final Logger log = LoggerFactory.getLogger(RoleChatClients.class);

    private final ChatClient primary;
    private final Map<String, ChatClient> roleClients;

    public RoleChatClients(ChatClient primary, Map<String, ChatClient> roleClients) {
        this.primary = primary;
        this.roleClients = roleClients == null ? Map.of() : Map.copyOf(roleClients);
    }

    /** 供测试与未配置角色场景使用：所有角色都回落主模型 */
    public static RoleChatClients primaryOnly(ChatClient primary) {
        return new RoleChatClients(primary, Map.of());
    }

    /**
     * 返回指定角色的聊天客户端；角色未配置时回落主模型，绝不返回 null。
     */
    public ChatClient forRole(String role) {
        // 不可变 Map 对 null key 的 get 会抛 NPE，先行防御
        if (role == null || role.isBlank()) {
            return primary;
        }
        ChatClient client = roleClients.get(role);
        if (client != null) {
            return client;
        }
        log.debug("No dedicated chat client for role '{}', falling back to primary", role);
        return primary;
    }
}
