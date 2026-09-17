package com.nailinai.ragent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class ExecutorConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService sseHeartbeatExecutor() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }
}
