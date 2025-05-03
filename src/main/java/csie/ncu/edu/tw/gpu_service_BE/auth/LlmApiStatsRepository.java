package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmApiStatsRepository extends JpaRepository<LlmApiStats, Long> {
}
