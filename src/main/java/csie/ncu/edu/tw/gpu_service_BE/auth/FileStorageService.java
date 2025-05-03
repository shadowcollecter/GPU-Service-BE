package csie.ncu.edu.tw.gpu_service_BE.auth;

import io.minio.MinioClient;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Service
public class FileStorageService {
    @Value("${minio.bucket.name}")
    private String bucketName;

    private final MinioClient minioClient;

    @Autowired
    public FileStorageService(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    /**
     * Stores the original notebook file under Minio S3-compatible storage.
     * Creates bucket if not exists.
     * Returns generated submissionId.
     */
    public String storeOriginal(String userId, MultipartFile file) {
        // generate unique composite ID: UUID + UTC+8 timestamp
        String rawId = UUID.randomUUID().toString();
        String timestamp = LocalDateTime.now(ZoneOffset.ofHours(8))
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        // ensure total length = 36 (UUID) + 14 (timestamp) = 50
        String submissionId = rawId + timestamp;
        String folderName = submissionId;
        String objectName = String.format("%s/%s/%s", userId, folderName, file.getOriginalFilename());
        try {
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to store file to Minio", e);
        }
        return submissionId;
    }
}