package com.nailinai.ragent.chat.service.impl;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话级执行串行化守卫。
 *
 * <p>同一会话的并发消息会互相污染上下文：历史读取不一致、消息落库顺序错乱、
 * Agent 运行记录互相覆盖。因此需要保证同一 {@code sessionId} 的消息严格串行执行。
 *
 * <p>单机部署下使用 JVM 内的 {@link ReentrantLock}，非阻塞获取；
 * 若未来升级为多实例部署，应替换为基于 Redis 的分布式锁（如 Redisson）。
 */
@Component
public class SessionExecutionGuard {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 尝试获取指定会话的执行锁（非阻塞）。
     *
     * @return true 表示获取成功（该会话当前无其他消息在处理）；false 表示已被占用
     */
    public boolean tryAcquire(String sessionId) {
        ReentrantLock lock = locks.computeIfAbsent(sessionId, key -> new ReentrantLock());
        return lock.tryLock();
    }

    /**
     * 释放指定会话的执行锁。仅允许持有者释放，避免误释放其他线程的锁。
     */
    public void release(String sessionId) {
        ReentrantLock lock = locks.get(sessionId);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
