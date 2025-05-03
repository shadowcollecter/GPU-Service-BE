package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/v1/admin/operation-log")
public class OperationLogController {
    @Autowired
    private OperationLogRepository operationLogRepository;

    // 查詢所有操作日誌（分頁，僅管理員）
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getAllLogs(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<OperationLog> logs = operationLogRepository.findAllByOrderByOperatedAtDesc(pageable);
        return ResponseEntity.ok(logs);
    }

    // 查詢指定使用者操作日誌（分頁，僅管理員）
    @GetMapping("/user/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getUserLogs(@PathVariable String userId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<OperationLog> logs = operationLogRepository.findByUserIdOrderByOperatedAtDesc(userId, pageable);
        return ResponseEntity.ok(logs);
    }

    // 查詢指定操作類型日誌（分頁，僅管理員）
    @GetMapping("/action/{action}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getActionLogs(@PathVariable String action,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<OperationLog> logs = operationLogRepository.findByActionOrderByOperatedAtDesc(action, pageable);
        return ResponseEntity.ok(logs);
    }

    // 複合查詢與關鍵字搜尋（分頁，僅管理員）
    @GetMapping("/search")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> searchLogs(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        Specification<OperationLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (userId != null && !userId.isEmpty())
                predicates.add(cb.equal(root.get("userId"), userId));
            if (action != null && !action.isEmpty())
                predicates.add(cb.equal(root.get("action"), action));
            if (targetType != null && !targetType.isEmpty())
                predicates.add(cb.equal(root.get("targetType"), targetType));
            if (keyword != null && !keyword.isEmpty()) {
                String like = "%" + keyword + "%";
                predicates.add(cb.or(
                    cb.like(root.get("detail"), like),
                    cb.like(root.get("targetId"), like)
                ));
            }
            if (from != null && !from.isEmpty())
                predicates.add(cb.greaterThanOrEqualTo(root.get("operatedAt"), LocalDateTime.parse(from)));
            if (to != null && !to.isEmpty())
                predicates.add(cb.lessThanOrEqualTo(root.get("operatedAt"), LocalDateTime.parse(to)));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<OperationLog> logs = operationLogRepository.findAll(spec, pageable);
        return ResponseEntity.ok(logs);
    }

    // 匯出操作日誌（CSV，僅管理員）
    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN')")
    public void exportLogs(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            HttpServletResponse response
    ) throws Exception {
        Specification<OperationLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (userId != null && !userId.isEmpty())
                predicates.add(cb.equal(root.get("userId"), userId));
            if (action != null && !action.isEmpty())
                predicates.add(cb.equal(root.get("action"), action));
            if (targetType != null && !targetType.isEmpty())
                predicates.add(cb.equal(root.get("targetType"), targetType));
            if (keyword != null && !keyword.isEmpty()) {
                String like = "%" + keyword + "%";
                predicates.add(cb.or(
                    cb.like(root.get("detail"), like),
                    cb.like(root.get("targetId"), like)
                ));
            }
            if (from != null && !from.isEmpty())
                predicates.add(cb.greaterThanOrEqualTo(root.get("operatedAt"), LocalDateTime.parse(from)));
            if (to != null && !to.isEmpty())
                predicates.add(cb.lessThanOrEqualTo(root.get("operatedAt"), LocalDateTime.parse(to)));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        List<OperationLog> logs = operationLogRepository.findAll(spec);
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=operation_logs.csv");
        var writer = response.getWriter();
        writer.println("logId,userId,action,targetType,targetId,result,detail,ipAddress,operatedAt");
        for (OperationLog log : logs) {
            writer.printf("%d,%s,%s,%s,%s,%s,%s,%s,%s\n",
                log.getLogId(),
                log.getUserId(),
                log.getAction(),
                log.getTargetType(),
                log.getTargetId(),
                log.getResult(),
                log.getDetail() != null ? log.getDetail().replaceAll(",", " ") : "",
                log.getIpAddress(),
                log.getOperatedAt()
            );
        }
        writer.flush();
    }
}
