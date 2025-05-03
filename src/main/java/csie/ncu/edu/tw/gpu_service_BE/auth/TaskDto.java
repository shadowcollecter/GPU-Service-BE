package csie.ncu.edu.tw.gpu_service_BE.auth;

import java.time.LocalDateTime;

public class TaskDto {
    private Long recordId;
    private String submissionId;
    private String userId;
    private TaskExecutionRecord.Status status;
    private TaskExecutionRecord.ResourceType resourceType;
    private Double vramSize;
    private LocalDateTime createdAt;
    private Long duration;
    private Integer riskScore;
    private String riskMessage;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String resultPath;

    // getters and setters
    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public String getSubmissionId() { return submissionId; }
    public void setSubmissionId(String submissionId) { this.submissionId = submissionId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public TaskExecutionRecord.Status getStatus() { return status; }
    public void setStatus(TaskExecutionRecord.Status status) { this.status = status; }
    public TaskExecutionRecord.ResourceType getResourceType() { return resourceType; }
    public void setResourceType(TaskExecutionRecord.ResourceType resourceType) { this.resourceType = resourceType; }
    public Double getVramSize() { return vramSize; }
    public void setVramSize(Double vramSize) { this.vramSize = vramSize; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Long getDuration() { return duration; }
    public void setDuration(Long duration) { this.duration = duration; }
    public Integer getRiskScore() { return riskScore; }
    public void setRiskScore(Integer riskScore) { this.riskScore = riskScore; }
    public String getRiskMessage() { return riskMessage; }
    public void setRiskMessage(String riskMessage) { this.riskMessage = riskMessage; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public String getResultPath() { return resultPath; }
    public void setResultPath(String resultPath) { this.resultPath = resultPath; }
}