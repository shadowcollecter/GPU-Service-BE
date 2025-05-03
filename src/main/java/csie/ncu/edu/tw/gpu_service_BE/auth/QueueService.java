package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.stereotype.Service;
import csie.ncu.edu.tw.gpu_service_BE.config.GpuConfigProperties;
import csie.ncu.edu.tw.gpu_service_BE.config.QueueProperties;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class QueueService {
    private final RedisTemplate<String, TaskInfo> redisTemplate;
    private final ListOperations<String, TaskInfo> listOps;
    @Autowired
    private QueueProperties queueProperties;
    @Autowired
    private GpuConfigProperties gpuConfig;

    public QueueService(RedisTemplate<String, TaskInfo> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.listOps = redisTemplate.opsForList();
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
        Long size = listOps.rightPush(key, info);
        return (size != null ? size.intValue() - 1 : -1);
    }

    // 根據位置估算等待時間（秒），例如每個任務假定平均執行 60 秒
    public int estimateWaitTime(int position) {
        return position * 60;
    }

    // 清空佇列
    public void clearQueue() {
        redisTemplate.delete(queueProperties.getRedisKeys().values());
    }

    public boolean removeTask(String queueName, String id) {
        String key = queueName != null ? queueProperties.getRedisKeys().get(queueName) : queueProperties.getRedisKeys().get(gpuConfig.getCpu().getQueue());
        List<TaskInfo> all = listOps.range(key, 0, -1);
        if (all == null) return false;
        for (TaskInfo task : all) {
            if (task.getSubmissionId().equals(id)) {
                listOps.remove(key, 1, task);
                return true;
            }
        }
        return false;
    }

    public boolean removeTask(String id) {
        // try to remove from any queue
        for (String qName : queueProperties.getRedisKeys().keySet()) {
            if (removeTask(qName, id)) {
                return true;
            }
        }
        return false;
    }
}
