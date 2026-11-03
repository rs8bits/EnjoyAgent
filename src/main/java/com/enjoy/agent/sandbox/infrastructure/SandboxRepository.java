package com.enjoy.agent.sandbox.infrastructure;

import com.enjoy.agent.sandbox.application.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class SandboxRepository {
  private final JdbcTemplate db;
  private final TransactionTemplate transaction;
  private static final String SELECT =
      "SELECT t.*,e.id AS execution_id FROM sandbox_task t JOIN sandbox_execution e ON"
          + " e.task_id=t.id ";

  public record Claim(SandboxTask task, UUID token) {}

  public record InputBinding(UUID fileId, String name) {}

  public SandboxRepository(JdbcTemplate db, PlatformTransactionManager tx) {
    this.db = db;
    transaction = new TransactionTemplate(tx);
  }

  public void lockTenant(long tenant) {
    db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "sandbox:" + tenant);
  }

  public long activeCount(long tenant) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM sandbox_task WHERE tenant_id=? AND completed_at IS NULL",
        Long.class,
        tenant);
  }

  public Optional<SandboxTask> idempotent(long tenant, long user, String key, String hash) {
    var rows =
        db.queryForList(
            "SELECT id,request_hash FROM sandbox_task WHERE tenant_id=? AND user_id=? AND"
                + " idempotency_key=?",
            tenant,
            user,
            key);
    if (rows.isEmpty()) return Optional.empty();
    if (!hash.equals(rows.getFirst().get("request_hash")))
      throw new com.enjoy.agent.shared.exception.ApiException(
          "IDEMPOTENCY_CONFLICT", "相同请求标识已用于其他任务", org.springframework.http.HttpStatus.CONFLICT);
    return Optional.of(internal((UUID) rows.getFirst().get("id")));
  }

  public void insert(
      UUID task,
      UUID execution,
      long tenant,
      long user,
      String key,
      String hash,
      String operation,
      String code,
      String step,
      int timeout,
      List<InputBinding> inputs) {
    db.update(
        "INSERT INTO"
            + " sandbox_task(id,tenant_id,user_id,idempotency_key,request_hash,operation,code_ciphertext,step_key,timeout_seconds)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        task,
        tenant,
        user,
        key,
        hash,
        operation,
        code,
        step,
        timeout);
    db.update("INSERT INTO sandbox_execution(id,task_id) VALUES(?,?)", execution, task);
    int order = 0;
    for (var input : inputs)
      db.update(
          "INSERT INTO sandbox_task_input(task_id,file_id,input_name,input_order) VALUES(?,?,?,?)",
          task,
          input.fileId(),
          input.name(),
          order++);
  }

  public Optional<SandboxTask> find(UUID id, long tenant, long user) {
    return db
        .query(
            SELECT + "WHERE t.id=? AND t.tenant_id=? AND t.user_id=?", this::map, id, tenant, user)
        .stream()
        .findFirst();
  }

  public SandboxTask internal(UUID id) {
    return db.query(SELECT + "WHERE t.id=?", this::map, id).getFirst();
  }

  public List<SandboxTask> list(long tenant, long user, int limit, int offset) {
    return db.query(
        SELECT
            + "WHERE t.tenant_id=? AND t.user_id=? ORDER BY t.created_at DESC,t.id LIMIT ? OFFSET"
            + " ?",
        this::map,
        tenant,
        user,
        limit,
        offset);
  }

  public long count(long tenant, long user) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM sandbox_task WHERE tenant_id=? AND user_id=?",
        Long.class,
        tenant,
        user);
  }

  public List<InputBinding> inputs(UUID task) {
    return db.query(
        "SELECT file_id,input_name FROM sandbox_task_input WHERE task_id=? ORDER BY"
            + " input_order,input_name",
        (rs, n) -> new InputBinding(rs.getObject(1, UUID.class), rs.getString(2)),
        task);
  }

  public Map<String, Object> execution(UUID task) {
    return db.queryForMap(
        "SELECT"
            + " id,attempt,container_id,image_id,exit_code,oom_killed,stdout,stderr,stdout_truncated,stderr_truncated,resource_usage::text,started_at,finished_at"
            + " FROM sandbox_execution WHERE task_id=?",
        task);
  }

  public void cancel(UUID task, long tenant, long user) {
    db.update(
        "UPDATE sandbox_task SET cancel_requested=TRUE,next_poll_at=NOW(),updated_at=NOW() WHERE"
            + " id=? AND tenant_id=? AND user_id=? AND completed_at IS NULL",
        task,
        tenant,
        user);
  }

  public Optional<Claim> claim() {
    return transaction.execute(
        status -> {
          var ids =
              db.queryForList(
                  "SELECT id FROM sandbox_task WHERE completed_at IS NULL AND next_poll_at<=NOW()"
                      + " AND (lease_until IS NULL OR lease_until<NOW()) ORDER BY"
                      + " next_poll_at,created_at FOR UPDATE SKIP LOCKED LIMIT 1",
                  UUID.class);
          if (ids.isEmpty()) return Optional.empty();
          UUID token = UUID.randomUUID(), id = ids.getFirst();
          db.update(
              "UPDATE sandbox_task SET claim_token=?,lease_until=NOW()+INTERVAL '2 minutes' WHERE"
                  + " id=?",
              token,
              id);
          return Optional.of(new Claim(internal(id), token));
        });
  }

  /** Lease renewal and fenced updates prevent a stale worker from changing a reclaimed task. */
  public void renew(UUID id, UUID token) {
    db.update(
        "UPDATE sandbox_task SET lease_until=NOW()+INTERVAL '2 minutes' WHERE id=? AND"
            + " claim_token=? AND completed_at IS NULL",
        id,
        token);
  }

  public void release(Claim claim, String error) {
    db.update(
        "UPDATE sandbox_task SET claim_token=NULL,lease_until=NULL,next_poll_at=NOW()+INTERVAL '3"
            + " seconds',error_message=?,updated_at=NOW() WHERE id=? AND claim_token=? AND"
            + " completed_at IS NULL",
        error,
        claim.task().id(),
        claim.token());
  }

  public void state(Claim claim, String state) {
    fenced(
        claim,
        () ->
            db.update(
                "UPDATE sandbox_task SET status=?,updated_at=NOW(),error_message=NULL WHERE id=?",
                state,
                claim.task().id()));
  }

  public void result(Claim claim, ManagerClient.Result result, String resources) {
    fenced(
        claim,
        () ->
            db.update(
                """
UPDATE sandbox_execution SET container_id=?,image_id=?,exit_code=?,oom_killed=?,stdout=?,stderr=?,stdout_truncated=?,stderr_truncated=?,
resource_usage=?::jsonb,started_at=?::timestamptz,finished_at=?::timestamptz WHERE id=?
""",
                result.containerId(),
                result.imageId(),
                result.exitCode(),
                result.oomKilled(),
                result.stdout(),
                result.stderr(),
                result.stdoutTruncated(),
                result.stderrTruncated(),
                resources,
                result.startedAt(),
                result.finishedAt(),
                claim.task().executionId()));
  }

  public void complete(Claim claim, String state, String code, String message) {
    fenced(
        claim,
        () ->
            db.update(
                "UPDATE sandbox_task SET status=CASE WHEN cancel_requested THEN 'CANCELLED' ELSE ?"
                    + " END,error_code=?,error_message=?,completed_at=NOW(),updated_at=NOW(),claim_token=NULL,lease_until=NULL"
                    + " WHERE id=?",
                state,
                code,
                message,
                claim.task().id()));
  }

  private void fenced(Claim claim, Runnable action) {
    transaction.executeWithoutResult(
        status -> {
          var tokens =
              db.queryForList(
                  "SELECT claim_token FROM sandbox_task WHERE id=? AND completed_at IS NULL FOR"
                      + " UPDATE",
                  claim.task().id());
          if (!tokens.isEmpty() && claim.token().equals(tokens.getFirst().get("claim_token")))
            action.run();
        });
  }

  public List<SandboxTask> awaitingAcknowledgement() {
    return db.query(
        SELECT
            + "WHERE t.completed_at IS NOT NULL AND NOT t.manager_acknowledged ORDER BY"
            + " t.completed_at LIMIT 20",
        this::map);
  }

  public void acknowledged(UUID task) {
    db.update(
        "UPDATE sandbox_task SET manager_acknowledged=TRUE WHERE id=? AND completed_at IS NOT NULL",
        task);
  }

  private SandboxTask map(ResultSet rs, int row) throws SQLException {
    return new SandboxTask(
        rs.getObject("id", UUID.class),
        rs.getLong("tenant_id"),
        rs.getLong("user_id"),
        rs.getString("operation"),
        rs.getString("code_ciphertext"),
        rs.getString("step_key"),
        rs.getInt("timeout_seconds"),
        rs.getString("status"),
        rs.getBoolean("cancel_requested"),
        rs.getString("error_code"),
        rs.getString("error_message"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        instant(rs, "completed_at"),
        rs.getObject("execution_id", UUID.class),
        rs.getString("arguments_json"));
  }

  public void configure(UUID task, String arguments, Long chatMessage, Long workflowExecution) {
    db.update(
        "UPDATE sandbox_task SET arguments_json=?::jsonb,chat_message_id=?,workflow_execution_id=?"
            + " WHERE id=?",
        arguments,
        chatMessage,
        workflowExecution,
        task);
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
