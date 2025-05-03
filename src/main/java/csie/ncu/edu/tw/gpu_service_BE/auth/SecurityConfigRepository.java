package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface SecurityConfigRepository extends JpaRepository<SecurityConfigEntity, Long> {
    Optional<SecurityConfigEntity> findTopByOrderByUpdatedAtDesc();
}
