package pe.licitagestion.security;

import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class LoginRateLimiter {
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> failedAttempts = new ConcurrentHashMap<>();

    public boolean isBlocked(String key) {
        if (key == null || key.isBlank()) return false;
        var queue = failedAttempts.get(key);
        if (queue == null) return false;
        Instant cutoff = Instant.now().minus(WINDOW);
        queue.removeIf(t -> t.isBefore(cutoff));
        return queue.size() >= MAX_ATTEMPTS;
    }

    public void recordFailure(String key) {
        if (key == null || key.isBlank()) return;
        var queue = failedAttempts.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        Instant cutoff = Instant.now().minus(WINDOW);
        queue.removeIf(t -> t.isBefore(cutoff));
        queue.addLast(Instant.now());
    }

    public void recordSuccess(String key) {
        if (key != null) failedAttempts.remove(key);
    }
}


