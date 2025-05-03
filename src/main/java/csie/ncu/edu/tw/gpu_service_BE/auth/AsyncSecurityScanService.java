package csie.ncu.edu.tw.gpu_service_BE.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kubernetes.client.openapi.ApiException;
import io.minio.MinioClient;
import io.minio.GetObjectArgs;
import io.minio.PutObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.messages.Item;
import io.minio.errors.MinioException;
import io.minio.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.models.V1Job;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Map;
import java.util.ArrayList;
import java.io.IOException;

import csie.ncu.edu.tw.gpu_service_BE.auth.SecurityCheckLog;
import csie.ncu.edu.tw.gpu_service_BE.auth.SecurityCheckLogRepository;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskExecutionRecord;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskExecutionRecordRepository;
import csie.ncu.edu.tw.gpu_service_BE.auth.SecurityConfigService;
import csie.ncu.edu.tw.gpu_service_BE.auth.QueueService;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskInfo;
import csie.ncu.edu.tw.gpu_service_BE.config.GpuConfigProperties;

@Service
public class AsyncSecurityScanService {
    private static final Logger logger = LoggerFactory.getLogger(AsyncSecurityScanService.class);

    @Autowired
    private MinioClient minioClient;
    @Autowired
    private SecurityCheckService securityCheckService;
    @Autowired
    private SecurityCheckLogRepository securityCheckLogRepository;
    @Autowired
    private TaskExecutionRecordRepository taskRecordRepo;
    @Autowired
    private SecurityConfigService securityConfigService;
    @Autowired
    private LlmHealthCheckService llmHealthCheckService;
    @Autowired
    private QueueService queueService;
    @Autowired
    private MinioPresignService presignService;
    @Autowired
    private BatchV1Api batchV1Api;
    @Autowired
    private GpuConfigProperties gpuConfig;
    
    @Value("${minio.bucket.name}")
    private String bucketName;
    
    @Value("${job.template.dir:customize-yaml}")
    private String jobTemplateDir;
    
    @Value("${job.image:shadowcollect/ipynb-runner:latest}")
    private String jobImage;
    
    @Value("${kubernetes.namespace}")
    private String k8sNamespace;
    
    @Value("${task.submit.mode:queue}")
    private String taskSubmitMode; // Options: "queue" or "direct"
    
    private final ObjectMapper mapper = new ObjectMapper();

    @Async
    public void scan(String userId, String submissionId, String filename) {
        logger.debug("Starting async LLM security scan for user={} submission={} file={}", userId, submissionId, filename);
        try {
            // direct object key path based on composite submissionId
            String objectKey = String.format("%s/%s/%s", userId, submissionId, filename);
            // download the notebook
            try (InputStream is = minioClient.getObject(
                    GetObjectArgs.builder().bucket(bucketName).object(objectKey).build())) {
                byte[] content = is.readAllBytes();
                logger.debug("Downloaded file from Minio: {} bytes", content.length);
                MultipartFile file = new MultipartFile() {
                    public String getName() { return filename; }
                    public String getOriginalFilename() { return filename; }
                    public String getContentType() { return null; }
                    public boolean isEmpty() { return content.length == 0; }
                    public long getSize() { return content.length; }
                    public byte[] getBytes() { return content; }
                    public InputStream getInputStream() { return new ByteArrayInputStream(content); }
                    public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), content); }
                };

                // extract code cells
                ObjectMapper mapper2 = new ObjectMapper();
                JsonNode root = mapper2.readTree(new ByteArrayInputStream(content));
                ArrayList<String> codeBlocks = new ArrayList<>();
                JsonNode cells = root.path("cells");
                if (cells.isArray()) {
                    for (JsonNode cell : cells) {
                        if ("code".equals(cell.path("cell_type").asText())) {
                            StringBuilder sb = new StringBuilder();
                            for (JsonNode line : cell.path("source")) sb.append(line.asText());
                            codeBlocks.add(sb.toString());
                        }
                    }
                }
                String combined = String.join("\n", codeBlocks);
                logger.debug("Extracted code for analysis: {}", combined);

                // call full JSON analysis
                JsonNode analysis = llmHealthCheckService.analyzeRiskFull(submissionId, combined);
                if (analysis == null) {
                    logger.error("LLM analysis returned null for submission={}", submissionId);
                }

                // build result JSON
                ObjectNode resultNode = mapper2.createObjectNode();
                resultNode.put("submissionId", submissionId);
                resultNode.put("checkedAt", LocalDateTime.now().toString());
                // always include full analysis fields
                int riskScoreNode = analysis != null ? analysis.path("risk_score").asInt(-1) : -1;
                resultNode.put("risk_score", riskScoreNode);
                JsonNode threatsNode = analysis != null ? analysis.path("threat_analysis") : mapper2.createArrayNode();
                resultNode.set("threat_analysis", threatsNode);
                JsonNode suggestNode = analysis != null ? analysis.path("mitigation_suggestions") : mapper2.createArrayNode();
                resultNode.set("mitigation_suggestions", suggestNode);
                String confLevel = analysis != null ? analysis.path("confidence_level").asText("") : "";
                resultNode.put("confidence_level", confLevel);
                byte[] bytes2 = mapper2.writeValueAsBytes(resultNode);
                String resultObject = String.format("%s/%s/scanner-result.json", userId, submissionId);
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(resultObject)
                        .stream(new ByteArrayInputStream(bytes2), bytes2.length, -1)
                        .contentType("application/json")
                        .build());
                logger.debug("Stored detailed scan result JSON to Minio at object={}", resultObject);

                // parse fields for log
                int riskScore = analysis != null ? analysis.path("risk_score").asInt(-1) : -1;
                JsonNode threats = analysis != null ? analysis.path("threat_analysis") : mapper2.createArrayNode();
                JsonNode suggestions = analysis != null ? analysis.path("mitigation_suggestions") : mapper2.createArrayNode();
                String confidence = analysis != null ? analysis.path("confidence_level").asText() : null;

                // build detail map safely to avoid null entries
                java.util.Map<String, Object> detailMap = new java.util.LinkedHashMap<>();
                detailMap.put("threat_analysis", threats);
                detailMap.put("mitigation_suggestions", suggestions);
                detailMap.put("confidence_level", confidence != null ? confidence : "");
                String fullDetail = mapper2.writeValueAsString(detailMap);

                // now parse and persist security check log
                int threshold = securityConfigService.getLatestConfig().getRiskThreshold();
                String action = (riskScore > threshold) ? "REJECTED" : "ALLOWED";
                SecurityCheckLog log = new SecurityCheckLog();
                log.setSubmissionId(submissionId);
                log.setUserId(userId);
                log.setRiskScore(riskScore);
                log.setRiskDescription(fullDetail);
                log.setActionTaken(action);
                log.setCheckStatus("SUCCESS");
                log.setCheckedAt(LocalDateTime.now());
                securityCheckLogRepository.save(log);

                // update task execution record with security scan results
                taskRecordRepo.findBySubmissionId(submissionId).ifPresent(rec -> {
                    rec.setRiskScore(riskScore);
                    rec.setRiskMessage(fullDetail);
                    if ("REJECTED".equals(action)) {
                        rec.setStatus(TaskExecutionRecord.Status.REJECTED);
                        rec.setEndTime(LocalDateTime.now());
                        rec.setRejectionReason("Risk score " + riskScore);
                    } else {
                        // Task passed security check - handle according to submit mode
                        if ("direct".equalsIgnoreCase(taskSubmitMode)) {
                            // Direct submission to Kubernetes
                            try {
                                // Create task info for either mode
                                TaskInfo info = new TaskInfo(
                                    submissionId,
                                    rec.getUserId(),
                                    rec.getResourceType() == TaskExecutionRecord.ResourceType.GPU,
                                    rec.getVramSize(),
                                    rec.getGpuType(),
                                    Instant.now(),
                                    null, // clientInfo
                                    null  // clientIp
                                );
                                
                                // Determine template based on GPU requirements
                                String templateFile = !info.isGpuRequired()
                                        ? gpuConfig.getCpu().getYaml()
                                        : gpuConfig.getTypes().stream()
                                                .filter(c -> c.getType().equals(info.getGpuType()))
                                                .map(GpuConfigProperties.GpuTypeConfig::getYaml)
                                                .findFirst().orElse(gpuConfig.getCpu().getYaml());
                                
                                // Read template file and prepare job
                                String template = Files.readString(Path.of(jobTemplateDir, templateFile));
                                String timestamp = submissionId.substring(submissionId.length() - 14);
                                String presignUrl = presignService.getPresignedUrl(rec.getOriginalPath(), 3600);
                                
                                // Replace placeholders in template
                                String jobYaml = template
                                    .replace("${SUBMISSION_ID}", submissionId)
                                    .replace("${USER_ID}", rec.getUserId())
                                    .replace("${TIMESTAMP}", timestamp)
                                    .replace("${PRESIGN_URL}", presignUrl)
                                    .replace("${GPU_TYPE}", rec.getGpuType() != null ? rec.getGpuType() : "");
                                
                                // Parse YAML and submit job
                                Yaml yaml = new Yaml();
                                V1Job job = yaml.loadAs(jobYaml, V1Job.class);
                                var container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
                                container.setImage(jobImage);
                                job.getMetadata().setName("task-" + submissionId);
                                
                                // Submit to K8s directly
                                batchV1Api.createNamespacedJob(k8sNamespace, job, null, null, null, null);
                                rec.setStatus(TaskExecutionRecord.Status.SCHEDULED);
                                logger.info("Task {} submitted directly to Kubernetes after passing security scan", submissionId);
                            } catch (Exception e) {
                                logger.error("Failed to submit task {} directly to Kubernetes: {}", submissionId, e.getMessage(), e);
                                // Fallback to queue mode if direct submission fails
                                submitToQueue(rec);
                            }
                        } else {
                            // Default queue mode
                            submitToQueue(rec);
                        }
                    }
                    taskRecordRepo.save(rec);
                });
            }
        } catch (Exception e) {
            logger.error("Error in async scan for submission={}: {}", submissionId, e.getMessage(), e);
        }
    }
    
    // Helper method to submit to Redis queue
    private void submitToQueue(TaskExecutionRecord rec) {
        try {
            TaskInfo info = new TaskInfo(
                rec.getSubmissionId(),
                rec.getUserId(),
                rec.getResourceType() == TaskExecutionRecord.ResourceType.GPU,
                rec.getVramSize(),
                rec.getGpuType(),
                Instant.now(),
                null, // clientInfo if needed
                null  // clientIp if needed
            );
            int position = queueService.submitTask(info);
            logger.debug("Task {} enqueued at position {} after scan", rec.getSubmissionId(), position);
        } catch (Exception e) {
            logger.error("Failed to submit task {} to queue: {}", rec.getSubmissionId(), e.getMessage(), e);
        }
    }
}