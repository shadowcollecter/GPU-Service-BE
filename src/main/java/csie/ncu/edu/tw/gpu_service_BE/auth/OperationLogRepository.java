package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long>, JpaSpecificationExecutor<OperationLog> {
    Page<OperationLog> findByUserIdOrderByOperatedAtDesc(String userId, Pageable pageable);
    Page<OperationLog> findByActionOrderByOperatedAtDesc(String action, Pageable pageable);
    Page<OperationLog> findAllByOrderByOperatedAtDesc(Pageable pageable);
}
