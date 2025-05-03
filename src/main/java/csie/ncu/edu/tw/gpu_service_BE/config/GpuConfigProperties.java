package csie.ncu.edu.tw.gpu_service_BE.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "gpu")
public class GpuConfigProperties {
    private List<GpuTypeConfig> types;
    private CpuConfig cpu;

    public List<GpuTypeConfig> getTypes() {
        return types;
    }
    public void setTypes(List<GpuTypeConfig> types) {
        this.types = types;
    }
    public CpuConfig getCpu() {
        return cpu;
    }
    public void setCpu(CpuConfig cpu) {
        this.cpu = cpu;
    }

    public static class GpuTypeConfig {
        private String type;
        private String yaml;
        private String queue;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getYaml() { return yaml; }
        public void setYaml(String yaml) { this.yaml = yaml; }
        public String getQueue() { return queue; }
        public void setQueue(String queue) { this.queue = queue; }
    }

    public static class CpuConfig {
        private String yaml;
        private String queue;

        public String getYaml() { return yaml; }
        public void setYaml(String yaml) { this.yaml = yaml; }
        public String getQueue() { return queue; }
        public void setQueue(String queue) { this.queue = queue; }
    }
}