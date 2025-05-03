package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import io.kubernetes.client.openapi.ApiException;
import java.util.List;

@Service("customTaskScheduler")
public class TaskScheduler {
    @Autowired
    private QueueService queueService;
    @Autowired
    private KaiSchedulerService kaiSchedulerService;
    @Autowired
    private TaskExecutionRecordRepository recordRepo;

    // 每隔10秒檢查佇列並提交新任務
    @Scheduled(fixedDelayString = "${scheduler.poll.interval:10000}")
    public void submitPendingTasks() {
        List<TaskInfo> pending = queueService.getPendingTasks(null);
        for (TaskInfo task : pending) {
            try {
                // 提交到 KAI Scheduler
                kaiSchedulerService.submitTask(task);
                // 標記為 SCHEDULED 並保存
                recordRepo.findBySubmissionId(task.getSubmissionId())
                    .ifPresent(rec -> {
                        rec.setStatus(TaskExecutionRecord.Status.SCHEDULED);
                        recordRepo.save(rec);
                    });
                // 從應用層佇列中移除
                queueService.removeTask(task.getSubmissionId());
            } catch (ApiException e) {
                // 日誌並保留在佇列中以便重試
                System.err.println("Failed to schedule task " + task.getSubmissionId() + ": " + e.getResponseBody());
            }
        }
    }
}