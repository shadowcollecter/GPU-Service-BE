package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.jpa.domain.Specification;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.persistence.criteria.Predicate;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/report")
public class AdminReportController {
    @Autowired
    private TaskExecutionRecordRepository taskExecutionRecordRepository;
    @Autowired
    private UserUsageSummaryRepository userUsageSummaryRepository;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getUsageReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        // convert LocalDate to LocalDateTime (full-day range)
        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime end = endDate != null ? endDate.atTime(LocalTime.MAX) : null;
        Page<TaskExecutionRecord> records = (start != null && end != null)
                ? taskExecutionRecordRepository.findByStartTimeBetweenOrderByStartTimeDesc(start, end, pageable)
                : taskExecutionRecordRepository.findAll(pageable);
        long totalUsedTime = records.stream().mapToLong(r -> r.getDuration() != null ? r.getDuration() : 0).sum();
        Map<String, Object> report = new HashMap<>();
        report.put("records", records.getContent());
        report.put("total", records.getTotalElements());
        report.put("totalUsedTime", totalUsedTime);
        report.put("page", page);
        report.put("size", size);
        return ResponseEntity.ok(report);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getReportSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        // convert LocalDate to LocalDateTime (full-day range)
        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime end = endDate != null ? endDate.atTime(LocalTime.MAX) : null;
        long totalUsers = userUsageSummaryRepository.count();
        long activeUsers = taskExecutionRecordRepository.countActiveUsers(start, end);
        long totalTasks = (start != null || end != null)
            ? taskExecutionRecordRepository.count((Specification<TaskExecutionRecord>) (root, query, cb) -> {
                Predicate p = cb.conjunction();
                if (start != null) p = cb.and(p, cb.greaterThanOrEqualTo(root.get("startTime"), start));
                if (end != null) p = cb.and(p, cb.lessThanOrEqualTo(root.get("startTime"), end));
                return p;
            })
            : taskExecutionRecordRepository.count();
        long totalUsedTime = (start != null || end != null)
            ? taskExecutionRecordRepository.findAll((Specification<TaskExecutionRecord>) (root, query, cb) -> {
                Predicate p = cb.conjunction();
                if (start != null) p = cb.and(p, cb.greaterThanOrEqualTo(root.get("startTime"), start));
                if (end != null) p = cb.and(p, cb.lessThanOrEqualTo(root.get("startTime"), end));
                return p;
            }).stream().mapToLong(r -> r.getDuration() != null ? r.getDuration() : 0).sum()
            : taskExecutionRecordRepository.findAll().stream().mapToLong(r -> r.getDuration() != null ? r.getDuration() : 0).sum();
        // 狀態分布
        List<Object[]> statusGroup = taskExecutionRecordRepository.countTaskStatusGroup(start, end);
        Map<String, Long> statusStats = statusGroup.stream().collect(Collectors.toMap(
            arr -> arr[0] != null ? arr[0].toString() : "UNKNOWN",
            arr -> (Long) arr[1]
        ));
        // 拒絕原因分布
        List<Object[]> rejectionGroup = taskExecutionRecordRepository.countRejectionReasonGroup(start, end);
        Map<String, Long> rejectionStats = rejectionGroup.stream().collect(Collectors.toMap(
            arr -> arr[0] != null ? arr[0].toString() : "UNKNOWN",
            arr -> (Long) arr[1]
        ));
        long rejectedTasks = taskExecutionRecordRepository.countRejectedTasks(start, end);
        LocalDateTime lastRejected = taskExecutionRecordRepository.findLastRejectedTime(start, end);
        // 剩餘時長分布
        List<UserUsageSummary> summaries = userUsageSummaryRepository.findAll();
        long remainOver50h = summaries.stream().filter(s -> s.getRemainingTime() >= 180000).count();
        long remain20to50h = summaries.stream().filter(s -> s.getRemainingTime() >= 72000 && s.getRemainingTime() < 180000).count();
        long remain10to20h = summaries.stream().filter(s -> s.getRemainingTime() >= 36000 && s.getRemainingTime() < 72000).count();
        long remain1to10h = summaries.stream().filter(s -> s.getRemainingTime() >= 3600 && s.getRemainingTime() < 36000).count();
        long remain0h = summaries.stream().filter(s -> s.getRemainingTime() < 3600).count();
        Map<String, Object> result = new HashMap<>();
        result.put("totalUsers", totalUsers);
        result.put("activeUsers", activeUsers);
        result.put("totalTasks", totalTasks);
        result.put("totalUsedTime", totalUsedTime);
        result.put("statusStats", statusStats);
        result.put("rejectionStats", rejectionStats);
        result.put("rejectedTasks", rejectedTasks);
        result.put("lastRejected", lastRejected);
        result.put("remainOver50h", remainOver50h);
        result.put("remain20to50h", remain20to50h);
        result.put("remain10to20h", remain10to20h);
        result.put("remain1to10h", remain1to10h);
        result.put("remain0h", remain0h);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN')")
    public void exportReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletResponse response
    ) throws Exception {
        // convert LocalDate to LocalDateTime (full-day range)
        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime end = endDate != null ? endDate.atTime(LocalTime.MAX) : null;
        List<TaskExecutionRecord> records = (start != null || end != null)
            ? taskExecutionRecordRepository.findAll((Specification<TaskExecutionRecord>) (root, query, cb) -> {
                Predicate p = cb.conjunction();
                if (start != null) p = cb.and(p, cb.greaterThanOrEqualTo(root.get("startTime"), start));
                if (end != null) p = cb.and(p, cb.lessThanOrEqualTo(root.get("startTime"), end));
                return p;
            })
            : taskExecutionRecordRepository.findAll();
        response.setContentType("text/csv");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report.csv");
        var writer = response.getWriter();
        writer.println("recordId,userId,submissionId,startTime,endTime,duration,status");
        for (TaskExecutionRecord r : records) {
            writer.printf("%d,%s,%s,%s,%s,%d,%s\n",
                r.getRecordId(),
                r.getUserId(),
                r.getSubmissionId(),
                r.getStartTime(),
                r.getEndTime(),
                r.getDuration() != null ? r.getDuration() : 0,
                r.getStatus()
            );
        }
        writer.flush();
    }
}
