package csie.ncu.edu.tw.gpu_service_BE.auth;

import java.time.LocalDateTime;

public class TaskFilter {
    private String userId;
    private String submissionId;
    private TaskExecutionRecord.Status status;
    private LocalDateTime startDate;
    private LocalDateTime endDate;

    // getters and setters
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSubmissionId() { return submissionId; }
    public void setSubmissionId(String submissionId) { this.submissionId = submissionId; }
    public TaskExecutionRecord.Status getStatus() { return status; }
    public void setStatus(TaskExecutionRecord.Status status) { this.status = status; }
    public LocalDateTime getStartDate() { return startDate; }
    public void setStartDate(LocalDateTime startDate) { this.startDate = startDate; }
    public LocalDateTime getEndDate() { return endDate; }
    public void setEndDate(LocalDateTime endDate) { this.endDate = endDate; }
}