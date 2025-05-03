package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.LocalDateTime;
import csie.ncu.edu.tw.gpu_service_BE.auth.OperationLog;
import csie.ncu.edu.tw.gpu_service_BE.auth.OperationLogRepository;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/tasks")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTaskQueryController {
    @Autowired
    private TaskQueryService queryService;
    @Autowired
    private TaskExecutionRecordRepository recordRepo;
    @Autowired
    private OperationLogRepository operationLogRepository;

    @GetMapping
    public Page<TaskDto> getTasks(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(required = false) String userId,
        @RequestParam(required = false) String submissionId,
        @RequestParam(required = false) TaskExecutionRecord.Status status,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate
    ) {
        TaskFilter filter = new TaskFilter();
        filter.setUserId(userId);
        filter.setSubmissionId(submissionId);
        filter.setStatus(status);
        filter.setStartDate(startDate);
        filter.setEndDate(endDate);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startTime"));
        return queryService.findAllTasks(filter, pageable);
    }

    @GetMapping("/{submissionId}")
    public ResponseEntity<?> getTaskDetail(@PathVariable String submissionId) {
        return queryService.getTaskBySubmissionId(submissionId)
            .<ResponseEntity<?>>map(dto -> ResponseEntity.ok(dto))
            .orElseGet(() -> ResponseEntity.status(404)
                .body(Map.of("errorCode","NOT_FOUND","message","Task not found")));
    }

    @GetMapping("/{submissionId}/download")
    public ResponseEntity<?> downloadResult(@PathVariable String submissionId, HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = auth.getName();
        return recordRepo.findBySubmissionId(submissionId)
            .map(rec -> {
                String result = "";
                String path = rec.getResultPath();
                if (path == null) {
                    result = "NO_RESULT";
                    // log
                    operationLogRepository.save(new OperationLog(userId, "DOWNLOAD_RESULT", "TASK", submissionId, result, request.getRemoteAddr(), LocalDateTime.now()));
                    return ResponseEntity.status(404)
                        .body(Map.of("errorCode","NO_RESULT","message","No result available"));
                }
                File file = new File(path);
                if (!file.exists() || !file.isFile()) {
                    result = "NO_FILE";
                    operationLogRepository.save(new OperationLog(userId, "DOWNLOAD_RESULT", "TASK", submissionId, result, request.getRemoteAddr(), LocalDateTime.now()));
                    return ResponseEntity.status(404)
                        .body(Map.of("errorCode","NO_FILE","message","Result file not found"));
                }
                result = "SUCCESS";
                operationLogRepository.save(new OperationLog(userId, "DOWNLOAD_RESULT", "TASK", submissionId, result, request.getRemoteAddr(), LocalDateTime.now()));
                Resource resource = new FileSystemResource(file);
                return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"result_" + submissionId + ".ipynb\"")
                    .body(resource);
            })
            .orElseGet(() -> {
                // not found
                operationLogRepository.save(new OperationLog(userId, "DOWNLOAD_RESULT", "TASK", submissionId, "NOT_FOUND", request.getRemoteAddr(), LocalDateTime.now()));
                return ResponseEntity.status(404)
                    .body(Map.of("errorCode","NOT_FOUND","message","Task not found"));
            });
    }
}