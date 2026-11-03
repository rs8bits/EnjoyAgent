package com.enjoy.agent.files.application;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class FileTypes {
  private FileTypes() {}

  private static final Map<String, String> TEXT =
      Map.of(
          "txt",
          "text/plain",
          "md",
          "text/plain",
          "csv",
          "text/csv",
          "tsv",
          "text/tab-separated-values",
          "json",
          "application/json",
          "py",
          "text/plain",
          "sql",
          "text/plain");
  private static final Map<String, String> OFFICE =
      Map.of(
          "docx",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          "xlsx",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          "pptx",
          "application/vnd.openxmlformats-officedocument.presentationml.presentation",
          "doc",
          "application/msword",
          "xls",
          "application/vnd.ms-excel",
          "ppt",
          "application/vnd.ms-powerpoint");

  public static String safeName(String name) {
    String result = name == null ? "file.bin" : name.replace('\\', '/');
    result = result.substring(result.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
    if (result.isBlank() || result.equals(".") || result.equals("..")) result = "file.bin";
    return result.substring(0, Math.min(result.length(), 240));
  }

  public static String extension(String name) {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  public static String detect(String name, byte[] head) {
    if (starts(head, "%PDF-".getBytes(StandardCharsets.US_ASCII))) return "application/pdf";
    if (starts(head, new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})) return "image/png";
    if (starts(head, new byte[] {(byte) 255, (byte) 216, (byte) 255})) return "image/jpeg";
    String ext = extension(name);
    if (Set.of("docx", "xlsx", "pptx").contains(ext) && starts(head, new byte[] {80, 75, 3, 4}))
      return OFFICE.get(ext);
    if (Set.of("doc", "xls", "ppt").contains(ext)
        && starts(
            head,
            new byte[] {
              (byte) 208, (byte) 207, 17, (byte) 224, (byte) 161, (byte) 177, 26, (byte) 225
            })) return OFFICE.get(ext);
    if (TEXT.containsKey(ext) && isText(head)) return TEXT.get(ext);
    return "application/octet-stream";
  }

  public static boolean isText(byte[] bytes) {
    for (byte b : bytes) if (b == 0) return false;
    return true;
  }

  public static boolean inline(String type) {
    return Set.of("application/pdf", "image/png", "image/jpeg").contains(type);
  }

  public static boolean text(String type) {
    return type.startsWith("text/") || type.equals("application/json");
  }

  private static boolean starts(byte[] bytes, byte[] prefix) {
    if (bytes.length < prefix.length) return false;
    for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
    return true;
  }
}
