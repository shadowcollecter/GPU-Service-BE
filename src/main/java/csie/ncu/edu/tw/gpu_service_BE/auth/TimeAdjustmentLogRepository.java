package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;

public interface TimeAdjustmentLogRepository extends JpaRepository<TimeAdjustmentLog, Long> {
    Page<TimeAdjustmentLog> findByUserIdOrderByAdjustedAtDesc(String userId, Pageable pageable);
    void deleteByAdjustedAtBefore(LocalDateTime adjustedAt);
}
