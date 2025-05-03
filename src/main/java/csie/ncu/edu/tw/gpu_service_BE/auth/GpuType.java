package csie.ncu.edu.tw.gpu_service_BE.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;

@Entity
@Table(name = "gpu_type")
public class GpuType {
    @Id
    private String type;

    @Column(nullable = false)
    private boolean enabled = true;

    protected GpuType() {}

    public GpuType(String type) {
        this.type = type;
        this.enabled = true;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}