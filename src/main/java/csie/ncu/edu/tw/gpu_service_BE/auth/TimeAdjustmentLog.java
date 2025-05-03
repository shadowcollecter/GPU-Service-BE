package csie.ncu.edu.tw.gpu_service_BE.auth;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "time_adjustment_log")
public class TimeAdjustmentLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "adjustment_id")
    private Long adjustmentId;

    @Column(name = "user_id", length = 50, nullable = false)
    private String userId;

    @Column(name = "admin_id", length = 50, nullable = false)
    private String adminId;

    @Column(name = "adjustment_amount", nullable = false)
    private long adjustmentAmount;

    @Column(name = "adjustment_reason")
    private String adjustmentReason;

    @Column(name = "adjusted_at", nullable = false)
    private LocalDateTime adjustedAt;

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setAdminId(String adminId) {
        this.adminId = adminId;
    }

    public void setAdjustmentAmount(long adjustmentAmount) {
        this.adjustmentAmount = adjustmentAmount;
    }

    public void setAdjustmentReason(String adjustmentReason) {
        this.adjustmentReason = adjustmentReason;
    }

    public void setAdjustedAt(LocalDateTime adjustedAt) {
        this.adjustedAt = adjustedAt;
    }
}
