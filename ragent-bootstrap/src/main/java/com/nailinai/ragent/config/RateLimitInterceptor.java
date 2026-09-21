package com.nailinai.ragent.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import com.nailinai.ragent.user.context.UserIdHolder;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final int maxRequests;
    private final long windowMs;
    private final int authMaxRequests;
    private final long authWindowMs;
    private final int uploadMaxRequests;
    private final long uploadWindowMs;
    /** 所有桶里最大的窗口，用于过期清理的保守 cutoff。 */
    private final long maxWindowMs;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public RateLimitInterceptor(
            @Value("${app.ratelimit.chat.max:10}") int maxRequests,
            @Value("${app.ratelimit.chat.window-seconds:60}") int windowSeconds,
            @Value("${app.ratelimit.auth.max:20}") int authMaxRequests,
            @Value("${app.ratelimit.auth.window-seconds:60}") int authWindowSeconds,
            @Value("${app.ratelimit.upload.max:10}") int uploadMaxRequests,
            @Value("${app.ratelimit.upload.window-seconds:60}") int uploadWindowSeconds) {
        this.maxRequests = maxRequests;
        this.windowMs = windowSeconds * 1000L;
        this.authMaxRequests = authMaxRequests;
        this.authWindowMs = authWindowSeconds * 1000L;
        this.uploadMaxRequests = uploadMaxRequests;
        this.uploadWindowMs = uploadWindowSeconds * 1000L;
        this.maxWindowMs = Math.max(windowMs, Math.max(authWindowMs, uploadWindowMs));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String bucket = bucketOf(request.getRequestURI());
        String key = bucket + ":" + resolveKey(request);
        long windowMs = windowFor(bucket);
        long now = System.currentTimeMillis();
        WindowCounter counter = counters.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart > windowMs) {
                return new WindowCounter(now, new AtomicInteger(0));
            }
            return existing;
        });

        int count = counter.count.incrementAndGet();
        int max = maxFor(bucket);
        if (count > max) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            try {
                response.getWriter().write("{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\"}");
                response.getWriter().flush();
            } catch (Exception ignored) {
            }
            log.warn("Rate limit exceeded for bucket={}, key={}, count={}/{}", bucket, key, count, max);
            return false;
        }


        return true;
    }

    /**
     * Periodically evict stale entries to prevent memory leak.
     */
    @Scheduled(fixedRateString = "${app.ratelimit.cleanup-interval-ms:300000}")
    public void evictStale() {
        long cutoff = System.currentTimeMillis() - maxWindowMs * 2;
        counters.entrySet().removeIf(e -> e.getValue().windowStart() < cutoff);
    }

    private String resolveKey(HttpServletRequest request) {
        // 安全：限流 key 绝不能取客户端可伪造的头（历史实现优先读 X-User-Id，
        // 攻击者每请求换一个值即可完全绕过限流）。登录用户取服务端登录态，
        // 匿名请求回退到来源 IP。
        Long userId = UserIdHolder.get();
        if (userId != null) {
            return "user:" + userId;
        }
        String ip = request.getRemoteAddr();
        return "ip:" + ip;
    }

    /**
     * 按路径前缀分桶，让不同接口组使用各自独立的阈值：
     * - auth：登录/注册是爆破入口，匿名请求按 IP 限流（20/60s，兼顾登录失败重试）；
     * - upload：文档上传/索引是大文件 + 计费 API（10/60s）；
     * - chat：其余已注册路径沿用原配置。
     * key 必须带桶前缀，否则各桶会共享同一个计数器，阈值语义就错了。
     */
    private String bucketOf(String path) {
        if (path.startsWith("/api/auth/login") || path.startsWith("/api/auth/register")) {
            return "auth";
        }
        if ((path.startsWith("/api/kb/") && path.contains("/documents"))
                || path.startsWith("/api/documents/")) {
            return "upload";
        }
        return "chat";
    }

    private long windowFor(String bucket) {
        return switch (bucket) {
            case "auth" -> authWindowMs;
            case "upload" -> uploadWindowMs;
            default -> windowMs;
        };
    }

    private int maxFor(String bucket) {
        return switch (bucket) {
            case "auth" -> authMaxRequests;
            case "upload" -> uploadMaxRequests;
            default -> maxRequests;
        };
    }

    private record WindowCounter(long windowStart, AtomicInteger count) {
    }
}
