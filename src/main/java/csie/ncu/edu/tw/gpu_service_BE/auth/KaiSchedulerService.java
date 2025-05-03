package csie.ncu.edu.tw.gpu_service_BE.auth;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;

import csie.ncu.edu.tw.gpu_service_BE.config.GpuConfigProperties;
import csie.ncu.edu.tw.gpu_service_BE.config.QueueProperties;

@Service
public class KaiSchedulerService {
    private CoreV1Api coreV1Api;
    private CustomObjectsApi customObjectsApi;
    private static final String GPU_LABEL = "nvidia.com/gpu.product";
    private static final String QUEUE_ANNOTATION = "scheduling.k8s.io/queue-name";

    @Value("${job.gpu.threshold.a100}")
    private Double thresholdA100;
    
    @Autowired
    private GpuConfigProperties gpuConfig;
    
    @Autowired
    private QueueProperties queueProperties;

    // Queue names from GpuConfigProperties
    private String cpuQueueName;
    private String gpuA100QueueName;
    private String gpuA40QueueName;

    @PostConstruct
    public void init() throws IOException {
        // Initialize queue names from configuration
        cpuQueueName = gpuConfig.getCpu().getQueue();
        
        // Find A100 and A40 queue names from GPU types
        for (GpuConfigProperties.GpuTypeConfig typeConfig : gpuConfig.getTypes()) {
            if (typeConfig.getType().contains("A100")) {
                gpuA100QueueName = typeConfig.getQueue();
            } else if (typeConfig.getType().contains("A40")) {
                gpuA40QueueName = typeConfig.getQueue();
            }
        }
        
        ApiClient client;
        try {
            // try in-cluster config
            client = Config.fromCluster();
        } catch (Exception e) {
            // fallback to local kubeconfig
            client = Config.defaultClient();
        }
        io.kubernetes.client.openapi.Configuration.setDefaultApiClient(client);
        this.coreV1Api = new CoreV1Api();
        this.customObjectsApi = new CustomObjectsApi();
    }

    // 查詢所有 GPU 節點及其型號
    public Map<String, String> getGpuNodeTypes() throws ApiException {
        Map<String, String> nodeMap = new HashMap<>();
        V1NodeList nodeList = coreV1Api.listNode(null, null, null, null, null, null, null, null, null, null);
        for (V1Node node : nodeList.getItems()) {
            Map<String, String> labels = node.getMetadata().getLabels();
            if (labels != null && labels.containsKey(GPU_LABEL)) {
                nodeMap.put(node.getMetadata().getName(), labels.get(GPU_LABEL));
            }
        }
        return nodeMap;
    }

    // 查詢所有 queue 狀態
    public Object listQueues() throws ApiException {
        return customObjectsApi.listClusterCustomObject(
            "scheduling.run.ai", "v2", "queues",
            null,           // _continue
            false,          // allowWatchBookmarks
            null,           // fieldSelector
            null,           // labelSelector
            null,           // resourceVersion
            null,           // limit (Integer)
            null,           // pretty (String)
            null,           // resourceVersionMatch (String)
            null,           // timeoutSeconds (Integer)
            false           // watch (Boolean)
        );
    }

    // 查詢 PodGroup 狀態
    public Object listPodGroups(String namespace) throws ApiException {
        return customObjectsApi.listNamespacedCustomObject(
            "scheduling.run.ai", "v2alpha2", namespace, "podgroups",
            null,           // _continue
            false,          // allowWatchBookmarks
            null,           // fieldSelector
            null,           // labelSelector
            null,           // resourceVersion
            null,           // limit (Integer)
            null,           // pretty (String)
            null,           // resourceVersionMatch (String)
            null,           // timeoutSeconds (Integer)
            false           // watch (Boolean)
        );
    }

    // 刪除 Pod
    public void deletePod(String podName, String namespace) throws ApiException {
        coreV1Api.deleteNamespacedPod(podName, namespace, null, null, null, null, null, null);
    }

    // 刪除 PodGroup
    public void deletePodGroup(String podGroupName, String namespace) throws ApiException {
        customObjectsApi.deleteNamespacedCustomObject(
            "scheduling.run.ai", "v2alpha2", namespace, "podgroups", podGroupName, null, null, null, null, null);
    }

    // 提交任務到對應 queue
    public void submitTask(TaskInfo task) throws ApiException {
        String queueName;
        Map<String, String> nodeSelector = new HashMap<>();
        // determine queue based on GPU requirement
        if (!task.isGpuRequired()) {
            queueName = cpuQueueName;
        } else {
            // GPU queue selection by VRAM size threshold
            if (task.getVramSize() != null && task.getVramSize() >= thresholdA100) {
                queueName = gpuA100QueueName;
                nodeSelector.put(GPU_LABEL, "A100");
            } else {
                queueName = gpuA40QueueName;
                nodeSelector.put(GPU_LABEL, "A40");
            }
        }
        // 建立 Pod spec
        V1Pod pod = new V1Pod()
                .metadata(new V1ObjectMeta()
                        .name("task-" + task.getSubmissionId())
                        .putAnnotationsItem(QUEUE_ANNOTATION, queueName))
                .spec(new V1PodSpec()
                        .containers(List.of(new V1Container()
                                .name("notebook-job")
                                .image("your-notebook-image") // TODO: 替換為實際映像
                                .args(List.of("run-task", task.getSubmissionId()))
                        ))
                        .restartPolicy("Never")
                        .nodeSelector(nodeSelector.isEmpty() ? null : nodeSelector)
                );
        // 提交 Pod
        coreV1Api.createNamespacedPod("default", pod, null, null, null, null);
    }
}
