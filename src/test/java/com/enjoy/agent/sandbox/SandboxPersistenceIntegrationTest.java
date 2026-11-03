package com.enjoy.agent.sandbox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.files.application.*;
import com.enjoy.agent.files.infrastructure.FileAssetRepository;
import com.enjoy.agent.sandbox.application.*;
import com.enjoy.agent.sandbox.infrastructure.SandboxRepository;
import com.enjoy.agent.shared.crypto.*;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.*;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@EnabledIfEnvironmentVariable(named = "ENJOY_TEST_DB_URL", matches = ".+")
class SandboxPersistenceIntegrationTest {
  static DriverManagerDataSource source;
  static JdbcTemplate db;
  static String schema;
  FileAssetService files;
  SandboxRepository repository;
  SandboxTaskService tasks;
  MemoryStore store;

  @BeforeAll
  static void migrate() {
    schema = "sandbox_test_" + UUID.randomUUID().toString().replace("-", "");
    String url = System.getenv("ENJOY_TEST_DB_URL"),
        user = System.getenv().getOrDefault("ENJOY_TEST_DB_USER", "postgres"),
        password = System.getenv("ENJOY_TEST_DB_PASSWORD");
    Flyway.configure()
        .dataSource(url, user, password)
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
    source =
        new DriverManagerDataSource(
            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
            user,
            password);
    db = new JdbcTemplate(source);
  }

  @AfterAll
  static void cleanup() {
    if (source != null) new JdbcTemplate(source).execute("DROP SCHEMA " + schema + " CASCADE");
  }

  @BeforeEach
  void setup() {
    db.execute("TRUNCATE tenant,app_user CASCADE");
    db.update(
        "INSERT INTO tenant(id,code,name,status) VALUES(1,'a','A','ACTIVE'),(2,'b','B','ACTIVE')");
    db.update(
        "INSERT INTO app_user(id,email,password_hash,display_name,status,system_role)"
            + " VALUES(1,'a@test','x','A','ACTIVE','USER'),(2,'b@test','x','B','ACTIVE','USER')");
    authenticate(1, 1);
    var tx = new DataSourceTransactionManager(source);
    var props =
        new SandboxProperties(
            true,
            URI.create("http://localhost:8091"),
            Path.of("unused"),
            1024,
            5,
            10,
            20,
            1024,
            120);
    store = new MemoryStore();
    var fileRepository = new FileAssetRepository(db);
    files = new FileAssetService(fileRepository, store, props, tx);
    repository = new SandboxRepository(db, tx);
    var encryption = new CredentialProperties();
    encryption.setAesKey("0123456789abcdef0123456789abcdef");
    tasks =
        new SandboxTaskService(
            repository,
            files,
            fileRepository,
            props,
            new AesCryptoService(encryption),
            new ObjectMapper().findAndRegisterModules(),
            tx);
  }

  @AfterEach
  void identity() {
    SecurityContextHolder.clearContext();
  }

  void authenticate(long tenant, long user) {
    var principal =
        new AuthenticatedUser(
            user, "a@test", "A", tenant, "a", "A", TenantMemberRole.OWNER, SystemRole.USER);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
  }

  FileAsset.View upload(String content) {
    return files.upload(new MockMultipartFile("file", "输入.csv", "text/csv", content.getBytes()));
  }

  SandboxTaskService.CreateRequest request(List<UUID> inputs, String code) {
    return new SandboxTaskService.CreateRequest("PYTHON", code, inputs, "analysis", 10);
  }

  @Test
  void fileAndTaskPermissionsRejectOtherUsersAndTenants() {
    var file = upload("abc");
    var task = tasks.create(request(List.of(file.id()), "print('ok')"), "request-1");
    authenticate(1, 2);
    assertThatThrownBy(() -> files.require(file.id())).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> tasks.detail(task.id())).isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> tasks.create(request(List.of(file.id()), "print('ok')"), "unauthorized"))
        .isInstanceOf(ApiException.class);
    authenticate(2, 1);
    assertThat(files.list(0, 20).items()).isEmpty();
    assertThatThrownBy(() -> files.require(file.id())).isInstanceOf(ApiException.class);
    assertThat(tasks.list(0, 20).items()).isEmpty();
    authenticate(1, 1);
    assertThat(files.require(file.id()).name()).isEqualTo("输入.csv");
  }

  @Test
  void idempotencyFreezesInputsAndEncryptedCode() {
    var input = upload("abc");
    var request = request(List.of(input.id()), "print('private code')");
    var first = tasks.create(request, "stable-key");
    assertThat(tasks.create(request, "stable-key").id()).isEqualTo(first.id());
    assertThat(
            db.queryForObject(
                "SELECT code_ciphertext FROM sandbox_task WHERE id=?", String.class, first.id()))
        .doesNotContain("private code");
    assertThatThrownBy(() -> tasks.create(request(List.of(input.id()), "changed"), "stable-key"))
        .isInstanceOf(ApiException.class);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM sandbox_task", Integer.class)).isEqualTo(1);
  }

  @Test
  void expiredClaimsCannotOverwriteNewOwner() {
    var task = tasks.create(request(List.of(), "print('ok')"), "lease-test");
    var first = repository.claim().orElseThrow();
    db.update(
        "UPDATE sandbox_task SET lease_until=NOW()-INTERVAL '1 second' WHERE id=?", task.id());
    var second = repository.claim().orElseThrow();
    repository.state(second, "RUNNING");
    repository.complete(first, "FAILED", "stale", "stale");
    assertThat(repository.internal(task.id()).status()).isEqualTo("RUNNING");
    repository.complete(second, "SUCCEEDED", null, null);
    assertThat(repository.internal(task.id()).status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void concurrentWorkersClaimEachTaskOnce() throws Exception {
    tasks.create(request(List.of(), "pass"), "concurrent");
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var one = workers.submit(repository::claim);
      var two = workers.submit(repository::claim);
      assertThat(List.of(one.get(), two.get()).stream().filter(Optional::isPresent).count())
          .isEqualTo(1);
    }
  }

  @Test
  void collectionRetryDoesNotDoubleChargeQuotaOrDuplicateArtifacts() throws Exception {
    upload("abc");
    var task = tasks.create(request(List.of(), "pass"), "collect");
    var execution = repository.internal(task.id()).executionId();
    UUID output = UUID.randomUUID();
    byte[] bytes = "xy".getBytes();
    String hash = HexFormat.of().formatHex(FileAssetService.sha256().digest(bytes));
    store.failOnce.set(true);
    assertThatThrownBy(
            () ->
                files.collect(
                    task.id(),
                    execution,
                    "analysis",
                    1,
                    1,
                    output,
                    "result.txt",
                    2,
                    hash,
                    new ByteArrayInputStream(bytes)))
        .isInstanceOf(ApiException.class);
    var artifact =
        files.collect(
            task.id(),
            execution,
            "analysis",
            1,
            1,
            output,
            "result.txt",
            2,
            hash,
            new ByteArrayInputStream(bytes));
    assertThat(
            files
                .collect(
                    task.id(),
                    execution,
                    "analysis",
                    1,
                    1,
                    output,
                    "result.txt",
                    2,
                    hash,
                    new ByteArrayInputStream(bytes))
                .id())
        .isEqualTo(artifact.id());
    assertThat(tasks.detail(task.id()).artifacts()).hasSize(1);
    assertThat(files.list(0, 20).total()).isEqualTo(2);
    assertThatThrownBy(() -> upload("z")).isInstanceOf(ApiException.class);
  }

  @Test
  void dispatcherRetriesCollectionWithoutSubmittingOrDuplicatingExecution() throws Exception {
    var created = tasks.create(request(List.of(), "print('once')"), "upload-failure");
    var execution = repository.internal(created.id()).executionId();
    UUID output = UUID.randomUUID();
    byte[] content = "xy".getBytes();
    String hash = HexFormat.of().formatHex(FileAssetService.sha256().digest(content));
    var manager = mock(ManagerClient.class);
    var result =
        new ManagerClient.Result(
            execution,
            "SUCCEEDED",
            0,
            false,
            "container-once",
            "sha256:test",
            "once",
            "",
            false,
            false,
            new ManagerClient.Usage(20, 1, 10, 0, 0),
            List.of(new ManagerClient.Output(output, "result.txt", 2, hash)),
            null,
            java.time.Instant.now().toString(),
            java.time.Instant.now().toString());
    when(manager.get(execution)).thenReturn(Optional.of(result));
    when(manager.output(
            eq(execution),
            eq(output),
            org.mockito.ArgumentMatchers.<java.util.function.Function<InputStream, Object>>any()))
        .thenAnswer(
            call -> {
              java.util.function.Function<InputStream, Object> reader = call.getArgument(2);
              return reader.apply(new ByteArrayInputStream(content));
            });
    var props =
        new SandboxProperties(
            true,
            URI.create("http://localhost:8091"),
            Path.of("unused"),
            1024,
            5,
            10,
            20,
            1024,
            120);
    var encryption = new CredentialProperties();
    encryption.setAesKey("0123456789abcdef0123456789abcdef");
    var dispatcher =
        new SandboxDispatcher(
            repository,
            files,
            manager,
            props,
            new AesCryptoService(encryption),
            new ObjectMapper().findAndRegisterModules());
    store.failOnce.set(true);
    dispatcher.poll();
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline && repository.internal(created.id()).errorMessage() == null)
      Thread.sleep(20);
    assertThat(repository.internal(created.id()).status()).isEqualTo("COLLECTING");
    assertThat(repository.internal(created.id()).errorMessage()).contains("重试");
    db.update("UPDATE sandbox_task SET next_poll_at=NOW() WHERE id=?", created.id());
    dispatcher.poll();
    deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline && repository.internal(created.id()).completedAt() == null)
      Thread.sleep(20);
    assertThat(repository.internal(created.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(tasks.detail(created.id()).artifacts()).hasSize(1);
    verify(manager, never()).submit(any(), any(), any());
    verify(manager, times(2)).get(execution);
  }

  @Test
  void rejectsTamperedOutputBeforePublishingAnyFile() {
    var task = tasks.create(request(List.of(), "pass"), "tamper");
    var execution = repository.internal(task.id()).executionId();
    assertThatThrownBy(
            () ->
                files.collect(
                    task.id(),
                    execution,
                    "analysis",
                    1,
                    1,
                    UUID.randomUUID(),
                    "result.txt",
                    3,
                    "0".repeat(64),
                    new ByteArrayInputStream("abc".getBytes())))
        .isInstanceOf(ApiException.class);
    assertThat(files.list(0, 20).items()).isEmpty();
    assertThat(tasks.detail(task.id()).artifacts()).isEmpty();
  }

  static class MemoryStore implements ObjectStore {
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final AtomicBoolean failOnce = new AtomicBoolean();

    public String bucket() {
      return "test";
    }

    public void put(String key, InputStream content, long size, String type) {
      if (failOnce.getAndSet(false))
        throw new ApiException(
            "FILE_STORAGE_UNAVAILABLE", "offline", org.springframework.http.HttpStatus.BAD_GATEWAY);
      try {
        objects.put(key, content.readAllBytes());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    public InputStream open(String bucket, String key) {
      return new ByteArrayInputStream(objects.get(key));
    }

    public void remove(String bucket, String key) {
      objects.remove(key);
    }
  }
}
