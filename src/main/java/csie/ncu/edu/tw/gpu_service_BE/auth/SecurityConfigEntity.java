package csie.ncu.edu.tw.gpu_service_BE.auth;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "security_config")
@Data
public class SecurityConfigEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long configId;

    @Column(nullable = false)
    private int riskThreshold;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String promptTemplate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FallbackPolicy fallbackPolicy;

    @Column(nullable = false, length = 50)
    private String updatedBy;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}