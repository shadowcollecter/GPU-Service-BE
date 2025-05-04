package csie.ncu.edu.tw.gpu_service_BE.k8s;

import com.google.gson.reflect.TypeToken;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;

import csie.ncu.edu.tw.gpu_service_BE.auth.TaskExecutionRecord;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskExecutionRecordRepository;

/**
 * Kubernetes Pod监视器，监控任务状态变化
 */
@Service
public class KubernetesPodWatcherService {
    private static final Logger log = LoggerFactory.getLogger(KubernetesPodWatcherService.class);

    @Value("${kubernetes.namespace}")
    private String k8sNamespace;

    @Autowired
    private CoreV1Api coreV1Api;
    
    @Autowired
    private TaskExecutionRecordRepository recordRepo;

    /**
     * 启动Pod监视器
     */
    @PostConstruct
    public void startPodWatch() throws IOException, ApiException {
        log.info("Starting Kubernetes Pod watcher in namespace: {}", k8sNamespace);
        
        // 创建K8s客户端
        ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);
        coreV1Api = new CoreV1Api(client);
        
        // 使用正确的Gson TypeToken而不是Jackson TypeFactory
        TypeToken<Watch.Response<V1Pod>> typeToken = 
            new TypeToken<Watch.Response<V1Pod>>() {};
            
        Watch<V1Pod> watch = Watch.createWatch(
            client,
            coreV1Api.listNamespacedPodCall(
                k8sNamespace,
                null, null, null,
                null, // 没有标签选择器
                null, // 字段选择器
                null, null, null, null,
                Boolean.TRUE,
                null
            ),
            typeToken.getType()
        );
        
        // 在单独线程中运行监视器
        Executors.newSingleThreadExecutor().submit(() -> {
            log.info("Pod watcher thread started");
            try {
                for (Watch.Response<V1Pod> item : watch) {
                    try {
                        V1Pod pod = item.object;
                        String name = pod.getMetadata().getName();
                        
                        // 只处理任务Pod（名称以task-开头）
                        if (!name.startsWith("task-")) continue;
                        
                        String submissionId = name.substring("task-".length());
                        String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : null;
                        
                        log.debug("Pod {} status changed to {}", name, phase);
                        
                        // 根据Pod状态更新任务记录
                        recordRepo.findBySubmissionId(submissionId).ifPresent(rec -> {
                            if (phase == null) {
                                return;
                            }
                            
                            switch (phase) {
                                case "Pending": 
                                    rec.setStatus(TaskExecutionRecord.Status.SCHEDULED); 
                                    break;
                                case "Running": 
                                    rec.setStatus(TaskExecutionRecord.Status.RUNNING);
                                    if (rec.getStartTime() == null) {
                                        rec.setStartTime(LocalDateTime.now());
                                    }
                                    break;
                                case "Succeeded": 
                                    rec.setStatus(TaskExecutionRecord.Status.COMPLETED);
                                    if (rec.getEndTime() == null) {
                                        rec.setEndTime(LocalDateTime.now());
                                    }
                                    if (rec.getStartTime() != null && rec.getEndTime() != null) {
                                        // 计算执行时间（秒）
                                        long durationSeconds = java.time.Duration.between(
                                            rec.getStartTime(), rec.getEndTime()).getSeconds();
                                        rec.setDuration(durationSeconds);
                                    }
                                    break;
                                case "Failed": 
                                    rec.setStatus(TaskExecutionRecord.Status.FAILED);
                                    if (rec.getEndTime() == null) {
                                        rec.setEndTime(LocalDateTime.now());
                                    }
                                    if (rec.getStartTime() != null && rec.getEndTime() != null) {
                                        // 计算执行时间（秒）
                                        long durationSeconds = java.time.Duration.between(
                                            rec.getStartTime(), rec.getEndTime()).getSeconds();
                                        rec.setDuration(durationSeconds);
                                    }
                                    break;
                                default: 
                                    break;
                            }
                            recordRepo.save(rec);
                        });
                    } catch (Exception e) {
                        // 记录错误并继续监视
                        log.error("Error processing pod status update: {}", e.getMessage(), e);
                    }
                }
            } catch (Exception e) {
                log.error("Pod watcher thread error: {}", e.getMessage(), e);
                // 尝试重新启动监视器
                try {
                    Thread.sleep(5000);
                    startPodWatch();
                } catch (Exception ex) {
                    log.error("Failed to restart pod watcher: {}", ex.getMessage(), ex);
                }
            }
        });
    }
}