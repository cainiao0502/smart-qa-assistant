package com.nailinai.ragent.user.config;

import cn.dev33.satoken.spring.SaTokenContextRegister;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理员墙的边界测试。
 *
 * <p>本项目的角色校验走路径规则，而不是 {@code @SaCheckRole} 注解——自定义的异步安全拦截器
 * （{@link AsyncSafeSaInterceptor}）未被注册时注解会被静默忽略，所以「哪些路径必须 admin、
 * 哪些是白名单」只能靠测试钉住：</p>
 *
 * <ul>
 *   <li>例外加漏 → 对话页技能选择器永远为空，且前端静默吞掉 403（这就是本测试要防的回归）；</li>
 *   <li>例外加宽 → MCP / 技能管理重新暴露给普通用户，等于开放外部进程执行入口。</li>
 * </ul>
 */
class AsyncSafeSaInterceptorTest {

    /**
     * 运行期 {@code SaRouter} 的路径匹配由 sa-token-spring-boot starter 的
     * {@link SaTokenContextRegister} 在装配阶段注册（内部是 Spring 的 PathPattern 匹配）。
     * 单测没有 Spring 上下文，不注册的话 {@code SaStrategy.routeMatcher} 仍是默认实现，
     * 调用即抛 {@code NotImpl 未实现具体路径匹配算法}——这里直接复用运行期的同一份初始化代码，
     * 保证断言的就是真实匹配语义。
     */
    @BeforeAll
    static void alignRouteMatcherWithRuntime() {
        new SaTokenContextRegister();
    }

    @Test
    @DisplayName("MCP / 技能管理 / 检索调试必须在管理员墙内")
    void platformCapabilityPathsRequireAdmin() {
        assertThat(AsyncSafeSaInterceptor.isAdminOnlyPath("/api/mcp/servers")).isTrue();
        assertThat(AsyncSafeSaInterceptor.isAdminOnlyPath("/api/mcp/servers/demo/start")).isTrue();
        assertThat(AsyncSafeSaInterceptor.isAdminOnlyPath("/api/skills")).isTrue();
        assertThat(AsyncSafeSaInterceptor.isAdminOnlyPath("/api/skills/codeagent")).isTrue();
        assertThat(AsyncSafeSaInterceptor.isAdminOnlyPath("/api/skills/codeagent/execute")).isTrue();
        assertThat(AsyncSafeSaInterceptor.isAdminOnlyPath("/api/skills/import")).isTrue();
        assertThat(AsyncSafeSaInterceptor.isAdminOnlyPath("/api/skills/reload")).isTrue();
        assertThat(AsyncSafeSaInterceptor.isAdminOnlyPath("/api/retrieval/debug/search")).isTrue();
    }

    @Test
    @DisplayName("技能目录是用户端能力发现入口，普通登录用户可用")
    void skillCatalogIsOpenToLoggedInUsers() {
        assertThat(AsyncSafeSaInterceptor.isAdminOnlyPath("/api/skills/available")).isFalse();
    }

    @Test
    @DisplayName("例外只覆盖技能目录这一个精确路径，不能顺带放过其他技能子路径")
    void exceptionCoversCatalogPathOnly() {
        assertThat(AsyncSafeSaInterceptor.isAdminOnlyPath("/api/skills/available/anything")).isTrue();
        assertThat(AsyncSafeSaInterceptor.isAdminOnlyPath("/api/skills/availablex")).isTrue();
    }

    @Test
    @DisplayName("用户域接口不受管理员墙影响")
    void userScopedPathsAreNotAdminOnly() {
        assertThat(AsyncSafeSaInterceptor.isAdminOnlyPath("/api/chat/stream")).isFalse();
        assertThat(AsyncSafeSaInterceptor.isAdminOnlyPath("/api/chat/sessions")).isFalse();
        assertThat(AsyncSafeSaInterceptor.isAdminOnlyPath("/api/kb")).isFalse();
        assertThat(AsyncSafeSaInterceptor.isAdminOnlyPath("/api/kb/1/documents/upload")).isFalse();
        assertThat(AsyncSafeSaInterceptor.isAdminOnlyPath("/api/documents/1/index")).isFalse();
    }
}
