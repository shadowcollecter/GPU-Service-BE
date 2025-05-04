package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.PostConstruct;

/**
 * Configuration for LLM security scanning with rate limiting.
 * Implements a token bucket algorithm to manage API rate limits.
 */
@Configuration
@EnableScheduling
public class LlmScannerConfig {
    private static final Logger log = LoggerFactory.getLogger(LlmScannerConfig.class);

    @Autowired
    private AsyncSecurityScanService securityScanService;

    @Value("${llm.rate-limit.requests-per-minute:20}")
    private int requestsPerMinute;

    @Value("${llm.rate-limit.burst-capacity:30}")
    private int burstCapacity;

    private RateLimiter rateLimiter;

    @PostConstruct
    public void init() {
        double refillRate = requestsPerMinute / 60.0; // convert to per-second rate
        rateLimiter = new RateLimiter(burstCapacity, refillRate);
        log.info("LLM Scanner Rate Limiter initialized with refill rate of {} requests/sec, burst capacity of {}", 
                 refillRate, burstCapacity);
    }

    @Bean
    public BlockingQueue<ScanTask> scanQueue() {
        return new LinkedBlockingQueue<>();
    }

    @Bean
    public RateLimiter rateLimiter() {
        return rateLimiter;
    }

    /**
     * Process tasks from the scan queue at a rate-limited pace.
     * This scheduled method runs frequently to check for new tasks but will
     * only process them according to the rate limiter's constraints.
     * Added @Transactional to ensure proper connection management.
     */
    @Scheduled(fixedDelayString = "${llm.scanner.poll-interval-ms:500}")
    @Transactional(readOnly = true)
    public void processScanQueue() {
        BlockingQueue<ScanTask> queue = scanQueue();
        if (queue.isEmpty()) return;

        ScanTask task = queue.peek();
        if (task != null && rateLimiter.tryAcquire()) {
            task = queue.poll(); // Only remove if we actually processed it
            if (task != null) {
                log.debug("Processing LLM scan task: userId={}, submissionId={}", 
                         task.userId(), task.submissionId());
                securityScanService.scan(task.userId(), task.submissionId(), task.filename());
            }
        } else if (task != null) {
            // We have a task but couldn't process it due to rate limiting
            long waitTime = rateLimiter.waitTimeMillis();
            log.debug("Rate limited LLM scan, next slot available in {} ms", waitTime);
        }
    }

    /**
     * Simple token bucket rate limiter.
     */
    public static class RateLimiter {
        private final int capacity;
        private final double refillRate; // tokens per second
        private double tokens;
        private long lastRefillTimestamp;
        private final ReentrantLock lock = new ReentrantLock();

        public RateLimiter(int capacity, double refillRate) {
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

    /**
     * Represents a task in the LLM scanning queue.
     */
    public record ScanTask(String userId, String submissionId, String filename) {}
}