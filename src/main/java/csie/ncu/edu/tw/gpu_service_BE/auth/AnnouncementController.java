package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

@RestController
public class AnnouncementController {
    @Autowired
    private AnnouncementRepository announcementRepository;

    @Autowired
    private OperationLogRepository operationLogRepository;

    private final PolicyFactory htmlPolicy = Sanitizers.FORMATTING.and(Sanitizers.LINKS).and(Sanitizers.BLOCKS).and(Sanitizers.IMAGES);

    // 公告查詢（有效期內，所有人可用）
    @GetMapping("/api/v1/announcements")
    public ResponseEntity<?> listAnnouncements(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        LocalDateTime now = LocalDateTime.now();

        // 輸出診斷日誌
        System.out.println("Fetching announcements at time: " + now);

        Page<Announcement> result = announcementRepository.findByStartDateBeforeAndEndDateAfterOrderByPriorityDescStartDateDesc(now, now, pageable);

        // 輸出找到的公告數量
        System.out.println("Found " + result.getTotalElements() + " active announcements");

        return ResponseEntity.ok(Map.of("announcements", result.getContent(), "total", result.getTotalElements()));
    }

    // 管理員查詢所有公告（不論有效期）
    @GetMapping("/api/v1/admin/announcements/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> listAllAnnouncements(@RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Announcement> result = announcementRepository.findAll(pageable);

        // 加入診斷信息
        LocalDateTime now = LocalDateTime.now();
        StringBuilder debug = new StringBuilder();
        debug.append("Current time: ").append(now).append("\n");

        for (Announcement a : result.getContent()) {
            debug.append("ID: ").append(a.getId())
                 .append(", Title: ").append(a.getTitle())
                 .append(", StartDate: ").append(a.getStartDate())
                 .append(", EndDate: ").append(a.getEndDate())
                 .append(", IsValid: ").append(a.getStartDate().isBefore(now) && a.getEndDate().isAfter(now))
                 .append("\n");
        }

        return ResponseEntity.ok(Map.of(
            "announcements", result.getContent(), 
            "total", result.getTotalElements(),
            "debug", debug.toString()
        ));
    }

    // 新增公告（僅 ADMIN）
    @PostMapping("/api/v1/admin/announcement")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> createAnnouncement(@RequestBody Map<String, Object> body, Principal principal, HttpServletRequest request) {
        Announcement a = new Announcement();
        a.setTitle((String) body.get("title"));
        a.setContent(htmlPolicy.sanitize((String) body.get("content")));
        a.setPriority(Announcement.Priority.valueOf(((String) body.getOrDefault("priority", "NORMAL")).toUpperCase()));
        String sd = (String) body.get("startDate");
        String ed = (String) body.get("endDate");
        a.setStartDate(LocalDate.parse(sd).atStartOfDay());
        a.setEndDate(LocalDate.parse(ed).atTime(LocalTime.MAX));
        a.setCreatedBy(principal.getName());
        a.setCreatedAt(LocalDateTime.now());
        Announcement saved = announcementRepository.save(a);
        // 寫入操作日誌
        OperationLog log = new OperationLog();
        log.setUserId(principal.getName());
        log.setAction("ANNOUNCEMENT_CREATE");
        log.setTargetType("ANNOUNCEMENT");
        log.setTargetId(saved.getId().toString());
        log.setResult("SUCCESS");
        log.setDetail("Created announcement: " + saved.getTitle());
        log.setIpAddress(request.getRemoteAddr());
        log.setOperatedAt(LocalDateTime.now());
        operationLogRepository.save(log);
        return ResponseEntity.ok(Map.of("id", saved.getId(), "message", "Announcement created"));
    }

    // 編輯公告（僅 ADMIN）
    @PutMapping("/api/v1/admin/announcement/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateAnnouncement(@PathVariable Long id, @RequestBody Map<String, Object> body, Principal principal, HttpServletRequest request) {
        Announcement a = announcementRepository.findById(id).orElseThrow();
        a.setTitle((String) body.get("title"));
        a.setContent(htmlPolicy.sanitize((String) body.get("content")));
        a.setPriority(Announcement.Priority.valueOf(((String) body.getOrDefault("priority", "NORMAL")).toUpperCase()));
        String sd = (String) body.get("startDate");
        String ed = (String) body.get("endDate");
        a.setStartDate(LocalDate.parse(sd).atStartOfDay());
        a.setEndDate(LocalDate.parse(ed).atTime(LocalTime.MAX));
        a.setUpdatedAt(LocalDateTime.now());
        Announcement saved = announcementRepository.save(a);
        // 寫入操作日誌
        OperationLog log = new OperationLog();
        log.setUserId(principal.getName());
        log.setAction("ANNOUNCEMENT_UPDATE");
        log.setTargetType("ANNOUNCEMENT");
        log.setTargetId(saved.getId().toString());
        log.setResult("SUCCESS");
        log.setDetail("Updated announcement: " + saved.getTitle());
        log.setIpAddress(request.getRemoteAddr());
        log.setOperatedAt(LocalDateTime.now());
        operationLogRepository.save(log);
        return ResponseEntity.ok(Map.of("message", "Announcement updated"));
    }

    // 刪除公告（僅 ADMIN）
    @DeleteMapping("/api/v1/admin/announcement/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteAnnouncement(@PathVariable Long id, Principal principal, HttpServletRequest request) {
        announcementRepository.deleteById(id);
        // 寫入操作日誌
        OperationLog log = new OperationLog();
        log.setUserId(principal.getName());
        log.setAction("ANNOUNCEMENT_DELETE");
        log.setTargetType("ANNOUNCEMENT");
        log.setTargetId(id.toString());
        log.setResult("SUCCESS");
        log.setDetail("Deleted announcement id: " + id);
        log.setIpAddress(request.getRemoteAddr());
        log.setOperatedAt(LocalDateTime.now());
        operationLogRepository.save(log);
        return ResponseEntity.ok(Map.of("message", "Announcement deleted"));
    }
}
