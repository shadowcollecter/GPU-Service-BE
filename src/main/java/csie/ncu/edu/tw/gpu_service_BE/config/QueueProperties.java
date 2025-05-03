package csie.ncu.edu.tw.gpu_service_BE.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "queue")
public class QueueProperties {
    private List<String> names;
    private Map<String, String> redisKeys;

    public List<String> getNames() {
        return names;
    }
    public void setNames(List<String> names) {
        this.names = names;
    }
    public Map<String, String> getRedisKeys() {
        return redisKeys;
    }
    public void setRedisKeys(Map<String, String> redisKeys) {
        this.redisKeys = redisKeys;
    }
}