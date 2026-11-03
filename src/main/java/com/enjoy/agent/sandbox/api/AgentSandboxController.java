package com.enjoy.agent.sandbox.api;

import com.enjoy.agent.shared.api.ApiResponse;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.CurrentUserContext;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents/{id}/sandbox")
public class AgentSandboxController {
  private final JdbcTemplate db;

  public AgentSandboxController(JdbcTemplate db) {
    this.db = db;
  }

  public record Capability(boolean enabled) {}

  private void require(long id) {
    var user = CurrentUserContext.requireCurrentUser();
    if (!Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM agent WHERE id=? AND tenant_id=?)",
            Boolean.class,
            id,
            user.tenantId())))
      throw new ApiException("AGENT_NOT_FOUND", "Agent 不存在", HttpStatus.NOT_FOUND);
  }

  @GetMapping
  public ApiResponse<Map<String, Boolean>> get(@PathVariable long id) {
    require(id);
    var rows =
        db.queryForList(
            "SELECT enabled FROM agent_sandbox_capability WHERE agent_id=?", Boolean.class, id);
    return ApiResponse.success(Map.of("enabled", !rows.isEmpty() && rows.getFirst()));
  }

  @PutMapping
  @PreAuthorize("hasRole('OWNER')")
  public ApiResponse<Capability> set(@PathVariable long id, @RequestBody Capability request) {
    require(id);
    db.update(
        "INSERT INTO agent_sandbox_capability(agent_id,enabled) VALUES(?,?) ON CONFLICT(agent_id)"
            + " DO UPDATE SET enabled=EXCLUDED.enabled",
        id,
        request.enabled());
    return ApiResponse.success(request);
  }
}
