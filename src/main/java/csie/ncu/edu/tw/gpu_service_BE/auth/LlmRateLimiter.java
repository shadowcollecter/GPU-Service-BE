package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Token bucket rate limiter for LLM API calls to prevent hitting rate limits.
 * More sophisticated than a fixed delay, this approach adapts to usage patterns.
 */
@Component
public class LlmRateLimiter {
    private final int capacity;
    private final double refillRate; // tokens per second
    private double tokens;
    private long lastRefillTimestamp;
    private final ReentrantLock lock = new ReentrantLock();

    public LlmRateLimiter(
            @Value("${llm.ratelimiter.capacity:10}") int capacity,
            @Value("${llm.ratelimiter.refill-rate:0.5}") double refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = capacity;
        this.lastRefillTimestamp = System.currentTimeMillis();
    }

    /**
     * Blocks until a token is available, then acquires the token.
     * Use this when you want to wait until you can proceed.
     */
    public void acquire() {
        lock.lock();
        try {
            long waitTime = waitTimeMillis();
            if (waitTime > 0) {
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            tokens -= 1;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Tries to acquire a token without waiting.
     * @return true if a token was acquired, false otherwise
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the wait time in milliseconds until a token is available.
     */
    public long waitTimeMillis() {
        lock.lock();
        try {
            refill();
            if (tokens >= 1) return 0;
            return (long) Math.ceil((1 - tokens) / refillRate * 1000);
        } finally {
            lock.unlock();
        }
    }

    private void refill() {
        long now = System.currentTimeMillis();
        double tokensToAdd = (now - lastRefillTimestamp) / 1000.0 * refillRate;
        tokens = Math.min(capacity, tokens + tokensToAdd);
        lastRefillTimestamp = now;
    }
}