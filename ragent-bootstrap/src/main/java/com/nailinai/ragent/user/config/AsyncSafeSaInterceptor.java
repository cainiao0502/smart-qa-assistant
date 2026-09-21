package com.nailinai.ragent.user.config;

import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * 异步安全的登录校验拦截器。
 *
 * <p>SSE（如 /api/chat/stream）等异步请求在 Tomcat asyncDispatch 阶段会再次进入
 * DispatcherServlet 并重新执行拦截器 preHandle。此时 sa-token 的 ThreadLocal
 * 请求上下文尚未在异步线程中绑定，直接执行登录校验会抛出
 * {@code SaTokenContextException: SaTokenContext 上下文尚未初始化}。
 *
 * <p>修复策略：仅对普通 REQUEST dispatch 执行登录校验；ASYNC / ERROR 等
 * dispatch 直接放行。因为登录校验已经在首次 REQUEST 阶段完成，异步阶段跳过是安全的。
 */
public class AsyncSafeSaInterceptor implements AsyncHandlerInterceptor {

    /**
     * 平台级高危能力（MCP / Skill 管理 / 检索调试）：配置里含可执行命令，start 语义 = 启动外部进程，
     * 普通用户可操作等于开放 RCE 面。仅管理员可用，普通用户直连接口返回 403。
     */
    static final String[] ADMIN_ONLY_PATTERNS = {
            "/api/mcp/**",
            "/api/skills/**",
            "/api/retrieval/debug/**"
    };

    /**
     * 管理员墙的例外：技能<b>目录</b>（{@code GET /api/skills/available}）是对话页的能力发现入口，
     * 只返回元数据（名称 / 标题 / 描述 / 是否可执行），既不含 SKILL.md 正文，也没有任何写操作，
     * 与模型每轮已经拿到的技能目录属同一信息面，故对普通登录用户开放。
     * 技能的管理动作（增删改 / import / execute / reload、以及带正文的详情接口）仍留在墙内。
     */
    static final String[] ADMIN_ONLY_EXCEPTIONS = {
            "/api/skills/available"
    };

    /**
     * 该路径是否必须 admin 角色。
     *
     * <p>抽成静态方法是为了让单元测试能<b>直接断言权限边界本身</b>：规则一旦被改动
     * （漏加例外、例外路径写错），测试立刻失败，而不是靠人工对照注释。拦截器与测试共用
     * 同一组常量，避免「测试覆盖的规则」和「实际生效的规则」漂移。</p>
     */
    public static boolean isAdminOnlyPath(String path) {
        return SaRouter.isMatch(ADMIN_ONLY_PATTERNS, path) && !SaRouter.isMatch(ADMIN_ONLY_EXCEPTIONS, path);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        SaRouter.match("/api/**")
                .notMatch(
                        "/api/auth/login",
                        "/api/auth/register"
                )
                .check(r -> StpUtil.checkLogin());

        SaRouter.match(ADMIN_ONLY_PATTERNS)
                .notMatch(ADMIN_ONLY_EXCEPTIONS)
                .check(r -> StpUtil.checkRole("admin"));
        return true;
    }
}
