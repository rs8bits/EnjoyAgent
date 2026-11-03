package com.enjoy.agent.files.application;

import java.time.Instant;
import java.util.UUID;

public record FileAsset(
    UUID id,
    String name,
    String mediaType,
    long sizeBytes,
    String sha256,
    String kind,
    String status,
    Instant createdAt,
    UUID taskId,
    String stepKey,
    String bucket,
    String objectKey) {
  // Object coordinates are internal; controllers expose the public view only.
  public View view() {
    return new View(
        id, name, mediaType, sizeBytes, sha256, kind, status, createdAt, taskId, stepKey);
  }

  public record View(
      UUID id,
      String name,
      String mediaType,
      long sizeBytes,
      String sha256,
      String kind,
      String status,
      Instant createdAt,
      UUID taskId,
      String stepKey) {}
}
