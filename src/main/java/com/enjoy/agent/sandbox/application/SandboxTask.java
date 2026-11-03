package com.enjoy.agent.sandbox.application;

import java.time.Instant;
import java.util.UUID;

public record SandboxTask(
    UUID id,
    long tenantId,
    long userId,
    String operation,
    String codeCiphertext,
    String stepKey,
    int timeoutSeconds,
    String status,
    boolean cancelRequested,
    String errorCode,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt,
    UUID executionId,
    String argumentsJson) {}
