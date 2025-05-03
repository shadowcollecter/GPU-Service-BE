package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import csie.ncu.edu.tw.gpu_service_BE.auth.TimeAdjustmentLog;
import csie.ncu.edu.tw.gpu_service_BE.auth.TimeAdjustmentLogRepository;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/usage")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUsageController {
    @Autowired
    private UsageService usageService;
    @Autowired
    private TimeAdjustmentLogRepository timeAdjustmentLogRepository;

    @PostMapping("/adjust")
    public ResponseEntity<?> adjustUserTime(@RequestBody Map<String, Object> body, Principal principal) {
        String userId = (String) body.get("userId");
        long amount = ((Number) body.get("amount")).longValue();
        String reason = (String) body.getOrDefault("reason", "");
        String adminId = principal.getName();
        usageService.adjustUserTime(userId, adminId, amount, reason);
        return ResponseEntity.ok(Map.of("message", "User time adjusted successfully"));
    }

    @GetMapping("/adjust-log")
    public ResponseEntity<?> getTimeAdjustmentLog(@RequestParam String userId, Pageable pageable) {
        Page<TimeAdjustmentLog> page = timeAdjustmentLogRepository.findByUserIdOrderByAdjustedAtDesc(userId, pageable);
        return ResponseEntity.ok(Map.of("records", page.getContent(), "total", page.getTotalElements()));
    }
}