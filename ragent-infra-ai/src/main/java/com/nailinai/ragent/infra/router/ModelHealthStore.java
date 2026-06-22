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
                    log.info("Circuit breaker for provider {} moved to HALF_OPEN", provider);
                    return true;
                }
                return false;
            }
            // HALF_OPEN: allow single probe
            return true;
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
        volatile CircuitState state = CircuitState.CLOSED;
        volatile Instant openedAt = Instant.EPOCH;
    }
}