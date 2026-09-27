package com.nailinai.ragent.infra.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;

/**
 * JDK HttpClient 的出站代理配置工具。
 *
 * <p>统一「系统属性 → 环境变量」的探测优先级（与 JVM/curl 惯例一致）：
 * <ol>
 *     <li>{@code https.proxyHost}/{@code https.proxyPort}（或 http 前缀）系统属性；</li>
 *     <li>{@code HTTPS_PROXY}/{@code https_proxy} 环境变量。</li>
 * </ol>
 * chat 客户端与 rerank 客户端共用：rerank 目标（Jina/Cohere）在国内需走代理，
 * chat 目标走代理无害（代理转发可达流量）。</p>
 */
public final class HttpProxySupport {

    private static final Logger log = LoggerFactory.getLogger(HttpProxySupport.class);

    private HttpProxySupport() {
    }

    /**
     * 按上述优先级探测代理并配置到 builder；未发现代理配置时不做任何改动。
     *
     * @param https 目标是否为 https（决定系统属性前缀与默认环境变量）
     */
    public static void configureProxy(HttpClient.Builder builder, boolean https) {
        String prefix = https ? "https" : "http";
        String host = System.getProperty(prefix + ".proxyHost");
        String port = System.getProperty(prefix + ".proxyPort");

        if (host == null || host.isBlank()) {
            String env = System.getenv(https ? "HTTPS_PROXY" : "HTTP_PROXY");
            if (env == null || env.isBlank()) {
                env = System.getenv(https ? "https_proxy" : "http_proxy");
            }
            if (env != null && !env.isBlank()) {
                try {
                    var uri = java.net.URI.create(env.trim());
                    if (uri.getHost() != null && uri.getPort() > 0) {
                        host = uri.getHost();
                        port = String.valueOf(uri.getPort());
                    }
                } catch (Exception ex) {
                    log.warn("Failed to parse proxy env '{}': {}", env, ex.getMessage());
                }
            }
        }

        if (host != null && !host.isBlank() && port != null) {
            try {
                int portNumber = Integer.parseInt(port.trim());
                builder.proxy(ProxySelector.of(new InetSocketAddress(host, portNumber)));
                log.info("Outbound HTTP client using proxy {}:{}", host, portNumber);
            } catch (NumberFormatException ex) {
                log.warn("Invalid proxy port '{}', proxy disabled", port);
            }
        }
    }
}
