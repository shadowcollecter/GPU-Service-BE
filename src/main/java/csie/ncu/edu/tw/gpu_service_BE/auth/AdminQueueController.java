package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.core.io.InputStreamResource;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Collections;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskExecutionRecord;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskExecutionRecordRepository;
import csie.ncu.edu.tw.gpu_service_BE.auth.OperationLog;
import csie.ncu.edu.tw.gpu_service_BE.auth.OperationLogRepository;
import io.minio.MinioClient;
import io.minio.GetObjectArgs;

@RestController
@RequestMapping("/api/v1/admin/queue")
@PreAuthorize("hasRole('ADMIN')")
public class AdminQueueController {
    @Autowired
    private QueueService queueService;
    @Autowired
    private TaskExecutionRecordRepository taskExecutionRecordRepository;
    @Autowired
    private MinioClient minioClient;
    @Value("${minio.bucket.name}")
    private String bucketName;
    @Autowired
    private OperationLogRepository operationLogRepository;

    // 查詢等待中任務
    @GetMapping("/tasks/pending")
    public ResponseEntity<?> getPendingTasks(@RequestParam(required = false) Integer limit) {
        List<TaskInfo> tasks = queueService.getPendingTasks(limit);
        return ResponseEntity.ok(Map.of("tasks", tasks));
    }

    // 清空佇列
    @PostMapping("/clear")
    public ResponseEntity<?> clearQueue() {
        // fetch all pending tasks, then clear queue and mark records failed
        var pendingTasks = queueService.getPendingTasks(null);
        queueService.clearQueue();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        for (TaskInfo ti : pendingTasks) {
            taskExecutionRecordRepository.findBySubmissionId(ti.getSubmissionId()).ifPresent(rec -> {
                rec.setStatus(TaskExecutionRecord.Status.FAILED);
                rec.setEndTime(now);
                rec.setRejectionReason("Cleared by admin");
                taskExecutionRecordRepository.save(rec);
            });
        }
        return ResponseEntity.ok(Map.of("message", "Queue cleared"));
    }

    // 移除單一佇列任務
    @PostMapping("/task/{id}/remove")
    public ResponseEntity<?> removeTask(@PathVariable String id) {
        boolean removed = queueService.removeTask(id);
        if (removed) {
            return ResponseEntity.ok(Map.of("message", "Task removed"));
        } else {
            return ResponseEntity.status(404).body(Map.of("errorCode", "NOT_FOUND", "message", "Task not found in queue"));
        }
    }

    @GetMapping
    public ResponseEntity<?> listQueue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search
    ) {
        List<TaskInfo> tasks = queueService.getPendingTasks(null);
        if (search != null && !search.isEmpty()) {
            tasks = tasks.stream()
                    .filter(t -> t.getSubmissionId().contains(search) || t.getUserId().contains(search))
                    .collect(Collectors.toList());
        }
        int total = tasks.size();
        int from = page * size;
        int to = Math.min(from + size, total);
        List<TaskInfo> pageList = (from >= total) ? Collections.emptyList() : tasks.subList(from, to);
        return ResponseEntity.ok(Map.of("tasks", pageList, "total", total));
    }

    @GetMapping("/task/{id}/download")
    public ResponseEntity<?> downloadOriginal(@PathVariable String id, HttpServletRequest request) {
        var recOpt = taskExecutionRecordRepository.findBySubmissionId(id);
        if (recOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("errorCode","NOT_FOUND","message","Task not found"));
        }
        TaskExecutionRecord rec = recOpt.get();
        // choose MinIO object: use resultPath when available, else originalPath
        String objectName = rec.getResultPath() != null ? rec.getResultPath() : rec.getOriginalPath();
        String action = rec.getResultPath() != null ? "DOWNLOAD_RESULT" : "DOWNLOAD_ORIGINAL";
        try {
            InputStream stream = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucketName).object(objectName).build());
            InputStreamResource resource = new InputStreamResource(stream);
            String filename = objectName.substring(objectName.lastIndexOf('/') + 1);
            // audit log success
            operationLogRepository.save(new OperationLog(
                SecurityContextHolder.getContext().getAuthentication().getName(),
                action, "TASK", id, "SUCCESS", request.getRemoteAddr(), LocalDateTime.now()));
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\"")
                .body(resource);
        } catch (Exception ex) {
            // audit log failure
            operationLogRepository.save(new OperationLog(
                SecurityContextHolder.getContext().getAuthentication().getName(),
                action, "TASK", id, "FAILURE", request.getRemoteAddr(), LocalDateTime.now()));
            return ResponseEntity.status(404)
                .body(Map.of("errorCode","NO_FILE","message","File not found or error fetching from storage"));
        }
    }
}
