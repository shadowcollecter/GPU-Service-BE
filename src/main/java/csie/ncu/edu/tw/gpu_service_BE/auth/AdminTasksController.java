package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/tasks")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTasksController {
    @Autowired
    private QueueService queueService;

    @GetMapping("/pending")
    public ResponseEntity<?> getPendingTasks(@RequestParam(required = false, defaultValue = "40") Integer limit) {
        // wrap into mutable list before sorting
        List<TaskInfo> tasks = new ArrayList<>(queueService.getPendingTasks(null));
        tasks.sort((a, b) -> b.getSubmissionTime().compareTo(a.getSubmissionTime()));
        // apply limit
        if (limit != null && tasks.size() > limit) {
            tasks = tasks.subList(0, limit);
        }
        // return tasks with fields: submissionId, userId, gpuRequired, vramSize, submissionTime, clientInfo, clientIp
        return ResponseEntity.ok(Map.of("tasks", tasks));
    }
}