package csie.ncu.edu.tw.gpu_service_BE.auth;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "user_usage_summary")
public class UserUsageSummary {
    @Id
    @Column(name = "user_id", length = 50)
    private String userId;

    @Column(name = "total_used_time", nullable = false)
    private long totalUsedTime = 0;

    @Column(name = "remaining_time", nullable = false)
    private long remainingTime = 360000; // 100小時

    @Column(name = "time_period_start", nullable = false)
    private LocalDateTime timePeriodStart;

    @Column(name = "time_period_end", nullable = false)
    private LocalDateTime timePeriodEnd;

    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;
}
