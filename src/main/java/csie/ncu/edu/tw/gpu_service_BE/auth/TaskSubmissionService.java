package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.models.V1Job;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import jakarta.transaction.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import csie.ncu.edu.tw.gpu_service_BE.config.GpuConfigProperties;
import csie.ncu.edu.tw.gpu_service_BE.config.GpuConfigProperties.GpuTypeConfig;

import org.springframework.scheduling.TaskScheduler;
import java.util.Date;

@Service
public class TaskSubmissionService {
    private static final Logger log = LoggerFactory.getLogger(TaskSubmissionService.class);

    @Autowired
    private FileStorageService fileStorageService;  // handles saving files to MinIO
    @Autowired
    private UsageService usageService;  // checks remaining time
    @Autowired
    private QueueService queueService;
    @Autowired
    private TaskExecutionRecordRepository recordRepo;
    @Autowired
    private AsyncSecurityScanService asyncSecurityScanService;
    @Autowired
    private BatchV1Api batchV1Api;
    @Autowired
    private GpuTypeRepository gpuTypeRepository;

    @Value("${job.template.dir:customize-yaml}")
    private String jobTemplateDir;

    @Autowired
    private MinioPresignService presignService;

    @Value("${job.image:shadowcollect/ipynb-runner:latest}")
    private String jobImage;

    @Autowired
    private GpuConfigProperties gpuConfig;

    @Value("${kubernetes.namespace}")
    private String k8sNamespace;

    @Autowired
    private TaskScheduler taskScheduler;
    
    // LLM Scan Queue
    @Autowired
    private BlockingQueue<LlmScannerConfig.ScanTask> scanQueue;
    
    @Value("${llm.scan.delay-ms:20000}")
    private long llmScanDelayMs;
    
    // For direct submission after scan
    @Autowired
    private LlmScannerConfig.RateLimiter rateLimiter;
    
    // Configuration for direct K8s submission
    @Value("${task.submit.mode:queue}")
    private String taskSubmitMode; // Options: "queue" or "direct"

    @Transactional
    public TaskSubmissionResponse submit(
            String userId,
            MultipartFile file,
            boolean gpuRequired,
            String gpuType,
            String clientInfo,
            String clientIp) {
        TaskSubmissionResponse resp = new TaskSubmissionResponse();
        // 1. validate file type and size
        if (!file.getOriginalFilename().endsWith(".ipynb") || file.getSize() > 10 * 1024 * 1024) {
            resp.setRejected(true);
            resp.setStatusCode(400);
            resp.setErrorCode("INVALID_FILE");
            resp.setMessage("Only .ipynb files under 10MB allowed");
            return resp;
        }
        // determine if GPU should actually be used (existence + enabled)
        boolean effectiveGpu = false;
        if (gpuRequired) {
            if (gpuType != null) {
                var opt = gpuTypeRepository.findById(gpuType);
                if (opt.isPresent() && opt.get().isEnabled()) {
                    effectiveGpu = true;
                } else {
                    log.warn("GPU type {} not available or disabled, fallback to CPU", gpuType);
                }
            } else {
                log.warn("gpuRequired=true but gpuType is null, fallback to CPU");
            }
        }
        // 2. store original notebook
        String submissionId = fileStorageService.storeOriginal(userId, file);
        // record the original file object path in MinIO: {userId}/{submissionId}/{filename}
        String originalPath = String.format("%s/%s/%s", userId, submissionId, file.getOriginalFilename());
        
        // 3. Add to rate-limited scan queue instead of scheduling with fixed delay
        log.debug("Adding task to LLM scan queue: userId={}, submissionId={}", userId, submissionId);
        scanQueue.add(new LlmScannerConfig.ScanTask(userId, submissionId, file.getOriginalFilename()));
        
        // 4. usage/time check
        if (!usageService.hasRemainingTime(userId)) {
            resp.setRejected(true);
            resp.setStatusCode(403);
            resp.setErrorCode("INSUFFICIENT_TIME");
            resp.setMessage("Insufficient remaining time to execute task.");
            return resp;
        }
        
        // 5. create record with PENDING status
        TaskExecutionRecord record = new TaskExecutionRecord();
        record.setSubmissionId(submissionId);
        record.setUserId(userId);
        record.setOriginalPath(originalPath);
        // set initial status and timestamps
        record.setStatus(TaskExecutionRecord.Status.PENDING);
        record.setCreatedAt(LocalDateTime.now());
        record.setStartTime(LocalDateTime.now());
        // set resource allocation info based on effective GPU availability
        if (effectiveGpu) {
            record.setResourceType(TaskExecutionRecord.ResourceType.GPU);
            record.setGpuType(gpuType);
        } else {
            record.setResourceType(TaskExecutionRecord.ResourceType.CPU);
            record.setGpuType(null);
        }
        recordRepo.save(record);

        // 6. prepare response
        resp.setRejected(false);
        resp.setSubmissionId(submissionId);
        resp.setStatus("WAITING");
        // return actual queue position and estimated wait time
        resp.setQueuePosition(0); // position will be updated after enqueue
        resp.setEstimatedWaitTime(queueService.estimateWaitTime(0));
        return resp;
    }
}