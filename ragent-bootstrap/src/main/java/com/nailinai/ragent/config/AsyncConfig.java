package com.nailinai.ragent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务配置类。
 * 启用 Spring @Async 支持，并为文档入库任务配置基于 Java 21 虚拟线程的执行器。
 * 虚拟线程由 JVM 调度，不占用 OS 线程，适合 I/O 密集型的 embedding 调用场景。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 文档入库任务专用的异步执行器。
     * 线程名前缀 "doc-task-"，便于在日志中识别文档处理线程。
     * 启用虚拟线程后无需配置线程池大小，JVM 自动调度。
     */
    @Bean("documentTaskExecutor")
    public Executor documentTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("doc-task-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
