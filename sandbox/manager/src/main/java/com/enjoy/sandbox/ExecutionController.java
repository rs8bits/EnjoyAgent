package com.enjoy.sandbox;

import com.enjoy.sandbox.Protocol.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class ExecutionController {
  private final ExecutionService service;
  private final DockerEngine docker;
  private final ManagerProperties properties;
  private final ObjectMapper json;

  public ExecutionController(
      ExecutionService service,
      DockerEngine docker,
      ManagerProperties properties,
      ObjectMapper json) {
    this.service = service;
    this.docker = docker;
    this.properties = properties;
    this.json = json;
  }

  @GetMapping("/health")
  public Map<String, Object> health() {
    try {
      docker.imageId(properties.image());
      docker.imageId(properties.documentImage());
      return Map.of("ready", true, "protocolVersion", 1);
    } catch (Exception e) {
      return Map.of("ready", false, "protocolVersion", 1);
    }
  }

  @PostMapping(path = "/internal/executions/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Result submit(
      @PathVariable UUID id,
      @RequestPart("spec") String spec,
      @RequestPart(value = "files", required = false) List<MultipartFile> files)
      throws IOException {
    return service.submit(id, json.readValue(spec, Spec.class), files == null ? List.of() : files);
  }

  @GetMapping("/internal/executions/{id}")
  public Result get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PostMapping("/internal/executions/{id}/cancel")
  public Result cancel(@PathVariable UUID id) throws IOException {
    return service.cancel(id);
  }

  @PostMapping("/internal/executions/{id}/acknowledge")
  public void acknowledge(@PathVariable UUID id) throws IOException {
    service.acknowledge(id);
  }

  @GetMapping("/internal/executions/{id}/outputs/{output}")
  public ResponseEntity<StreamingResponseBody> output(
      @PathVariable UUID id, @PathVariable UUID output) throws IOException {
    Path path = service.output(id, output);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .contentLength(Files.size(path))
        .body(
            out -> {
              try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                in.transferTo(out);
              }
            });
  }
}
