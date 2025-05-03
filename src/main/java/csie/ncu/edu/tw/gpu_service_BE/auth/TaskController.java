package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
        // 只允許自己或管理員取消
        var pendingTasks = queueService.getPendingTasks(null);
        var taskOpt = pendingTasks.stream().filter(t -> t.getSubmissionId().equals(id)).findFirst();
        if (taskOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("errorCode", "NOT_FOUND", "message", "Task not found in queue"));
        }
        var task = taskOpt.get();
        if (!isAdmin && !task.getUserId().equals(userId)) {
            return ResponseEntity.status(403).body(Map.of("errorCode", "ACCESS_DENIED", "message", "You are not authorized to cancel this task."));
        }
        boolean removed = queueService.removeTask(id);
        if (removed) {
            return ResponseEntity.ok(Map.of("message", "Task cancelled"));
        } else {
            return ResponseEntity.status(404).body(Map.of("errorCode", "NOT_FOUND", "message", "Task not found in queue"));
        }
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

    @PostMapping("/status/{submissionId}")
    public ResponseEntity<?> callbackStatus(
            @PathVariable String submissionId,
            @RequestBody Map<String, Object> payload) {
        return taskExecutionRecordRepository.findBySubmissionId(submissionId)
            .map(record -> {
                // update status
                String status = (String) payload.get("status");
                record.setStatus(TaskExecutionRecord.Status.valueOf(status));
                if (payload.containsKey("resultPath")) {
                    record.setResultPath((String) payload.get("resultPath"));
                }
                if (payload.containsKey("startTime")) {
                    record.setStartTime(LocalDateTime.parse((String) payload.get("startTime")));
                }
                if (payload.containsKey("endTime")) {
                    record.setEndTime(LocalDateTime.parse((String) payload.get("endTime")));
                }
                if (payload.containsKey("duration")) {
                    record.setDuration(((Number) payload.get("duration")).longValue());
                }
                taskExecutionRecordRepository.save(record);
                return ResponseEntity.ok(Map.of("message", "Status updated"));
            })
            .orElseGet(() -> ResponseEntity.status(404).body(Map.of("errorCode", "NOT_FOUND", "message", "Submission not found.")));
    }
}
