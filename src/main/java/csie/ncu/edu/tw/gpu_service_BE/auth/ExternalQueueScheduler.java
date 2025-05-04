package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import io.kubernetes.client.openapi.models.V1Job;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import csie.ncu.edu.tw.gpu_service_BE.config.GpuConfigProperties;

@Service
public class ExternalQueueScheduler {
    private static final Logger log = LoggerFactory.getLogger(ExternalQueueScheduler.class);

    @Autowired
    private QueueService queueService;
    @Autowired
    private TaskExecutionRecordRepository recordRepo;
    @Autowired
    private BatchV1Api batchV1Api;
    @Autowired
    private MinioPresignService presignService;
    @Autowired
    private GpuConfigProperties gpuConfig;
    @Value("${job.template.dir:customize-yaml}")
    private String jobTemplateDir;
    @Value("${job.image:shadowcollect/ipynb-runner:latest}")
    private String jobImage;
    @Value("${kubernetes.namespace}")
    private String k8sNamespace;

    @Value("${external.scheduler.poll.interval:10000}")
    private long pollInterval;

    @Scheduled(fixedDelayString = "${external.scheduler.poll.interval:10000}")
    public void submitPendingExternalTasks() {
        List<TaskInfo> pending = queueService.getPendingTasks(null);
        log.debug("Found {} pending tasks in queue", pending.size());
        for (TaskInfo info : pending) {
            String submissionId = info.getSubmissionId();
            recordRepo.findBySubmissionId(submissionId).ifPresent(rec -> {
                try {
                    // 检查originalPath是否为null或空
                    if (rec.getOriginalPath() == null || rec.getOriginalPath().isEmpty()) {
                        log.error("Task {} has null or empty originalPath, marking as failed", submissionId);
                        rec.setStatus(TaskExecutionRecord.Status.FAILED);
                        rec.setEndTime(java.time.LocalDateTime.now());
                        rec.setRejectionReason("Missing original path information");
                        recordRepo.save(rec);
                        queueService.removeTask(submissionId);
                        return;
                    }
                    
                    // determine template
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
                    
                    String template = Files.readString(templatePath);
                    log.debug("Loaded template {} for task {}, size: {} bytes", templateFile, submissionId, template.length());
                    
                    String timestamp = submissionId.substring(submissionId.length() - 14);
                    String presignUrl = presignService.getPresignedUrl(rec.getOriginalPath(), 3600);
                    log.debug("Generated presigned URL for object: {}", rec.getOriginalPath());
                    
                    String jobYaml = template
                        .replace("${SUBMISSION_ID}", submissionId)
                        .replace("${USER_ID}", rec.getUserId())
                        .replace("${TIMESTAMP}", timestamp)
                        .replace("${PRESIGN_URL}", presignUrl)
                        .replace("${GPU_TYPE}", rec.getGpuType() != null ? rec.getGpuType() : "");
                    
                    // load and submit
                    Yaml yaml = new Yaml();
                    log.debug("Parsing job YAML for task {}", submissionId);
                    V1Job job;
                    try {
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
                    
                    var container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
                    container.setImage(jobImage);
                    job.getMetadata().setName("task-" + submissionId);
                    
                    log.debug("Submitting job to Kubernetes namespace {} for task {}", k8sNamespace, submissionId);
                    try {
                        batchV1Api.createNamespacedJob(k8sNamespace, job, null, null, null, null);
                        log.info("Successfully submitted task {} to Kubernetes", submissionId);
                        rec.setStatus(TaskExecutionRecord.Status.SCHEDULED);
                        recordRepo.save(rec);
                        queueService.removeTask(submissionId);
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
                        // 只在特定情況下將任務標記為失敗，例如資源不可用，否則保留在隊列中重試
                        if (apiEx.getCode() == 404 || apiEx.getCode() == 403 || apiEx.getCode() == 400) {
                            rec.setStatus(TaskExecutionRecord.Status.FAILED);
                            rec.setEndTime(java.time.LocalDateTime.now());
                            rec.setRejectionReason("Kubernetes API error: " + apiEx.getMessage());
                            recordRepo.save(rec);
                            queueService.removeTask(submissionId);
                        }
                    } catch (Exception e) {
                        log.error("Failed to submit task {} to K8s: {}", submissionId, e.getMessage(), e);
                    }
                } catch (Exception e) {
                    log.error("General error processing task {}: {}", submissionId, e.getMessage(), e);
                }
            });
        }
    }
}