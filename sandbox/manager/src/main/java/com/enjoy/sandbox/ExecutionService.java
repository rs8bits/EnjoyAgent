package com.enjoy.sandbox;

import com.enjoy.sandbox.Protocol.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.slf4j.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExecutionService {
  private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);
  private static final Set<String> TERMINAL =
      Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED");
  private final ConcurrentMap<UUID, Stored> records = new ConcurrentHashMap<>();
  private final ManagerProperties properties;
  private final DockerEngine docker;
  private final SandboxProvider provider;
  private final ObjectMapper json;

  public record Stored(
      String digest,
      String tenantKey,
      String createdAt,
      boolean cancelRequested,
      boolean acknowledged,
      Result result) {}

  public ExecutionService(
      ManagerProperties properties,
      DockerEngine docker,
      SandboxProvider provider,
      ObjectMapper json) {
    this.properties = properties;
    this.docker = docker;
    this.provider = provider;
    this.json = json;
  }

  @PostConstruct
  void recover() throws IOException, InterruptedException {
    Files.createDirectories(properties.workRoot().resolve("records"));
    Files.createDirectories(properties.workRoot().resolve("jobs"));
    // A single manager owns a namespace. Never touch unrelated Docker containers.
    String containers =
        docker.run(
            Duration.ofSeconds(10),
            "ps",
            "-aq",
            "--filter",
            "label=enjoyagent.sandbox.namespace=" + properties.namespace());
    for (String id : containers.split("\\s+"))
      if (!id.isBlank()) docker.run(Duration.ofSeconds(15), "rm", "--force", id);
    try (var paths = Files.list(properties.workRoot().resolve("records"))) {
      for (Path path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
        Stored state = json.readValue(path.toFile(), Stored.class);
        records.put(state.result().executionId(), state);
        if (!TERMINAL.contains(state.result().status())) {
          Result old = state.result();
          save(
              new Stored(
                  state.digest(),
                  state.tenantKey(),
                  state.createdAt(),
                  state.cancelRequested(),
                  false,
                  new Result(
                      old.executionId(),
                      state.cancelRequested() ? "CANCELLED" : "FAILED",
                      null,
                      false,
                      old.containerId(),
                      old.imageId(),
                      old.stdout(),
                      old.stderr(),
                      old.stdoutTruncated(),
                      old.stderrTruncated(),
                      old.resourceUsage(),
                      List.of(),
                      "MANAGER_RESTARTED",
                      old.startedAt(),
                      Instant.now().toString())));
        }
      }
    }
    // A crash during staging can leave a directory without a record.
    try (var paths = Files.list(properties.workRoot().resolve("jobs"))) {
      for (Path path : paths.toList()) {
        try {
          if (!records.containsKey(UUID.fromString(path.getFileName().toString())))
            deleteTree(path);
        } catch (IllegalArgumentException e) {
          deleteTree(path);
        }
      }
    }
  }

  public synchronized Result submit(UUID id, Spec spec, List<MultipartFile> inputs)
      throws IOException {
    validate(spec);
    String digest =
        HexFormat.of()
            .formatHex(
                sha()
                    .digest(
                        json.writer()
                            .with(
                                com.fasterxml.jackson.databind.SerializationFeature
                                    .ORDER_MAP_ENTRIES_BY_KEYS)
                            .writeValueAsBytes(spec)));
    Stored previous = records.get(id);
    if (previous != null) {
      if (!previous.digest().isEmpty() && !previous.digest().equals(digest))
        throw problem(409, "Execution ID already has a different request");
      return previous.result();
    }
    long active =
        records.values().stream().filter(s -> !TERMINAL.contains(s.result().status())).count();
    long tenantActive =
        records.values().stream()
            .filter(
                s ->
                    s.tenantKey().equals(spec.tenantKey())
                        && !TERMINAL.contains(s.result().status()))
            .count();
    if (active >= properties.maxConcurrent() || tenantActive >= properties.maxPerTenant())
      throw problem(429, "Sandbox capacity is busy");
    if (inputs.size() != spec.inputs().size()) throw problem(400, "Input manifest mismatch");
    Path directory = job(id);
    if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) deleteTree(directory);
    try {
      for (String folder : List.of("input", "code", "workspace", "output")) {
        Path path = Files.createDirectories(directory.resolve(folder));
        Files.setPosixFilePermissions(
            path,
            PosixFilePermissions.fromString(
                folder.equals("input") || folder.equals("code") ? "rwxr-xr-x" : "rwxrwxrwx"));
      }
      Set<String> seen = new HashSet<>();
      for (MultipartFile file : inputs) {
        String name = file.getOriginalFilename();
        Input expected =
            spec.inputs().stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow(() -> problem(400, "Unknown input file"));
        if (!seen.add(name) || file.getSize() != expected.sizeBytes())
          throw problem(400, "Input size mismatch");
        Path target = directory.resolve("input").resolve(name);
        MessageDigest hash = sha();
        try (var in = new DigestInputStream(file.getInputStream(), hash);
            var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
          copy(in, out, expected.sizeBytes());
        }
        if (!HexFormat.of().formatHex(hash.digest()).equals(expected.sha256()))
          throw problem(400, "Input hash mismatch");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--r--r--"));
      }
      Path code = directory.resolve("code/main.py");
      Files.writeString(code, spec.code() == null ? "" : spec.code());
      Files.setPosixFilePermissions(code, PosixFilePermissions.fromString("r--r--r--"));
      Path specification = directory.resolve("code/spec.json");
      json.writeValue(
          specification.toFile(), Map.of("inputs", spec.inputs(), "arguments", spec.arguments()));
      Files.setPosixFilePermissions(specification, PosixFilePermissions.fromString("r--r--r--"));
      Result pending =
          new Result(
              id,
              "PREPARING",
              null,
              false,
              null,
              null,
              "",
              "",
              false,
              false,
              new Usage(0, 0, 0, 0, 0),
              List.of(),
              null,
              null,
              null);
      save(new Stored(digest, spec.tenantKey(), Instant.now().toString(), false, false, pending));
    } catch (IOException | RuntimeException failure) {
      deleteTree(directory);
      throw failure;
    }
    Thread.ofVirtual().name("sandbox-" + id).start(() -> execute(id, spec));
    return records.get(id).result();
  }

  private void validate(Spec spec) {
    if (spec == null
        || spec.tenantKey() == null
        || !spec.tenantKey().matches("[0-9]{1,20}")
        || !Set.of(
                "DEMO",
                "PYTHON",
                "SQL",
                "PDF_EXTRACT",
                "PDF_SPLIT",
                "PDF_MERGE",
                "PDF_RENDER",
                "OFFICE_CONVERT",
                "TABLE_INSPECT",
                "TABLE_ANALYZE")
            .contains(spec.operation() == null ? "" : spec.operation())
        || spec.timeoutSeconds() < 1
        || spec.timeoutSeconds() > 300
        || spec.inputs() == null
        || spec.inputs().size() > 10
        || spec.code() != null && spec.code().length() > 100000
        || "PYTHON".equals(spec.operation()) && (spec.code() == null || spec.code().isBlank()))
      throw problem(400, "Invalid execution specification");
    Set<String> names = new HashSet<>();
    long total = 0;
    for (Input input : spec.inputs()) {
      if (input == null
          || input.name() == null
          || !input.name().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,179}")
          || !names.add(input.name())
          || input.sizeBytes() < 0
          || input.sizeBytes() > 209715200
          || input.sha256() == null
          || !input.sha256().matches("[a-f0-9]{64}")) throw problem(400, "Invalid input manifest");
      total += input.sizeBytes();
    }
    if (total > 209715200) throw problem(413, "Inputs exceed transfer limit");
  }

  public Result get(UUID id) {
    return require(id).result();
  }

  private Stored require(UUID id) {
    Stored state = records.get(id);
    if (state == null) throw problem(404, "Execution not found");
    return state;
  }

  public synchronized Result cancel(UUID id) throws IOException {
    Stored state = records.get(id);
    if (state == null) {
      Result result =
          new Result(
              id,
              "CANCELLED",
              null,
              false,
              null,
              null,
              "",
              "",
              false,
              false,
              new Usage(0, 0, 0, 0, 0),
              List.of(),
              null,
              null,
              Instant.now().toString());
      save(new Stored("", "", Instant.now().toString(), true, false, result));
      return result;
    }
    if (TERMINAL.contains(state.result().status())) return state.result();
    save(
        new Stored(
            state.digest(), state.tenantKey(), state.createdAt(), true, false, state.result()));
    if (state.result().containerId() != null) kill(state.result().containerId());
    return state.result();
  }

  public synchronized void acknowledge(UUID id) throws IOException {
    Stored state = require(id);
    if (!TERMINAL.contains(state.result().status()))
      throw problem(409, "Execution is still running");
    deleteTree(job(id));
    save(
        new Stored(
            state.digest(),
            state.tenantKey(),
            state.createdAt(),
            state.cancelRequested(),
            true,
            state.result()));
  }

  public Path output(UUID id, UUID outputId) throws IOException {
    Stored state = require(id);
    if (state.acknowledged())
      throw problem(410, "Execution outputs have been collected or expired");
    Output output =
        state.result().outputs().stream()
            .filter(o -> o.id().equals(outputId))
            .findFirst()
            .orElseThrow(() -> problem(404, "Output not found"));
    Path root = job(id).resolve("output");
    Path path = root.resolve(output.name()).normalize();
    if (!path.startsWith(root)
        || !path.toRealPath().equals(path)
        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw problem(400, "Unsafe output path");
    return path;
  }

  private void execute(UUID id, Spec spec) {
    String container = null,
        image = null,
        started = Instant.now().toString(),
        status = "FAILED",
        error = null;
    Integer exit = null;
    boolean oom = false;
    var out = new BoundedLog(properties.maxLogBytes());
    var err = new BoundedLog(properties.maxLogBytes());
    var metrics = new Metrics();
    var sampling = new AtomicBoolean(true);
    long start = System.nanoTime();
    Process logs = null;
    Thread outReader = null, errReader = null, sampler = null;
    List<Output> outputs = List.of();
    try {
      if (require(id).cancelRequested()) {
        status = "CANCELLED";
        return;
      }
      var allocation = provider.acquire(id, job(id), spec);
      container = allocation.containerId();
      image = allocation.imageId();
      publish(
          id,
          new Result(
              id,
              "PREPARING",
              null,
              false,
              container,
              image,
              "",
              "",
              false,
              false,
              metrics.usage(start),
              List.of(),
              null,
              started,
              null));
      if (require(id).cancelRequested()) {
        status = "CANCELLED";
        return;
      }
      logs = docker.startAttached(container);
      Process stream = logs;
      outReader = Thread.ofVirtual().start(() -> out.read(stream.getInputStream()));
      errReader = Thread.ofVirtual().start(() -> err.read(stream.getErrorStream()));
      String runningId = container;
      sampler =
          Thread.ofVirtual()
              .start(
                  () -> {
                    while (sampling.get()) {
                      try {
                        metrics.sample(docker.stats(runningId));
                        Thread.sleep(250);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                      } catch (IOException ignored) {
                        try {
                          Thread.sleep(250);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          return;
                        }
                      }
                    }
                  });
      long nextScan = 0;
      while (true) {
        var inspection = docker.inspect(container);
        var state = inspection.path("State");
        if (state.path("Status").asText().equals("created")) {
          if (require(id).cancelRequested()) {
            status = "CANCELLED";
            break;
          }
          if (System.nanoTime() - start > Duration.ofSeconds(spec.timeoutSeconds()).toNanos()) {
            status = "TIMED_OUT";
            error = "EXECUTION_TIMEOUT";
            break;
          }
          if (!logs.isAlive()) throw new IOException("Container could not be started");
          Thread.sleep(25);
          continue;
        }
        if (!state.path("Running").asBoolean()) {
          exit = state.path("ExitCode").asInt();
          oom = state.path("OOMKilled").asBoolean();
          if (!status.equals("TIMED_OUT") && !status.equals("CANCELLED") && error == null) {
            status = exit == 0 ? "SUCCEEDED" : "FAILED";
            if (exit != 0) error = oom ? "OOM_KILLED" : "PROCESS_EXIT_NONZERO";
          }
          break;
        }
        if (require(id).cancelRequested()) {
          status = "CANCELLED";
          kill(container);
        } else if (System.nanoTime() - start
            > Duration.ofSeconds(spec.timeoutSeconds()).toNanos()) {
          status = "TIMED_OUT";
          error = "EXECUTION_TIMEOUT";
          kill(container);
        }
        if (System.nanoTime() > nextScan) {
          try {
            scan(job(id).resolve("output"), properties.maxOutputBytes(), 100);
            scan(job(id).resolve("workspace"), properties.maxWorkspaceBytes(), 1000);
          } catch (IOException unsafe) {
            status = "FAILED";
            error = "OUTPUT_LIMIT_OR_UNSAFE_FILE";
            kill(container);
          }
          nextScan = System.nanoTime() + Duration.ofSeconds(1).toNanos();
          publish(
              id,
              result(
                  id,
                  "RUNNING",
                  exit,
                  oom,
                  container,
                  image,
                  out,
                  err,
                  metrics.usage(start),
                  List.of(),
                  error,
                  started,
                  null));
        }
        Thread.sleep(100);
      }
      if (outReader != null) outReader.join(2000);
      if (errReader != null) errReader.join(2000);
      if (require(id).cancelRequested()) {
        status = "CANCELLED";
        outputs = List.of();
      } else if (status.equals("SUCCEEDED")) {
        try {
          outputs = inventory(id);
        } catch (IOException unsafe) {
          status = "FAILED";
          error = "OUTPUT_LIMIT_OR_UNSAFE_FILE";
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      error = "MANAGER_INTERRUPTED";
      status = "FAILED";
    } catch (Exception e) {
      error = "EXECUTION_INFRASTRUCTURE_FAILED";
      status = "FAILED";
      log.warn("Sandbox execution {} failed: {}", id, e.getClass().getSimpleName());
    } finally {
      sampling.set(false);
      if (sampler != null) sampler.interrupt();
      if (logs != null && logs.isAlive()) logs.destroyForcibly();
      if (container != null) provider.release(container);
      try {
        publish(
            id,
            result(
                id,
                status,
                exit,
                oom,
                container,
                image,
                out,
                err,
                metrics.usage(start),
                outputs,
                error,
                started,
                Instant.now().toString()));
      } catch (IOException e) {
        log.error("Cannot persist sandbox result {}", id, e);
      }
    }
  }

  private Result result(
      UUID id,
      String status,
      Integer exit,
      boolean oom,
      String container,
      String image,
      BoundedLog out,
      BoundedLog err,
      Usage usage,
      List<Output> outputs,
      String error,
      String start,
      String finish) {
    return new Result(
        id,
        status,
        exit,
        oom,
        container,
        image,
        out.text(),
        err.text(),
        out.truncated(),
        err.truncated(),
        usage,
        outputs,
        error,
        start,
        finish);
  }

  private synchronized void publish(UUID id, Result result) throws IOException {
    Stored old = require(id);
    save(
        new Stored(
            old.digest(),
            old.tenantKey(),
            old.createdAt(),
            old.cancelRequested(),
            old.acknowledged(),
            result));
  }

  private void save(Stored state) throws IOException {
    UUID id = state.result().executionId();
    Path target = properties.workRoot().resolve("records").resolve(id + ".json");
    Path temp = Files.createTempFile(target.getParent(), id.toString(), ".tmp");
    try {
      json.writeValue(temp.toFile(), state);
      try {
        Files.move(
            temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
      records.put(id, state);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private List<Output> inventory(UUID id) throws IOException {
    Path root = job(id).resolve("output");
    scan(root, properties.maxOutputBytes(), 100);
    List<Output> outputs = new ArrayList<>();
    try (var paths = Files.walk(root)) {
      for (Path path :
          paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
        String name = root.relativize(path).toString().replace('\\', '/');
        var digest = sha();
        try (var in =
            new DigestInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS), digest)) {
          in.transferTo(OutputStream.nullOutputStream());
        }
        UUID outputId = UUID.nameUUIDFromBytes((id + ":" + name).getBytes(StandardCharsets.UTF_8));
        outputs.add(
            new Output(
                outputId, name, Files.size(path), HexFormat.of().formatHex(digest.digest())));
      }
    }
    return List.copyOf(outputs);
  }

  static void scan(Path root, long maxBytes, int maxFiles) throws IOException {
    long total = 0;
    int count = 0, entries = 0;
    try (var paths = Files.walk(root, 9)) {
      for (var iterator = paths.iterator(); iterator.hasNext(); ) {
        Path path = iterator.next();
        var attrs =
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (++entries > 2000
            || root.relativize(path).getNameCount() > 8
            || attrs.isSymbolicLink()
            || !attrs.isDirectory() && !attrs.isRegularFile())
          throw new IOException("Unsafe output entry");
        if (attrs.isRegularFile()) {
          Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
          if (((Number) links).intValue() != 1
              || ++count > maxFiles
              || (total += attrs.size()) > maxBytes) throw new IOException("Output limit exceeded");
        }
      }
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  private void kill(String id) {
    try {
      docker.run(Duration.ofSeconds(10), "kill", id);
    } catch (IOException e) {
      /* It may have exited concurrently. */
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Path job(UUID id) {
    return properties.workRoot().resolve("jobs").resolve(id.toString());
  }

  private static MessageDigest sha() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void copy(InputStream in, OutputStream out, long limit) throws IOException {
    long total = 0;
    byte[] buffer = new byte[65536];
    int count;
    while ((count = in.read(buffer)) != -1) {
      if ((total += count) > limit) throw problem(413, "Input exceeds declared size");
      out.write(buffer, 0, count);
    }
    if (total != limit) throw problem(400, "Input size mismatch");
  }

  static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }

  @Scheduled(fixedDelay = 3600000)
  public synchronized void expire() throws IOException {
    Instant cutoff = Instant.now().minus(Duration.ofHours(properties.retentionHours()));
    for (Stored state : new ArrayList<>(records.values())) {
      if (TERMINAL.contains(state.result().status())
          && Instant.parse(state.createdAt()).isBefore(cutoff)
          && (!state.acknowledged()
              || !state.result().stdout().isEmpty()
              || !state.result().stderr().isEmpty()
              || !state.result().outputs().isEmpty())) {
        deleteTree(job(state.result().executionId()));
        Result old = state.result();
        boolean expired = !state.acknowledged() && old.status().equals("SUCCEEDED");
        Result compact =
            new Result(
                old.executionId(),
                expired ? "FAILED" : old.status(),
                old.exitCode(),
                old.oomKilled(),
                old.containerId(),
                old.imageId(),
                "",
                "",
                old.stdoutTruncated(),
                old.stderrTruncated(),
                old.resourceUsage(),
                List.of(),
                expired ? "OUTPUTS_EXPIRED" : old.errorCode(),
                old.startedAt(),
                old.finishedAt());
        // Keep a compact idempotency tombstone, so an old request cannot execute again.
        save(
            new Stored(
                state.digest(),
                state.tenantKey(),
                state.createdAt(),
                state.cancelRequested(),
                true,
                compact));
      }
    }
  }

  @Scheduled(fixedDelay = 10000)
  public void reapContainers() {
    try {
      String ids =
          docker.run(
              Duration.ofSeconds(10),
              "ps",
              "-aq",
              "--filter",
              "label=enjoyagent.sandbox.namespace=" + properties.namespace());
      for (String container : ids.split("\\s+")) {
        if (container.isBlank()) continue;
        var inspection = docker.inspect(container);
        var labels = inspection.path("Config").path("Labels");
        UUID id = UUID.fromString(labels.path("enjoyagent.sandbox.execution").asText());
        Stored state = records.get(id);
        long deadline = Long.parseLong(labels.path("enjoyagent.sandbox.expires").asText());
        if (state == null
            || TERMINAL.contains(state.result().status())
            || Instant.now().getEpochSecond() > deadline) docker.remove(container);
      }
    } catch (Exception e) {
      log.debug("Docker reconciliation will be retried");
    }
  }

  private static ResponseStatusException problem(int status, String message) {
    return new ResponseStatusException(HttpStatus.valueOf(status), message);
  }

  static final class BoundedLog {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private boolean truncated;

    BoundedLog(int limit) {
      this.limit = limit;
    }

    void read(InputStream stream) {
      try (stream) {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = stream.read(buffer)) != -1) append(buffer, n);
      } catch (IOException ignored) {
        /* Container shutdown closes streams. */
      }
    }

    synchronized void append(byte[] buffer, int n) {
      int take = Math.min(n, limit - bytes.size());
      bytes.write(buffer, 0, take);
      if (take < n) truncated = true;
    }

    synchronized String text() {
      return bytes.toString(StandardCharsets.UTF_8);
    }

    synchronized boolean truncated() {
      return truncated;
    }
  }

  static final class Metrics {
    private long cpu, peak, read, write;

    synchronized void sample(com.fasterxml.jackson.databind.JsonNode node) {
      cpu = Math.max(cpu, node.path("cpu_stats").path("cpu_usage").path("total_usage").asLong());
      peak = Math.max(peak, node.path("memory_stats").path("usage").asLong());
      long r = 0, w = 0;
      for (var entry : node.path("blkio_stats").path("io_service_bytes_recursive")) {
        if (entry.path("op").asText().equalsIgnoreCase("read")) r += entry.path("value").asLong();
        if (entry.path("op").asText().equalsIgnoreCase("write")) w += entry.path("value").asLong();
      }
      read = Math.max(read, r);
      write = Math.max(write, w);
    }

    synchronized Usage usage(long start) {
      return new Usage(
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), cpu, peak, read, write);
    }
  }
}
