package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Map;
import io.minio.MinioClient;
import io.minio.GetObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import java.io.InputStream;

@RestController
@RequestMapping("/api/v1/task")
public class TaskResultController {
    @Autowired
    private TaskExecutionRecordRepository taskExecutionRecordRepository;
    @Autowired
    private OperationLogRepository operationLogRepository;
    @Autowired
    private MinioClient minioClient;
    @Value("${minio.bucket.name}")
    private String bucketName;

    // 下載任務結果
    @GetMapping("/result/{id}")
    public ResponseEntity<?> downloadResult(@PathVariable("id") String submissionId, HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = auth.getName();
        boolean isAdmin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        var recordOpt = taskExecutionRecordRepository.findBySubmissionId(submissionId);
        if (recordOpt.isEmpty()) {
            logDownload(userId, submissionId, request, "NOT_FOUND");
            return ResponseEntity.status(404).body(Map.of("errorCode", "NOT_FOUND", "message", "Task not found"));
        }
        var record = recordOpt.get();
        if (!isAdmin && !record.getUserId().equals(userId)) {
            logDownload(userId, submissionId, request, "ACCESS_DENIED");
            return ResponseEntity.status(403).body(Map.of("errorCode", "ACCESS_DENIED", "message", "You are not authorized to access this task result."));
        }
        String resultPath = record.getResultPath();
        if (resultPath == null) {
            logDownload(userId, submissionId, request, "NO_RESULT");
            return ResponseEntity.status(404).body(Map.of("errorCode", "NO_RESULT", "message", "Result path is null"));
        }
        
        // Improved logging for debugging
        System.out.println("Result path from database: " + resultPath);
        
        // Modified path validation - be more lenient with the extension check
        if (!resultPath.contains(".ipynb")) {
            logDownload(userId, submissionId, request, "NO_RESULT");
            return ResponseEntity.status(404).body(Map.of(
                "errorCode", "INVALID_FORMAT", 
                "message", "Result file does not have .ipynb extension: " + resultPath
            ));
        }
        
        // Better handle the s3:// prefix
        String objectName;
        if (resultPath.startsWith("s3://")) {
            // Format: s3://bucketName/path/to/file.ipynb
            String[] parts = resultPath.substring(5).split("/", 2);
            if (parts.length < 2) {
                return ResponseEntity.status(404).body(Map.of(
                    "errorCode", "INVALID_PATH", 
                    "message", "Invalid S3 path format: " + resultPath
                ));
            }
            // Use bucket from path rather than application properties
            String pathBucket = parts[0];
            objectName = parts[1];
            
            // Debug info
            System.out.println("Using bucket from path: " + pathBucket);
            System.out.println("Object name: " + objectName);
            
            // If bucket in path differs from configured bucket, print warning
            if (!pathBucket.equals(bucketName)) {
                System.out.println("Warning: Bucket in path (" + pathBucket + ") differs from configured bucket (" + bucketName + ")");
            }
        } else {
            // If no s3:// prefix, use the path directly as object name
            objectName = resultPath;
            System.out.println("Using direct object name: " + objectName);
        }
        
        try {
            System.out.println("Attempting to fetch from bucket: " + bucketName + ", object: " + objectName);
            
            InputStream stream = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucketName).object(objectName).build());
            
            InputStreamResource resource = new InputStreamResource(stream);
            String filename = objectName.substring(objectName.lastIndexOf('/') + 1);
            
            System.out.println("File retrieved successfully, sending as: " + filename);
            
            logDownload(userId, submissionId, request, "SUCCESS");
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(resource);
        } catch (Exception e) {
            System.err.println("Error retrieving file: " + e.getMessage());
            e.printStackTrace();
            
            logDownload(userId, submissionId, request, "FAILURE: " + e.getMessage());
            return ResponseEntity.status(404).body(Map.of(
                "errorCode", "RETRIEVAL_ERROR", 
                "message", "Error fetching file: " + e.getMessage(),
                "path", objectName
            ));
        }
    }

    private void logDownload(String userId, String submissionId, HttpServletRequest request, String result) {
        OperationLog log = new OperationLog();
        log.setUserId(userId);
        log.setAction("TASK_RESULT_DOWNLOAD");
        log.setTargetType("TASK");
        log.setTargetId(submissionId);
        log.setResult(result);
        log.setDetail("Download task result: " + submissionId);
        log.setIpAddress(request.getRemoteAddr());
        log.setOperatedAt(LocalDateTime.now());
        operationLogRepository.save(log);
    }
}
