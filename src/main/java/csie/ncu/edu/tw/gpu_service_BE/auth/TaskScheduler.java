package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 定期检查PENDING状态的任务，确保它们被添加到Redis队列中
 */
@Service("customTaskScheduler")
public class TaskScheduler {
    private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);
    
    @Autowired
    private QueueService queueService;
    
    @Autowired
    private TaskExecutionRecordRepository recordRepo;

    /**
     * 每隔10秒检查数据库中PENDING状态的任务，确保它们被添加到Redis队列中
     * 添加事务管理确保数据库连接被正确释放
     */
    @Scheduled(fixedDelayString = "${scheduler.poll.interval:10000}")
    @Transactional(readOnly = true)
    public void checkPendingTasks() {
        // 获取所有PENDING状态的任务记录
        List<TaskExecutionRecord> pendingRecords = recordRepo.findByStatus(TaskExecutionRecord.Status.PENDING);
        
        if (pendingRecords.isEmpty()) {
            return;
        }
        
        log.debug("Found {} pending tasks in database to add to queue", pendingRecords.size());
        
        // 获取所有待处理任务，减少重复调用
        List<TaskInfo> queuedTasks = queueService.getPendingTasks(null);
        
        for (TaskExecutionRecord record : pendingRecords) {
            // 检查任务是否已经在队列中 - 使用预先获取的队列任务
            boolean isAlreadyQueued = queuedTasks.stream()
                .anyMatch(task -> task.getSubmissionId().equals(record.getSubmissionId()));
            
            if (!isAlreadyQueued) {
                // 创建TaskInfo并提交到队列
                TaskInfo taskInfo = new TaskInfo(
                    record.getSubmissionId(),
                    record.getUserId(),
                    record.getResourceType() == TaskExecutionRecord.ResourceType.GPU,
                    record.getVramSize(),
                    record.getGpuType(),
                    record.getCreatedAt().toInstant(java.time.ZoneOffset.UTC),
                    null, // clientInfo
                    null  // clientIp
                );
                
                try {
                    int position = queueService.submitTask(taskInfo);
                    log.info("Added pending task {} to queue at position {}", record.getSubmissionId(), position);
                } catch (Exception e) {
                    log.error("Failed to add task {} to queue: {}", record.getSubmissionId(), e.getMessage(), e);
                }
            }
        }
    }
}