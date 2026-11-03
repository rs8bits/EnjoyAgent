package com.enjoy.agent.sandbox.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("enjoy.sandbox")
public record SandboxProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("http://127.0.0.1:8091") URI managerUrl,
    @DefaultValue(".sandbox/manager-token") Path tokenFile,
    @DefaultValue("20971520") @Min(1) long maxFileBytes,
    @DefaultValue("2147483648") @Min(1) long maxStoredBytesPerUser,
    @DefaultValue("1000") @Min(1) int maxFilesPerUser,
    @DefaultValue("20") @Min(1) int maxActiveTasksPerTenant,
    @DefaultValue("209715200") @Min(1) long maxOutputBytes,
    @DefaultValue("120") @Min(1) @Max(300) int defaultTimeoutSeconds) {}
