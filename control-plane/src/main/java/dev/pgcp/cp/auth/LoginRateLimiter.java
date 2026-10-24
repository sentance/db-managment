package dev.pgcp.cp.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import dev.pgcp.cp.common.ApiException;

/** Sliding-window limit on failed authentication attempts per source address and per username. */
@Component
public class LoginRateLimiter {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public void check(String... keys) {
        Instant cutoff = Instant.now().minus(WINDOW);
        for (String key : keys) {
            Deque<Instant> q = failures.get(key);
            if (q == null) {
                continue;
            }
            synchronized (q) {
                while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
                    q.pollFirst();
                }
                if (q.size() >= MAX_FAILURES) {
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                            "too many failed attempts, try again later");
                }
            }
        }
    }

    public void recordFailure(String... keys) {
        for (String key : keys) {
            Deque<Instant> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (q) {
                q.addLast(Instant.now());
            }
        }
    }

    public void reset(String... keys) {
        for (String key : keys) {
            failures.remove(key);
        }
    }
}
