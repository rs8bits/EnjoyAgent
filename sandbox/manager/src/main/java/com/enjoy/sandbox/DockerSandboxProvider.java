package com.enjoy.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class DockerSandboxProvider implements SandboxProvider {
  private final DockerEngine docker;
  private final ManagerProperties properties;

  public DockerSandboxProvider(DockerEngine docker, ManagerProperties properties) {
    this.docker = docker;
    this.properties = properties;
  }

  public Allocation acquire(UUID id, Path directory, Protocol.Spec spec)
      throws IOException, InterruptedException {
    String image =
        docker.imageId(
            spec.operation().equals("OFFICE_CONVERT")
                ? properties.documentImage()
                : properties.image());
    var args =
        new ArrayList<>(
            List.of(
                "create",
                "--pull=never",
                "--name",
                "ea-" + properties.namespace() + "-" + id,
                "--label",
                "enjoyagent.sandbox.namespace=" + properties.namespace(),
                "--label",
                "enjoyagent.sandbox.execution=" + id,
                "--label",
                "enjoyagent.sandbox.expires="
                    + Instant.now().plusSeconds(spec.timeoutSeconds() + 30).getEpochSecond(),
                "--init",
                "--read-only",
                "--network=none",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--user",
                "10001:10001",
                "--pids-limit=128",
                "--memory=" + properties.memoryBytes(),
                "--memory-swap=" + properties.memoryBytes(),
                "--cpus=" + properties.cpus(),
                "--shm-size=16m",
                "--ulimit",
                "nofile=256:256",
                "--ulimit",
                "fsize=" + properties.maxOutputBytes() + ":" + properties.maxOutputBytes(),
                "--tmpfs",
                "/tmp:rw,nosuid,nodev,noexec,size=67108864,mode=1777",
                "--log-driver=json-file",
                "--log-opt",
                "max-size=1m",
                "--log-opt",
                "max-file=1",
                "--stop-timeout=1",
                "--workdir=/workspace"));
    for (String name : List.of("input", "code", "workspace", "output")) {
      args.add("--mount");
      args.add(
          "type=bind,src="
              + directory.resolve(name)
              + ",dst=/"
              + name
              + (name.equals("input") || name.equals("code") ? ",readonly" : ""));
    }
    args.add(image);
    args.add(spec.operation());
    return new Allocation(docker.run(Duration.ofSeconds(30), args.toArray(String[]::new)), image);
  }

  public void release(String id) {
    docker.remove(id);
  }
}
