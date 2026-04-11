package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.shared.config.MinioProperties;
import com.enjoy.agent.shared.exception.ApiException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * MinIO 文件存储服务。
 */
@Service
public class MinioStorageService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public MinioStorageService(MinioProperties minioProperties) {
        this.minioProperties = minioProperties;
        this.minioClient = MinioClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();
    }

    /**
     * 上传知识库文档到 MinIO。
     */
    public StoredObjectInfo uploadKnowledgeDocument(Long knowledgeBaseId, String originalFilename, String contentType, byte[] fileBytes) {
        String bucket = minioProperties.getBucket();
        String objectKey = buildObjectKey(knowledgeBaseId, originalFilename);

        try {
            ensureBucketExists(bucket);
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .stream(new ByteArrayInputStream(fileBytes), fileBytes.length, -1)
                            .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                            .build()
            );
            return new StoredObjectInfo(bucket, objectKey);
        } catch (Exception ex) {
            throw new ApiException("MINIO_UPLOAD_FAILED", "Failed to upload file to MinIO", HttpStatus.BAD_GATEWAY);
        }
    }

    /**
     * 上传共享市场安装包中的文档快照。
     */
    public StoredObjectInfo uploadMarketAssetDocument(String scope, String originalFilename, String contentType, byte[] fileBytes) {
        String bucket = minioProperties.getBucket();
        String objectKey = buildMarketObjectKey(scope, originalFilename);

        try {
            ensureBucketExists(bucket);
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .stream(new ByteArrayInputStream(fileBytes), fileBytes.length, -1)
                            .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                            .build()
            );
            return new StoredObjectInfo(bucket, objectKey);
        } catch (Exception ex) {
            throw new ApiException("MINIO_UPLOAD_FAILED", "Failed to upload market package file to MinIO", HttpStatus.BAD_GATEWAY);
        }
    }

    /**
     * 下载 MinIO 中的对象内容。
     */
    public byte[] downloadObject(String bucket, String objectKey) {
        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .build()
        ); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            inputStream.transferTo(outputStream);
            return outputStream.toByteArray();
        } catch (Exception ex) {
            throw new ApiException("MINIO_DOWNLOAD_FAILED", "Failed to download file from MinIO", HttpStatus.BAD_GATEWAY);
        }
    }

    /**
     * 删除 MinIO 中的对象。
     */
    public void removeObject(String bucket, String objectKey) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build()
            );
        } catch (Exception ex) {
            throw new ApiException("MINIO_DELETE_FAILED", "Failed to delete file from MinIO", HttpStatus.BAD_GATEWAY);
        }
    }

    /**
     * 确保 bucket 已存在。
     */
    private void ensureBucketExists(String bucket) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    /**
     * 构造 MinIO 对象路径。
     */
    private String buildObjectKey(Long knowledgeBaseId, String originalFilename) {
        String safeFileName = originalFilename == null || originalFilename.isBlank()
                ? "document.bin"
                : originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
        return "knowledge-base/%s/%s-%s".formatted(knowledgeBaseId, UUID.randomUUID(), safeFileName);
    }

    private String buildMarketObjectKey(String scope, String originalFilename) {
        String safeScope = scope == null || scope.isBlank()
                ? "shared"
                : scope.replaceAll("[^a-zA-Z0-9/_-]", "_");
        String safeFileName = originalFilename == null || originalFilename.isBlank()
                ? "document.bin"
                : originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
        return "market-asset/%s/%s-%s".formatted(safeScope, UUID.randomUUID(), safeFileName);
    }
}
