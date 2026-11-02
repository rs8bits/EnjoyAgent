package com.enjoy.agent.workflow.application;

import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.CurrentUserContext;
import com.enjoy.agent.workflow.domain.entity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Encrypted checkpoints with expiring ownership. An interrupted write node must be reconciled
 * manually.
 */
@Service
public class WorkflowCheckpointService {
  private static final InheritableThreadLocal<Scope> CURRENT = new InheritableThreadLocal<>();
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final AesCryptoService crypto;
  private final org.springframework.transaction.support.TransactionTemplate transaction;

  public WorkflowCheckpointService(JdbcTemplate db, ObjectMapper json, AesCryptoService crypto) {
    this.db = db;
    this.json = json;
    this.crypto = crypto;
    transaction =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                java.util.Objects.requireNonNull(db.getDataSource())));
  }

  private void fenced(Scope scope, Runnable mutation) {
    transaction.executeWithoutResult(
        status -> {
          db.queryForList(
              "SELECT execution_id FROM workflow_run_state WHERE execution_id=? FOR UPDATE",
              scope.id);
          scope.checkActiveOwner();
          mutation.run();
        });
  }

  public Long previousChatExecution(Long tenant, Long user, Long message) {
    var rows =
        db.queryForList(
            "SELECT execution_id FROM workflow_run_state WHERE tenant_id=? AND user_id=? AND"
                + " chat_message_id=?",
            tenant,
            user,
            message);
    return rows.isEmpty() ? null : ((Number) rows.getFirst().get("execution_id")).longValue();
  }

  public Scope open(
      WorkflowExecution execution,
      Long user,
      Long message,
      Long model,
      String input,
      List<WorkflowNode> nodes,
      List<WorkflowEdge> edges,
      boolean resume) {
    String fingerprint = fingerprint(nodes, edges);
    String owner = UUID.randomUUID().toString();
    db.update(
        """
INSERT INTO workflow_run_state(execution_id,tenant_id,user_id,chat_message_id,model_config_id,input_ciphertext,graph_digest,graph_ciphertext,status)
VALUES(?,?,?,?,?,?,?,?,'NEW') ON CONFLICT(execution_id) DO NOTHING
""",
        execution.getId(),
        execution.getTenant().getId(),
        user,
        message,
        model,
        crypto.encrypt(input),
        fingerprint,
        crypto.encrypt(encode(snapshot(nodes, edges))));
    int claimed =
        db.update(
            """
UPDATE workflow_run_state SET status='RUNNING',owner_token=?,lease_until=NOW()+INTERVAL '30 seconds'
WHERE execution_id=? AND user_id=? AND graph_digest=? AND
(status IN ('NEW','FAILED','WAITING_APPROVAL','CANCELLED') OR (status='COMPLETED' AND ?) OR (status='RUNNING' AND lease_until<NOW()))
""",
            owner,
            execution.getId(),
            user,
            fingerprint,
            resume);
    if (claimed != 1) throw error("WORKFLOW_RESUME_CONFLICT", "流程正在运行、已完成或定义已改变，无法恢复");
    return new Scope(this, execution.getId(), owner);
  }

  public Map<String, Object> resumable(Long id) {
    var u = CurrentUserContext.requireCurrentUser();
    var rows =
        db.queryForList(
            "SELECT * FROM workflow_run_state WHERE execution_id=? AND tenant_id=? AND user_id=?",
            id,
            u.tenantId(),
            u.userId());
    if (rows.isEmpty()) throw error("WORKFLOW_EXECUTION_NOT_FOUND", "未找到当前用户的执行记录");
    var result = new HashMap<>(rows.getFirst());
    result.put("input", crypto.decrypt((String) result.remove("input_ciphertext")));
    try {
      result.put(
          "canvas",
          json.readValue(
              crypto.decrypt((String) result.remove("graph_ciphertext")),
              com.enjoy.agent.workflow.api.request.SaveWorkflowCanvasRequest.class));
    } catch (Exception ex) {
      throw new IllegalStateException("Invalid workflow snapshot", ex);
    }
    return result;
  }

  public List<Map<String, Object>> nodes(Long id) {
    resumable(id);
    return db.queryForList(
        "SELECT node_key,node_type,status,attempts,started_at,completed_at,error_code FROM"
            + " workflow_node_checkpoint WHERE execution_id=? ORDER BY started_at,node_key",
        id);
  }

  public static NodeExecutionResult execute(
      WorkflowNode node, String key, Supplier<NodeExecutionResult> action) {
    Scope scope = CURRENT.get();
    return scope == null ? action.get() : scope.service.node(scope, node, key, action);
  }

  public static void checkActive() {
    Scope s = CURRENT.get();
    if (s != null) s.checkActiveOwner();
  }

  private NodeExecutionResult node(
      Scope s, WorkflowNode n, String key, Supplier<NodeExecutionResult> action) {
    s.checkActiveOwner();
    var rows =
        db.queryForList(
            "SELECT * FROM workflow_node_checkpoint WHERE execution_id=? AND node_key=?",
            s.id,
            key);
    if (!rows.isEmpty()) {
      var r = rows.getFirst();
      String status = (String) r.get("status");
      if ("COMPLETED".equals(status)) {
        try {
          return json.readValue(
              crypto.decrypt((String) r.get("result_ciphertext")), NodeExecutionResult.class);
        } catch (Exception ex) {
          throw new IllegalStateException("Invalid checkpoint", ex);
        }
      }
      if (((Number) r.get("attempts")).intValue() >= 3 && !isWrite(n))
        throw error("WORKFLOW_NODE_RETRY_EXHAUSTED", "节点已达到三次尝试上限");
      if (isWrite(n) && !"WAITING_APPROVAL".equals(status))
        throw error("WORKFLOW_WRITE_OUTCOME_UNKNOWN", "写节点结果不确定，请先核对外部系统，不能自动重试");
    }
    fenced(
        s,
        () ->
            db.update(
                """
INSERT INTO workflow_node_checkpoint(execution_id,node_key,node_type,status,attempts,started_at)
VALUES(?,?,?,'RUNNING',1,NOW()) ON CONFLICT(execution_id,node_key)
DO UPDATE SET status='RUNNING',attempts=workflow_node_checkpoint.attempts+1,started_at=NOW(),completed_at=NULL,error_code=NULL
""",
                s.id,
                key,
                n.getNodeType().name()));
    try {
      NodeExecutionResult result = action.get();
      s.checkActiveOwner();
      fenced(
          s,
          () ->
              db.update(
                  "UPDATE workflow_node_checkpoint SET"
                      + " status='COMPLETED',result_ciphertext=?,completed_at=NOW() WHERE"
                      + " execution_id=? AND node_key=?",
                  crypto.encrypt(encode(result)),
                  s.id,
                  key));
      return result;
    } catch (RuntimeException ex) {
      String code = ex instanceof ApiException a ? a.getCode() : "NODE_FAILED";
      String status =
          "MCP_ACTION_CONFIRMATION_REQUIRED".equals(code)
              ? "WAITING_APPROVAL"
              : (isWrite(n) ? "UNKNOWN" : "FAILED");
      if (s.owns())
        fenced(
            s,
            () ->
                db.update(
                    "UPDATE workflow_node_checkpoint SET status=?,error_code=?,completed_at=NOW()"
                        + " WHERE execution_id=? AND node_key=?",
                    status,
                    code,
                    s.id,
                    key));
      throw ex;
    }
  }

  private boolean isWrite(WorkflowNode n) {
    return n.getNodeType().name().equals("TOOL") || n.getNodeType().name().equals("LOOP");
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private com.enjoy.agent.workflow.api.request.SaveWorkflowCanvasRequest snapshot(
      List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
    var items =
        nodes.stream()
            .map(
                n ->
                    new com.enjoy.agent.workflow.api.request.SaveWorkflowCanvasRequest.NodeItem(
                        n.getName(),
                        n.getNodeKey(),
                        n.getNodeType().name(),
                        normalizedConfig(n, nodes),
                        n.getPositionX(),
                        n.getPositionY()))
            .toList();
    var links =
        edges.stream()
            .map(
                e ->
                    new com.enjoy.agent.workflow.api.request.SaveWorkflowCanvasRequest.EdgeItem(
                        nodes.indexOf(e.getSourceNode()),
                        nodes.indexOf(e.getTargetNode()),
                        e.getSourceHandle(),
                        e.getTargetHandle()))
            .toList();
    return new com.enjoy.agent.workflow.api.request.SaveWorkflowCanvasRequest(items, links);
  }

  private String normalizedConfig(WorkflowNode node, List<WorkflowNode> nodes) {
    try {
      var config = json.readTree(node.getConfigJson());
      if (node.getNodeType().name().equals("LOOP")
          && config.has("bodyNodeIds")
          && !config.has("bodyNodeKeys")) {
        var keys = json.createArrayNode();
        for (var id : config.get("bodyNodeIds"))
          keys.add(
              nodes.stream()
                  .filter(n -> java.util.Objects.equals(n.getId(), id.asLong()))
                  .findFirst()
                  .orElseThrow()
                  .getNodeKey());
        ((com.fasterxml.jackson.databind.node.ObjectNode) config).set("bodyNodeKeys", keys);
        ((com.fasterxml.jackson.databind.node.ObjectNode) config).remove("bodyNodeIds");
      }
      return encode(config);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid node configuration", ex);
    }
  }

  public String fingerprint(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
    var ns =
        nodes.stream()
            .map(n -> List.of(n.getNodeKey(), n.getNodeType().name(), normalizedConfig(n, nodes)))
            .sorted(Comparator.comparing(v -> v.getFirst()))
            .toList();
    var es =
        edges.stream()
            .map(
                e ->
                    Arrays.asList(
                        e.getSourceNode().getNodeKey(),
                        e.getTargetNode().getNodeKey(),
                        e.getSourceHandle(),
                        e.getTargetHandle()))
            .sorted(Comparator.comparing(Object::toString))
            .toList();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(encode(List.of(ns, es)).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private ApiException error(String code, String message) {
    return new ApiException(code, message, HttpStatus.CONFLICT);
  }

  public static final class Scope implements AutoCloseable {
    private final WorkflowCheckpointService service;
    private final Long id;
    private final String owner;
    private final Scope previous;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean lost;

    Scope(WorkflowCheckpointService service, Long id, String owner) {
      this.service = service;
      this.id = id;
      this.owner = owner;
      previous = CURRENT.get();
      CURRENT.set(this);
      heartbeat.scheduleAtFixedRate(
          () -> {
            try {
              if (service.db.update(
                      "UPDATE workflow_run_state SET lease_until=NOW()+INTERVAL '30 seconds' WHERE"
                          + " execution_id=? AND owner_token=? AND status='RUNNING' AND"
                          + " lease_until>NOW()",
                      id,
                      owner)
                  != 1) lost = true;
            } catch (Exception ex) {
              lost = true;
            }
          },
          5,
          5,
          TimeUnit.SECONDS);
    }

    boolean owns() {
      return !lost
          && Boolean.TRUE.equals(
              service.db.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM workflow_run_state WHERE execution_id=? AND"
                      + " owner_token=? AND status='RUNNING' AND lease_until>NOW())",
                  Boolean.class,
                  id,
                  owner));
    }

    void checkActiveOwner() {
      if (!owns()) throw service.error("WORKFLOW_LEASE_LOST", "执行所有权已失效");
    }

    public boolean finish(String status) {
      return service.db.update(
              "UPDATE workflow_run_state SET status=?,lease_until=NOW() WHERE execution_id=? AND"
                  + " owner_token=? AND status='RUNNING' AND lease_until>NOW()",
              status,
              id,
              owner)
          == 1;
    }

    @Override
    public void close() {
      heartbeat.shutdownNow();
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }
}
