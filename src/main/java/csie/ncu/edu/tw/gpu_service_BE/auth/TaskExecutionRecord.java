package csie.ncu.edu.tw.gpu_service_BE.auth;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "task_execution_record")
public class TaskExecutionRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long recordId;

    @Column(nullable = false, length = 50, unique = true)
    private String submissionId;

    @Column(nullable = false, length = 50)
    private String userId;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(nullable = false)
    private Long duration = 0L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(length = 20000)
    private String rejectionReason;

    @Column(name = "result_path", length = 1024)
    private String resultPath;

    @Column(name = "original_path", length = 1024)
    private String originalPath;

    // requested resource and parameters
    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", length = 10)
    private ResourceType resourceType;

    @Column(name = "vram_size")
    private Double vramSize;

    @Column(name = "gpu_type", length = 100)
    private String gpuType;

    // security scan results
    @Column(name = "risk_score")
    private Integer riskScore;

    @Column(name = "risk_message", length = 20000)
    private String riskMessage;

    public enum Status {
        PENDING, SCHEDULED, RUNNING, COMPLETED, FAILED, CANCELLED, REJECTED, TIMEOUT
    }

    public enum ResourceType {
        CPU, GPU
    }

    public String getGpuType() {
        return gpuType;
    }

    public void setGpuType(String gpuType) {
        this.gpuType = gpuType;
    }
}
