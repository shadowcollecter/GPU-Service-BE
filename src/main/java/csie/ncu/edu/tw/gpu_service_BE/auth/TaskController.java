package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.RequestBody;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/v1/task")
public class TaskController {
    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    @Autowired
    private QueueService queueService;

    @Autowired
    private TaskExecutionRecordRepository taskExecutionRecordRepository;

    @Autowired
    private TaskSubmissionService taskSubmissionService;  // service to handle submission logic

    @Autowired
    private MinioPresignService presignService;

    @Value("${minio.bucket.name}")
    private String bucketName;
    
    // Keeping the API key for backward compatibility if needed temporarily
    @Value("${api.internal.key}")
    private String internalApiKey;

    @Autowired
    private csie.ncu.edu.tw.gpu_service_BE.k8s.DirectKubernetesSchedulerService kubernetesService;

    @GetMapping("/list")
    public ResponseEntity<?> listTasks(@RequestParam int page, @RequestParam int size, Authentication auth) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<TaskExecutionRecord> result = taskExecutionRecordRepository.findByUserId(auth.getName(), pageable);
        return ResponseEntity.ok(Map.of("tasks", result.getContent(), "total", result.getTotalElements()));
    }

    // 僅允許任務擁有者或管理員取消等待中任務
    @PostMapping("/cancel/{id}")
    public ResponseEntity<?> cancelTask(@PathVariable("id") String id) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = auth.getName();
        boolean isAdmin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        
        // 检查数据库中的任务记录
        return taskExecutionRecordRepository.findBySubmissionId(id)
            .map(record -> {
                // 检查权限 - 只允许自己或管理员取消
                if (!isAdmin && !record.getUserId().equals(userId)) {
                    return ResponseEntity.status(403).body(Map.of(
                        "errorCode", "ACCESS_DENIED", 
                        "message", "You are not authorized to cancel this task."
                    ));
                }
                
                // 检查任务状态 - 只能取消PENDING或SCHEDULED状态的任务
                if (record.getStatus() != TaskExecutionRecord.Status.PENDING && 
                    record.getStatus() != TaskExecutionRecord.Status.SCHEDULED) {
                    return ResponseEntity.status(400).body(Map.of(
                        "errorCode", "INVALID_STATE", 
                        "message", "Only pending or scheduled tasks can be cancelled."
                    ));
                }
                
                try {
                    // 1. 如果任务在队列中，从队列移除
                    queueService.removeTask(id);
                    
                    // 2. 如果任务已提交到Kubernetes，尝试从K8s删除
                    if (record.getStatus() == TaskExecutionRecord.Status.SCHEDULED) {
                        try {
                            kubernetesService.deleteJob(id);
                            log.info("Kubernetes job for task {} deleted", id);
                        } catch (Exception e) {
                            log.warn("Failed to delete Kubernetes job for task {}: {}", id, e.getMessage());
                            // 继续处理，即使Kubernetes删除失败
                        }
                    }
                    
                    // 3. 更新任务状态为已取消
                    record.setStatus(TaskExecutionRecord.Status.CANCELLED);
                    record.setEndTime(LocalDateTime.now());
                    taskExecutionRecordRepository.save(record);
                    
                    return ResponseEntity.ok(Map.of(
                        "message", "Task cancelled successfully",
                        "status", "CANCELLED"
                    ));
                } catch (Exception e) {
                    log.error("Error cancelling task {}: {}", id, e.getMessage(), e);
                    return ResponseEntity.status(500).body(Map.of(
                        "errorCode", "CANCEL_ERROR", 
                        "message", "Error cancelling task: " + e.getMessage()
                    ));
                }
            })
            .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                "errorCode", "NOT_FOUND", 
                "message", "Task not found."
            )));
    }

    @PostMapping(value = "/submit", consumes = {"multipart/form-data"})
    public ResponseEntity<?> submitTask(
            @RequestParam("file") MultipartFile file,
            @RequestParam boolean gpuRequired,
            @RequestParam(required = false) String gpuType,
            @RequestParam String clientInfo,
            Authentication auth,
            HttpServletRequest request) {
        try {
            String userId = auth.getName();
            String clientIp = request.getRemoteAddr();
            // validate gpuType when gpuRequired is true
            if (gpuRequired && (gpuType == null || gpuType.isBlank())) {
                return ResponseEntity.status(400).body(Map.of(
                    "errorCode", "INVALID_PARAMETER",
                    "message", "gpuType is required when gpuRequired is true"
                ));
            }
            // delegate to service layer
            TaskSubmissionResponse response = taskSubmissionService.submit(userId, file, gpuRequired, gpuType, clientInfo, clientIp);
            if (response.isRejected()) {
                // build map manually to avoid null in Map.of
                var err = new java.util.LinkedHashMap<String, Object>();
                err.put("errorCode", response.getErrorCode());
                err.put("message", response.getMessage());
                if (response.getRiskScore() != null) {
                    err.put("riskScore", response.getRiskScore());
                }
                return ResponseEntity.status(response.getStatusCode()).body(err);
            }
            return ResponseEntity.ok(Map.of(
                    "submissionId", response.getSubmissionId(),
                    "status", response.getStatus(),
                    "queuePosition", response.getQueuePosition(),
                    "estimatedWaitTime", response.getEstimatedWaitTime()
            ));
        } catch (Exception e) {
            log.error("Error in submitTask", e);
            return ResponseEntity.status(500).body(Map.of(
                "errorCode", "SUBMIT_ERROR",
                "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/status/{submissionId}")
    public ResponseEntity<?> getTaskStatus(@PathVariable String submissionId, Authentication auth) {
        String userId = auth.getName();
        boolean isAdmin = auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return taskExecutionRecordRepository.findBySubmissionId(submissionId)
            .map(record -> {
                if (!isAdmin && !record.getUserId().equals(userId)) {
                    Map<String, String> err = new java.util.HashMap<>();
                    err.put("errorCode", "ACCESS_DENIED");
                    err.put("message", "Not authorized to view this task.");
                    return ResponseEntity.status(403).body(err);
                }
                java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
                result.put("submissionId", record.getSubmissionId());
                result.put("userId", record.getUserId());
                result.put("status", record.getStatus().name());
                // pending queue info
                if (record.getStatus() == TaskExecutionRecord.Status.PENDING) {
                    java.util.List<TaskInfo> pending = queueService.getPendingTasks(null);
                    int pos = 1;
                    for (TaskInfo ti : pending) {
                        if (ti.getSubmissionId().equals(submissionId)) break;
                        pos++;
                    }
                    result.put("queuePosition", pos);
                    result.put("estimatedWaitTime", pos * 1800);
                }
                // timestamps
                result.put("createdAt", record.getCreatedAt());
                if (record.getStartTime() != null) result.put("startTime", record.getStartTime());
                if (record.getEndTime() != null) result.put("endTime", record.getEndTime());
                result.put("duration", record.getDuration());
                // include resource allocation info
                if (record.getResourceType() != null) {
                    result.put("resourceType", record.getResourceType().name());
                }
                if (record.getVramSize() != null) {
                    result.put("vramSize", record.getVramSize());
                }
                // include security scan results if present
                if (record.getRiskScore() != null) {
                    result.put("riskScore", record.getRiskScore());
                }
                if (record.getRiskMessage() != null) {
                    result.put("riskMessage", record.getRiskMessage());
                }
                // optional rejection reason and resultPath
                if (record.getRejectionReason() != null) result.put("rejectionReason", record.getRejectionReason());
                if (record.getResultPath() != null) result.put("resultPath", record.getResultPath());
                return ResponseEntity.ok(result);
            })
            .orElseGet(() -> {
                Map<String, String> err = new java.util.HashMap<>();
                err.put("errorCode", "NOT_FOUND");
                err.put("message", "Task not found.");
                return ResponseEntity.status(404).body(err);
            });
    }

    @GetMapping("/presign-url/{submissionId}")
    public ResponseEntity<?> getPresignUrl(@PathVariable String submissionId, Authentication auth) {
        String userId = auth.getName();
        boolean isAdmin = auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return taskExecutionRecordRepository.findBySubmissionId(submissionId)
            .map(record -> {
                if (!isAdmin && !record.getUserId().equals(userId)) {
                    return ResponseEntity.status(403).body(Map.of("errorCode", "ACCESS_DENIED", "message", "Not authorized to access presigned URL."));
                }
                String objectName = record.getOriginalPath();
                String url = presignService.getPresignedUrl(objectName, 3600);
                return ResponseEntity.ok(Map.of("presignedUrl", url));
            })
            .orElseGet(() -> ResponseEntity.status(404).body(Map.of("errorCode", "NOT_FOUND", "message", "Submission not found.")));
    }

    /**
     * @deprecated This endpoint is maintained for backward compatibility only.
     * Task status updates are now handled automatically by the KubernetesJobMonitorService
     * which monitors job statuses and retrieves results from Minio directly.
     */
    @PostMapping("/status/{submissionId}")
    public ResponseEntity<?> callbackStatus(
            @PathVariable String submissionId,
            @RequestBody Map<String, Object> payload) {
        // Log the callback but respond with a message that this method is deprecated
        log.info("Received deprecated callback for submissionId={} - Status updates are now handled by the job monitor service", submissionId);
        return ResponseEntity.ok(Map.of(
            "message", "Callback received, but task status updates are now handled automatically by the job monitor service"
        ));
    }

    /**
     * @deprecated This endpoint is maintained for backward compatibility only.
     * Task status updates are now handled automatically by the KubernetesJobMonitorService
     * which monitors job statuses and retrieves results from Minio directly.
     */
    @PostMapping("/status/{submissionId}/internal")
    public ResponseEntity<?> internalCallbackStatus(
            @PathVariable String submissionId,
            @RequestHeader(name = "X-API-Key", required = false) String apiKey,
            @RequestBody Map<String, Object> payload) {
        
        // Validate API key for backward compatibility
        if (apiKey == null || !apiKey.equals(internalApiKey)) {
            log.warn("Invalid or missing API key in deprecated internal callback for submissionId={}", submissionId);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid or missing API key"));
        }
        
        log.info("Received deprecated internal callback for submissionId={} - Status updates are now handled by the job monitor service", submissionId);
        return ResponseEntity.ok(Map.of(
            "message", "Internal callback received, but task status updates are now handled automatically by the job monitor service"
        ));
    }
}
