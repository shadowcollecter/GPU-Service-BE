package csie.ncu.edu.tw.gpu_service_BE.k8s;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.util.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import csie.ncu.edu.tw.gpu_service_BE.auth.MinioPresignService;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskExecutionRecord;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskExecutionRecordRepository;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskInfo;
import csie.ncu.edu.tw.gpu_service_BE.config.GpuConfigProperties;

/**
 * 直接使用Kubernetes API提交任务，不再依赖KAI调度器
 */
@Service
public class DirectKubernetesSchedulerService {
    private static final Logger log = LoggerFactory.getLogger(DirectKubernetesSchedulerService.class);

    @Autowired
    private BatchV1Api batchV1Api;
    
    @Autowired
    private CoreV1Api coreV1Api;
    
    @Autowired
    private MinioPresignService presignService;
    
    @Autowired
    private TaskExecutionRecordRepository recordRepo;
    
    @Autowired
    private GpuConfigProperties gpuConfig;

    @Value("${job.template.dir:customize-yaml}")
    private String jobTemplateDir;
    
    @Value("${job.image:shadowcollect/ipynb-runner:latest}")
    private String jobImage;
    
    @Value("${kubernetes.namespace}")
    private String k8sNamespace;

    /**
     * 初始化Kubernetes客户端
     */
    @PostConstruct
    public void init() {
        log.info("Initializing DirectKubernetesSchedulerService with namespace: {}", k8sNamespace);
    }

    /**
     * 直接提交任务到Kubernetes
     */
    public void submitTask(TaskInfo task) throws IOException, ApiException {
        log.info("Submitting task {} directly to Kubernetes", task.getSubmissionId());
        
        // 获取用户记录
        TaskExecutionRecord record = recordRepo.findBySubmissionId(task.getSubmissionId())
            .orElseThrow(() -> new IllegalStateException("Task record not found: " + task.getSubmissionId()));
        
        // 确定使用哪个YAML模板
        String templateFile = !task.isGpuRequired()
                ? gpuConfig.getCpu().getYaml()
                : gpuConfig.getTypes().stream()
                        .filter(c -> c.getType().equals(task.getGpuType()))
                        .map(GpuConfigProperties.GpuTypeConfig::getYaml)
                        .findFirst().orElse(gpuConfig.getCpu().getYaml());
        
        // 读取模板文件
        String template = Files.readString(Path.of(jobTemplateDir, templateFile));
        String timestamp = task.getSubmissionId().substring(task.getSubmissionId().length() - 14);
        
        // 检查originalPath是否为null或空
        if (record.getOriginalPath() == null || record.getOriginalPath().isEmpty()) {
            throw new IllegalStateException("Original path is null or empty for task: " + task.getSubmissionId());
        }
        
        // 获取预签名URL
        String presignUrl = presignService.getPresignedUrl(record.getOriginalPath(), 3600);
        log.debug("Generated presigned URL for object: {}", record.getOriginalPath());
        
        // 替换模板中的变量
        String jobYaml = template
            .replace("${SUBMISSION_ID}", task.getSubmissionId())
            .replace("${USER_ID}", task.getUserId())
            .replace("${TIMESTAMP}", timestamp)
            .replace("${PRESIGN_URL}", presignUrl)
            .replace("${GPU_TYPE}", task.getGpuType() != null ? task.getGpuType() : "");
        
        // 解析YAML并创建Job
        Yaml yaml = new Yaml();
        V1Job job = yaml.loadAs(jobYaml, V1Job.class);
        var container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
        container.setImage(jobImage);
        job.getMetadata().setName("task-" + task.getSubmissionId());
        
        // 提交到Kubernetes
        batchV1Api.createNamespacedJob(k8sNamespace, job, null, null, null, null);
        
        // 更新任务状态
        record.setStatus(TaskExecutionRecord.Status.SCHEDULED);
        recordRepo.save(record);
        
        log.info("Task {} submitted successfully with job name: task-{}", 
                task.getSubmissionId(), task.getSubmissionId());
    }

    /**
     * 删除Kubernetes任务
     */
    public void deleteJob(String submissionId) throws ApiException {
        String jobName = "task-" + submissionId;
        log.info("Deleting job: {}", jobName);
        
        try {
            // 删除Job
            batchV1Api.deleteNamespacedJob(
                jobName, k8sNamespace, null, null, null, null, null, null);
            log.info("Job {} deleted successfully", jobName);
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                log.warn("Job {} not found, may have been already deleted", jobName);
            } else {
                throw e;
            }
        }
    }

    /**
     * 获取任务Pod状态
     */
    public List<Map<String, Object>> getTaskPods(String submissionId) throws ApiException {
        String labelSelector = "job-name=task-" + submissionId;
        
        V1PodList podList = coreV1Api.listNamespacedPod(
            k8sNamespace, null, null, null, null, labelSelector, null, null, null, null, null);
        
        return podList.getItems().stream().map(pod -> {
            Map<String, Object> result = Map.of(
                "name", pod.getMetadata().getName(),
                "phase", pod.getStatus().getPhase(),
                "startTime", pod.getStatus().getStartTime(),
                "creationTimestamp", pod.getMetadata().getCreationTimestamp()
            );
            return result;
        }).collect(Collectors.toList());
    }
}