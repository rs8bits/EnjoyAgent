package com.enjoy.agent.sandbox.application;

import com.enjoy.agent.files.application.*;
import com.enjoy.agent.files.infrastructure.FileAssetRepository;
import com.enjoy.agent.sandbox.infrastructure.SandboxRepository;
import com.enjoy.agent.shared.api.PagedResponse;
import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.CurrentUserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SandboxTaskService {
  public record CreateRequest(
      @NotNull
          @Pattern(
              regexp =
                  "DEMO|PYTHON|SQL|PDF_EXTRACT|PDF_SPLIT|PDF_MERGE|PDF_RENDER|OFFICE_CONVERT|TABLE_INSPECT|TABLE_ANALYZE")
          String operation,
      @Size(max = 100000) String code,
      @Size(max = 10) List<@NotNull UUID> fileIds,
      @Size(max = 128) String stepKey,
      @Min(1) @Max(300) Integer timeoutSeconds,
      Map<String, Object> arguments) {
    public CreateRequest(
        String operation, String code, List<UUID> fileIds, String stepKey, Integer timeoutSeconds) {
      this(operation, code, fileIds, stepKey, timeoutSeconds, Map.of());
    }

    public CreateRequest {
      arguments =
          arguments == null
              ? Map.of()
              : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
  }

  public record InputView(UUID fileId, String inputName, String originalName) {}

  public record Summary(
      UUID id,
      String operation,
      String status,
      String stepKey,
      boolean cancelRequested,
      java.time.Instant createdAt,
      java.time.Instant completedAt,
      String errorCode,
      String errorMessage) {}

  public record Detail(
      Summary task,
      UUID executionId,
      int timeoutSeconds,
      List<InputView> inputs,
      List<FileAsset.View> artifacts,
      Map<String, Object> execution) {}

  private final SandboxRepository tasks;
  private final FileAssetService files;
  private final FileAssetRepository fileRepository;
  private final SandboxProperties properties;
  private final AesCryptoService crypto;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;

  public SandboxTaskService(
      SandboxRepository tasks,
      FileAssetService files,
      FileAssetRepository fileRepository,
      SandboxProperties properties,
      AesCryptoService crypto,
      ObjectMapper json,
      PlatformTransactionManager tx) {
    this.tasks = tasks;
    this.files = files;
    this.fileRepository = fileRepository;
    this.properties = properties;
    this.crypto = crypto;
    this.json = json;
    transaction = new TransactionTemplate(tx);
  }

  public Summary create(CreateRequest request, String key) {
    var user = CurrentUserContext.requireCurrentUser();
    return createFor(user.tenantId(), user.userId(), request, key, null, null);
  }

  public Summary createFor(
      long tenant,
      long userId,
      CreateRequest request,
      String key,
      Long chatMessageId,
      Long workflowExecutionId) {
    if (request == null
        || request.operation() == null
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
            .contains(request.operation())
        || request.code() != null && request.code().length() > 100000
        || request.fileIds() != null
            && (request.fileIds().size() > 10
                || request.fileIds().stream().anyMatch(Objects::isNull))
        || request.stepKey() != null && request.stepKey().length() > 128
        || request.timeoutSeconds() != null
            && (request.timeoutSeconds() < 1 || request.timeoutSeconds() > 300))
      throw new ApiException("SANDBOX_ARGUMENTS_INVALID", "沙箱任务参数无效", HttpStatus.BAD_REQUEST);
    if (!properties.enabled())
      throw new ApiException("SANDBOX_DISABLED", "沙箱执行尚未启用", HttpStatus.SERVICE_UNAVAILABLE);
    if (key == null || !key.matches("[a-zA-Z0-9._:-]{1,128}"))
      throw new ApiException(
          "IDEMPOTENCY_KEY_REQUIRED", "请提供有效的 Idempotency-Key", HttpStatus.BAD_REQUEST);
    String code = request.code() == null ? "" : request.code();
    if (Set.of("PYTHON", "SQL").contains(request.operation()) && code.isBlank())
      throw new ApiException("SANDBOX_CODE_REQUIRED", "Python 代码不能为空", HttpStatus.BAD_REQUEST);
    List<UUID> ids =
        request.fileIds() == null ? List.of() : request.fileIds().stream().distinct().toList();
    String step =
        request.stepKey() == null || request.stepKey().isBlank() ? "execute" : request.stepKey();
    int timeout =
        request.timeoutSeconds() == null
            ? properties.defaultTimeoutSeconds()
            : request.timeoutSeconds();
    String hash;
    try {
      hash =
          HexFormat.of()
              .formatHex(
                  FileAssetService.sha256()
                      .digest(
                          json.writer()
                              .with(
                                  com.fasterxml.jackson.databind.SerializationFeature
                                      .ORDER_MAP_ENTRIES_BY_KEYS)
                              .writeValueAsBytes(
                                  List.of(
                                      request.operation(),
                                      code,
                                      ids,
                                      step,
                                      timeout,
                                      request.arguments()))));
      if (json.writeValueAsString(request.arguments()).length() > 20000)
        throw new ApiException("SANDBOX_ARGUMENTS_TOO_LARGE", "工具参数过长", HttpStatus.BAD_REQUEST);
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return transaction.execute(
        status -> {
          tasks.lockTenant(tenant);
          var existing = tasks.idempotent(tenant, userId, key, hash);
          if (existing.isPresent()) return summary(existing.get());
          if (tasks.activeCount(tenant) >= properties.maxActiveTasksPerTenant())
            throw new ApiException(
                "SANDBOX_TASK_QUOTA_EXCEEDED", "当前工作区待处理任务过多", HttpStatus.TOO_MANY_REQUESTS);
          List<SandboxRepository.InputBinding> inputs =
              ids.stream()
                  .map(
                      id -> {
                        var file = files.require(id, tenant, userId);
                        String ext = FileTypes.extension(file.name());
                        return new SandboxRepository.InputBinding(
                            id, id + "." + (ext.matches("[a-z0-9]{1,12}") ? ext : "bin"));
                      })
                  .toList();
          UUID task = UUID.randomUUID();
          tasks.insert(
              task,
              UUID.randomUUID(),
              tenant,
              userId,
              key,
              hash,
              request.operation(),
              crypto.encrypt(code),
              step,
              timeout,
              inputs);
          try {
            tasks.configure(
                task,
                json.writeValueAsString(request.arguments()),
                chatMessageId,
                workflowExecutionId);
          } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
          }
          return summary(tasks.internal(task));
        });
  }

  public SandboxTask require(UUID id) {
    var user = CurrentUserContext.requireCurrentUser();
    return tasks
        .find(id, user.tenantId(), user.userId())
        .orElseThrow(
            () -> new ApiException("SANDBOX_TASK_NOT_FOUND", "任务不存在或无权访问", HttpStatus.NOT_FOUND));
  }

  public Detail detail(UUID id) {
    SandboxTask task = require(id);
    return detailFor(task);
  }

  public Detail detailFor(SandboxTask task) {
    UUID id = task.id();
    var inputs =
        tasks.inputs(id).stream()
            .map(
                binding ->
                    new InputView(
                        binding.fileId(),
                        binding.name(),
                        files.require(binding.fileId(), task.tenantId(), task.userId()).name()))
            .toList();
    var execution = new LinkedHashMap<>(tasks.execution(id));
    try {
      execution.put("resource_usage", json.readTree(execution.get("resource_usage").toString()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return new Detail(
        summary(task),
        task.executionId(),
        task.timeoutSeconds(),
        inputs,
        fileRepository.artifacts(id, task.tenantId(), task.userId()).stream()
            .map(FileAsset::view)
            .toList(),
        execution);
  }

  public Summary cancel(UUID id) {
    var task = require(id);
    tasks.cancel(id, task.tenantId(), task.userId());
    return summary(require(id));
  }

  public PagedResponse<Summary> list(int page, int size) {
    var user = CurrentUserContext.requireCurrentUser();
    page = Math.max(0, Math.min(page, 100000));
    size = Math.max(1, Math.min(size, 100));
    long total = tasks.count(user.tenantId(), user.userId());
    return new PagedResponse<>(
        tasks.list(user.tenantId(), user.userId(), size, page * size).stream()
            .map(this::summary)
            .toList(),
        page,
        size,
        total,
        (int) ((total + size - 1) / size));
  }

  private Summary summary(SandboxTask task) {
    return new Summary(
        task.id(),
        task.operation(),
        task.status(),
        task.stepKey(),
        task.cancelRequested(),
        task.createdAt(),
        task.completedAt(),
        task.errorCode(),
        task.errorMessage());
  }
}
