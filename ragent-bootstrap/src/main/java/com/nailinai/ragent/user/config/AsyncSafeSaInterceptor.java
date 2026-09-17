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

        // 平台级高危能力（MCP / Skill 管理）：配置里含可执行命令，start 语义 = 启动外部进程，
        // 普通用户可操作等于开放 RCE 面。仅管理员可用，普通用户直连接口返回 403。
        SaRouter.match("/api/mcp/**", "/api/skills/**", "/api/retrieval/debug/**")
                .check(r -> StpUtil.checkRole("admin"));
        return true;
    }
}
