package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TaskExecutionRecordRepository extends JpaRepository<TaskExecutionRecord, Long>, JpaSpecificationExecutor<TaskExecutionRecord> {
    Page<TaskExecutionRecord> findByUserIdOrderByStartTimeDesc(String userId, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT t.userId) FROM TaskExecutionRecord t WHERE (:start IS NULL OR t.startTime >= :start) AND (:end IS NULL OR t.startTime <= :end)")
    long countActiveUsers(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT t.status, COUNT(t) FROM TaskExecutionRecord t WHERE (:start IS NULL OR t.startTime >= :start) AND (:end IS NULL OR t.startTime <= :end) GROUP BY t.status")
    List<Object[]> countTaskStatusGroup(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT t.rejectionReason, COUNT(t) FROM TaskExecutionRecord t WHERE t.status = 'REJECTED' AND (:start IS NULL OR t.startTime >= :start) AND (:end IS NULL OR t.startTime <= :end) GROUP BY t.rejectionReason")
    List<Object[]> countRejectionReasonGroup(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(t) FROM TaskExecutionRecord t WHERE t.status = 'REJECTED' AND (:start IS NULL OR t.startTime >= :start) AND (:end IS NULL OR t.startTime <= :end)")
    long countRejectedTasks(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT MAX(t.endTime) FROM TaskExecutionRecord t WHERE t.status = 'REJECTED' AND (:start IS NULL OR t.startTime >= :start) AND (:end IS NULL OR t.startTime <= :end)")
    LocalDateTime findLastRejectedTime(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    Optional<TaskExecutionRecord> findBySubmissionId(String submissionId);
    void deleteByEndTimeBefore(LocalDateTime endTime);

    Page<TaskExecutionRecord> findByStartTimeBetweenOrderByStartTimeDesc(LocalDateTime startTime, LocalDateTime endTime, Pageable pageable);

    // count total tasks by userId for admin user listing
    long countByUserId(String userId);

    Page<TaskExecutionRecord> findByUserId(String userId, Pageable pageable);

    Page<TaskExecutionRecord> findByStatusOrderByCreatedAtDesc(TaskExecutionRecord.Status status, Pageable pageable);
    
    // New methods for queue status tracking
    long countByStatusAndResourceType(TaskExecutionRecord.Status status, TaskExecutionRecord.ResourceType resourceType);
    
    long countByStatusAndResourceTypeAndGpuType(TaskExecutionRecord.Status status, TaskExecutionRecord.ResourceType resourceType, String gpuType);
    
    // Method to find all tasks with a specific status
    List<TaskExecutionRecord> findByStatus(TaskExecutionRecord.Status status);
}