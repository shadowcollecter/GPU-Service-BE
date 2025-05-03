package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {
    Page<Announcement> findByStartDateBeforeAndEndDateAfterOrderByPriorityDescStartDateDesc(LocalDateTime now1, LocalDateTime now2, Pageable pageable);
}
