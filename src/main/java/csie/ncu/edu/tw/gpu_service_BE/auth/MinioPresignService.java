package csie.ncu.edu.tw.gpu_service_BE.auth;

import io.minio.MinioClient;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MinioPresignService {
    @Value("${minio.bucket.name}")
    private String bucketName;

    @Autowired
    private MinioClient minioClient;

    public String getPresignedUrl(String objectName, int expirySeconds) {
        try {
            GetPresignedObjectUrlArgs args = GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucketName)
                .object(objectName)
                .expiry(expirySeconds)
                .build();
            return minioClient.getPresignedObjectUrl(args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate presigned URL", e);
        }
    }
}