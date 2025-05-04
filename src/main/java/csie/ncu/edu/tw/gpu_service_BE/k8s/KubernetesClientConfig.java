package csie.ncu.edu.tw.gpu_service_BE.k8s;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.util.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.IOException;

@Configuration
public class KubernetesClientConfig {
    private static final Logger log = LoggerFactory.getLogger(KubernetesClientConfig.class);

    @Bean
    @Primary
    public ApiClient apiClient() throws IOException {
        ApiClient client;
        try {
            // 先嘗試使用本地 kubeconfig
            client = Config.defaultClient();
            log.info("Using local kubeconfig for Kubernetes API client");
        } catch (Exception e) {
            log.warn("Local kubeconfig not found or invalid, falling back to in-cluster config: {}", e.getMessage());
            try {
                client = Config.fromCluster();
                log.info("Using in-cluster configuration for Kubernetes API client");
            } catch (Exception ex) {
                log.error("Failed to configure Kubernetes API client from cluster: {}", ex.getMessage());
                throw new IOException("Cannot configure Kubernetes API client", ex);
            }
        }
        io.kubernetes.client.openapi.Configuration.setDefaultApiClient(client);
        return client;
    }

    @Bean
    public BatchV1Api batchV1Api(ApiClient apiClient) {
        return new BatchV1Api(apiClient);
    }

    @Bean
    public CoreV1Api coreV1Api(ApiClient apiClient) {
        return new CoreV1Api(apiClient);
    }
    
    @Bean
    public CustomObjectsApi customObjectsApi(ApiClient apiClient) {
        return new CustomObjectsApi(apiClient);
    }
}
