package csie.ncu.edu.tw.gpu_service_BE.auth;

public class TaskSubmissionResponse {
    private String submissionId;
    private String status;
    private int queuePosition;
    private int estimatedWaitTime;
    private boolean rejected;
    private int statusCode;
    private String errorCode;
    private String message;
    private Double riskScore;

    // getters and setters
    public String getSubmissionId() { return submissionId; }
    public void setSubmissionId(String submissionId) { this.submissionId = submissionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getQueuePosition() { return queuePosition; }
    public void setQueuePosition(int queuePosition) { this.queuePosition = queuePosition; }
    public int getEstimatedWaitTime() { return estimatedWaitTime; }
    public void setEstimatedWaitTime(int estimatedWaitTime) { this.estimatedWaitTime = estimatedWaitTime; }
    public boolean isRejected() { return rejected; }
    public void setRejected(boolean rejected) { this.rejected = rejected; }
    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Double getRiskScore() { return riskScore; }
    public void setRiskScore(Double riskScore) { this.riskScore = riskScore; }
}