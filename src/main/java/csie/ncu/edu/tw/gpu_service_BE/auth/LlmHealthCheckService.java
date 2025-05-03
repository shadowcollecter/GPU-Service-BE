package csie.ncu.edu.tw.gpu_service_BE.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import csie.ncu.edu.tw.gpu_service_BE.auth.SecurityConfigService;

@Service
public class LlmHealthCheckService {
    private static final Logger logger = LoggerFactory.getLogger(LlmHealthCheckService.class);

    @Autowired
    private LlmApiKeyRepository apiKeyRepo;
    @Autowired
    private LlmApiStatsRepository statsRepo;
    @Autowired
    private SecurityConfigService securityConfigService;

    @Scheduled(cron = "0 0 0 * * *") // every day at midnight
    public void dailyHealthCheck() {
        List<LlmApiKey> keys = apiKeyRepo.findByActiveTrue();
        long total = 0, success = 0, fail = 0, totalRespTimeMs = 0;
        String lastFailureMsg = null;
        for (LlmApiKey key : keys) {
            total++;
            WebClient client = WebClient.builder()
                .baseUrl("https://openrouter.ai/api/v1/chat/completions")
                .defaultHeader("Authorization", "Bearer " + key.getApiKey())
                .build();
            try {
                long start = System.nanoTime();
                // simple ping message
                Map<String, Object> payload = Map.of(
                    "model", key.getModel(),
                    "messages", List.of(Map.of(
                        "role", "user",
                        "content", "health check"
                    ))
                );
                String body = client.post()
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                success++;
                totalRespTimeMs += elapsedMs;
            } catch (Exception ex) {
                fail++;
                lastFailureMsg = ex.getMessage();
            }
        }
        // persist metrics
        LlmApiStats stats = statsRepo.findAll().stream().findFirst().orElse(new LlmApiStats());
        stats.setTotalCalls(total);
        stats.setSuccessfulCalls(success);
        stats.setFailedCalls(fail);
        stats.setTotalResponseTime(totalRespTimeMs);
        stats.setLastFailure(lastFailureMsg);
        statsRepo.save(stats);
    }

    /**
     * Performs LLM risk analysis by sending code to LLM API.
     * Returns risk score (1-10), or null if unavailable.
     */
    public Double analyzeRisk(String submissionId, String code) {
        // build prompt from database-configured template
        var cfg = securityConfigService.getLatestConfig();
        String template = cfg != null ? cfg.getPromptTemplate() : "你是一名程式碼安全審稽員，負責檢查以下程式碼是否存在安全風險。僅輸出一個 1 到 10 的風險指數，越高表示風險越大。程式碼如下：";
        String prompt = template + "\n" + code;
        logger.debug("LLM prompt sent: {}", prompt);
        List<LlmApiKey> keys = apiKeyRepo.findByActiveTrue().stream()
            .filter(k -> k.getApiKey() != null && !k.getApiKey().isBlank())
            .collect(Collectors.toList());
        if (keys.isEmpty()) {
            return null;
        }
        LlmApiKey key = keys.get(0);
        logger.debug("Using LLM API key id={} for risk analysis", key.getId());
        WebClient client = WebClient.builder()
            .baseUrl("https://openrouter.ai/api/v1/chat/completions")
            .defaultHeader("Authorization", "Bearer " + key.getApiKey())
            .build();
        ObjectMapper mapper = new ObjectMapper();
        try {
            // single attempt: prepare and send chat messages
            List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", template),
                Map.of("role", "user", "content", code)
            );
            Map<String, Object> payloadOne = Map.of(
                "model", key.getModel(),
                "messages", messages
            );
            String bodyOne = client.post()
                .bodyValue(payloadOne)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(60));
            logger.debug("LLM raw response: {}", bodyOne);
            JsonNode rootOne = mapper.readTree(bodyOne);
            String contentOne = rootOne.path("choices").get(0).path("message").path("content").asText();
            logger.debug("LLM response content: {}", contentOne);
            String digitsOne = contentOne.replaceAll("[^0-9]", "");
            return digitsOne.isEmpty() ? null : Double.valueOf(Integer.parseInt(digitsOne));
        } catch (Exception ex) {
            logger.error("LLM risk analysis failed for submission={}: {}", submissionId, ex.getMessage());
            return null;
        }
    }

    /**
     * Performs LLM risk analysis and returns full JSON detail as JsonNode.
     */
    public com.fasterxml.jackson.databind.JsonNode analyzeRiskFull(String submissionId, String code) {
        var cfg = securityConfigService.getLatestConfig();
        String template = cfg != null ? cfg.getPromptTemplate() : "你是一名程式碼安全審稽員，負責檢查以下程式碼是否存在安全風險。僅輸出符合 JSON 格式的檢測結果。程式碼如下：";
        List<LlmApiKey> keys = apiKeyRepo.findByActiveTrue().stream()
            .filter(k -> k.getApiKey() != null && !k.getApiKey().isBlank())
            .collect(Collectors.toList());
        if (keys.isEmpty()) return null;
        LlmApiKey key = keys.get(0);
        WebClient client = WebClient.builder()
            .baseUrl("https://openrouter.ai/api/v1/chat/completions")
            .defaultHeader("Authorization", "Bearer " + key.getApiKey())
            .build();
        ObjectMapper mapper = new ObjectMapper();
        try {
            // single call for full JSON analysis
            List<Map<String, Object>> messagesFull = List.of(
                Map.of("role", "system", "content", template),
                Map.of("role", "user", "content", code)
            );
            Map<String, Object> payloadFull = Map.of(
                "model", key.getModel(),
                "messages", messagesFull
            );
            String bodyFull = client.post()
                .bodyValue(payloadFull)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(60));
            logger.debug("LLM raw response (full analysis): {}", bodyFull);
            if (bodyFull == null) return null;
            JsonNode rootFull = mapper.readTree(bodyFull);
            String contentFull = rootFull.path("choices").get(0).path("message").path("content").asText();
            logger.debug("LLM content JSON string (raw): {}", contentFull);
            // remove markdown code fences if present
            String cleaned = contentFull.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(?:json)?", "").replaceAll("```$", "").trim();
                logger.debug("LLM content JSON string (cleaned): {}", cleaned);
            }
            JsonNode analysisJsonOne = mapper.readTree(cleaned);
            logger.debug("LLM parsed analysis JSON: {}", analysisJsonOne);
            return analysisJsonOne;
        } catch (Exception ex) {
            logger.error("LLM full analysis failed for submission={}: {}", submissionId, ex.getMessage());
            return null;
        }
    }
}