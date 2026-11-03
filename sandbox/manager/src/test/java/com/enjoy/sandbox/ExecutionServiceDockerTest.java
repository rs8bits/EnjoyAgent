package com.enjoy.sandbox;

import static org.assertj.core.api.Assertions.*;

import com.enjoy.sandbox.Protocol.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Real Docker acceptance checks; each test owns a unique namespace and directory. */
@EnabledIfEnvironmentVariable(named = "SANDBOX_DOCKER_TEST", matches = "true")
class ExecutionServiceDockerTest {
  ExecutionService service;
  ManagerProperties properties;
  DockerEngine docker;
  ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void setup() throws Exception {
    Path base = Path.of(".sandbox").toAbsolutePath();
    Files.createDirectories(base);
    Path root = Files.createTempDirectory(base, "manager-test-");
    properties =
        new ManagerProperties(
            root,
            root.resolve("unused-token"),
            "enjoyagent-python:local",
            "test-" + UUID.randomUUID(),
            2,
            1,
            134217728,
            1,
            65536,
            1048576,
            4096,
            24);
    docker = new DockerEngine(json);
    service =
        new ExecutionService(
            properties, docker, new DockerSandboxProvider(docker, properties), json);
    service.recover();
  }

  @AfterEach
  void cleanup() throws Exception {
    service.recover();
    ExecutionService.deleteTree(properties.workRoot());
  }

  Result run(String code, int timeout) throws Exception {
    UUID id = UUID.randomUUID();
    service.submit(id, new Spec("1", "PYTHON", code, timeout, List.of()), List.of());
    return await(id);
  }

  Result await(UUID id) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
    while (System.nanoTime() < deadline) {
      Result result = service.get(id);
      if (Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED").contains(result.status()))
        return result;
      Thread.sleep(100);
    }
    throw new AssertionError("Sandbox did not finish");
  }

  @Test
  void executesCodeCapturesStreamsAndCollectsVerifiableArtifacts() throws Exception {
    Result result =
        run(
            "import sys\n"
                + "from pathlib import Path\n"
                + "print('hello')\n"
                + "print('warning',file=sys.stderr)\n"
                + "Path('/output/report.txt').write_text('report')\n",
            10);
    assertThat(result.status()).as(result.errorCode()).isEqualTo("SUCCEEDED");
    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).contains("hello");
    assertThat(result.stderr()).contains("warning");
    assertThat(result.imageId()).startsWith("sha256:");
    assertThat(result.outputs()).hasSize(1);
    var output = result.outputs().getFirst();
    assertThat(Files.readString(service.output(result.executionId(), output.id())))
        .isEqualTo("report");
    assertThat(output.sha256()).hasSize(64);
    service.acknowledge(result.executionId());
    assertThatThrownBy(() -> service.output(result.executionId(), output.id()))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(
            Files.exists(
                properties.workRoot().resolve("jobs").resolve(result.executionId().toString())))
        .isFalse();
  }

  @Test
  void enforcesReadOnlyInputsNetworkIsolationAndSecretBoundary() throws Exception {
    byte[] input = "private input".getBytes();
    String hash =
        HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));
    String code =
        """
        import os,socket
        from pathlib import Path
        try:
            Path('/input/source.txt').write_text('changed')
            raise AssertionError('input was writable')
        except OSError: print('readonly')
        assert not Path('/var/run/docker.sock').exists()
        assert 'OPENAI_API_KEY' not in os.environ
        assert 'MINIO_SECRET_KEY' not in os.environ
        try:
            socket.create_connection(('1.1.1.1',443),timeout=1)
            raise AssertionError('network was available')
        except OSError: print('offline')
        assert os.getuid()==10001
        print('isolated')
        """;
    UUID id = UUID.randomUUID();
    var spec =
        new Spec("1", "PYTHON", code, 10, List.of(new Input("source.txt", input.length, hash)));
    service.submit(
        id, spec, List.of(new MockMultipartFile("files", "source.txt", "text/plain", input)));
    var result = await(id);
    assertThat(result.status()).as(result.stderr()).isEqualTo("SUCCEEDED");
    assertThat(result.stdout()).contains("readonly", "offline", "isolated");
    assertThat(
            Files.readString(
                properties
                    .workRoot()
                    .resolve("jobs")
                    .resolve(id.toString())
                    .resolve("input/source.txt")))
        .isEqualTo("private input");
  }

  @Test
  void noisyShortTasksPreserveTheInitialOutputBeforeDockerLogRotation() throws Exception {
    var result = run("print('BEGIN',flush=True)\nprint('x'*3000000)\nprint('END',flush=True)", 10);
    assertThat(result.status()).as(result.stderr()).isEqualTo("SUCCEEDED");
    assertThat(result.stdout()).startsWith("BEGIN");
    assertThat(result.stdoutTruncated()).isTrue();
  }

  @Test
  void preservesNonzeroExitCodeAndCapsNoisyLogs() throws Exception {
    var result =
        run("import sys\nprint('x'*20000)\nprint('error',file=sys.stderr)\nsys.exit(17)", 10);
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.exitCode()).isEqualTo(17);
    assertThat(result.stdoutTruncated()).isTrue();
    assertThat(result.stdout().length()).isLessThanOrEqualTo(4096);
    assertThat(result.stderr()).contains("error");
  }

  @Test
  void timesOutAndKillsTheContainer() throws Exception {
    var result = run("import time\ntime.sleep(30)", 1);
    assertThat(result.status()).isEqualTo("TIMED_OUT");
    assertThat(result.errorCode()).isEqualTo("EXECUTION_TIMEOUT");
    assertThat(
            docker.run(
                Duration.ofSeconds(10),
                "ps",
                "-aq",
                "--filter",
                "label=enjoyagent.sandbox.namespace=" + properties.namespace()))
        .isEmpty();
  }

  @Test
  void cancelsRunningTasksAndRejectsLateSubmissionAfterCancellation() throws Exception {
    UUID id = UUID.randomUUID();
    var spec = new Spec("1", "PYTHON", "import time\ntime.sleep(30)", 30, List.of());
    service.submit(id, spec, List.of());
    service.cancel(id);
    assertThat(await(id).status()).isEqualTo("CANCELLED");
    UUID neverStarted = UUID.randomUUID();
    service.cancel(neverStarted);
    assertThat(service.submit(neverStarted, spec, List.of()).status()).isEqualTo("CANCELLED");
  }

  @Test
  void idempotentSubmissionCannotChangeFrozenCode() throws Exception {
    UUID id = UUID.randomUUID();
    var spec = new Spec("1", "PYTHON", "print('once')", 10, List.of());
    service.submit(id, spec, List.of());
    service.submit(id, spec, List.of());
    assertThat(await(id).stdout()).contains("once");
    assertThatThrownBy(
            () ->
                service.submit(
                    id, new Spec("1", "PYTHON", "print('changed')", 10, List.of()), List.of()))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void rejectsSymlinkArtifacts() throws Exception {
    var result =
        run("from pathlib import Path\nPath('/output/leak').symlink_to('/etc/passwd')", 10);
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.outputs()).isEmpty();
  }

  @Test
  void reportsOutOfMemorySeparately() throws Exception {
    var result = run("chunks=[]\nwhile True: chunks.append(bytearray(16*1024*1024))", 15);
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.oomKilled()).as(result.toString()).isTrue();
    assertThat(result.errorCode()).isEqualTo("OOM_KILLED");
  }

  @Test
  void restartReconcilesOwnedOrphansWithoutReexecutingCode() throws Exception {
    UUID id = UUID.randomUUID();
    Path directory = properties.workRoot().resolve("jobs").resolve(id.toString());
    for (String name : List.of("input", "code", "output", "workspace"))
      Files.createDirectories(directory.resolve(name));
    Files.writeString(directory.resolve("code/main.py"), "import time\ntime.sleep(30)");
    var allocation =
        new DockerSandboxProvider(docker, properties)
            .acquire(id, directory, new Spec("1", "PYTHON", "", 30, List.of()));
    docker.run(Duration.ofSeconds(10), "start", allocation.containerId());
    var result =
        new Result(
            id,
            "RUNNING",
            null,
            false,
            allocation.containerId(),
            allocation.imageId(),
            "",
            "",
            false,
            false,
            new Usage(0, 0, 0, 0, 0),
            List.of(),
            null,
            Instant.now().toString(),
            null);
    json.writeValue(
        properties.workRoot().resolve("records").resolve(id + ".json").toFile(),
        new ExecutionService.Stored("digest", "1", Instant.now().toString(), false, false, result));
    service.recover();
    assertThat(service.get(id).status()).isEqualTo("FAILED");
    assertThat(service.get(id).errorCode()).isEqualTo("MANAGER_RESTARTED");
    assertThat(
            docker.run(
                Duration.ofSeconds(10),
                "ps",
                "-aq",
                "--filter",
                "label=enjoyagent.sandbox.namespace=" + properties.namespace()))
        .isEmpty();
  }
}
