package csie.ncu.edu.tw.gpu_service_BE.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Collections;

import csie.ncu.edu.tw.gpu_service_BE.config.GpuConfigProperties;
import csie.ncu.edu.tw.gpu_service_BE.config.QueueProperties;

@Service
public class InternalQueueScheduler {
    private static final Logger log = LoggerFactory.getLogger(InternalQueueScheduler.class);

    @Autowired
    private KaiSchedulerService kaiService;
    @Autowired
    private QueueService queueService;
    @Autowired
    private TaskExecutionRecordRepository recordRepo;
    @Autowired
    private MinioPresignService presignService;
    @Autowired
    private GpuConfigProperties gpuConfig;
    @Autowired
    private QueueProperties queueProperties;

    @Value("${job.template.dir:customize-yaml}")
    private String jobTemplateDir;
    @Value("${job.image:shadowcollect/ipynb-runner:latest}")
    private String jobImage;
    @Value("${kubernetes.namespace}")
    private String k8sNamespace;

    @Value("${internal.scheduler.poll-interval-ms:10000}")
    private long pollIntervalMs;
    @Value("${internal.scheduler.pending-count:2}")
    private int desiredPending;
    @Value("${internal.scheduler.running-count:1}")
    private int desiredRunning;

    private final ObjectMapper mapper = new ObjectMapper();

    @Scheduled(fixedDelayString = "${internal.scheduler.poll-interval-ms:10000}")
    public void dispatch() {
        List<String> queueNames = queueProperties.getNames();

        for (String queueName : queueNames) {
            try {
                // list all PodGroups in namespace
                Object raw = kaiService.listPodGroups(k8sNamespace);
                Map<?,?> root = (Map<?,?>) raw;
                // use raw type or specify Object to satisfy generics
                Object itemsObj = root.get("items");
                List<?> items = (itemsObj instanceof List<?>) ? (List<?>) itemsObj : Collections.emptyList();
                // filter by queue annotation
                List<Map<String,Object>> filtered = items.stream()
                    .map(o -> (Map<String,Object>) o)
                    .filter(m -> {
                        Map<String,String> ann = (Map<String,String>) ((Map<?,?>) m.get("metadata")).get("annotations");
                        return ann != null && queueName.equals(ann.get("scheduling.k8s.io/queue-name"));
                    }).map(o -> (Map<String,Object>) o)
                    .collect(Collectors.toList());
                int pendingCount = 0, runningCount = 0;
                for (Map<String,Object> pg : filtered) {
                    Map<?,?> status = (Map<?,?>) pg.get("status");
                    String phase = status != null ? (String) status.get("phase") : null;
                    if ("Pending".equals(phase)) pendingCount++;
                    else if ("Running".equals(phase)) runningCount++;
                }
                int toSchedule = (desiredPending + desiredRunning) - (pendingCount + runningCount);
                for (int i = 0; i < toSchedule; i++) {
                    dispatchOne(queueName);
                }
            } catch (ApiException e) {
                log.error("K8s listPodGroups error; skipping dispatch: {}", e.getResponseBody(), e);
            } catch (Exception e) {
                log.error("InternalQueueScheduler error", e);
            }
        }
    }

    private void dispatchOne(String queueName) {
        // fetch one from the specific Redis queue
        List<TaskInfo> tasks = queueService.getPendingTasks(queueName, 1);
        if (tasks.isEmpty()) return;
        TaskInfo info = tasks.get(0);
        try {
            // submit to Kai Scheduler
            kaiService.submitTask(info);
            recordRepo.findBySubmissionId(info.getSubmissionId()).ifPresent(rec -> {
                rec.setStatus(TaskExecutionRecord.Status.SCHEDULED);
                recordRepo.save(rec);
            });
            // remove from the same external Redis queue
            queueService.removeTask(queueName, info.getSubmissionId());
            log.debug("Dispatched task {} to K8s queue {}", info.getSubmissionId(), queueName);
        } catch (ApiException e) {
            log.error("Failed to dispatch {} to queue {}: {}", info.getSubmissionId(), queueName, e.getResponseBody(), e);
        } catch (Exception e) {
            log.error("Unexpected error dispatching {}", info.getSubmissionId(), e);
        }
    }
}