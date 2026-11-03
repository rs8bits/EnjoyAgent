package com.enjoy.agent.sandbox.application;

import com.enjoy.agent.files.application.*;
import com.enjoy.agent.sandbox.infrastructure.SandboxRepository;
import com.enjoy.agent.sandbox.infrastructure.SandboxRepository.Claim;
import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.enjoy.agent.shared.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * The task table is a durable queue. Polls are leased, short-lived and idempotent across restarts.
 */
@Component
public class SandboxDispatcher {
  private static final Logger log = LoggerFactory.getLogger(SandboxDispatcher.class);
  private final SandboxRepository tasks;
  private final FileAssetService files;
  private final ManagerClient manager;
  private final SandboxProperties properties;
  private final AesCryptoService crypto;
  private final ObjectMapper json;
  private final Semaphore permits = new Semaphore(4);
  private final ConcurrentMap<UUID, UUID> claims = new ConcurrentHashMap<>();
  private final AtomicBoolean acknowledging = new AtomicBoolean();

  public SandboxDispatcher(
      SandboxRepository tasks,
      FileAssetService files,
      ManagerClient manager,
      SandboxProperties properties,
      AesCryptoService crypto,
      ObjectMapper json) {
    this.tasks = tasks;
    this.files = files;
    this.manager = manager;
    this.properties = properties;
    this.crypto = crypto;
    this.json = json;
  }

  @org.springframework.context.event.EventListener(
      org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public void startup() {
    if (properties.enabled()) {
      if (manager.ready()) log.info("Sandbox manager is ready");
      else log.warn("Sandbox manager is unavailable; persisted tasks will wait for recovery");
    }
  }

  @Scheduled(fixedDelayString = "${enjoy.sandbox.poll-delay-ms:1000}")
  public void poll() {
    if (!properties.enabled()) return;
    while (permits.tryAcquire()) {
      Optional<Claim> claim;
      try {
        claim = tasks.claim();
      } catch (RuntimeException e) {
        permits.release();
        log.warn("Sandbox queue unavailable");
        return;
      }
      if (claim.isEmpty()) {
        permits.release();
        return;
      }
      var work = claim.get();
      claims.put(work.task().id(), work.token());
      Thread.ofVirtual()
          .name("sandbox-dispatch-" + work.task().id())
          .start(
              () -> {
                try {
                  process(work);
                } finally {
                  claims.remove(work.task().id(), work.token());
                  permits.release();
                }
              });
    }
  }

  @Scheduled(fixedDelay = 20000)
  public void renew() {
    claims.forEach(tasks::renew);
  }

  private void process(Claim claim) {
    SandboxTask task = claim.task();
    String retryMessage = null;
    try {
      ManagerClient.Result result;
      if (task.cancelRequested()) result = manager.cancel(task.executionId());
      else {
        var existing = manager.get(task.executionId());
        if (existing.isPresent()) result = existing.get();
        else {
          if (Set.of("RUNNING", "COLLECTING").contains(task.status())) {
            tasks.complete(claim, "FAILED", "EXECUTION_STATE_LOST", "执行记录丢失，已停止自动重试，请重新提交任务");
            return;
          }
          tasks.state(claim, "PREPARING");
          var bindings = tasks.inputs(task.id());
          var assets =
              bindings.stream()
                  .map(input -> files.require(input.fileId(), task.tenantId(), task.userId()))
                  .toList();
          var inputs = new ArrayList<ManagerClient.Input>();
          for (int i = 0; i < assets.size(); i++) {
            var file = assets.get(i);
            inputs.add(
                new ManagerClient.Input(bindings.get(i).name(), file.sizeBytes(), file.sha256()));
          }
          result =
              manager.submit(
                  task.executionId(),
                  new ManagerClient.Spec(
                      Long.toString(task.tenantId()),
                      task.operation(),
                      crypto.decrypt(task.codeCiphertext()),
                      task.timeoutSeconds(),
                      inputs,
                      json.readValue(
                          task.argumentsJson(),
                          new com.fasterxml.jackson.core.type.TypeReference<
                              Map<String, Object>>() {})),
                  assets);
        }
      }
      if (result == null || !task.executionId().equals(result.executionId()))
        throw new IOException("Invalid execution response");
      tasks.result(claim, result, json.writeValueAsString(result.resourceUsage()));
      if (result.status().equals("SUCCEEDED")) {
        tasks.state(claim, "COLLECTING");
        long total = 0;
        if (result.outputs() == null || result.outputs().size() > 100)
          throw new IOException("Invalid artifact manifest");
        for (var output : result.outputs()) {
          if (output.sizeBytes() < 0 || (total += output.sizeBytes()) > properties.maxOutputBytes())
            throw new ApiException(
                "ARTIFACT_LIMIT_EXCEEDED",
                "产物超过允许大小",
                org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
          manager.output(
              task.executionId(),
              output.id(),
              input -> {
                try {
                  return files.collect(
                      task.id(),
                      task.executionId(),
                      task.stepKey(),
                      task.tenantId(),
                      task.userId(),
                      output.id(),
                      output.name(),
                      output.sizeBytes(),
                      output.sha256(),
                      input);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
        }
        tasks.complete(claim, "SUCCEEDED", null, null);
      } else if (Set.of("FAILED", "CANCELLED", "TIMED_OUT").contains(result.status())) {
        tasks.complete(
            claim,
            result.status(),
            result.errorCode(),
            result.errorCode() == null ? null : "执行未完成，请查看退出码和日志");
      } else if (Set.of("PREPARING", "RUNNING").contains(result.status()))
        tasks.state(claim, result.status());
      else throw new IOException("Unknown execution status");
    } catch (ApiException e) {
      if (Set.of(
              "FILE_QUOTA_EXCEEDED",
              "ARTIFACT_INTEGRITY_FAILED",
              "ARTIFACT_LIMIT_EXCEEDED",
              "FILE_NOT_FOUND")
          .contains(e.getCode())) tasks.complete(claim, "FAILED", e.getCode(), e.getMessage());
      else retryMessage = "文件存储暂不可用，正在重试成果收集";
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 410
          || e.getStatusCode().value() == 409
          || e.getStatusCode().value() == 400
          || e.getStatusCode().value() == 413)
        tasks.complete(claim, "FAILED", "MANAGER_REQUEST_REJECTED", "沙箱请求或产物已失效");
      else retryMessage = e.getStatusCode().value() == 429 ? "等待空闲沙箱" : "沙箱管理服务暂不可用，正在重试";
    } catch (Exception e) {
      retryMessage = "沙箱管理服务或文件存储暂不可用，正在重试";
      log.warn("Sandbox task {} deferred: {}", task.id(), e.getClass().getSimpleName());
    } finally {
      tasks.release(claim, retryMessage);
    }
  }

  @Scheduled(fixedDelay = 10000)
  public void acknowledge() {
    if (!properties.enabled() || !acknowledging.compareAndSet(false, true)) return;
    Thread.ofVirtual()
        .name("sandbox-acknowledge")
        .start(
            () -> {
              try {
                for (var task : tasks.awaitingAcknowledgement()) {
                  try {
                    manager.acknowledge(task.executionId());
                    tasks.acknowledged(task.id());
                  } catch (RestClientResponseException e) {
                    if (e.getStatusCode().value() == 404) tasks.acknowledged(task.id());
                    else break;
                  } catch (Exception e) {
                    log.debug("Sandbox cleanup will be retried for {}", task.id());
                    break;
                  }
                }
              } finally {
                acknowledging.set(false);
              }
            });
  }
}
