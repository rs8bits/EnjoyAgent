package com.enjoy.sandbox;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Fixed argv only: task data never becomes shell syntax or Docker flags. */
@Component
public class DockerEngine {
  private final ObjectMapper json;

  public DockerEngine(ObjectMapper json) {
    this.json = json;
  }

  public String run(Duration timeout, String... arguments)
      throws IOException, InterruptedException {
    var command = new ArrayList<String>();
    command.add("docker");
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    CompletableFuture<byte[]> bytes =
        CompletableFuture.supplyAsync(
            () -> {
              try (var in = process.getInputStream()) {
                return in.readNBytes(4 * 1024 * 1024);
              } catch (IOException e) {
                throw new CompletionException(e);
              }
            });
    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
        throw new IOException("Docker command timed out");
      String output = new String(bytes.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).strip();
      if (process.exitValue() != 0) throw new IOException("Docker operation failed");
      return output;
    } catch (ExecutionException | TimeoutException e) {
      throw new IOException("Docker response unavailable", e);
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  public JsonNode inspect(String id) throws IOException, InterruptedException {
    return json.readTree(run(Duration.ofSeconds(10), "inspect", id)).get(0);
  }

  public JsonNode stats(String id) throws IOException, InterruptedException {
    // Raw Engine stats preserve cumulative CPU time and byte counters.
    String api = "/containers/" + id + "/stats?stream=false&one-shot=true";
    String socket = System.getenv().getOrDefault("DOCKER_HOST", "unix:///var/run/docker.sock");
    if (!socket.startsWith("unix://")) return json.createObjectNode();
    var address = java.net.UnixDomainSocketAddress.of(socket.substring(7));
    try (var channel = java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
      channel.configureBlocking(false);
      channel.connect(address);
      long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
      while (!channel.finishConnect()) {
        if (System.nanoTime() > deadline) throw new IOException("Docker connect timeout");
        Thread.sleep(5);
      }
      var request =
          java.nio.ByteBuffer.wrap(
              ("GET " + api + " HTTP/1.0\r\nHost: localhost\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      while (request.hasRemaining()) {
        channel.write(request);
        if (System.nanoTime() > deadline) throw new IOException("Docker write timeout");
      }
      var response = new ByteArrayOutputStream();
      var buffer = java.nio.ByteBuffer.allocate(8192);
      while (true) {
        int read = channel.read(buffer);
        if (read < 0) break;
        if (read == 0) {
          if (System.nanoTime() > deadline) throw new IOException("Docker stats timeout");
          Thread.sleep(5);
          continue;
        }
        response.write(buffer.array(), 0, read);
        buffer.clear();
        if (response.size() > 1048576) throw new IOException("Docker stats response too large");
      }
      String raw = response.toString(StandardCharsets.UTF_8);
      int split = raw.indexOf("\r\n\r\n");
      if (split < 0 || !raw.startsWith("HTTP/1.0 200") && !raw.startsWith("HTTP/1.1 200"))
        throw new IOException("Docker stats unavailable");
      return json.readTree(raw.substring(split + 4));
    }
  }

  public Process startAttached(String id) throws IOException {
    // Attach before starting so a short/noisy task cannot outrun log collection/rotation.
    return new ProcessBuilder("docker", "start", "--attach", id).start();
  }

  public void remove(String id) {
    try {
      run(Duration.ofSeconds(15), "rm", "--force", id);
    } catch (IOException e) {
      /* Reconciliation retries cleanup. */
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public String imageId(String image) throws IOException, InterruptedException {
    return run(Duration.ofSeconds(10), "image", "inspect", "--format", "{{.Id}}", image);
  }
}
