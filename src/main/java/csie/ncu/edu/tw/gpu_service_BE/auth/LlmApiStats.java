package csie.ncu.edu.tw.gpu_service_BE.auth;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "llm_api_stats")
public class LlmApiStats {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long statsId;

    @Column(nullable = false)
    private long totalCalls = 0;

    @Column(nullable = false)
    private long successfulCalls = 0;

    @Column(nullable = false)
    private long failedCalls = 0;

    @Column(nullable = false)
    private long totalResponseTime = 0;

    @Column(length = 255)
    private String lastFailure;

    // getters and setters ...
}
