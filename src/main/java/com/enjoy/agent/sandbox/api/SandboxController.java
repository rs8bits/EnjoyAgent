package com.enjoy.agent.sandbox.api;

import com.enjoy.agent.sandbox.application.*;
import com.enjoy.agent.shared.api.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sandbox")
public class SandboxController {
  private final SandboxTaskService tasks;
  private final SandboxProperties properties;
  private final ManagerClient manager;

  public SandboxController(
      SandboxTaskService tasks, SandboxProperties properties, ManagerClient manager) {
    this.tasks = tasks;
    this.properties = properties;
    this.manager = manager;
  }

  @GetMapping("/status")
  public ApiResponse<Map<String, Object>> status() {
    return ApiResponse.success(
        Map.of(
            "enabled",
            properties.enabled(),
            "ready",
            manager.ready(),
            "maxFileBytes",
            properties.maxFileBytes(),
            "defaultTimeoutSeconds",
            properties.defaultTimeoutSeconds()));
  }

  @PostMapping("/tasks")
  public ApiResponse<SandboxTaskService.Summary> create(
      @Valid @RequestBody SandboxTaskService.CreateRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return ApiResponse.success(tasks.create(request, key));
  }

  @GetMapping("/tasks")
  public ApiResponse<PagedResponse<SandboxTaskService.Summary>> list(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(tasks.list(page, size));
  }

  @GetMapping("/tasks/{id}")
  public ApiResponse<SandboxTaskService.Detail> get(@PathVariable UUID id) {
    return ApiResponse.success(tasks.detail(id));
  }

  @PostMapping("/tasks/{id}/cancel")
  public ApiResponse<SandboxTaskService.Summary> cancel(@PathVariable UUID id) {
    return ApiResponse.success(tasks.cancel(id));
  }
}
