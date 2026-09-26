package com.nailinai.ragent.infra.router;

import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.chat.LlmRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RoleChatClients 按角色分发路由的单元测试。
 *
 * <p>覆盖：已配置角色返回专属客户端、未配置角色回落主模型、primaryOnly 工厂。</p>
 */
class RoleChatClientsTest {

    @Test
    @DisplayName("已配置的角色返回专属客户端，未配置的角色回落主模型")
    void configuredRole_shouldUseDedicatedClient_othersFallBack() {
        ChatClient primary = stubClient("primary");
        ChatClient utility = stubClient("utility");
        Map<String, ChatClient> roleClients = new LinkedHashMap<>();
        roleClients.put(RoleChatClients.ROLE_UTILITY, utility);

        RoleChatClients router = new RoleChatClients(primary, roleClients);

        assertThat(router.forRole(RoleChatClients.ROLE_UTILITY)).isSameAs(utility);
        assertThat(router.forRole(RoleChatClients.ROLE_JUDGE)).isSameAs(primary);
        assertThat(router.forRole("unknown-role")).isSameAs(primary);
        assertThat(router.forRole(null)).isSameAs(primary);
    }

    @Test
    @DisplayName("primaryOnly 工厂：所有角色都回落主模型")
    void primaryOnly_shouldAlwaysFallBack() {
        ChatClient primary = stubClient("primary");
        RoleChatClients router = RoleChatClients.primaryOnly(primary);

        assertThat(router.forRole(RoleChatClients.ROLE_UTILITY)).isSameAs(primary);
        assertThat(router.forRole(RoleChatClients.ROLE_JUDGE)).isSameAs(primary);
    }

    private ChatClient stubClient(String name) {
        ChatClient client = org.mockito.Mockito.mock(ChatClient.class);
        org.mockito.Mockito.when(client.name()).thenReturn(name);
        org.mockito.Mockito.when(client.chat(org.mockito.ArgumentMatchers.any(LlmRequest.class)))
                .thenReturn(null);
        return client;
    }
}
