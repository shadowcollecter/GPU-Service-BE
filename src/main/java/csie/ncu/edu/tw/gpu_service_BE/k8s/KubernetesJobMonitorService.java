package csie.ncu.edu.tw.gpu_service_BE.k8s;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskExecutionRecord;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskExecutionRecordRepository;
import csie.ncu.edu.tw.gpu_service_BE.config.GpuConfigProperties;

/**
 * 監控Kubernetes任務状態的服務
 * 該服務專注於監控K8s中的任務狀態，不再負責任務提交
 */
@Service
public class KubernetesJobMonitorService {
    private static final Logger log = LoggerFactory.getLogger(KubernetesJobMonitorService.class);

    // Kubernetes API 客戶端
    @Autowired
    private BatchV1Api batchV1Api;
    
    @Autowired
    private CoreV1Api coreV1Api;
    
    @Autowired
    private TaskExecutionRecordRepository recordRepo;
    
    @Autowired
    private GpuConfigProperties gpuConfig;
    
    @Value("${kubernetes.namespace}")
    private String k8sNamespace;
    
    @Value("${task.ttl.cleanup.enabled:true}")
    private boolean enableTtlCleanup;
    
    @Value("${task.monitor.interval:60000}")
    private long monitorIntervalMs;
    
    /**
     * 定期監控 Kubernetes 中的任務並更新任務狀態
     */
    @Scheduled(fixedDelayString = "${task.monitor.interval:60000}")
    public void monitorJobs() {
        try {
            // 監控現有的 Kubernetes 任務狀態
            scanKubernetesJobs();
            
            // 清理已完成或失敗的任務 (如果啟用了 TTL 清理)
            if (enableTtlCleanup) {
                cleanupCompletedJobs();
            }
        } catch (ApiException e) {
            log.error("Failed to monitor Kubernetes jobs: {}", e.getResponseBody(), e);
        } catch (Exception e) {
            log.error("Unexpected error in job monitoring: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 掃描 Kubernetes 中的任務並更新數據庫中的狀態
     */
    private void scanKubernetesJobs() throws ApiException {
        log.debug("Scanning Kubernetes jobs in namespace {}", k8sNamespace);
        
        // 查詢所有任務
        V1JobList jobList = batchV1Api.listNamespacedJob(
                k8sNamespace, null, null, null, null, null, null, null, null, null, null);
        
        log.debug("Found {} jobs in namespace {}", jobList.getItems().size(), k8sNamespace);
        
        // 更新數據庫中的任務狀態
        for (V1Job job : jobList.getItems()) {
            String jobName = job.getMetadata().getName();
            
            // 只處理我們的任務 (前綴為 "task-")
            if (!jobName.startsWith("task-")) {
                continue;
            }
            
            String submissionId = jobName.substring(5); // 移除 "task-" 前綴
            updateJobStatus(submissionId, job);
        }
    }
    
    /**
     * 根據 Kubernetes Job 狀態更新數據庫中的任務記錄
     */
    private void updateJobStatus(String submissionId, V1Job job) {
        recordRepo.findBySubmissionId(submissionId).ifPresent(record -> {
            // 跳過已完成或失敗的任務
            if (record.getStatus() == TaskExecutionRecord.Status.COMPLETED || 
                record.getStatus() == TaskExecutionRecord.Status.FAILED) {
                return;
            }
            
            V1JobStatus status = job.getStatus();
            boolean jobCompleted = status.getCompletionTime() != null;
            boolean jobFailed = status.getFailed() != null && status.getFailed() > 0;
            
            // 更新任務狀態
            if (jobCompleted) {
                if (record.getStatus() != TaskExecutionRecord.Status.COMPLETED) {
                    log.info("Job {} completed successfully", submissionId);
                    record.setStatus(TaskExecutionRecord.Status.COMPLETED);
                    record.setEndTime(LocalDateTime.now());
                    recordRepo.save(record);
                }
            } else if (jobFailed) {
                if (record.getStatus() != TaskExecutionRecord.Status.FAILED) {
                    log.warn("Job {} failed", submissionId);
                    record.setStatus(TaskExecutionRecord.Status.FAILED);
                    record.setEndTime(LocalDateTime.now());
                    if (record.getRejectionReason() == null || record.getRejectionReason().isEmpty()) {
                        record.setRejectionReason("Kubernetes job failed");
                    }
                    recordRepo.save(record);
                }
            } else if (status.getActive() != null && status.getActive() > 0) {
                // 任務正在運行中
                if (record.getStatus() != TaskExecutionRecord.Status.RUNNING) {
                    log.info("Job {} is now running", submissionId);
                    record.setStatus(TaskExecutionRecord.Status.RUNNING);
                    recordRepo.save(record);
                }
            }
        });
    }
    
    /**
     * 清理已完成或失敗的任務，僅處理有 TTL 設置的任務
     */
    private void cleanupCompletedJobs() throws ApiException {
        log.debug("Cleaning up completed jobs in namespace {}", k8sNamespace);
        
        V1JobList jobList = batchV1Api.listNamespacedJob(
                k8sNamespace, null, null, null, null, null, null, null, null, null, null);
        
        for (V1Job job : jobList.getItems()) {
            String jobName = job.getMetadata().getName();
            
            // 只處理我們的任務 (前綴為 "task-")
            if (!jobName.startsWith("task-")) {
                continue;
            }
            
            V1JobStatus status = job.getStatus();
            boolean jobFinished = (status.getCompletionTime() != null) || 
                                 (status.getFailed() != null && status.getFailed() > 0);
            
            // 根據 TTL 設置處理已完成的任務
            if (jobFinished && job.getSpec().getTtlSecondsAfterFinished() == null) {
                // 如果任務沒有設置 TTL，手動刪除
                try {
                    log.info("Cleaning up completed job {} without TTL", jobName);
                    batchV1Api.deleteNamespacedJob(
                            jobName, k8sNamespace, null, null, null, null, null, null);
                } catch (ApiException e) {
                    if (e.getCode() != 404) { // 忽略已刪除的任務
                        log.warn("Failed to cleanup job {}: {}", jobName, e.getResponseBody());
                    }
                }
            }
        }
    }
    
    /**
     * 獲取指定任務類型的運行中和待處理任務數量
     */
    public Map<String, Integer> getJobCounts() {
        Map<String, Integer> counts = new HashMap<>();
        
        try {
            V1JobList jobList = batchV1Api.listNamespacedJob(
                    k8sNamespace, null, null, null, null, null, null, null, null, null, null);
            
            // 計算不同類型的任務數量
            int cpuRunning = 0;
            int gpuA100Running = 0;
            int gpuA40Running = 0;
            
            for (V1Job job : jobList.getItems()) {
                // 跳過非我們系統的任務
                if (!job.getMetadata().getName().startsWith("task-")) {
                    continue;
                }
                
                // 只計算活躍任務
                V1JobStatus status = job.getStatus();
                if (status.getActive() == null || status.getActive() == 0) {
                    continue;
                }
                
                // 根據標籤判斷任務類型
                Map<String, String> labels = job.getSpec().getTemplate().getMetadata().getLabels();
                if (labels != null) {
                    String resourceType = labels.get("resource-type");
                    String gpuType = labels.get("gpu-type");
                    
                    if ("cpu".equals(resourceType)) {
                        cpuRunning++;
                    } else if ("gpu".equals(resourceType)) {
                        if ("a100".equals(gpuType)) {
                            gpuA100Running++;
                        } else if ("a40".equals(gpuType)) {
                            gpuA40Running++;
                        }
                    }
                }
            }
            
            counts.put("cpu", cpuRunning);
            counts.put("gpu-a100", gpuA100Running);
            counts.put("gpu-a40", gpuA40Running);
            
        } catch (ApiException e) {
            log.error("Failed to get job counts: {}", e.getResponseBody(), e);
        }
        
        return counts;
    }
}