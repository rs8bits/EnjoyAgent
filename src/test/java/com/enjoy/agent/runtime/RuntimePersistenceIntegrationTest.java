package com.enjoy.agent.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.knowledge.api.KnowledgeEvidenceController;
import com.enjoy.agent.mcp.application.*;
import com.enjoy.agent.mcp.domain.enums.*;
import com.enjoy.agent.shared.crypto.*;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.*;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import com.enjoy.agent.workflow.application.*;
import com.enjoy.agent.workflow.domain.entity.*;
import com.enjoy.agent.workflow.domain.enums.WorkflowNodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Runs real PostgreSQL migrations and atomic claims. Use an isolated database, never a production
 * URL.
 */
@EnabledIfEnvironmentVariable(named = "ENJOY_TEST_DB_URL", matches = ".+")
class RuntimePersistenceIntegrationTest {
  static JdbcTemplate db;
  static DriverManagerDataSource source;
  static String schema;
  AesCryptoService crypto;
  McpRuntimeService runtime;
  McpGatewayService gateway;
  McpActionConfirmationService actions;
  PreparedMcpTool tool;

  @BeforeAll
  static void migrate() {
    String url = System.getenv("ENJOY_TEST_DB_URL");
    String user = System.getenv().getOrDefault("ENJOY_TEST_DB_USER", "postgres");
    String password = System.getenv("ENJOY_TEST_DB_PASSWORD");
    schema = "runtime_test_" + UUID.randomUUID().toString().replace("-", "");
    var admin = new DriverManagerDataSource(url, user, password);
    Flyway.configure().dataSource(admin).schemas(schema).defaultSchema(schema).load().migrate();
    source =
        new DriverManagerDataSource(
            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
            user,
            password);
    db = new JdbcTemplate(source);
  }

  @AfterAll
  static void cleanup() {
    new JdbcTemplate(source).execute("DROP SCHEMA " + schema + " CASCADE");
  }

  @BeforeEach
  void setup() {
    db.execute("TRUNCATE tenant,app_user CASCADE");
    db.update(
        "INSERT INTO tenant(id,code,name,status) VALUES(1,'a','A','ACTIVE'),(2,'b','B','ACTIVE')");
    db.update(
        "INSERT INTO app_user(id,email,password_hash,display_name,status,system_role)"
            + " VALUES(1,'a@test','x','A','ACTIVE','USER'),(2,'b@test','x','B','ACTIVE','USER')");
    authenticate(1L, 1L);
    var props = new CredentialProperties();
    props.setAesKey("0123456789abcdef0123456789abcdef");
    crypto = new AesCryptoService(props);
    runtime = mock(McpRuntimeService.class);
    gateway = mock(McpGatewayService.class);
    actions =
        new McpActionConfirmationService(
            db, new ObjectMapper(), crypto, runtime, gateway, Clock.systemUTC());
    tool =
        new PreparedMcpTool(
            7L,
            "delete_record",
            "Delete one record",
            "{\"type\":\"object\"}",
            McpToolRiskLevel.HIGH,
            new PreparedMcpServer(
                3L, "test", "https://example.com/mcp", McpTransportType.STREAMABLE_HTTP, null));
    when(runtime.getRunnableToolForTenant(1L, 7L)).thenReturn(tool);
    when(gateway.callTool(eq(tool), anyMap())).thenReturn(new McpToolCallResult("done", "{}"));
  }

  @AfterEach
  void clearIdentity() {
    SecurityContextHolder.clearContext();
  }

  static void authenticate(Long tenant, Long user) {
    var identity =
        new AuthenticatedUser(
            user, "a@test", "A", tenant, "a", "A", TenantMemberRole.OWNER, SystemRole.USER);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(identity, null, identity.authorities()));
  }

  UUID propose(String scope) {
    assertThatThrownBy(
            () ->
                actions.requireResult(
                    1L, 1L, null, null, scope, tool, Map.of("recordId", 42, "token", "hidden")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("确认");
    return db.queryForObject(
        "SELECT id FROM mcp_action_confirmation WHERE scope_key=?", UUID.class, scope);
  }

  @Test
  void confirmationFreezesArgumentsAndReplaysResultWithoutRepeatingWrite() {
    UUID id = propose("turn:1");
    String cipher =
        db.queryForObject(
            "SELECT arguments_ciphertext FROM mcp_action_confirmation WHERE id=?",
            String.class,
            id);
    assertThat(cipher).doesNotContain("recordId", "hidden");
    actions.confirm(id);
    actions.confirm(id);
    assertThat(
            actions
                .requireResult(
                    1L, 1L, null, null, "turn:1", tool, Map.of("token", "hidden", "recordId", 42))
                .modelVisibleContent())
        .isEqualTo("done");
    verify(gateway, times(1)).callTool(eq(tool), eq(Map.of("recordId", 42, "token", "hidden")));
  }

  @Test
  void rejectsOtherTenantAndChangedToolAndCancelledAction() {
    UUID id = propose("turn:2");
    authenticate(2L, 2L);
    assertThatThrownBy(() -> actions.confirm(id)).isInstanceOf(ApiException.class);
    authenticate(1L, 1L);
    when(runtime.getRunnableToolForTenant(1L, 7L))
        .thenReturn(
            new PreparedMcpTool(
                7L, "different", "changed", "{}", McpToolRiskLevel.HIGH, tool.server()));
    assertThatThrownBy(() -> actions.confirm(id)).hasMessageContaining("定义已改变");
    actions.cancel(id);
    assertThatThrownBy(() -> actions.confirm(id)).isInstanceOf(ApiException.class);
    verifyNoInteractions(gateway);
  }

  @Test
  void uncertainNetworkOutcomeCannotBeReplayed() {
    UUID id = propose("turn:3");
    when(gateway.callTool(eq(tool), anyMap())).thenThrow(new IllegalStateException("timeout"));
    assertThatThrownBy(() -> actions.confirm(id)).hasMessageContaining("不确定");
    assertThatThrownBy(() -> actions.confirm(id)).isInstanceOf(ApiException.class);
    verify(gateway, times(1)).callTool(eq(tool), anyMap());
    assertThat(
            db.queryForObject(
                "SELECT status FROM mcp_action_confirmation WHERE id=?", String.class, id))
        .isEqualTo("UNKNOWN");
  }

  @Test
  void concurrentConfirmHasOneNetworkCall() throws Exception {
    UUID id = propose("turn:4");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(gateway.callTool(eq(tool), anyMap()))
        .thenAnswer(
            inv -> {
              entered.countDown();
              assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
              return new McpToolCallResult("done", "{}");
            });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                authenticate(1L, 1L);
                try {
                  return actions.confirm(id);
                } finally {
                  SecurityContextHolder.clearContext();
                }
              });
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> actions.confirm(id)).isInstanceOf(ApiException.class);
      } finally {
        release.countDown();
      }
      assertThat(first.get(5, TimeUnit.SECONDS).get("status")).isEqualTo("EXECUTED");
    }
    verify(gateway, times(1)).callTool(eq(tool), anyMap());
  }

  WorkflowExecution execution() {
    db.update("INSERT INTO workflow(id,tenant_id,name) VALUES(1,1,'test')");
    db.update(
        "INSERT INTO workflow_execution(id,workflow_id,tenant_id,trigger_type,status)"
            + " VALUES(1,1,1,'TEST','RUNNING')");
    var tenant = new Tenant();
    tenant.setId(1L);
    var execution = new WorkflowExecution();
    execution.setId(1L);
    execution.setTenant(tenant);
    return execution;
  }

  WorkflowNode node(String key, WorkflowNodeType type) {
    var node = new WorkflowNode();
    node.setNodeKey(key);
    node.setNodeType(type);
    node.setConfigJson("{}");
    return node;
  }

  @Test
  void snapshotsEdgesAcrossDifferentEntityInstances() {
    var service = new WorkflowCheckpointService(db, new ObjectMapper(), crypto);
    var execution = execution();
    var start = node("start", WorkflowNodeType.START);
    start.setId(1L);
    var end = node("end", WorkflowNodeType.END);
    end.setId(2L);
    var detachedStart = node("start", WorkflowNodeType.START);
    detachedStart.setId(1L);
    var detachedEnd = node("end", WorkflowNodeType.END);
    detachedEnd.setId(2L);
    var edge = new WorkflowEdge();
    edge.setSourceNode(detachedStart);
    edge.setTargetNode(detachedEnd);
    try (var scope =
        service.open(execution, 1L, null, 3L, "q", List.of(start, end), List.of(edge), false)) {
      scope.finish("COMPLETED");
    }
    var canvas =
        (com.enjoy.agent.workflow.api.request.SaveWorkflowCanvasRequest)
            service.resumable(execution.getId()).get("canvas");
    assertThat(canvas.edges().getFirst().sourceNodeIndex()).isZero();
    assertThat(canvas.edges().getFirst().targetNodeIndex()).isEqualTo(1);
  }

  @Test
  void resumesAfterLeaseExpiryAndReusesEncryptedNodeResult() {
    var service = new WorkflowCheckpointService(db, new ObjectMapper(), crypto);
    var execution = execution();
    var node = node("summary", WorkflowNodeType.LLM);
    var count = new AtomicInteger();
    try (var scope =
        service.open(execution, 1L, null, 3L, "question", List.of(node), List.of(), false)) {
      WorkflowCheckpointService.execute(
          node,
          "summary",
          () -> {
            count.incrementAndGet();
            return new NodeExecutionResult("yes", Map.of("llm_output", "secret-result"));
          });
    }
    db.update("UPDATE workflow_run_state SET lease_until=NOW()-INTERVAL '1 minute'");
    try (var scope =
        service.open(execution, 1L, null, 3L, "question", List.of(node), List.of(), true)) {
      assertThat(
              WorkflowCheckpointService.execute(
                      node,
                      "summary",
                      () -> {
                        throw new AssertionError("must not run again");
                      })
                  .nextHandle())
          .isEqualTo("yes");
      scope.finish("COMPLETED");
    }
    assertThat(count.get()).isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT result_ciphertext FROM workflow_node_checkpoint", String.class))
        .doesNotContain("secret-result");
  }

  @Test
  void blocksUnknownWriteAndChangedGraph() {
    var service = new WorkflowCheckpointService(db, new ObjectMapper(), crypto);
    var execution = execution();
    var node = node("write", WorkflowNodeType.TOOL);
    try (var scope = service.open(execution, 1L, null, 3L, "q", List.of(node), List.of(), false)) {
      assertThatThrownBy(
              () ->
                  WorkflowCheckpointService.execute(
                      node,
                      "write",
                      () -> {
                        throw new IllegalStateException("timeout");
                      }))
          .isInstanceOf(IllegalStateException.class);
      scope.finish("FAILED");
    }
    try (var scope = service.open(execution, 1L, null, 3L, "q", List.of(node), List.of(), true)) {
      assertThatThrownBy(
              () ->
                  WorkflowCheckpointService.execute(
                      node,
                      "write",
                      () -> {
                        throw new AssertionError("must not retry");
                      }))
          .hasMessageContaining("不确定");
      scope.finish("FAILED");
    }
    node.setConfigJson("{\"new\":true}");
    assertThatThrownBy(
            () -> service.open(execution, 1L, null, 3L, "q", List.of(node), List.of(), true))
        .hasMessageContaining("定义已改变");
    authenticate(2L, 2L);
    assertThatThrownBy(() -> service.nodes(1L)).isInstanceOf(ApiException.class);
  }

  @Test
  void rejectsExpiredAndTamperedConfirmation() {
    UUID expired = propose("turn:expired");
    db.update(
        "UPDATE mcp_action_confirmation SET expires_at=NOW()-INTERVAL '1 second' WHERE id=?",
        expired);
    assertThatThrownBy(() -> actions.confirm(expired)).hasMessageContaining("过期");
    UUID tampered = propose("turn:tampered");
    db.update(
        "UPDATE mcp_action_confirmation SET arguments_ciphertext=? WHERE id=?",
        crypto.encrypt("{}"),
        tampered);
    assertThatThrownBy(() -> actions.confirm(tampered)).hasMessageContaining("校验失败");
    verifyNoInteractions(gateway);
  }

  @Test
  void liveLeaseRejectsConcurrentResumeAndFencesOldWorker() {
    var service = new WorkflowCheckpointService(db, new ObjectMapper(), crypto);
    var execution = execution();
    var node = node("read", WorkflowNodeType.LLM);
    try (var scope = service.open(execution, 1L, null, 3L, "q", List.of(node), List.of(), false)) {
      assertThatThrownBy(
              () -> service.open(execution, 1L, null, 3L, "q", List.of(node), List.of(), true))
          .hasMessageContaining("正在运行");
      db.update("UPDATE workflow_run_state SET owner_token='replacement'");
      assertThatThrownBy(
              () ->
                  WorkflowCheckpointService.execute(
                      node,
                      "read",
                      () -> {
                        throw new AssertionError("stale worker must stop");
                      }))
          .hasMessageContaining("所有权");
      assertThat(scope.finish("COMPLETED")).isFalse();
    }
  }

  @Test
  void sourceEndpointNeverReturnsForeignOrDeletedEvidence() {
    var controller = new KnowledgeEvidenceController(db);
    db.update(
        "INSERT INTO"
            + " model_config(id,tenant_id,name,provider,model_type,model_name,credential_source)"
            + " VALUES(1,1,'embed','OPENAI','EMBEDDING','test','PLATFORM')");
    db.update(
        "INSERT INTO knowledge_base(id,tenant_id,name,embedding_model_config_id)"
            + " VALUES(1,1,'docs',1)");
    db.update(
        "INSERT INTO"
            + " knowledge_document(id,tenant_id,knowledge_base_id,file_name,file_size,storage_bucket,storage_object_key,status)"
            + " VALUES(1,1,1,'guide.pdf',123,'test','test','READY')");
    var vector = new float[1024];
    vector[0] = 1;
    new com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeChunkJdbcRepository(db)
        .batchInsert(
            1L,
            1L,
            1L,
            List.of(
                new com.enjoy.agent.knowledge.application.KnowledgeChunkRow(
                    0,
                    "Evidence",
                    vector,
                    new com.enjoy.agent.knowledge.application.DocumentChunker.ChunkLocation(
                        10, 18, 2, "Guide"))));
    Long id = db.queryForObject("SELECT id FROM knowledge_chunk", Long.class);
    assertThat(controller.evidence(id).data())
        .containsEntry("page_number", 2)
        .containsEntry("source_start", 10)
        .containsEntry("heading", "Guide");
    authenticate(2L, 2L);
    assertThatThrownBy(() -> controller.evidence(id)).isInstanceOf(ApiException.class);
    authenticate(1L, 1L);
    db.update("UPDATE knowledge_document SET status='DELETING' WHERE id=1");
    assertThatThrownBy(() -> controller.evidence(id)).isInstanceOf(ApiException.class);
  }
}
