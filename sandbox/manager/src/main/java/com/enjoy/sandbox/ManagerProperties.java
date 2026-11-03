package com.enjoy.sandbox;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("sandbox")
public record ManagerProperties(
    Path workRoot,
    Path tokenFile,
    String image,
    String namespace,
    int maxConcurrent,
    int maxPerTenant,
    long memoryBytes,
    double cpus,
    long maxOutputBytes,
    long maxWorkspaceBytes,
    int maxLogBytes,
    int retentionHours,
    String documentImage) {
  public ManagerProperties(
      Path workRoot,
      Path tokenFile,
      String image,
      String namespace,
      int maxConcurrent,
      int maxPerTenant,
      long memoryBytes,
      double cpus,
      long maxOutputBytes,
      long maxWorkspaceBytes,
      int maxLogBytes,
      int retentionHours) {
    this(
        workRoot,
        tokenFile,
        image,
        namespace,
        maxConcurrent,
        maxPerTenant,
        memoryBytes,
        cpus,
        maxOutputBytes,
        maxWorkspaceBytes,
        maxLogBytes,
        retentionHours,
        image);
  }

  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public ManagerProperties {
    if (workRoot == null || !workRoot.isAbsolute())
      throw new IllegalArgumentException("SANDBOX_WORK_ROOT must be an absolute Docker-host path");
    if (workRoot.toString().contains(",")
        || workRoot.toString().chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(
          "SANDBOX_WORK_ROOT contains unsupported Docker mount characters");
    if (namespace == null || !namespace.matches("[a-zA-Z0-9._-]{1,64}"))
      throw new IllegalArgumentException("Invalid sandbox namespace");
    if (image == null
        || image.isBlank()
        || image.startsWith("-")
        || maxConcurrent < 1
        || maxPerTenant < 1
        || memoryBytes < 67108864
        || cpus <= 0
        || maxOutputBytes < 1
        || maxWorkspaceBytes < maxOutputBytes
        || maxLogBytes < 1
        || maxLogBytes > 1048576
        || retentionHours < 1)
      throw new IllegalArgumentException("Invalid sandbox resource limits");
    workRoot = workRoot.normalize();
    if (documentImage == null || documentImage.isBlank() || documentImage.startsWith("-"))
      throw new IllegalArgumentException("Invalid document image");
  }
}
