package com.enjoy.sandbox;

import java.util.*;

public final class Protocol {
  private Protocol() {}

  public record Input(String name, long sizeBytes, String sha256) {}

  public record Spec(
      String tenantKey,
      String operation,
      String code,
      int timeoutSeconds,
      List<Input> inputs,
      Map<String, Object> arguments) {
    public Spec(
        String tenantKey, String operation, String code, int timeoutSeconds, List<Input> inputs) {
      this(tenantKey, operation, code, timeoutSeconds, inputs, Map.of());
    }

    public Spec {
      arguments = arguments == null ? Map.of() : arguments;
    }
  }

  public record Output(UUID id, String name, long sizeBytes, String sha256) {}

  public record Usage(
      long wallTimeMs,
      long cpuTimeNanos,
      long sampledPeakMemoryBytes,
      long blockReadBytes,
      long blockWriteBytes) {}

  public record Result(
      UUID executionId,
      String status,
      Integer exitCode,
      boolean oomKilled,
      String containerId,
      String imageId,
      String stdout,
      String stderr,
      boolean stdoutTruncated,
      boolean stderrTruncated,
      Usage resourceUsage,
      List<Output> outputs,
      String errorCode,
      String startedAt,
      String finishedAt) {}
}
