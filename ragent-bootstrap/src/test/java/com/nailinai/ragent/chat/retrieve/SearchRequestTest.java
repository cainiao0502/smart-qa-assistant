package com.nailinai.ragent.chat.retrieve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 检索归属 fail-closed 契约的单元测试：ownerUserId 为 null 必须 fail-fast，
 * 而不是像旧契约那样被解释为「内部调用、跳过归属过滤」——那会把异步链路
 * 身份传播断链静默降级为跨租户读取。
 */
class SearchRequestTest {

    @Test
    @DisplayName("ownerUserId 为 null：fail-fast，拒绝构造检索请求")
    void nullOwner_shouldFailFast() {
        assertThatThrownBy(() -> SearchRequest.of(1L, "问题", null, 4, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerUserId is required");
    }

    @Test
    @DisplayName("ownerUserId 非空：正常构造")
    void nonNullOwner_shouldBuild() {
        assertThatCode(() -> SearchRequest.of(1L, "问题", null, 4, null, null, null, 1L))
                .doesNotThrowAnyException();
    }
}
