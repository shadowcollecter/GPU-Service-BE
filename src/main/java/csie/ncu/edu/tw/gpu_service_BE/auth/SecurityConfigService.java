package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class SecurityConfigService {
    @Autowired
    private SecurityConfigRepository configRepository;

    @Value("${security.check.threshold:8.0}")
    private double defaultThreshold;

    /**
     * 取得最新一筆安全檢查參數設定
     */
    public SecurityConfigEntity getLatestConfig() {
        Optional<SecurityConfigEntity> opt = configRepository.findTopByOrderByUpdatedAtDesc();
        return opt.orElse(null);
    }

    /**
     * 取得風險閾值，若資料庫無設定則使用預設值
     */
    public double getThreshold() {
        SecurityConfigEntity cfg = getLatestConfig();
        return cfg != null ? cfg.getRiskThreshold() : defaultThreshold;
    }
}
