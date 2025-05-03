package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.security.Principal;
import java.util.Map;


// import csie.ncu.edu.tw.gpu_service_BE.auth.TaskExecutionRecordRepository;


@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {
    @Autowired
    private UsageService usageService;

    @Autowired
    private TaskExecutionRecordRepository taskExecutionRecordRepository;
    @Autowired
    private TimeAdjustmentLogRepository timeAdjustmentLogRepository;

    @GetMapping("/current")
    public ResponseEntity<?> getCurrentUsage(Principal principal) {
        String userId = principal.getName();
        return usageService.getCurrentUsage(userId)
                .map(summary -> Map.of(
                        "totalUsedTime", summary.getTotalUsedTime(),
                        "remainingTime", summary.getRemainingTime(),
                        "periodStart", summary.getTimePeriodStart(),
                        "periodEnd", summary.getTimePeriodEnd()
                ))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/history")
    public ResponseEntity<?> getUsageHistory(Principal principal, Pageable pageable) {
        String userId = principal.getName();
        Page<TaskExecutionRecord> page = taskExecutionRecordRepository.findByUserIdOrderByStartTimeDesc(userId, pageable);
        return ResponseEntity.ok(page);
    }

    @PostMapping("/admin/adjust")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> adjustUserTime(@RequestBody Map<String, Object> body, Principal principal) {
        String userId = (String) body.get("userId");
        long amount = ((Number) body.get("amount")).longValue();
        String reason = (String) body.getOrDefault("reason", "");
        String adminId = principal.getName();
        usageService.adjustUserTime(userId, adminId, amount, reason);
        return ResponseEntity.ok(Map.of("message", "User time adjusted successfully"));
    }

    @GetMapping("/admin/adjust-log")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getTimeAdjustmentLog(@RequestParam String userId, Pageable pageable) {
        Page<TimeAdjustmentLog> page = timeAdjustmentLogRepository.findByUserIdOrderByAdjustedAtDesc(userId, pageable);
        return ResponseEntity.ok(page);
    }
}
