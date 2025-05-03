package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LlmApiKeyRepository extends JpaRepository<LlmApiKey, Long> {
    List<LlmApiKey> findByActiveTrue();
}
