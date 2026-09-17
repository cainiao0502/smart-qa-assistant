package com.nailinai.ragent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class WebConfig {

    /**
     * 前端来源白名单，逗号分隔，支持 * 通配（例如 http://localhost:*）。
     *
     * <p>含通配符的条目会走 {@code allowedOriginPatterns}：Spring 的 {@code allowedOrigins}
     * 不允许 "*" 与 {@code allowCredentials(true)} 同时使用，而本地开发端口容易漂移
     * （5173 被其他项目占用时 vite 会自动改用 5174/5175），写死端口会导致浏览器请求
     * 被 CORS 拦成 403 "Invalid CORS request"。</p>
     *
     * <p>部署到服务器后，请通过 {@code APP_CORS_ALLOWED_ORIGINS} 换成真实域名。</p>
     */
    @Value("${app.cors.allowed-origins:http://localhost:*,http://127.0.0.1:*}")
    private String[] allowedOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer(RateLimitInterceptor rateLimitInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                CorsRegistration registration = registry.addMapping("/api/**")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowCredentials(true);

                boolean hasPattern = Arrays.stream(allowedOrigins).anyMatch(origin -> origin.contains("*"));
                if (hasPattern) {
                    registration.allowedOriginPatterns(allowedOrigins);
                } else {
                    registration.allowedOrigins(allowedOrigins);
                }
            }

            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(rateLimitInterceptor)
                        .addPathPatterns("/api/chat", "/api/chat/stream");
            }
        };
    }
}
