package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.security.Principal;

import csie.ncu.edu.tw.gpu_service_BE.auth.SecurityConfigService;

@RestController
@RequestMapping("/api/v1/admin/security")
@PreAuthorize("hasRole('ADMIN')")
public class LlmSecurityAdminController {
    @Autowired
    private LlmApiKeyRepository llmApiKeyRepository;
    @Autowired
    private SecurityConfigRepository securityConfigRepository;
    @Autowired
    private SecurityCheckLogRepository securityCheckLogRepository;
    @Autowired
    private LlmApiStatsRepository llmApiStatsRepository;
    @Autowired
    private SecurityConfigService securityConfigService;

    // LLM API Key CRUD
    @GetMapping("/apikey")
    public List<LlmApiKey> listApiKeys() {
        return llmApiKeyRepository.findAll();
    }
    @PostMapping("/apikey")
    public LlmApiKey saveApiKey(@RequestBody LlmApiKey key) {
        // deactivate existing active keys
        List<LlmApiKey> activeKeys = llmApiKeyRepository.findByActiveTrue();
        for (LlmApiKey oldKey : activeKeys) {
            oldKey.setActive(false);
        }
        llmApiKeyRepository.saveAll(activeKeys);
        // ensure new key is active
        key.setActive(true);
        return llmApiKeyRepository.save(key);
    }
    @DeleteMapping("/apikey/{id}")
    public ResponseEntity<?> deleteApiKey(@PathVariable Long id) {
        llmApiKeyRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "deleted"));
    }
    @PostMapping("/apikey/test")
    public ResponseEntity<?> testApiKey(@RequestBody Map<String, String> body) {
        String apiKey = body.get("apiKey");
        String model = body.get("model");
        try {
            WebClient client = WebClient.builder()
                .baseUrl("https://openrouter.ai/api/v1/chat/completions")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
            Map<String, Object> req = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", "hello"))
            );
            client.post().bodyValue(req)
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10));
            return ResponseEntity.ok(Map.of("success", true, "message", "API Key 測試成功"));
        } catch (WebClientResponseException e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getResponseBodyAsString()));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // LLM 安全檢查參數設定
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        // get active API key with non-empty credentials
        var activeKey = llmApiKeyRepository.findByActiveTrue().stream()
            .filter(k -> k.getApiKey() != null && !k.getApiKey().isBlank()
                     && k.getModel() != null && !k.getModel().isBlank())
            .findFirst();
        String apiKey = activeKey.map(LlmApiKey::getApiKey).orElse("");
        String model  = activeKey.map(LlmApiKey::getModel).orElse("");
        // get latest security config
        var cfg = securityConfigService.getLatestConfig();
        int threshold = cfg != null ? cfg.getRiskThreshold() : 0;
        String prompt = cfg != null ? cfg.getPromptTemplate() : "";
        String fallback = cfg != null ? cfg.getFallbackPolicy().name() : "";
        return Map.of(
            "apiKey", apiKey,
            "model", model,
            "riskThreshold", threshold,
            "promptTemplate", prompt,
            "fallbackPolicy", fallback
        );
    }

    @PostMapping("/config")
    public SecurityConfigEntity saveConfig(@RequestBody SecurityConfigEntity config, Principal principal) {
        config.setUpdatedAt(LocalDateTime.now());
        config.setUpdatedBy(principal.getName());
        return securityConfigRepository.save(config);
    }

    // LLM API 健康狀態（聚合）
    @GetMapping("/status")
    public Map<String, Object> getApiStatus() {
        List<LlmApiStats> stats = llmApiStatsRepository.findAll();
        long total = stats.stream().mapToLong(LlmApiStats::getTotalCalls).sum();
        long success = stats.stream().mapToLong(LlmApiStats::getSuccessfulCalls).sum();
        long fail = stats.stream().mapToLong(LlmApiStats::getFailedCalls).sum();
        long respTime = stats.stream().mapToLong(LlmApiStats::getTotalResponseTime).sum();
        String lastFailure = stats.stream().map(LlmApiStats::getLastFailure).filter(s -> s != null).reduce((a, b) -> b).orElse("");
        double successRate = total > 0 ? (double) success / total : 1.0;
        double avgResp = success > 0 ? (double) respTime / success : 0.0;
        String health = (fail < 3 && successRate > 0.95) ? "HEALTHY" : (fail > 10 ? "ERROR" : "WARNING");
        return Map.of(
            "apiHealth", health,
            "successRate", successRate,
            "avgResponseTime", avgResp,
            "lastFailure", lastFailure
        );
    }

    // LLM 安全檢查報告查詢（分布統計）
    @GetMapping("/report")
    public Map<String, Object> getSecurityReport(
            @RequestParam(required = false) Integer minRisk,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        LocalDateTime fromTime = from != null ? LocalDateTime.parse(from) : null;
        LocalDateTime toTime = to != null ? LocalDateTime.parse(to) : null;
        Page<SecurityCheckLog> pageData;
        if (minRisk != null && fromTime != null && toTime != null) {
            pageData = securityCheckLogRepository.findByRiskScoreGreaterThanEqualAndCheckedAtBetween(minRisk, fromTime, toTime, pageable);
        } else if (minRisk != null) {
            pageData = securityCheckLogRepository.findByRiskScoreGreaterThanEqual(minRisk, pageable);
        } else if (fromTime != null && toTime != null) {
            pageData = securityCheckLogRepository.findByCheckedAtBetween(fromTime, toTime, pageable);
        } else {
            pageData = securityCheckLogRepository.findAll(pageable);
        }
        // 風險分布統計（全域統計，非僅分頁內）
        List<SecurityCheckLog> all = securityCheckLogRepository.findAll();
        Map<Integer, Long> riskDist = all.stream()
                .collect(Collectors.groupingBy(SecurityCheckLog::getRiskScore, Collectors.counting()));
        return Map.of(
                "checks", pageData.getContent(),
                "total", pageData.getTotalElements(),
                "riskDistribution", riskDist
        );
    }
}
