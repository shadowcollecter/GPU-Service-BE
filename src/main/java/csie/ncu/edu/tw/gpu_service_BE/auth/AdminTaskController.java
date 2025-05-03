package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/task")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTaskController {
    @Autowired
    private QueueService queueService;

    // Admin cancels a pending task (per API.md)
    @PostMapping("/cancel/{id}")
    public ResponseEntity<?> cancelTask(@PathVariable("id") String id) {
        boolean removed = queueService.removeTask(id);
        if (removed) {
            return ResponseEntity.ok(Map.of("message", "Task cancelled"));
        }
        return ResponseEntity.status(404)
                .body(Map.of("errorCode", "NOT_FOUND", "message", "Task not found in queue"));
    }
}