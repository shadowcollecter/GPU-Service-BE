package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import csie.ncu.edu.tw.gpu_service_BE.config.GpuConfigProperties;
import csie.ncu.edu.tw.gpu_service_BE.config.QueueProperties;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class QueueService {
    private static final Logger log = LoggerFactory.getLogger(QueueService.class);
    
    private final RedisTemplate<String, TaskInfo> redisTemplate;
    private final ListOperations<String, TaskInfo> listOps;
    private final RedisTemplate<String, String> stringRedisTemplate;
    private final ValueOperations<String, String> stringOps;
    @Autowired
    private QueueProperties queueProperties;
    @Autowired
    private GpuConfigProperties gpuConfig;

    public QueueService(RedisTemplate<String, TaskInfo> redisTemplate, RedisTemplate<String, String> stringRedisTemplate) {
        this.redisTemplate = redisTemplate;
        this.listOps = redisTemplate.opsForList();
        this.stringRedisTemplate = stringRedisTemplate;
        this.stringOps = stringRedisTemplate.opsForValue();
    }

    /**
     * Fetch pending tasks from all queues or specific one.
     */
    public List<TaskInfo> getPendingTasks(String queueName, Integer limit) {
        Map<String, String> redisKeys = queueProperties.getRedisKeys();
        String key = queueName != null ? redisKeys.get(queueName) : null;
        long size = (key != null ? (listOps.size(key) != null ? listOps.size(key) : 0) :
            redisKeys.values().stream().mapToLong(k -> listOps.size(k) != null ? listOps.size(k) : 0).sum());
        if (size == 0) return List.of();
        int fetch = (limit != null && limit > 0) ? Math.min(limit, (int)size) : (int)size;
        return key != null ? listOps.range(key, 0, fetch - 1)
            : redisKeys.values().stream()
                .flatMap(k -> listOps.range(k, 0, fetch - 1).stream())
                .collect(Collectors.toList());
    }

    // convenience overloads
    public List<TaskInfo> getPendingTasks() {
        return getPendingTasks(null, null);
    }

    public List<TaskInfo> getPendingTasks(Integer limit) {
        return getPendingTasks(null, limit);
    }

    /**
     * Choose Redis queue based on TaskInfo resource and push FIFO.
     */
    public int submitTask(TaskInfo info) {
        String qName;
        if (!info.isGpuRequired()) {
            // CPU queue from config
            qName = gpuConfig.getCpu().getQueue();
        } else {
            // find matching GPU type config
            var match = gpuConfig.getTypes().stream()
                .filter(c -> c.getType().equalsIgnoreCase(info.getGpuType()))
                .findFirst();
            qName = match.map(c -> c.getQueue())
                .orElse(gpuConfig.getCpu().getQueue());
        }
        String key = queueProperties.getRedisKeys().getOrDefault(qName, queueProperties.getRedisKeys().get(gpuConfig.getCpu().getQueue()));
        
        // 任務去重：先檢查任務是否已經在佇列中
        if (isTaskInQueue(info.getSubmissionId())) {
            log.warn("Task {} already exists in queue, skipping submission", info.getSubmissionId());
            // 返回隊列位置估計值（-1表示已存在）
            return -1;
        }
        
        Long size = listOps.rightPush(key, info);
        return (size != null ? size.intValue() - 1 : -1);
    }
    
    /**
     * 檢查任務是否已經在任何佇列中
     * @param submissionId 任務ID
     * @return 如果任務已存在於任何佇列中則返回true
     */
    public boolean isTaskInQueue(String submissionId) {
        // 檢查所有佇列
        for (String queueKey : queueProperties.getRedisKeys().values()) {
            List<TaskInfo> tasks = listOps.range(queueKey, 0, -1);
            if (tasks != null && tasks.stream().anyMatch(t -> t.getSubmissionId().equals(submissionId))) {
                return true;
            }
        }
        return false;
    }

    // 根據位置估算等待時間（秒），例如每個任務假定平均執行 60 秒
    public int estimateWaitTime(int position) {
        return position * 60;
    }

    // 清空佇列
    public void clearQueue() {
        redisTemplate.delete(queueProperties.getRedisKeys().values());
    }

    /**
     * 從指定佇列中移除任務，使用原子操作並支援重試
     * @param queueName 佇列名稱
     * @param id 任務ID
     * @return 是否成功移除
     */
    public boolean removeTask(String queueName, String id) {
        String key = queueName != null ? queueProperties.getRedisKeys().get(queueName) : queueProperties.getRedisKeys().get(gpuConfig.getCpu().getQueue());
        
        // 使用Redis事務操作，首先獲取所有任務
        List<TaskInfo> all = listOps.range(key, 0, -1);
        if (all == null || all.isEmpty()) {
            return false;
        }
        
        // 使用分佈式鎖防止並發問題
        String lockKey = "queue-lock:" + key;
        Boolean acquired = stringOps.setIfAbsent(lockKey, "1", 5, TimeUnit.SECONDS);
        
        if (acquired == null || !acquired) {
            log.warn("Could not acquire lock for queue {}, retrying operation", key);
            try {
                Thread.sleep(100); // 短暫等待
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return removeTask(queueName, id); // 遞歸重試
        }
        
        try {
            for (TaskInfo task : all) {
                if (task.getSubmissionId().equals(id)) {
                    Long removed = listOps.remove(key, 1, task);
                    boolean success = (removed != null && removed > 0);
                    log.debug("Task {} removal from queue {}: {}", id, key, success ? "success" : "failed");
                    return success;
                }
            }
            return false;
        } finally {
            // 釋放鎖
            stringRedisTemplate.delete(lockKey);
        }
    }

    /**
     * 從所有佇列中移除指定任務
     * @param id 任務ID
     * @return 是否成功移除
     */
    public boolean removeTask(String id) {
        // 建立一個任務操作標記鑰匙，防止重複操作
        String operationKey = "task-removal:" + id;
        Boolean uniqueOperation = stringOps.setIfAbsent(operationKey, "1", 30, TimeUnit.SECONDS);
        
        if (uniqueOperation == null || !uniqueOperation) {
            log.debug("Task {} is already being processed for removal", id);
            return true; // 假設成功，因為另一個線程正在處理
        }
        
        try {
            // 嘗試從所有佇列中移除
            for (String qName : queueProperties.getRedisKeys().keySet()) {
                if (removeTask(qName, id)) {
                    return true;
                }
            }
            return false;
        } finally {
            // 操作完成後釋放標記
            stringRedisTemplate.delete(operationKey);
        }
    }
}
