package csie.ncu.edu.tw.gpu_service_BE.k8s;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.util.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import csie.ncu.edu.tw.gpu_service_BE.auth.QueueService;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskExecutionRecord;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskExecutionRecordRepository;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskInfo;
import csie.ncu.edu.tw.gpu_service_BE.config.GpuConfigProperties;

/**
 * 监控Kubernetes任务状态并管理队列的服务
 */
@Service
public class KubernetesJobMonitorService {
    private static final Logger log = LoggerFactory.getLogger(KubernetesJobMonitorService.class);

    @Autowired
    private BatchV1Api batchV1Api;
    
    @Autowired
    private CoreV1Api coreV1Api;
    
    @Autowired
    private TaskExecutionRecordRepository recordRepo;
    
    @Autowired
    private DirectKubernetesSchedulerService kubernetesService;
    
    @Autowired
    private QueueService queueService;
    
    @Autowired
    private GpuConfigProperties gpuConfig;

    @Autowired
    private Environment environment;

    @Value("${kubernetes.namespace}")
    private String k8sNamespace;
    
    @Value("${k8s.job.monitor.interval-ms:10000}")
    private long monitorIntervalMs;
    
    @Value("${k8s.job.clean.completed-jobs-ttl-seconds:3600}")
    private int completedJobsTtlSeconds;
    
    @Value("${k8s.job.clean.failed-jobs:true}")
    private boolean cleanFailedJobs;
    
    // GPU类型和并发限制的配置映射
    private Map<String, Integer> gpuTypeConcurrentLimits = new HashMap<>();
    
    // 正在运行的任务记录，按类型分类
    private final Map<String, Set<String>> runningJobs = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        log.info("Initializing KubernetesJobMonitorService with monitor interval: {}ms", monitorIntervalMs);
        
        // 初始化runningJobs集合 - CPU队列
        runningJobs.put("cpu", new HashSet<>());
        
        // 加载CPU并发限制 - 从Spring配置获取，而不是系统属性
        int cpuConcurrentLimit = Integer.parseInt(
            environment.getProperty("k8s.job.concurrent-limit.cpu", "2"));
        gpuTypeConcurrentLimits.put("cpu", cpuConcurrentLimit);
        log.info("Configured CPU with concurrent limit {}", cpuConcurrentLimit);
        
        // 初始化所有GPU类型的队列和并发限制
        for (GpuConfigProperties.GpuTypeConfig typeConfig : gpuConfig.getTypes()) {
            String gpuType = typeConfig.getType().toLowerCase().replace(" ", "-");
            runningJobs.put(gpuType, new HashSet<>());
            
            // 读取配置中的并发限制，默认为1，从Spring配置获取
            String propKey = "k8s.job.concurrent-limit." + gpuType;
            int concurrentLimit = Integer.parseInt(
                environment.getProperty(propKey, "1"));
            gpuTypeConcurrentLimits.put(gpuType, concurrentLimit);
            
            log.info("Configured GPU type {} with concurrent limit {}", gpuType, concurrentLimit);
        }
        
        // 初始扫描，检查现有任务
        try {
            scanKubernetesJobs();
        } catch (Exception e) {
            log.error("Error performing initial job scan: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 定期扫描Kubernetes中的任务状态
     */
    @Scheduled(fixedDelayString = "${k8s.job.monitor.interval-ms:10000}")
    public void monitorAndScheduleJobs() {
        try {
            // 1. 扫描现有任务状态
            scanKubernetesJobs();
            
            // 2. 清理已完成或失败的任务
            cleanupCompletedJobs();
            
            // 3. 从队列中提取新任务并运行
            scheduleNewJobs();
        } catch (Exception e) {
            log.error("Error in job monitoring cycle: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 扫描Kubernetes中的所有任务，更新状态
     */
    private void scanKubernetesJobs() throws ApiException {
        log.debug("Scanning Kubernetes jobs in namespace: {}", k8sNamespace);
        
        // 清空当前运行任务记录
        runningJobs.values().forEach(Set::clear);
        
        // 获取所有任务
        V1JobList jobList = batchV1Api.listNamespacedJob(
            k8sNamespace, null, null, null, null, 
            null, null, null, null, null, null
        );
        
        for (V1Job job : jobList.getItems()) {
            String jobName = job.getMetadata().getName();
            
            // 只处理我们的任务（以task-开头）
            if (!jobName.startsWith("task-")) {
                continue;
            }
            
            String submissionId = jobName.substring("task-".length());
            
            // 检查任务状态
            boolean isCompleted = job.getStatus() != null && 
                                 job.getStatus().getCompletionTime() != null;
            boolean isFailed = job.getStatus() != null && 
                              job.getStatus().getFailed() != null && 
                              job.getStatus().getFailed() > 0;
            
            if (isCompleted || isFailed) {
                // 任务已完成或失败，无需跟踪
                log.debug("Job {} is completed or failed", jobName);
                
                // 更新数据库中的任务状态
                recordRepo.findBySubmissionId(submissionId).ifPresent(rec -> {
                    if (isCompleted && rec.getStatus() != TaskExecutionRecord.Status.COMPLETED) {
                        rec.setStatus(TaskExecutionRecord.Status.COMPLETED);
                        if (rec.getEndTime() == null) {
                            rec.setEndTime(LocalDateTime.now());
                        }
                    } else if (isFailed && rec.getStatus() != TaskExecutionRecord.Status.FAILED) {
                        rec.setStatus(TaskExecutionRecord.Status.FAILED);
                        if (rec.getEndTime() == null) {
                            rec.setEndTime(LocalDateTime.now());
                        }
                    }
                    recordRepo.save(rec);
                });
            } else {
                // 任务正在运行，添加到运行中任务集合
                recordRepo.findBySubmissionId(submissionId).ifPresent(rec -> {
                    String type = getJobType(rec);
                    runningJobs.get(type).add(submissionId);
                    log.debug("Added running job {} to type {}", jobName, type);
                });
            }
        }
        
        // 记录当前运行情况
        StringBuilder sb = new StringBuilder("Running jobs - ");
        for (Map.Entry<String, Set<String>> entry : runningJobs.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue().size()).append(", ");
        }
        log.info(sb.toString());
    }
    
    /**
     * 清理已完成或失败的任务
     */
    private void cleanupCompletedJobs() throws ApiException {
        log.debug("Cleaning up completed and failed jobs");
        
        V1JobList jobList = batchV1Api.listNamespacedJob(
            k8sNamespace, null, null, null, null, 
            null, null, null, null, null, null
        );
        
        for (V1Job job : jobList.getItems()) {
            String jobName = job.getMetadata().getName();
            
            // 只处理我们的任务（以task-开头）
            if (!jobName.startsWith("task-")) {
                continue;
            }
            
            boolean isCompleted = job.getStatus() != null && 
                                 job.getStatus().getCompletionTime() != null;
            boolean isFailed = job.getStatus() != null && 
                              job.getStatus().getFailed() != null && 
                              job.getStatus().getFailed() > 0;
            
            // 判断任务是否已经完成或失败
            if (isCompleted || (isFailed && cleanFailedJobs)) {
                try {
                    // 获取任务完成时间
                    LocalDateTime completionTime = null;
                    if (job.getStatus().getCompletionTime() != null) {
                        completionTime = LocalDateTime.ofInstant(
                            job.getStatus().getCompletionTime().toInstant(), 
                            ZoneId.systemDefault()
                        );
                    }
                    
                    // 检查任务完成后是否已经超过TTL
                    boolean shouldCleanup = false;
                    if (completionTime != null) {
                        long secondsElapsed = ChronoUnit.SECONDS.between(completionTime, LocalDateTime.now());
                        shouldCleanup = secondsElapsed > completedJobsTtlSeconds;
                    } else if (isFailed) {
                        // 失败任务直接清理
                        shouldCleanup = true;
                    }
                    
                    if (shouldCleanup) {
                        log.info("Cleaning up job: {}", jobName);
                        batchV1Api.deleteNamespacedJob(
                            jobName, k8sNamespace, 
                            null, null, null, null, null, null
                        );
                    }
                } catch (ApiException e) {
                    log.warn("Error cleaning up job {}: {}", jobName, e.getMessage());
                }
            }
        }
    }
    
    /**
     * 从队列中提取新任务并提交到Kubernetes
     */
    private void scheduleNewJobs() {
        // 处理CPU任务
        scheduleTasksForType("cpu", gpuConfig.getCpu().getQueue());
        
        // 处理所有GPU类型任务
        for (GpuConfigProperties.GpuTypeConfig typeConfig : gpuConfig.getTypes()) {
            String gpuType = typeConfig.getType().toLowerCase().replace(" ", "-");
            scheduleTasksForType(gpuType, typeConfig.getQueue());
        }
    }
    
    /**
     * 为特定类型的任务调度队列中的作业
     */
    private void scheduleTasksForType(String jobType, String queueName) {
        if (!runningJobs.containsKey(jobType) || !gpuTypeConcurrentLimits.containsKey(jobType)) {
            log.warn("Unknown job type or missing concurrent limit: {}", jobType);
            return;
        }
        
        int concurrentLimit = gpuTypeConcurrentLimits.get(jobType);
        int currentRunning = runningJobs.get(jobType).size();
        int toRun = concurrentLimit - currentRunning;
        
        if (toRun <= 0) {
            return;  // 已达到并发限制
        }
        
        // 从队列中获取任务并提交
        List<TaskInfo> tasks = queueService.getPendingTasks(queueName, toRun);
        if (!tasks.isEmpty()) {
            log.info("Scheduling {} tasks of type {} from queue {}", tasks.size(), jobType, queueName);
            submitTasks(tasks);
        }
    }
    
    /**
     * 批量提交任务到Kubernetes
     */
    private void submitTasks(List<TaskInfo> tasks) {
        for (TaskInfo task : tasks) {
            try {
                // 检查任务记录中的originalPath是否存在
                TaskExecutionRecord record = recordRepo.findBySubmissionId(task.getSubmissionId())
                    .orElseThrow(() -> new IllegalStateException("Task record not found: " + task.getSubmissionId()));
                
                if (record.getOriginalPath() == null || record.getOriginalPath().isEmpty()) {
                    log.error("Task {} has null or empty originalPath, skipping submission", task.getSubmissionId());
                    
                    // 更新任务状态为FAILED并设置错误信息
                    record.setStatus(TaskExecutionRecord.Status.FAILED);
                    record.setEndTime(LocalDateTime.now());
                    record.setRejectionReason("Missing original path information");
                    recordRepo.save(record);
                    
                    // 从队列中移除任务
                    queueService.removeTask(task.getSubmissionId());
                    continue;
                }
                
                // 提交任务到Kubernetes
                kubernetesService.submitTask(task);
                
                // 从队列中移除任务
                queueService.removeTask(task.getSubmissionId());
                
                log.info("Scheduled task {} to Kubernetes", task.getSubmissionId());
            } catch (IOException e) {
                log.error("IO error scheduling task {}: {}", task.getSubmissionId(), e.getMessage(), e);
            } catch (ApiException e) {
                log.error("K8s API error scheduling task {}: {}", task.getSubmissionId(), e.getResponseBody(), e);
            } catch (Exception e) {
                log.error("Unexpected error scheduling task {}: {}", task.getSubmissionId(), e.getMessage(), e);
            }
        }
    }
    
    /**
     * 根据任务记录确定任务类型
     */
    private String getJobType(TaskExecutionRecord record) {
        if (record.getResourceType() == TaskExecutionRecord.ResourceType.CPU) {
            return "cpu";
        } else if (record.getGpuType() != null) {
            return record.getGpuType().toLowerCase().replace(" ", "-");
        }
        // 默认为CPU
        return "cpu";
    }
}