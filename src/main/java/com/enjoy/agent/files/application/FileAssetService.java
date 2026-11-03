package com.enjoy.agent.files.application;

import com.enjoy.agent.files.infrastructure.FileAssetRepository;
import com.enjoy.agent.sandbox.application.SandboxProperties;
import com.enjoy.agent.shared.api.PagedResponse;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.CurrentUserContext;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileAssetService {
  private final FileAssetRepository files;
  private final ObjectStore store;
  private final SandboxProperties properties;
  private final TransactionTemplate transaction;
  private final AtomicBoolean cleaning = new AtomicBoolean();

  public FileAssetService(
      FileAssetRepository files,
      ObjectStore store,
      SandboxProperties properties,
      PlatformTransactionManager tx) {
    this.files = files;
    this.store = store;
    this.properties = properties;
    transaction = new TransactionTemplate(tx);
  }

  public FileAsset.View upload(MultipartFile source) {
    var user = CurrentUserContext.requireCurrentUser();
    if (source.isEmpty() || source.getSize() > properties.maxFileBytes())
      throw error("FILE_SIZE_INVALID", "文件为空或超过上传限制", HttpStatus.PAYLOAD_TOO_LARGE);
    Path temporary = null;
    UUID id = UUID.randomUUID();
    String key = "files/" + user.tenantId() + "/" + user.userId() + "/" + id;
    boolean reserved = false;
    boolean completed = false;
    try {
      temporary = Files.createTempFile("enjoy-upload-", ".tmp");
      MessageDigest digest = sha256();
      long size;
      try (var in = new DigestInputStream(source.getInputStream(), digest);
          var out = Files.newOutputStream(temporary)) {
        size = copyBounded(in, out, properties.maxFileBytes());
      }
      String name = FileTypes.safeName(source.getOriginalFilename());
      String type;
      try (var in = Files.newInputStream(temporary)) {
        type = FileTypes.detect(name, in.readNBytes(8192));
      }
      String hash = HexFormat.of().formatHex(digest.digest());
      reserve(id, user.tenantId(), user.userId(), name, type, size, hash, key, "INPUT");
      reserved = true;
      try (var in = Files.newInputStream(temporary)) {
        store.put(key, in, size, type);
      }
      files.status(id, "READY");
      completed = true;
      return require(id).view();
    } catch (IOException e) {
      throw error("FILE_UPLOAD_FAILED", "文件上传失败", HttpStatus.BAD_GATEWAY);
    } finally {
      if (reserved && !completed) files.status(id, "FAILED");
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          /* OS temp cleanup is a fallback. */
        }
    }
  }

  private void reserve(
      UUID id,
      long tenant,
      long user,
      String name,
      String type,
      long size,
      String hash,
      String key,
      String kind) {
    transaction.executeWithoutResult(
        status -> {
          files.lockOwner(tenant, user);
          var existing = files.reservation(id, tenant, user);
          if (!existing.isEmpty()
              && (!hash.equals(existing.get("sha256"))
                  || size != ((Number) existing.get("size_bytes")).longValue()))
            throw error("ARTIFACT_INTEGRITY_FAILED", "产物与此前上传内容不一致", HttpStatus.BAD_GATEWAY);
          boolean charged =
              !existing.isEmpty() && Set.of("UPLOADING", "READY").contains(existing.get("status"));
          if (files.storedBytes(tenant, user) + (charged ? 0 : size)
                  > properties.maxStoredBytesPerUser()
              || files.count(tenant, user) + (charged ? 0 : 1) > properties.maxFilesPerUser())
            throw error("FILE_QUOTA_EXCEEDED", "文件存储额度已用完", HttpStatus.TOO_MANY_REQUESTS);
          files.insert(id, tenant, user, name, type, size, hash, store.bucket(), key, kind);
        });
  }

  /** Repeated collection uses deterministic IDs and the same object key. */
  public FileAsset.View collect(
      UUID task,
      UUID execution,
      String step,
      long tenant,
      long user,
      UUID output,
      String name,
      long expectedSize,
      String expectedHash,
      InputStream input)
      throws IOException {
    UUID id = UUID.nameUUIDFromBytes((execution + ":" + output).getBytes(StandardCharsets.UTF_8));
    var existing = files.find(id, tenant, user);
    if (existing.isPresent()) return existing.get().view();
    Path temporary = Files.createTempFile("enjoy-artifact-", ".tmp");
    try {
      var digest = sha256();
      long size;
      try (var in = new DigestInputStream(input, digest);
          var out = Files.newOutputStream(temporary)) {
        size = copyBounded(in, out, properties.maxOutputBytes());
      }
      String hash = HexFormat.of().formatHex(digest.digest());
      if (size != expectedSize || !hash.equals(expectedHash))
        throw error("ARTIFACT_INTEGRITY_FAILED", "产物完整性检查失败", HttpStatus.BAD_GATEWAY);
      String safe = FileTypes.safeName(name);
      String type;
      try (var in = Files.newInputStream(temporary)) {
        type = FileTypes.detect(safe, in.readNBytes(8192));
      }
      String key = "artifacts/" + tenant + "/" + task + "/" + id;
      reserve(id, tenant, user, safe, type, size, hash, key, "ARTIFACT");
      try (var in = Files.newInputStream(temporary)) {
        store.put(key, in, size, type);
      }
      transaction.executeWithoutResult(
          status -> {
            files.artifact(id, task, execution, step, output);
            files.status(id, "READY");
          });
      return files.find(id, tenant, user).orElseThrow().view();
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public FileAsset require(UUID id) {
    var user = CurrentUserContext.requireCurrentUser();
    return require(id, user.tenantId(), user.userId());
  }

  public FileAsset require(UUID id, long tenant, long user) {
    return files
        .find(id, tenant, user)
        .orElseThrow(() -> error("FILE_NOT_FOUND", "文件不存在或无权访问", HttpStatus.NOT_FOUND));
  }

  public PagedResponse<FileAsset.View> list(int page, int size) {
    var user = CurrentUserContext.requireCurrentUser();
    page = Math.max(0, Math.min(page, 100000));
    size = Math.max(1, Math.min(size, 100));
    long total = files.readyCount(user.tenantId(), user.userId());
    return new PagedResponse<>(
        files.list(user.tenantId(), user.userId(), size, page * size).stream()
            .map(FileAsset::view)
            .toList(),
        page,
        size,
        total,
        (int) ((total + size - 1) / size));
  }

  public InputStream open(FileAsset file) {
    return store.open(file.bucket(), file.objectKey());
  }

  public Map<String, Object> preview(UUID id) {
    var file = require(id);
    if (Set.of("csv", "tsv", "xls", "xlsx").contains(FileTypes.extension(file.name())))
      return Map.of("kind", "prepare", "operation", "TABLE_INSPECT");
    if (Set.of("doc", "docx", "ppt", "pptx", "odt", "ods", "odp")
        .contains(FileTypes.extension(file.name())))
      return Map.of("kind", "prepare", "operation", "OFFICE_CONVERT");
    if (!FileTypes.text(file.mediaType()))
      return Map.of(
          "kind",
          FileTypes.inline(file.mediaType()) ? "binary" : "download",
          "mediaType",
          file.mediaType());
    try (var in = open(file)) {
      byte[] bytes = in.readNBytes(65537);
      return Map.of(
          "kind",
          "text",
          "text",
          new String(bytes, 0, Math.min(65536, bytes.length), StandardCharsets.UTF_8),
          "truncated",
          bytes.length > 65536);
    } catch (IOException e) {
      throw error("FILE_READ_FAILED", "文件读取失败", HttpStatus.BAD_GATEWAY);
    }
  }

  public static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Scheduled(fixedDelay = 60000)
  public void cleanAbandonedUploads() {
    if (!cleaning.compareAndSet(false, true)) return;
    Thread.ofVirtual()
        .name("file-upload-cleanup")
        .start(
            () -> {
              try {
                for (var entry : files.abandonedUploads()) {
                  try {
                    store.remove(
                        entry.get("bucket").toString(), entry.get("object_key").toString());
                    files.status((UUID) entry.get("id"), "DELETED");
                  } catch (RuntimeException ignored) {
                    break; /* Keep the row for the next cleanup attempt. */
                  }
                }
              } finally {
                cleaning.set(false);
              }
            });
  }

  public static long copyBounded(InputStream in, OutputStream out, long limit) throws IOException {
    byte[] buffer = new byte[65536];
    long total = 0;
    int read;
    while ((read = in.read(buffer)) != -1) {
      total += read;
      if (total > limit) throw error("FILE_SIZE_INVALID", "文件超过允许大小", HttpStatus.PAYLOAD_TOO_LARGE);
      out.write(buffer, 0, read);
    }
    return total;
  }

  private static ApiException error(String code, String message, HttpStatus status) {
    return new ApiException(code, message, status);
  }
}
