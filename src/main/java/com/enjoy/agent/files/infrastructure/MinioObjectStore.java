package com.enjoy.agent.files.infrastructure;

import com.enjoy.agent.files.application.ObjectStore;
import com.enjoy.agent.shared.config.MinioProperties;
import com.enjoy.agent.shared.exception.ApiException;
import io.minio.*;
import java.io.InputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MinioObjectStore implements ObjectStore {
  private final MinioClient client;
  private final String bucket;

  public MinioObjectStore(MinioProperties properties) {
    client =
        MinioClient.builder()
            .endpoint(properties.getEndpoint())
            .credentials(properties.getAccessKey(), properties.getSecretKey())
            .build();
    bucket = properties.getBucket();
  }

  public String bucket() {
    return bucket;
  }

  public void put(String key, InputStream content, long size, String mediaType) {
    try {
      if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
        try {
          client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        } catch (Exception race) {
          if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) throw race;
        }
      }
      client.putObject(
          PutObjectArgs.builder().bucket(bucket).object(key).stream(content, size, -1)
              .contentType(mediaType)
              .build());
    } catch (Exception e) {
      throw storageError();
    }
  }

  public InputStream open(String bucket, String key) {
    try {
      return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception e) {
      throw storageError();
    }
  }

  public void remove(String bucket, String key) {
    try {
      client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception e) {
      throw storageError();
    }
  }

  private ApiException storageError() {
    return new ApiException("FILE_STORAGE_UNAVAILABLE", "文件存储暂不可用，请稍后重试", HttpStatus.BAD_GATEWAY);
  }
}
