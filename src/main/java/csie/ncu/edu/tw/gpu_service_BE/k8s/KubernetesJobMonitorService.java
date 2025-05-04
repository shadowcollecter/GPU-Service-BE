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
import io.minio.MinioClient;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskExecutionRecord;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskExecutionRecordRepository;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 監控Kubernetes任務状態的服務
 * 該服務專注於監控K8s中的任務狀態，不再負責任務提交
 * 增強了對任務結果和錯誤日誌的自動讀取，移除了對任務回調的依賴
 */
@Service
public class KubernetesJobMonitorService {
    private static final Logger log = LoggerFactory.getLogger(KubernetesJobMonitorService.class);

    // Kubernetes API 客戶端
    @Autowired
    private BatchV1Api batchV1Api;
    
    @Autowired
    private TaskExecutionRecordRepository recordRepo;
    
    @Autowired
    private MinioClient minioClient;
    
    @Value("${kubernetes.namespace}")
    private String k8sNamespace;
    
    @Value("${task.ttl.cleanup.enabled:true}")
    private boolean enableTtlCleanup;
    
    @Value("${task.monitor.interval:60000}")
    private long monitorIntervalMs;
    
    @Value("${minio.bucket.name}")
    private String bucketName;
    
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
     * 增強版: 同時檢查任務結果和錯誤日誌，無需依賴回調
     */
    private void scanKubernetesJobs() throws ApiException {
        log.debug("Scanning Kubernetes jobs in namespace {}", k8sNamespace);
        
        // 查詢所有任務
        V1JobList jobList = batchV1Api.listNamespacedJob(
                k8sNamespace, null, null, null, null, null, null, null, null, null, null);
        
        log.debug("Found {} jobs in namespace {}", 
                jobList != null && jobList.getItems() != null ? jobList.getItems().size() : 0, 
                k8sNamespace);
        
        // 更新數據庫中的任務狀態
        if (jobList != null && jobList.getItems() != null) {
            for (V1Job job : jobList.getItems()) {
                if (job == null) {
                    continue;
                }
                
                if (job.getMetadata() == null) {
                    continue;
                }
                
                String jobName = job.getMetadata().getName();
                if (jobName == null) {
                    continue;
                }
                
                // 只處理我們的任務 (前綴為 "task-")
                if (!jobName.startsWith("task-")) {
                    continue;
                }
                
                String submissionId = jobName.substring(5); // 移除 "task-" 前綴
                updateJobStatus(submissionId, job);
            }
        }
    }
    
    /**
     * 根據 Kubernetes Job 狀態更新數據庫中的任務記錄
     * 增強版: 接管回調邏輯，直接從 Minio 獲取結果
     */
    private void updateJobStatus(String submissionId, V1Job job) {
        if (job == null || job.getStatus() == null) {
            log.warn("Job or job status is null for submissionId {}", submissionId);
            return;
        }
        
        recordRepo.findBySubmissionId(submissionId).ifPresent(record -> {
            V1JobStatus status = job.getStatus();
            // 在这里，status已确保不为null
            
            boolean jobCompleted = status.getCompletionTime() != null;
            boolean jobFailed = status.getFailed() != null && 
                                Objects.requireNonNull(status.getFailed()) > 0;
            
            // 設置任務開始時間（如果尚未設置）
            if (record.getStartTime() == null && 
                status.getActive() != null && 
                Objects.requireNonNull(status.getActive()) > 0) {
                
                record.setStartTime(LocalDateTime.now());
                log.debug("Setting start time for job {}", submissionId);
            }
            
            // 更新任務狀態
            if (jobCompleted) {
                // 只有當狀態不是 COMPLETED 時才處理
                if (record.getStatus() != TaskExecutionRecord.Status.COMPLETED) {
                    log.info("Job {} completed successfully, fetching results from Minio", submissionId);
                    record.setStatus(TaskExecutionRecord.Status.COMPLETED);
                    
                    // 設置結束時間（如果尚未設置）
                    if (record.getEndTime() == null) {
                        record.setEndTime(LocalDateTime.now());
                    }
                    
                    // 從 Minio 獲取結果
                    fetchResultFromMinio(record);
                    
                    // 計算任務持續時間
                    if (record.getStartTime() != null && record.getEndTime() != null) {
                        long durationSeconds = java.time.Duration.between(
                            record.getStartTime(), record.getEndTime()).getSeconds();
                        record.setDuration(durationSeconds);
                    }
                    
                    recordRepo.save(record);
                    log.info("Successfully updated completed job status for {}", submissionId);
                }
            } else if (jobFailed) {
                // 只有當狀態不是 FAILED 時才處理
                if (record.getStatus() != TaskExecutionRecord.Status.FAILED) {
                    log.warn("Job {} failed, retrieving error logs from Minio", submissionId);
                    record.setStatus(TaskExecutionRecord.Status.FAILED);
                    
                    // 設置結束時間（如果尚未設置）
                    if (record.getEndTime() == null) {
                        record.setEndTime(LocalDateTime.now());
                    }
                    
                    // 從 Minio 獲取錯誤日誌
                    fetchErrorLogFromMinio(record);
                    
                    // 計算任務持續時間
                    if (record.getStartTime() != null && record.getEndTime() != null) {
                        long durationSeconds = java.time.Duration.between(
                            record.getStartTime(), record.getEndTime()).getSeconds();
                        record.setDuration(durationSeconds);
                    }
                    
                    recordRepo.save(record);
                    log.info("Successfully updated failed job status for {}", submissionId);
                }
            } else if (status.getActive() != null && Objects.requireNonNull(status.getActive()) > 0) {
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
     * 從Minio獲取任務結果並更新記錄
     * 增強版: 嘗試多種可能的結果路徑，確保找到結果
     */
    private void fetchResultFromMinio(TaskExecutionRecord record) {
        try {
            String timestamp = record.getSubmissionId().substring(Math.max(0, record.getSubmissionId().length() - 14));
            String userId = record.getUserId();
            
            // 檢查可能的結果文件路徑
            String[] possibleResultPaths = {
                String.format("submissions/%s/%s_results/result_notebook.ipynb", userId, timestamp),
                String.format("submissions/%s/%s_results/output.ipynb", userId, timestamp),
                String.format("submissions/%s/%s_results/result.ipynb", userId, timestamp)
            };
            
            boolean resultFound = false;
            
            // 嘗試查找可能的結果文件
            for (String path : possibleResultPaths) {
                if (checkObjectExists(path)) {
                    String s3Path = String.format("s3://%s/%s", bucketName, path);
                    record.setResultPath(s3Path);
                    log.info("Successfully set result path for job {}: {}", record.getSubmissionId(), s3Path);
                    resultFound = true;
                    break;
                }
            }
            
            if (!resultFound) {
                // 查找 _results 目錄下的所有可能的輸出文件
                String prefixPath = String.format("submissions/%s/%s_results/", userId, timestamp);
                Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                        .bucket(bucketName)
                        .prefix(prefixPath)
                        .recursive(true)
                        .build());
                
                for (Result<Item> result : results) {
                    Item item = result.get();
                    if (item == null) continue;
                    
                    String objectName = item.objectName();
                    if (objectName != null && objectName.endsWith(".ipynb")) {
                        String s3Path = String.format("s3://%s/%s", bucketName, objectName);
                        record.setResultPath(s3Path);
                        log.info("Found alternative result file for job {}: {}", record.getSubmissionId(), s3Path);
                        resultFound = true;
                        break;
                    }
                }
                
                if (!resultFound) {
                    log.warn("No result file found in Minio for job: {}", record.getSubmissionId());
                }
            }
        } catch (Exception e) {
            log.error("Error fetching result from Minio for job {}: {}", 
                    record.getSubmissionId(), e.getMessage(), e);
        }
    }

    /**
     * 從Minio獲取錯誤日誌並更新記錄
     */
    private void fetchErrorLogFromMinio(TaskExecutionRecord record) {
        try {
            String timestamp = record.getSubmissionId().substring(Math.max(0, record.getSubmissionId().length() - 14));
            String errorLogPath = String.format("submissions/%s/%s_results/error.log", 
                                 record.getUserId(), timestamp);
            
            // Check if error log exists
            boolean fileExists = checkObjectExists(errorLogPath);
            
            if (fileExists) {
                // Read error log content
                String errorMessage = readObjectContent(errorLogPath);
                
                // Truncate if too long
                if (errorMessage != null) {
                    // Limit error message to 1000 characters
                    String truncatedError = errorMessage.length() > 1000 ? 
                                           errorMessage.substring(0, 1000) + "..." : 
                                           errorMessage;
                    record.setRejectionReason(truncatedError);
                    log.info("Set error message for job {} from Minio error log", record.getSubmissionId());
                }
            } else if (record.getRejectionReason() == null || record.getRejectionReason().isEmpty()) {
                // Default error message if no specific error found
                record.setRejectionReason("Task execution failed without specific error information");
            }
            
            // Calculate job duration if we have both start and end times
            if (record.getStartTime() != null && record.getEndTime() != null) {
                long durationSeconds = java.time.Duration.between(
                    record.getStartTime(), record.getEndTime()).getSeconds();
                record.setDuration(durationSeconds);
            }
        } catch (Exception e) {
            log.error("Error fetching error log from Minio for job {}: {}", 
                     record.getSubmissionId(), e.getMessage(), e);
            
            // Set a generic error if we couldn't fetch the specific one
            if (record.getRejectionReason() == null || record.getRejectionReason().isEmpty()) {
                record.setRejectionReason("Task execution failed: " + e.getMessage());
            }
        }
    }

    /**
     * 檢查Minio中對象是否存在
     */
    private boolean checkObjectExists(String objectPath) {
        try {
            // Use listObjects with the exact path to check if it exists
            Iterable<Result<Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder()
                    .bucket(bucketName)
                    .prefix(objectPath)
                    .recursive(false)
                    .build());
            
            // If we can iterate and find at least one matching result, the object exists
            for (Result<Item> result : results) {
                Item item = result.get();
                if (item != null && objectPath.equals(item.objectName())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("Error checking if object exists in Minio: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 讀取Minio對象內容
     */
    private String readObjectContent(String objectPath) {
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectPath)
                    .build())) {
                    
            // Read the content of the error log
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = stream.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
            return result.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            log.warn("Error reading object content from Minio: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 清理已完成或失敗的任務，僅處理有 TTL 設置的任務
     */
    private void cleanupCompletedJobs() throws ApiException {
        log.debug("Cleaning up completed jobs in namespace {}", k8sNamespace);
        
        V1JobList jobList = batchV1Api.listNamespacedJob(
                k8sNamespace, null, null, null, null, null, null, null, null, null, null);
        
        if (jobList == null || jobList.getItems() == null) {
            return;
        }
        
        for (V1Job job : jobList.getItems()) {
            if (job == null) {
                continue;
            }
            
            if (job.getMetadata() == null) {
                continue;
            }
            
            String jobName = job.getMetadata().getName();
            if (jobName == null) {
                continue;
            }
            
            // 只處理我們的任務 (前綴為 "task-")
            if (!jobName.startsWith("task-")) {
                continue;
            }
            
            V1JobStatus status = job.getStatus();
            if (status == null) {
                continue;
            }
            
            boolean jobFinished = (status.getCompletionTime() != null) || 
                                 (status.getFailed() != null && Objects.requireNonNull(status.getFailed()) > 0);
            
            // 根據 TTL 設置處理已完成的任務
            if (jobFinished && job.getSpec() == null) {
                continue;
            }
            
            if (jobFinished && job.getSpec() != null && job.getSpec().getTtlSecondsAfterFinished() == null) {
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
        counts.put("cpu", 0);
        counts.put("gpu-a100", 0);
        counts.put("gpu-a40", 0);
        
        try {
            V1JobList jobList = batchV1Api.listNamespacedJob(
                    k8sNamespace, null, null, null, null, null, null, null, null, null, null);
            
            if (jobList == null || jobList.getItems() == null) {
                return counts;
            }
            
            // 計算不同類型的任務數量
            for (V1Job job : jobList.getItems()) {
                if (job == null) {
                    continue;
                }
                
                if (job.getMetadata() == null) {
                    continue;
                }
                
                String jobName = job.getMetadata().getName();
                if (jobName == null) {
                    continue;
                }
                
                // 跳過非我們系統的任務
                if (!job.getMetadata().getName().startsWith("task-")) {
                    continue;
                }
                
                // 只計算活躍任務
                V1JobStatus status = job.getStatus();
                if (status == null || status.getActive() == null || status.getActive() == 0) {
                    continue;
                }
                
                // 根據標籤判斷任務類型
                if (job.getSpec() == null || 
                    job.getSpec().getTemplate() == null || 
                    job.getSpec().getTemplate().getMetadata() == null) {
                    continue;
                }
                
                Map<String, String> labels = job.getSpec().getTemplate().getMetadata().getLabels();
                if (labels != null) {
                    String resourceType = labels.get("resource-type");
                    String gpuType = labels.get("gpu-type");
                    
                    if ("cpu".equals(resourceType)) {
                        counts.put("cpu", counts.get("cpu") + 1);
                    } else if ("gpu".equals(resourceType)) {
                        if ("a100".equals(gpuType)) {
                            counts.put("gpu-a100", counts.get("gpu-a100") + 1);
                        } else if ("a40".equals(gpuType)) {
                            counts.put("gpu-a40", counts.get("gpu-a40") + 1);
                        }
                    }
                }
            }
        } catch (ApiException e) {
            log.error("Failed to get job counts: {}", e.getResponseBody(), e);
        }
        
        return counts;
    }
}