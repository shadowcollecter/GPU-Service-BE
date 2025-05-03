package csie.ncu.edu.tw.gpu_service_BE.auth;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "llm_api_key")
public class LlmApiKey {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String apiKey;

    @Column(nullable = false, length = 50)
    private String model;

    @Column(nullable = false)
    private boolean active = true;
}
