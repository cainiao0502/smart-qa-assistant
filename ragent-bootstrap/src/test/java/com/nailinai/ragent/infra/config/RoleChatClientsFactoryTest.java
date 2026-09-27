package com.nailinai.ragent.infra.config;

import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.router.RoleChatClients;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色路由装配路径的单元测试。
 *
 * <p>四个业务接入点的单测走 fallback 路径（构造器注入 chatClient），生产走
 * {@code InfraAiAutoConfiguration#roleChatClients} 装配——本测试不启 Spring 上下文，
 * 直接以配置对象调用该工厂方法，锁死「properties → 工厂 → RoleChatClients」这段
 * 生产装配逻辑：候选有效性过滤、无效候选回落、空角色回落。</p>
 */
class RoleChatClientsFactoryTest {

    private final InfraAiAutoConfiguration factory = new InfraAiAutoConfiguration();

    @Test
    @DisplayName("配置了 utility 角色：装配出专属客户端；缺 api-key 的候选被过滤")
    void configuredRole_shouldAssembleDedicatedClient() {
        AiProperties properties = new AiProperties();
        AiProperties.Candidate valid = candidate("utility-provider", "sk-key");
        AiProperties.Candidate missingKey = candidate("broken-provider", "");
        properties.getChat().getRoles().put("utility", List.of(valid, missingKey));

        ChatClient primary = stubPrimary();
        RoleChatClients router = factory.roleChatClients(properties, objectMapper(), primary);

        assertThat(router.forRole("utility")).isNotSameAs(primary);
        assertThat(router.forRole("judge")).isSameAs(primary);
    }

    @Test
    @DisplayName("角色候选全部无效（缺 key）：回落主模型而非装配空路由")
    void allCandidatesInvalid_shouldFallBackToPrimary() {
        AiProperties properties = new AiProperties();
        properties.getChat().getRoles().put("utility",
                List.of(candidate("broken-provider", "")));

        ChatClient primary = stubPrimary();
        RoleChatClients router = factory.roleChatClients(properties, objectMapper(), primary);

        assertThat(router.forRole("utility")).isSameAs(primary);
    }

    @Test
    @DisplayName("未配置任何角色：所有角色回落主模型（与旧行为一致）")
    void noRoles_shouldAlwaysFallBack() {
        AiProperties properties = new AiProperties();

        ChatClient primary = stubPrimary();
        RoleChatClients router = factory.roleChatClients(properties, objectMapper(), primary);

        assertThat(router.forRole("utility")).isSameAs(primary);
        assertThat(router.forRole("judge")).isSameAs(primary);
    }

    @Test
    @DisplayName("多个角色同时配置：各自装配各自的客户端")
    void multipleRoles_shouldAssembleSeparately() {
        AiProperties properties = new AiProperties();
        properties.getChat().getRoles().put("utility", List.of(candidate("u", "sk-1")));
        properties.getChat().getRoles().put("judge", List.of(candidate("j", "sk-2")));

        ChatClient primary = stubPrimary();
        RoleChatClients router = factory.roleChatClients(properties, objectMapper(), primary);

        ChatClient utility = router.forRole("utility");
        ChatClient judge = router.forRole("judge");
        assertThat(utility).isNotSameAs(primary);
        assertThat(judge).isNotSameAs(primary);
        assertThat(utility).isNotSameAs(judge);
    }

    private AiProperties.Candidate candidate(String provider, String apiKey) {
        AiProperties.Candidate candidate = new AiProperties.Candidate();
        candidate.setProvider(provider);
        candidate.setBaseUrl("https://example.invalid/v1");
        candidate.setApiKey(apiKey);
        candidate.setModel("test-model");
        return candidate;
    }

    private ChatClient stubPrimary() {
        ChatClient client = org.mockito.Mockito.mock(ChatClient.class);
        org.mockito.Mockito.when(client.name()).thenReturn("primary");
        return client;
    }

    private com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
