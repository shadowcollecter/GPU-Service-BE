package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import io.kubernetes.client.openapi.models.V1Job;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import csie.ncu.edu.tw.gpu_service_BE.config.GpuConfigProperties;

@Service
public class ExternalQueueScheduler {
    private static final Logger log = LoggerFactory.getLogger(ExternalQueueScheduler.class);

    @Autowired
    private QueueService queueService;
    @Autowired
    private TaskExecutionRecordRepository recordRepo;
    @Autowired
    private BatchV1Api batchV1Api;
    @Autowired
    private MinioPresignService presignService;
    @Autowired
    private GpuConfigProperties gpuConfig;
    @Value("${job.template.dir:customize-yaml}")
    private String jobTemplateDir;
    @Value("${job.image:shadowcollect/ipynb-runner:latest}")
    private String jobImage;
    @Value("${kubernetes.namespace}")
    private String k8sNamespace;

    @Value("${external.scheduler.poll.interval:10000}")
    private long pollInterval;

    @Scheduled(fixedDelayString = "${external.scheduler.poll.interval:10000}")
    public void submitPendingExternalTasks() {
        List<TaskInfo> pending = queueService.getPendingTasks(null);
        for (TaskInfo info : pending) {
            String submissionId = info.getSubmissionId();
            recordRepo.findBySubmissionId(submissionId).ifPresent(rec -> {
                try {
                    // determine template
                    String templateFile = !info.isGpuRequired()
                            ? gpuConfig.getCpu().getYaml()
                            : gpuConfig.getTypes().stream()
                                    .filter(c -> c.getType().equals(info.getGpuType()))
                                    .map(GpuConfigProperties.GpuTypeConfig::getYaml)
                                    .findFirst().orElse(gpuConfig.getCpu().getYaml());
                    String template = Files.readString(Path.of(jobTemplateDir, templateFile));
                    String timestamp = submissionId.substring(submissionId.length() - 14);
                    String presignUrl = presignService.getPresignedUrl(rec.getOriginalPath(), 3600);
                    String jobYaml = template
                        .replace("${SUBMISSION_ID}", submissionId)
                        .replace("${USER_ID}", rec.getUserId())
                        .replace("${TIMESTAMP}", timestamp)
                        .replace("${PRESIGN_URL}", presignUrl)
                        .replace("${GPU_TYPE}", rec.getGpuType() != null ? rec.getGpuType() : "");
                    // load and submit
                    Yaml yaml = new Yaml();
                    V1Job job = yaml.loadAs(jobYaml, V1Job.class);
                    var container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
                    container.setImage(jobImage);
                    job.getMetadata().setName("task-" + submissionId);
                    batchV1Api.createNamespacedJob(k8sNamespace, job, null, null, null, null);
                    rec.setStatus(TaskExecutionRecord.Status.SCHEDULED);
                    recordRepo.save(rec);
                    queueService.removeTask(submissionId);
                } catch (Exception e) {
                    log.error("Failed to submit task {} to K8s", submissionId, e);
                }
            });
        }
    }
}