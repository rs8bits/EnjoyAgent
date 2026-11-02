package com.enjoy.agent.mcp.api;

import com.enjoy.agent.mcp.application.McpActionConfirmationService;
import com.enjoy.agent.shared.api.ApiResponse;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mcp/confirmations")
public class McpActionConfirmationController {
  private final McpActionConfirmationService service;

  public McpActionConfirmationController(McpActionConfirmationService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<List<Map<String, Object>>> pending(@RequestParam Long sessionId) {
    return ApiResponse.success(service.pending(sessionId));
  }

  @GetMapping("/workflow/{executionId}")
  public ApiResponse<List<Map<String, Object>>> pendingWorkflow(@PathVariable Long executionId) {
    return ApiResponse.success(service.pendingWorkflow(executionId));
  }

  @PostMapping("/{id}/confirm")
  public ApiResponse<Map<String, Object>> confirm(@PathVariable UUID id) {
    return ApiResponse.success(service.confirm(id));
  }

  @PostMapping("/{id}/cancel")
  public ApiResponse<Void> cancel(@PathVariable UUID id) {
    service.cancel(id);
    return ApiResponse.success(null);
  }
}
