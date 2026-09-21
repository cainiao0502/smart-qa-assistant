package com.nailinai.ragent.infra.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ModelHealthStore {

    private static final Logger log = LoggerFactory.getLogger(ModelHealthStore.class);

    private static final int FAILURE_THRESHOLD = 2;
    private static final Duration OPEN_DURATION = Duration.ofSeconds(30);

    private final Map<String, HealthState> states = new ConcurrentHashMap<>();

    public boolean allowCall(String provider) {
        HealthState state = states.computeIfAbsent(provider, k -> new HealthState());
        synchronized (state) {
            CircuitState current = state.state;
            if (current == CircuitState.CLOSED) {
                return true;
            }
            if (current == CircuitState.OPEN) {
                if (Instant.now().isAfter(state.openedAt.plus(OPEN_DURATION))) {
                    state.state = CircuitState.HALF_OPEN;
                    // 上一次探测失败可能把许可留在 1，进入 HALF_OPEN 前重置，
                    // 确保本轮探测许可可用
                    state.probePermit.set(0);
                    log.info("Circuit breaker for provider {} moved to HALF_OPEN", provider);
                    return true;
                }
                return false;
            }
            // HALF_OPEN: allow single probe —— 用探测许可保证并发下只有一个请求
            // 放行做探测，其余请求一律按 OPEN 拒绝，避免探测期并发洪峰全部打到
            // 尚未确认恢复的 provider 上。探测成功转 CLOSED 时恢复许可，
            // 探测失败重新 OPEN（见 markFailure），下次 OPEN->HALF_OPEN 转换时重置许可。
            return state.probePermit.compareAndSet(0, 1);
        }
    }

    public void markSuccess(String provider) {
        HealthState state = states.get(provider);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.failures.set(0);
            if (state.state != CircuitState.CLOSED) {
                state.state = CircuitState.CLOSED;
                // 探测成功恢复 CLOSED，归还探测许可供下次熔断周期使用
                state.probePermit.set(0);
                log.info("Circuit breaker for provider {} recovered to CLOSED", provider);
            }
        }
    }

    public void markFailure(String provider) {
        HealthState state = states.computeIfAbsent(provider, k -> new HealthState());
        synchronized (state) {
            int count = state.failures.incrementAndGet();
            if (state.state == CircuitState.HALF_OPEN) {
                state.state = CircuitState.OPEN;
                state.openedAt = Instant.now();
                log.warn("Circuit breaker for provider {} moved to OPEN (half-open probe failed)", provider);
                return;
            }
            if (count >= FAILURE_THRESHOLD && state.state == CircuitState.CLOSED) {
                state.state = CircuitState.OPEN;
                state.openedAt = Instant.now();
                log.warn("Circuit breaker for provider {} moved to OPEN (failures={})", provider, count);
            }
        }
    }

    public CircuitState getState(String provider) {
        HealthState state = states.get(provider);
        if (state == null) {
            return CircuitState.CLOSED;
        }
        synchronized (state) {
            return state.state;
        }
    }

    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private static class HealthState {
        final AtomicInteger failures = new AtomicInteger(0);
        // HALF_OPEN 状态下的单次探测许可：0=可用，1=已发放。
        // 在 allowCall 内 state 锁保护下 CAS 发放，探测成功/进入下一轮 HALF_OPEN 时重置。
        final AtomicInteger probePermit = new AtomicInteger(0);
        volatile CircuitState state = CircuitState.CLOSED;
        volatile Instant openedAt = Instant.EPOCH;
    }
}