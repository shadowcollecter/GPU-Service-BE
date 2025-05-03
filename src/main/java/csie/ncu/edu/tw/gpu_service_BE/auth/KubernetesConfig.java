package csie.ncu.edu.tw.gpu_service_BE.auth;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.util.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class KubernetesConfig {
    @Bean
    public ApiClient apiClient() throws IOException {
        ApiClient client;
        try {
            // in-cluster config
            client = Config.fromCluster();
        } catch (Exception e) {
            // fallback to local kubeconfig
            client = Config.defaultClient();
        }
        return client;
    }

    @Bean
    public CoreV1Api coreV1Api(ApiClient apiClient) {
        return new CoreV1Api(apiClient);
    }

    @Bean
    public CustomObjectsApi customObjectsApi(ApiClient apiClient) {
        return new CustomObjectsApi(apiClient);
    }

    @Bean
    public BatchV1Api batchV1Api(ApiClient apiClient) {
        return new BatchV1Api(apiClient);
    }
}