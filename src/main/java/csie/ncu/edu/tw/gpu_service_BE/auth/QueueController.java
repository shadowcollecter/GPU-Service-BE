package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

import csie.ncu.edu.tw.gpu_service_BE.config.GpuConfigProperties;
import csie.ncu.edu.tw.gpu_service_BE.config.QueueProperties;
import jakarta.annotation.PostConstruct;

@RestController
@RequestMapping("/api/v1/queue")
public class QueueController {
    @Autowired
    private QueueService queueService;
    
    @Autowired
    private GpuConfigProperties gpuConfig;
    
    @Autowired
    private QueueProperties queueProperties;
    
    @Autowired
    private TaskExecutionRecordRepository taskExecutionRecordRepository;

    private String cpuQueueName;
    private String gpuA100QueueName;
    private String gpuA40QueueName;

    private static final int MAX_DEPTH = 40;
    private static final int AVG_TASK_MINUTES = 30; // 平均任務時長(分鐘)
    
    @PostConstruct
    public void init() {
        // Initialize queue names from configuration
        cpuQueueName = gpuConfig.getCpu().getQueue();
        
        // Find A100 and A40 queue names from GPU types
        for (GpuConfigProperties.GpuTypeConfig typeConfig : gpuConfig.getTypes()) {
            if (typeConfig.getType().contains("A100")) {
                gpuA100QueueName = typeConfig.getQueue();
            } else if (typeConfig.getType().contains("A40")) {
                gpuA40QueueName = typeConfig.getQueue();
            }
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> getQueueStatus() {
        // Get tasks in Redis queues
        long cpuQueueDepth = queueService.getPendingTasks(cpuQueueName, null).size();
        long gpuA100QueueDepth = queueService.getPendingTasks(gpuA100QueueName, null).size();
        long gpuA40QueueDepth = queueService.getPendingTasks(gpuA40QueueName, null).size();
        
        // Also count tasks that are in PENDING state but haven't been added to Redis yet
        long pendingCpuTasks = taskExecutionRecordRepository.countByStatusAndResourceType(
                TaskExecutionRecord.Status.PENDING, TaskExecutionRecord.ResourceType.CPU);
        
        long pendingGpuA100Tasks = taskExecutionRecordRepository.countByStatusAndResourceTypeAndGpuType(
                TaskExecutionRecord.Status.PENDING, 
                TaskExecutionRecord.ResourceType.GPU, 
                gpuConfig.getTypes().stream()
                    .filter(t -> t.getType().contains("A100"))
                    .findFirst()
                    .map(GpuConfigProperties.GpuTypeConfig::getType)
                    .orElse("A100"));
        
        long pendingGpuA40Tasks = taskExecutionRecordRepository.countByStatusAndResourceTypeAndGpuType(
                TaskExecutionRecord.Status.PENDING, 
                TaskExecutionRecord.ResourceType.GPU, 
                gpuConfig.getTypes().stream()
                    .filter(t -> t.getType().contains("A40"))
                    .findFirst()
                    .map(GpuConfigProperties.GpuTypeConfig::getType)
                    .orElse("A40"));
        
        // Total depths including both Redis queue and pending DB records
        long cpuDepth = cpuQueueDepth + pendingCpuTasks;
        int cpuMax = MAX_DEPTH;
        
        // GPU queues
        List<Map<String,Object>> gpuQueues = new ArrayList<>();
        gpuQueues.add(new LinkedHashMap<>(Map.of(
            "type", gpuA100QueueName,
            "depth", gpuA100QueueDepth + pendingGpuA100Tasks,
            "max", MAX_DEPTH
        )));
        gpuQueues.add(new LinkedHashMap<>(Map.of(
            "type", gpuA40QueueName,
            "depth", gpuA40QueueDepth + pendingGpuA40Tasks,
            "max", MAX_DEPTH
        )));
        
        // estimated wait time (minutes)
        int totalPending = (int) (cpuDepth + 
                                   (gpuA100QueueDepth + pendingGpuA100Tasks) + 
                                   (gpuA40QueueDepth + pendingGpuA40Tasks));
        int estimated = totalPending * AVG_TASK_MINUTES;

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("cpuQueueDepth", cpuDepth);
        result.put("cpuQueueMax", cpuMax);
        result.put("gpuQueues", gpuQueues);
        result.put("estimatedWaitTime", estimated);
        return ResponseEntity.ok(result);
    }
}
