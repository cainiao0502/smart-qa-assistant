package com.nailinai.ragent.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
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
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public RateLimitInterceptor(
            @Value("${app.ratelimit.chat.max:10}") int maxRequests,
            @Value("${app.ratelimit.chat.window-seconds:60}") int windowSeconds) {
        this.maxRequests = maxRequests;
        this.windowMs = windowSeconds * 1000L;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String key = resolveKey(request);
        long now = System.currentTimeMillis();
        WindowCounter counter = counters.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart > windowMs) {
                return new WindowCounter(now, new AtomicInteger(0));
            }
            return existing;
        });

        int count = counter.count.incrementAndGet();
        if (count > maxRequests) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            try {
                response.getWriter().write("{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\"}");
                response.getWriter().flush();
            } catch (Exception ignored) {
            }
            log.warn("Rate limit exceeded for key={}, count={}/{}", key, count, maxRequests);
            return false;
        }

        return true;
    }

    /**
     * Periodically evict stale entries to prevent memory leak.
     */
    @Scheduled(fixedRateString = "${app.ratelimit.cleanup-interval-ms:300000}")
    public void evictStale() {
        long cutoff = System.currentTimeMillis() - windowMs * 2;
        counters.entrySet().removeIf(e -> e.getValue().windowStart() < cutoff);
    }

    private String resolveKey(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId != null) {
            return "user:" + userId;
        }
        String ip = request.getRemoteAddr();
        return "ip:" + ip;
    }

    private record WindowCounter(long windowStart, AtomicInteger count) {
    }
}
