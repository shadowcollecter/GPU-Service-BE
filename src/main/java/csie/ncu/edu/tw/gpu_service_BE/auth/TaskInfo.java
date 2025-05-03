package csie.ncu.edu.tw.gpu_service_BE.auth;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class TaskInfo {
    private final String submissionId;
    private final String userId;
    private final boolean gpuRequired;
    private final Double vramSize;
    private final String gpuType;
    private final Instant submissionTime;
    private final String clientInfo;
    private final String clientIp;

    @JsonCreator
    public TaskInfo(
        @JsonProperty("submissionId") String submissionId,
        @JsonProperty("userId") String userId,
        @JsonProperty("gpuRequired") boolean gpuRequired,
        @JsonProperty("vramSize") Double vramSize,
        @JsonProperty("gpuType") String gpuType,
        @JsonProperty("submissionTime") Instant submissionTime,
        @JsonProperty("clientInfo") String clientInfo,
        @JsonProperty("clientIp") String clientIp) {
        this.submissionId = submissionId;
        this.userId = userId;
        this.gpuRequired = gpuRequired;
        this.vramSize = vramSize;
        this.gpuType = gpuType;
        this.submissionTime = submissionTime;
        this.clientInfo = clientInfo;
        this.clientIp = clientIp;
    }

    // getters
    public String getSubmissionId() { return submissionId; }
    public String getUserId() { return userId; }
    public boolean isGpuRequired() { return gpuRequired; }
    public Double getVramSize() { return vramSize; }
    public String getGpuType() { return gpuType; }
    public Instant getSubmissionTime() { return submissionTime; }
    public String getClientInfo() { return clientInfo; }
    public String getClientIp() { return clientIp; }
}
