package csie.ncu.edu.tw.gpu_service_BE.auth;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "security_check_log")
public class SecurityCheckLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long checkId;

    @Column(nullable = false, length = 50)
    private String submissionId;

    @Column(nullable = false, length = 50)
    private String userId;

    @Column(nullable = false)
    private int riskScore;

    @Column(columnDefinition = "TEXT")
    private String riskDescription;

    @Column(nullable = false, length = 20)
    private String actionTaken; // ALLOWED, REJECTED, ALLOWED_FALLBACK

    @Column(nullable = false, length = 20)
    private String checkStatus; // SUCCESS, FAILED

    @Column(nullable = false)
    private int retryCount = 0;

    @Column(nullable = false)
    private LocalDateTime checkedAt;
}
