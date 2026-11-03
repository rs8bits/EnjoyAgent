package com.enjoy.agent.sandbox.application;

import com.enjoy.agent.files.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Function;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;

@Component
public class ManagerClient {
  public record Input(String name, long sizeBytes, String sha256) {}

  public record Spec(
      String tenantKey,
      String operation,
      String code,
      int timeoutSeconds,
      List<Input> inputs,
      Map<String, Object> arguments) {
    public Spec(
        String tenantKey, String operation, String code, int timeoutSeconds, List<Input> inputs) {
      this(tenantKey, operation, code, timeoutSeconds, inputs, Map.of());
    }
  }

  public record Output(UUID id, String name, long sizeBytes, String sha256) {}

  public record Usage(
      long wallTimeMs,
      long cpuTimeNanos,
      long sampledPeakMemoryBytes,
      long blockReadBytes,
      long blockWriteBytes) {}

  public record Result(
      UUID executionId,
      String status,
      Integer exitCode,
      boolean oomKilled,
      String containerId,
      String imageId,
      String stdout,
      String stderr,
      boolean stdoutTruncated,
      boolean stderrTruncated,
      Usage resourceUsage,
      List<Output> outputs,
      String errorCode,
      String startedAt,
      String finishedAt) {}

  private final SandboxProperties properties;
  private final FileAssetService files;
  private final ObjectMapper json;
  private final RestClient client;
  private final RestClient healthClient;

  public ManagerClient(SandboxProperties properties, FileAssetService files, ObjectMapper json) {
    this.properties = properties;
    this.files = files;
    this.json = json;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000);
    factory.setReadTimeout(60000);
    client =
        RestClient.builder()
            .baseUrl(properties.managerUrl().toString())
            .requestFactory(factory)
            .build();
    var healthFactory = new SimpleClientHttpRequestFactory();
    healthFactory.setConnectTimeout(2000);
    healthFactory.setReadTimeout(3000);
    healthClient =
        RestClient.builder()
            .baseUrl(properties.managerUrl().toString())
            .requestFactory(healthFactory)
            .build();
  }

  private String token() {
    try {
      return Files.readString(properties.tokenFile()).strip();
    } catch (IOException e) {
      throw new IllegalStateException("Sandbox manager token is not configured");
    }
  }

  public boolean ready() {
    if (!properties.enabled()) return false;
    try {
      var response =
          healthClient
              .get()
              .uri("/health")
              .retrieve()
              .body(com.fasterxml.jackson.databind.JsonNode.class);
      return response != null
          && response.path("ready").asBoolean()
          && response.path("protocolVersion").asInt() == 1;
    } catch (Exception e) {
      return false;
    }
  }

  public Optional<Result> get(UUID id) {
    try {
      return Optional.ofNullable(
          client
              .get()
              .uri("/internal/executions/{id}", id)
              .header("X-Sandbox-Token", token())
              .retrieve()
              .body(Result.class));
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) return Optional.empty();
      throw e;
    }
  }

  public Result submit(UUID id, Spec spec, List<FileAsset> assets) throws IOException {
    var body = new LinkedMultiValueMap<String, Object>();
    body.add("spec", json.writeValueAsString(spec));
    List<InputStream> streams = new ArrayList<>();
    try {
      for (int i = 0; i < assets.size(); i++) {
        FileAsset asset = assets.get(i);
        Input descriptor = spec.inputs().get(i);
        InputStream stream = files.open(asset);
        streams.add(stream);
        body.add(
            "files",
            new InputStreamResource(stream) {
              @Override
              public String getFilename() {
                return descriptor.name();
              }

              @Override
              public long contentLength() {
                return descriptor.sizeBytes();
              }
            });
      }
      return client
          .post()
          .uri("/internal/executions/{id}", id)
          .header("X-Sandbox-Token", token())
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(body)
          .retrieve()
          .body(Result.class);
    } finally {
      for (InputStream stream : streams)
        try {
          stream.close();
        } catch (IOException ignored) {
        }
    }
  }

  public Result cancel(UUID id) {
    return client
        .post()
        .uri("/internal/executions/{id}/cancel", id)
        .header("X-Sandbox-Token", token())
        .retrieve()
        .body(Result.class);
  }

  public void acknowledge(UUID id) {
    client
        .post()
        .uri("/internal/executions/{id}/acknowledge", id)
        .header("X-Sandbox-Token", token())
        .retrieve()
        .toBodilessEntity();
  }

  public <T> T output(UUID execution, UUID output, Function<InputStream, T> reader) {
    return client
        .get()
        .uri("/internal/executions/{id}/outputs/{output}", execution, output)
        .header("X-Sandbox-Token", token())
        .exchange(
            (request, response) -> {
              if (!response.getStatusCode().is2xxSuccessful())
                throw new HttpClientErrorException(response.getStatusCode());
              return reader.apply(response.getBody());
            });
  }
}
