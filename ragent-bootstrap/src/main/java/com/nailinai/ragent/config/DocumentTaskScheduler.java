package com.nailinai.ragent.config;

import com.nailinai.ragent.chat.service.DocumentTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class DocumentTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(DocumentTaskScheduler.class);
    private static final long TIMEOUT_SECONDS = 30;

    private final DocumentTaskService documentTaskService;

    public DocumentTaskScheduler(DocumentTaskService documentTaskService) {
        this.documentTaskService = documentTaskService;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void retryStaleTasks() {
        try {
            int count = CompletableFuture.supplyAsync(() -> documentTaskService.retryStaleTasks())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (count > 0) {
                log.info("Document task scheduler triggered {} retries", count);
            }
        } catch (TimeoutException ex) {
            log.error("Document task scheduler timed out after {}s", TIMEOUT_SECONDS, ex);
        } catch (Exception ex) {
            log.error("Document task scheduler failed", ex);
        }
    }
}