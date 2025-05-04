package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.kubernetes.client.openapi.models.V1Job;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import csie.ncu.edu.tw.gpu_service_BE.config.GpuConfigProperties;

/**
 * 唯一負責從Redis佇列獲取任務並提交到Kubernetes的服務
 */
@Service
public class ExternalQueueScheduler {
    private static final Logger log = LoggerFactory.getLogger(ExternalQueueScheduler.class);
    
    @Autowired
    private QueueService queueService;
    
    @Autowired
    private BatchV1Api batchV1Api;
    
    @Autowired
    private TaskExecutionRecordRepository recordRepo;
    
    @Autowired
    private MinioPresignService presignService;
    
    @Autowired
    private GpuConfigProperties gpuConfig;
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    @Value("${job.template.dir:customize-yaml}")
    private String jobTemplateDir;
    
    @Value("${job.image:shadowcollect/ipynb-runner:latest}")
    private String jobImage;
    
    @Value("${kubernetes.namespace}")
    private String k8sNamespace;
    
    @Value("${external.scheduler.poll.interval:10000}")
    private long pollIntervalMs;
    
    @Value("${external.scheduler.lock.expiry:30000}")
    private long lockExpiryMs;
    
    @Value("${external.scheduler.batch.size:5}")
    private int batchSize;
    
    // 唯一的調度器ID，用於分佈式鎖
    private final String schedulerId = UUID.randomUUID().toString();
    
    /**
     * 定期從佇列中獲取並提交待處理的任務
     */
    @Scheduled(fixedDelayString = "${external.scheduler.poll.interval:10000}")
    public void submitPendingExternalTasks() {
        log.debug("Scheduler {} checking for pending tasks", schedulerId);
        
        // 獲取所有佇列中的待處理任務，但限制批處理大小
        List<TaskInfo> pending = queueService.getPendingTasks(batchSize);
        log.debug("Found {} pending tasks in queue", pending.size());
        
        // 處理每個待處理任務
        for (TaskInfo info : pending) {
            String submissionId = info.getSubmissionId();
            
            // 使用分佈式鎖確保任務只被一個調度器處理
            String lockKey = "task-lock:" + submissionId;
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, schedulerId, lockExpiryMs, TimeUnit.MILLISECONDS);
            
            if (acquired == null || !acquired) {
                log.debug("Task {} is being processed by another scheduler, skipping", submissionId);
                continue;
            }
            
            try {
                log.info("Processing task {} from queue", submissionId);
                processTask(info, submissionId);
            } finally {
                // 釋放鎖，但只有當鎖仍然是由當前調度器擁有時
                String currentOwner = redisTemplate.opsForValue().get(lockKey);
                if (schedulerId.equals(currentOwner)) {
                    redisTemplate.delete(lockKey);
                    log.debug("Released lock for task {}", submissionId);
                }
            }
        }
    }
    
    /**
     * 處理單個任務的提交邏輯
     */
    @Transactional
    private void processTask(TaskInfo info, String submissionId) {
        recordRepo.findBySubmissionId(submissionId).ifPresent(rec -> {
            try {
                // 檢查originalPath是否為null或空
                if (rec.getOriginalPath() == null || rec.getOriginalPath().isEmpty()) {
                    log.error("Task {} has null or empty originalPath, marking as failed", submissionId);
                    rec.setStatus(TaskExecutionRecord.Status.FAILED);
                    rec.setEndTime(java.time.LocalDateTime.now());
                    rec.setRejectionReason("Missing original path information");
                    recordRepo.save(rec);
                    queueService.removeTask(submissionId);
                    return;
                }
                
                // 檢查 Job 是否已經存在
                String jobName = "task-" + submissionId;
                try {
                    batchV1Api.readNamespacedJob(jobName, k8sNamespace, null);
                    // 如果能讀取到 Job，說明它已經存在
                    log.info("Job {} already exists in Kubernetes, marking as SCHEDULED", submissionId);
                    rec.setStatus(TaskExecutionRecord.Status.SCHEDULED);
                    recordRepo.save(rec);
                    queueService.removeTask(submissionId);
                    return;
                } catch (io.kubernetes.client.openapi.ApiException apiEx) {
                    // 404 代表未找到，這是預期的，可以繼續處理
                    if (apiEx.getCode() != 404) {
                        log.warn("Error checking for existing job {}: code={}", jobName, apiEx.getCode());
                    }
                }
                
                // 確定要使用的模板
                String templateFile = !info.isGpuRequired()
                        ? gpuConfig.getCpu().getYaml()
                        : gpuConfig.getTypes().stream()
                                .filter(c -> c.getType().equals(info.getGpuType()))
                                .map(GpuConfigProperties.GpuTypeConfig::getYaml)
                                .findFirst().orElse(gpuConfig.getCpu().getYaml());
                
                // 檢查模板文件是否存在
                Path templatePath = Path.of(jobTemplateDir, templateFile);
                if (!Files.exists(templatePath)) {
                    log.error("Template file not found: {}", templatePath.toString());
                    rec.setStatus(TaskExecutionRecord.Status.FAILED);
                    rec.setEndTime(java.time.LocalDateTime.now());
                    rec.setRejectionReason("Job template file not found: " + templateFile);
                    recordRepo.save(rec);
                    queueService.removeTask(submissionId);
                    return;
                }
                
                // 讀取模板文件
                String template = Files.readString(templatePath);
                log.debug("Loaded template {} for task {}, size: {} bytes", templateFile, submissionId, template.length());
                
                // 生成時間戳和預簽名URL
                String timestamp = submissionId.substring(submissionId.length() - 14);
                String presignUrl = presignService.getPresignedUrl(rec.getOriginalPath(), 3600);
                log.debug("Generated presigned URL for object: {}", rec.getOriginalPath());
                
                // 替換模板中的變數
                String jobYaml = template
                    .replace("${SUBMISSION_ID}", submissionId)
                    .replace("${USER_ID}", rec.getUserId())
                    .replace("${TIMESTAMP}", timestamp)
                    .replace("${PRESIGN_URL}", presignUrl)
                    .replace("${GPU_TYPE}", rec.getGpuType() != null ? rec.getGpuType() : "");
                
                // 解析YAML並創建Job
                V1Job job;
                try {
                    Yaml yaml = new Yaml();
                    job = yaml.loadAs(jobYaml, V1Job.class);
                } catch (Exception e) {
                    log.error("Failed to parse job YAML for task {}: {}", submissionId, e.getMessage(), e);
                    rec.setStatus(TaskExecutionRecord.Status.FAILED);
                    rec.setEndTime(java.time.LocalDateTime.now());
                    rec.setRejectionReason("Failed to parse job template: " + e.getMessage());
                    recordRepo.save(rec);
                    queueService.removeTask(submissionId);
                    return;
                }
                
                // 設置任務屬性
                var container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
                container.setImage(jobImage);
                job.getMetadata().setName("task-" + submissionId);
                
                // 提交到Kubernetes
                log.debug("Submitting job to Kubernetes namespace {} for task {}", k8sNamespace, submissionId);
                try {
                    batchV1Api.createNamespacedJob(k8sNamespace, job, null, null, null, null);
                    log.info("Successfully submitted task {} to Kubernetes", submissionId);
                    
                    // 更新任務狀態為已調度
                    rec.setStatus(TaskExecutionRecord.Status.SCHEDULED);
                    recordRepo.save(rec);
                    
                    // 確保可靠地從佇列中移除任務
                    boolean removed = removeTaskWithRetry(submissionId, 3);
                    if (!removed) {
                        log.error("Failed to remove task {} from queue, may cause duplicate submissions", submissionId);
                    }
                } catch (io.kubernetes.client.openapi.ApiException apiEx) {
                    log.error("Kubernetes API error when submitting task {}: code={}, body={}", 
                            submissionId, apiEx.getCode(), apiEx.getResponseBody(), apiEx);
                    
                    // 處理衝突錯誤 (409) - 表示任務已存在
                    if (apiEx.getCode() == 409) {
                        log.info("Job {} already exists in Kubernetes, marking as SCHEDULED", submissionId);
                        rec.setStatus(TaskExecutionRecord.Status.SCHEDULED);
                        recordRepo.save(rec);
                        queueService.removeTask(submissionId);
                        return;
                    }
                    
                    // 處理特定錯誤代碼，確保任務不會被重複提交
                    boolean shouldRemoveTask = false;
                    if (apiEx.getCode() == 404 || apiEx.getCode() == 403 || apiEx.getCode() == 400) {
                        rec.setStatus(TaskExecutionRecord.Status.FAILED);
                        rec.setEndTime(java.time.LocalDateTime.now());
                        rec.setRejectionReason("Kubernetes API error: " + apiEx.getMessage());
                        recordRepo.save(rec);
                        shouldRemoveTask = true;
                    }
                    
                    // 其他錯誤代碼 (如 500、503 等)，可能是臨時性的，任務保留在佇列中以便稍後重試
                    
                    if (shouldRemoveTask) {
                        boolean removed = removeTaskWithRetry(submissionId, 3);
                        if (!removed) {
                            log.error("Failed to remove failed task {} from queue", submissionId);
                        }
                    }
                } catch (Exception e) {
                    log.error("General error submitting task {} to K8s: {}", submissionId, e.getMessage(), e);
                }
            } catch (Exception e) {
                log.error("General error processing task {}: {}", submissionId, e.getMessage(), e);
            }
        });
    }
    
    /**
     * 嘗試多次從佇列中移除任務，提高可靠性
     */
    private boolean removeTaskWithRetry(String submissionId, int maxRetries) {
        boolean removed = false;
        
        for (int i = 0; i < maxRetries && !removed; i++) {
            try {
                removed = queueService.removeTask(submissionId);
                if (!removed && i < maxRetries - 1) {
                    log.warn("Failed to remove task {} from queue, retrying ({}/{})", submissionId, i+1, maxRetries);
                    Thread.sleep(100); // 短暫等待後重試
                }
            } catch (Exception e) {
                log.warn("Error removing task {} from queue, retrying ({}/{}): {}", 
                         submissionId, i+1, maxRetries, e.getMessage());
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        return removed;
    }
}