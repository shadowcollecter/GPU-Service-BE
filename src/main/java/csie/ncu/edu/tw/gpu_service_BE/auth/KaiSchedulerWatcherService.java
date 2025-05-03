package csie.ncu.edu.tw.gpu_service_BE.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodStatus;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;

@Service
public class KaiSchedulerWatcherService {
    @Value("${kubernetes.namespace}")
    private String k8sNamespace;

    @Autowired
    private CoreV1Api coreV1Api;
    @Autowired
    private TaskExecutionRecordRepository recordRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    public void startPodWatch() throws IOException, ApiException {
        ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);
        coreV1Api = new CoreV1Api(client);
        // watch all pods in the specified namespace and filter by name prefix 'task-'
        java.lang.reflect.Type type = TypeFactory.defaultInstance()
            .constructParametricType(Watch.Response.class, V1Pod.class);
        Watch<V1Pod> watch = Watch.createWatch(
            client,
            coreV1Api.listNamespacedPodCall(
                k8sNamespace,
                null, null, null,
                null, // no label selector
                null, // field selector
                null, null, null, null,
                Boolean.TRUE,
                null
            ),
            type
        );
        Executors.newSingleThreadExecutor().submit(() -> {
            for (Watch.Response<V1Pod> item : watch) {
                try {
                    V1Pod pod = item.object;
                    String name = pod.getMetadata().getName();
                    if (!name.startsWith("task-")) continue;
                    String submissionId = name.substring("task-".length());
                    V1PodStatus status = pod.getStatus();
                    String phase = status != null ? status.getPhase() : null;
                    // update record based on phase
                    recordRepo.findBySubmissionId(submissionId).ifPresent(rec -> {
                        switch (phase) {
                            case "Pending": rec.setStatus(TaskExecutionRecord.Status.SCHEDULED); break;
                            case "Running": rec.setStatus(TaskExecutionRecord.Status.RUNNING);
                                              rec.setStartTime(LocalDateTime.now()); break;
                            case "Succeeded": rec.setStatus(TaskExecutionRecord.Status.COMPLETED);
                                                rec.setEndTime(LocalDateTime.now()); break;
                            case "Failed": rec.setStatus(TaskExecutionRecord.Status.FAILED);
                                             rec.setEndTime(LocalDateTime.now()); break;
                            default: break;
                        }
                        recordRepo.save(rec);
                    });
                } catch (Exception e) {
                    // log and continue
                    System.err.println("Watcher error: " + e.getMessage());
                }
            }
        });
    }
}