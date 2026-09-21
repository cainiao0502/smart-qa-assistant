package com.nailinai.ragent.chat.service.impl;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话级执行串行化守卫。
 *
 * <p>同一会话的并发消息会互相污染上下文：历史读取不一致、消息落库顺序错乱、
 * Agent 运行记录互相覆盖。因此需要保证同一 {@code sessionId} 的消息严格串行执行。
 *
 * <p>单机部署下使用 JVM 内的 {@link ReentrantLock}，非阻塞获取；
 * 若未来升级为多实例部署，应替换为基于 Redis 的分布式锁（如 Redisson）。
 *
 * <p><b>锁的有界性：</b>sessionId 来自前端（随机串），若按会话无界创建锁，
 * 恶意方可以用大量伪造 sessionId 撑爆内存。这里用 access-order 的
 * {@link LinkedHashMap} 做 LRU，活跃会话的锁永远在表内，长期不活跃的
 * 会话锁在超过 {@value #MAX_LOCKS} 条后被淘汰。被淘汰的锁存在理论并发窗口
 * （旧请求仍持有旧锁、新请求拿到新建的锁），可接受：锁仅用于同会话请求串行化，
 * 淘汰只会命中长时间不活跃的会话——此时持有者早已结束执行；且每次
 * {@link #tryAcquire}/{@link #release} 都会刷新 access 顺序，正被使用的锁
 * 实际上不可能被淘汰。
 */
@Component
public class SessionExecutionGuard {

    /** 锁表上限：足够容纳真实活跃会话，同时封死恶意 sessionId 撑大内存的可能 */
    private static final int MAX_LOCKS = 4096;

    private final Object lockGate = new Object();

    /** accessOrder=true：get / computeIfAbsent 都会把条目移到「最近使用」端，配合 removeEldestEntry 实现 LRU */
    private final Map<String, ReentrantLock> locks = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ReentrantLock> eldest) {
            return size() > MAX_LOCKS;
        }
    };

    /**
     * 尝试获取指定会话的执行锁（非阻塞）。
     *
     * @return true 表示获取成功（该会话当前无其他消息在处理）；false 表示已被占用
     */
    public boolean tryAcquire(String sessionId) {
        ReentrantLock lock;
        synchronized (lockGate) {
            lock = locks.computeIfAbsent(sessionId, key -> new ReentrantLock());
        }
        return lock.tryLock();
    }

    /**
     * 释放指定会话的执行锁。仅允许持有者释放，避免误释放其他线程的锁。
     */
    public void release(String sessionId) {
        ReentrantLock lock;
        synchronized (lockGate) {
            lock = locks.get(sessionId);
        }
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
