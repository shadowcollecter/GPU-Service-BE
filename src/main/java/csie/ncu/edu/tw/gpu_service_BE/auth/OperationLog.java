package csie.ncu.edu.tw.gpu_service_BE.auth;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "operation_log")
public class OperationLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long logId;

    @Column(nullable = false, length = 50)
    private String userId;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(length = 50)
    private String targetType;

    @Column(length = 50)
    private String targetId;

    @Column(nullable = false, length = 10)
    private String result;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(length = 45)
    private String ipAddress;

    @Column(nullable = false)
    private LocalDateTime operatedAt;

    public OperationLog() {
        // Default constructor for compatibility
    }

    public OperationLog(String userId, String action, String targetType, String targetId, String result, String ipAddress, LocalDateTime operatedAt) {
        this.userId = userId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.result = result;
        this.ipAddress = ipAddress;
        this.operatedAt = operatedAt;
    }
}
