package com.enjoy.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** Allocation boundary reserved for a future clean prewarmed pool. */
public interface SandboxProvider {
  record Allocation(String containerId, String imageId) {}

  Allocation acquire(UUID id, Path directory, Protocol.Spec spec)
      throws IOException, InterruptedException;

  void release(String containerId);
}
