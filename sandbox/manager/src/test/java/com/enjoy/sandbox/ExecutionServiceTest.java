package com.enjoy.sandbox;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ExecutionServiceTest {
  @TempDir Path root;

  @Test
  void rejectsSymlinksInsteadOfFollowingThem() throws Exception {
    Path outside = Files.createTempFile("sandbox-outside-", ".txt");
    try {
      Files.createSymbolicLink(root.resolve("leak.txt"), outside);
      assertThatThrownBy(() -> ExecutionService.scan(root, 1024, 10))
          .isInstanceOf(IOException.class);
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void rejectsHardLinksAndOversizedOutputs() throws Exception {
    Files.writeString(root.resolve("a"), "12345");
    assertThatThrownBy(() -> ExecutionService.scan(root, 4, 10)).isInstanceOf(IOException.class);
    Files.createLink(root.resolve("b"), root.resolve("a"));
    assertThatThrownBy(() -> ExecutionService.scan(root, 100, 10)).isInstanceOf(IOException.class);
  }

  @Test
  void limitsFileCountAndAcceptsOrdinaryNestedArtifacts() throws Exception {
    Files.createDirectory(root.resolve("nested"));
    Files.writeString(root.resolve("nested/report.csv"), "a,b\n1,2");
    ExecutionService.scan(root, 100, 1);
    Files.writeString(root.resolve("second"), "x");
    assertThatThrownBy(() -> ExecutionService.scan(root, 100, 1)).isInstanceOf(IOException.class);
  }

  @Test
  void keepsDrainingLogsAfterTheSavedPrefixIsFull() {
    var log = new ExecutionService.BoundedLog(8);
    var source = new ByteArrayInputStream("0123456789abcdefghijklmnopqrstuvwxyz".getBytes());
    log.read(source);
    assertThat(log.text()).isEqualTo("01234567");
    assertThat(log.truncated()).isTrue();
    assertThat(source.available()).isZero();
  }
}
