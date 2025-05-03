package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;

public interface SecurityCheckLogRepository extends JpaRepository<SecurityCheckLog, Long> {
    Page<SecurityCheckLog> findByRiskScoreGreaterThanEqual(int riskScore, Pageable pageable);
    Page<SecurityCheckLog> findByCheckedAtBetween(LocalDateTime start, LocalDateTime end, Pageable pageable);
    Page<SecurityCheckLog> findByRiskScoreGreaterThanEqualAndCheckedAtBetween(int riskScore, LocalDateTime start, LocalDateTime end, Pageable pageable);
    Page<SecurityCheckLog> findAll(Pageable pageable);
}
