package com.enjoy.agent.shared.runtime;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One event contract shared by chat, tools and workflow workers. Payloads never contain raw tool
 * data.
 */
public final class RuntimeEvents implements AutoCloseable {
  private static final InheritableThreadLocal<RuntimeEvents> CURRENT =
      new InheritableThreadLocal<>();
  private final RuntimeEvents previous;
  private final Consumer<Event> sink;
  private final String runId = UUID.randomUUID().toString();
  private final AtomicLong sequence = new AtomicLong();

  public RuntimeEvents(Consumer<Event> sink) {
    this.sink = sink;
    previous = CURRENT.get();
    CURRENT.set(this);
  }

  public static void emit(String type, Map<String, Object> data) {
    RuntimeEvents current = CURRENT.get();
    if (current != null)
      current.sink.accept(
          new Event(
              current.runId,
              current.sequence.incrementAndGet(),
              type,
              Instant.now().toString(),
              data));
  }

  @Override
  public void close() {
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
  }

  public record Event(
      String runId, long sequence, String type, String timestamp, Map<String, Object> data) {}
}
