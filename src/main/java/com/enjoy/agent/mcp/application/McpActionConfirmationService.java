package com.enjoy.agent.mcp.application;

import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.runtime.RuntimeEvents;
import com.enjoy.agent.shared.security.CurrentUserContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Frozen server-side proposals. Claim commits before the network call; unknown outcomes are never
 * replayed.
 */
@Service
public class McpActionConfirmationService {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final AesCryptoService crypto;
  private final McpRuntimeService runtime;
  private final McpGatewayService gateway;
  private final Clock clock;

  public McpActionConfirmationService(
      JdbcTemplate db,
      ObjectMapper json,
      AesCryptoService crypto,
      McpRuntimeService runtime,
      McpGatewayService gateway,
      Clock clock) {
    this.db = db;
    this.json = json;
    this.crypto = crypto;
    this.runtime = runtime;
    this.gateway = gateway;
    this.clock = clock;
  }

  public McpToolCallResult requireResult(
      Long tenant,
      Long user,
      Long agent,
      Long session,
      String scope,
      PreparedMcpTool tool,
      Map<String, Object> arguments) {
    if (scope.length() > 160) throw conflict("确认范围无效");
    String payload = encode(canonical(arguments));
    if (payload.length() > 100_000) throw conflict("操作参数过大");
    String digest = hash(payload);
    UUID id = UUID.randomUUID();
    db.update(
        """
INSERT INTO mcp_action_confirmation
(id,tenant_id,user_id,agent_id,session_id,scope_key,tool_id,tool_name,tool_digest,arguments_ciphertext,arguments_digest,status,expires_at)
VALUES (?,?,?,?,?,?,?,?,?,?,?,'PENDING',?) ON CONFLICT (tenant_id,user_id,scope_key,tool_id,arguments_digest) DO NOTHING
""",
        id,
        tenant,
        user,
        agent,
        session,
        scope,
        tool.id(),
        tool.name(),
        toolDigest(tool),
        crypto.encrypt(payload),
        digest,
        java.sql.Timestamp.from(clock.instant().plusSeconds(600)));
    var rows =
        db.queryForList(
            "SELECT * FROM mcp_action_confirmation WHERE tenant_id=? AND user_id=? AND scope_key=?"
                + " AND tool_id=? AND arguments_digest=?",
            tenant,
            user,
            scope,
            tool.id(),
            digest);
    var row = rows.getFirst();
    if ("EXECUTED".equals(row.get("status")))
      return decode(crypto.decrypt((String) row.get("result_ciphertext")), McpToolCallResult.class);
    if (!"PENDING".equals(row.get("status"))) throw conflict("该操作已取消、失败或结果不确定，请核对后创建新请求");
    if (((java.sql.Timestamp) row.get("expires_at")).toInstant().isBefore(clock.instant()))
      throw conflict("确认已过期，请创建新请求");
    var proposal = publicView(row);
    RuntimeEvents.emit("confirmation_required", proposal);
    throw new ApiException(
        "MCP_ACTION_CONFIRMATION_REQUIRED", "请确认具体工具参数后继续；确认完成后重试当前请求。", HttpStatus.CONFLICT);
  }

  public List<Map<String, Object>> pending(Long session) {
    var user = CurrentUserContext.requireCurrentUser();
    return db
        .queryForList(
            "SELECT * FROM mcp_action_confirmation WHERE tenant_id=? AND user_id=? AND session_id=?"
                + " AND (status IN ('EXECUTING','UNKNOWN','FAILED') OR (status='PENDING' AND"
                + " expires_at>?)) ORDER BY created_at DESC LIMIT 20",
            user.tenantId(),
            user.userId(),
            session,
            java.sql.Timestamp.from(clock.instant()))
        .stream()
        .map(this::publicView)
        .toList();
  }

  public List<Map<String, Object>> pendingWorkflow(Long executionId) {
    var user = CurrentUserContext.requireCurrentUser();
    return db
        .queryForList(
            "SELECT * FROM mcp_action_confirmation WHERE tenant_id=? AND user_id=? AND scope_key"
                + " LIKE ? AND (status IN ('EXECUTING','UNKNOWN','FAILED') OR (status='PENDING' AND"
                + " expires_at>?)) ORDER BY created_at DESC LIMIT 20",
            user.tenantId(),
            user.userId(),
            "workflow:" + executionId + ":%",
            java.sql.Timestamp.from(clock.instant()))
        .stream()
        .map(this::publicView)
        .toList();
  }

  public Map<String, Object> confirm(UUID id) {
    var user = CurrentUserContext.requireCurrentUser();
    var row = owned(id);
    if ("EXECUTED".equals(row.get("status"))) return Map.of("id", id, "status", "EXECUTED");
    if (!"PENDING".equals(row.get("status"))
        || ((java.sql.Timestamp) row.get("expires_at")).toInstant().isBefore(clock.instant()))
      throw conflict("确认已过期或已处理");
    if (row.get("session_id") != null
        && !Boolean.TRUE.equals(
            db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM chat_session WHERE id=? AND tenant_id=?)",
                Boolean.class,
                row.get("session_id"),
                user.tenantId()))) throw conflict("原会话已删除");
    if (row.get("scope_key").toString().startsWith("workflow:")
        && user.role() != com.enjoy.agent.tenant.domain.enums.TenantMemberRole.OWNER)
      throw conflict("工作流操作需要租户 OWNER 权限");
    Long toolId = ((Number) row.get("tool_id")).longValue();
    PreparedMcpTool tool = runtime.getRunnableToolForTenant(user.tenantId(), toolId);
    Long agent = row.get("agent_id") == null ? null : ((Number) row.get("agent_id")).longValue();
    if (agent != null
        && runtime.listRunnableToolsForAgent(agent).stream().noneMatch(t -> t.id().equals(toolId)))
      throw conflict("工具绑定已改变");
    if (!toolDigest(tool).equals(row.get("tool_digest"))) throw conflict("工具定义已改变，请重新生成确认");
    String payload = crypto.decrypt((String) row.get("arguments_ciphertext"));
    if (!hash(payload).equals(row.get("arguments_digest"))) throw conflict("确认参数校验失败");
    int claimed =
        db.update(
            "UPDATE mcp_action_confirmation SET status='EXECUTING' WHERE id=? AND tenant_id=? AND"
                + " user_id=? AND status='PENDING' AND expires_at>?",
            id,
            user.tenantId(),
            user.userId(),
            java.sql.Timestamp.from(clock.instant()));
    if (claimed != 1) throw conflict("操作正在执行或已处理，请勿重复提交");
    try {
      Map<String, Object> args = decode(payload, new TypeReference<Map<String, Object>>() {});
      McpToolCallResult result = gateway.callTool(tool, args);
      String status = result.error() ? "FAILED" : "EXECUTED";
      db.update(
          "UPDATE mcp_action_confirmation SET status=?,result_ciphertext=?,completed_at=NOW() WHERE"
              + " id=?",
          status,
          crypto.encrypt(encode(result)),
          id);
      return Map.of(
          "id",
          id,
          "status",
          status,
          "result",
          result.modelVisibleContent() == null ? "" : result.modelVisibleContent());
    } catch (RuntimeException ex) {
      db.update(
          "UPDATE mcp_action_confirmation SET status='UNKNOWN',completed_at=NOW() WHERE id=? AND"
              + " status='EXECUTING'",
          id);
      throw new ApiException(
          "MCP_ACTION_OUTCOME_UNKNOWN", "操作结果不确定，请在外部系统核对，禁止自动重放。", HttpStatus.CONFLICT);
    }
  }

  public void cancel(UUID id) {
    var user = CurrentUserContext.requireCurrentUser();
    owned(id);
    if (db.update(
            "UPDATE mcp_action_confirmation SET status='CANCELLED',completed_at=NOW() WHERE id=?"
                + " AND tenant_id=? AND user_id=? AND status='PENDING'",
            id,
            user.tenantId(),
            user.userId())
        != 1) throw conflict("操作无法取消");
  }

  private Map<String, Object> owned(UUID id) {
    var user = CurrentUserContext.requireCurrentUser();
    var rows =
        db.queryForList(
            "SELECT * FROM mcp_action_confirmation WHERE id=? AND tenant_id=? AND user_id=?",
            id,
            user.tenantId(),
            user.userId());
    if (rows.isEmpty())
      throw new ApiException("MCP_CONFIRMATION_NOT_FOUND", "未找到确认记录", HttpStatus.NOT_FOUND);
    return rows.getFirst();
  }

  private Map<String, Object> publicView(Map<String, Object> row) {
    return Map.of(
        "id",
        row.get("id").toString(),
        "toolId",
        row.get("tool_id"),
        "toolName",
        row.get("tool_name"),
        "arguments",
        redact(
            decode(
                crypto.decrypt((String) row.get("arguments_ciphertext")),
                new TypeReference<Map<String, Object>>() {})),
        "status",
        row.get("status"),
        "expiresAt",
        ((java.sql.Timestamp) row.get("expires_at")).toInstant().toString());
  }

  private Object redact(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new TreeMap<>();
      map.forEach(
          (k, v) ->
              out.put(
                  k.toString(),
                  k.toString().matches("(?i).*(password|secret|token|api.?key|authorization).*")
                      ? "[已隐藏]"
                      : redact(v)));
      return out;
    }
    if (value instanceof List<?> list) return list.stream().map(this::redact).toList();
    return value;
  }

  static Object canonical(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new TreeMap<>();
      map.forEach((k, v) -> out.put(k.toString(), canonical(v)));
      return out;
    }
    if (value instanceof List<?> list)
      return list.stream().map(McpActionConfirmationService::canonical).toList();
    return value;
  }

  String toolDigest(PreparedMcpTool t) {
    return hash(
        encode(
            List.of(
                t.id(),
                t.name(),
                t.description() == null ? "" : t.description(),
                t.inputSchemaJson(),
                t.riskLevel().name(),
                t.server().id(),
                t.server().baseUrl())));
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid action payload", ex);
    }
  }

  private <T> T decode(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid action payload", ex);
    }
  }

  private <T> T decode(String value, TypeReference<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid action payload", ex);
    }
  }

  private ApiException conflict(String message) {
    return new ApiException("MCP_CONFIRMATION_CONFLICT", message, HttpStatus.CONFLICT);
  }
}
