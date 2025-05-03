package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"file.storage.base-path=target/test-notebooks"})
public class FileStorageServiceTest {

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private org.springframework.core.env.Environment env;

    @AfterEach
    void cleanup() throws IOException {
        String base = env.getProperty("file.storage.base-path");
        Path baseDir = Paths.get(base);
        if (Files.exists(baseDir)) {
            Files.walk(baseDir)
                 .sorted((a, b) -> b.compareTo(a))
                 .forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void testStoreOriginal_createsFileAndReturnsId() throws IOException {
        String userId = "testuser";
        MockMultipartFile file = new MockMultipartFile(
                "file", "notebook.ipynb", "application/json", "{}".getBytes()
        );
        String submissionId = fileStorageService.storeOriginal(userId, file);
        assertThat(submissionId).isNotBlank();
        String base = env.getProperty("file.storage.base-path");
        Path target = Paths.get(base, userId, submissionId, file.getOriginalFilename());
        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.readAllBytes(target)).contains(file.getBytes());
    }
}