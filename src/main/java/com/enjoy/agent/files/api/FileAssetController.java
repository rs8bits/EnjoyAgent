package com.enjoy.agent.files.api;

import com.enjoy.agent.files.application.*;
import com.enjoy.agent.shared.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/files")
public class FileAssetController {
  private final FileAssetService files;

  public FileAssetController(FileAssetService files) {
    this.files = files;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<FileAsset.View> upload(@RequestPart("file") MultipartFile file) {
    return ApiResponse.success(files.upload(file));
  }

  @GetMapping
  public ApiResponse<PagedResponse<FileAsset.View>> list(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(files.list(page, size));
  }

  @GetMapping("/{id}")
  public ApiResponse<FileAsset.View> get(@PathVariable UUID id) {
    return ApiResponse.success(files.require(id).view());
  }

  @GetMapping("/{id}/preview")
  public ApiResponse<Map<String, Object>> preview(@PathVariable UUID id) {
    return ApiResponse.success(files.preview(id));
  }

  @GetMapping("/{id}/download")
  public ResponseEntity<StreamingResponseBody> download(
      @PathVariable UUID id, @RequestParam(defaultValue = "false") boolean inline) {
    var file = files.require(id);
    var disposition =
        inline && FileTypes.inline(file.mediaType())
            ? ContentDisposition.inline()
            : ContentDisposition.attachment();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.mediaType()))
        .contentLength(file.sizeBytes())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            disposition.filename(file.name(), StandardCharsets.UTF_8).build().toString())
        .header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .header("Content-Security-Policy", "sandbox; default-src 'none'")
        .body(
            out -> {
              try (var in = files.open(file)) {
                in.transferTo(out);
              }
            });
  }
}
