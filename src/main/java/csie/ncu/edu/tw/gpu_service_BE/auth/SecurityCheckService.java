package csie.ncu.edu.tw.gpu_service_BE.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class SecurityCheckService {

    private static final Logger logger = LoggerFactory.getLogger(SecurityCheckService.class);

    @Autowired
    private SecurityConfigService securityConfigService;

    @Autowired
    private LlmHealthCheckService llmHealthCheckService;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Analyzes notebook file, extracts code cells, and returns a risk score.
     * Returns null if analysis is unavailable.
     */
    public Double checkRisk(String submissionId, MultipartFile file) {
        try {
            JsonNode root = mapper.readTree(file.getInputStream());
            JsonNode cells = root.get("cells");
            if (cells == null || !cells.isArray()) {
                return null;
            }
            List<String> codeBlocks = new ArrayList<>();
            for (JsonNode cell : cells) {
                if ("code".equals(cell.path("cell_type").asText())) {
                    JsonNode source = cell.get("source");
                    if (source != null && source.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode line : source) {
                            sb.append(line.asText());
                        }
                        codeBlocks.add(sb.toString());
                    }
                }
            }
            if (codeBlocks.isEmpty()) {
                return 0.0;
            }
            // concatenate and send to LLM for risk analysis
            String combined = String.join("\n", codeBlocks);
            logger.debug("SecurityCheckService combined prompt: {}", combined);
            return llmHealthCheckService.analyzeRisk(submissionId, combined);
        } catch (IOException e) {
            return null;
        }
    }

    public double getThreshold() {
        return securityConfigService.getThreshold();
    }
}
