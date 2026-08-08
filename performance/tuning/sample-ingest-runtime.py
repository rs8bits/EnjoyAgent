#!/usr/bin/env python3
"""Sample application and RabbitMQ pressure during one ingestion run."""

from __future__ import annotations

import argparse
import base64
import json
import math
import os
import re
import signal
import threading
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SAMPLE_PATTERN = re.compile(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+([^\s]+)")
LABEL_PATTERN = re.compile(r'(\w+)="((?:\\.|[^"\\])*)"')


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def parse_prometheus(text: str) -> dict[str, float | None]:
    process_cpu_usage: float | None = None
    heap_used = 0.0
    heap_max = 0.0
    heap_used_seen = False
    heap_max_seen = False
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        match = SAMPLE_PATTERN.match(line)
        if not match:
            continue
        name, raw_labels, raw_value = match.groups()
        try:
            value = float(raw_value)
        except ValueError:
            continue
        if not math.isfinite(value):
            continue
        labels = dict(LABEL_PATTERN.findall(raw_labels or ""))
        if name == "process_cpu_usage":
            process_cpu_usage = value
        elif name == "jvm_memory_used_bytes" and labels.get("area") == "heap":
            heap_used += value
            heap_used_seen = True
        elif name == "jvm_memory_max_bytes" and labels.get("area") == "heap" and value > 0:
            heap_max += value
            heap_max_seen = True
    return {
        "process_cpu_usage": process_cpu_usage,
        "jvm_heap_used_bytes": heap_used if heap_used_seen else None,
        "jvm_heap_max_bytes": heap_max if heap_max_seen else None,
    }


def fetch_text(url: str, *, timeout_seconds: float, headers: dict[str, str] | None = None) -> str:
    request = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
        return response.read().decode("utf-8")


def fetch_rabbit(
    url: str,
    *,
    username: str,
    password: str,
    timeout_seconds: float,
) -> dict[str, int]:
    encoded = base64.b64encode(f"{username}:{password}".encode()).decode()
    payload = json.loads(
        fetch_text(
            url,
            timeout_seconds=timeout_seconds,
            headers={"Authorization": f"Basic {encoded}", "Accept": "application/json"},
        )
    )
    if not isinstance(payload, dict):
        raise ValueError("RabbitMQ response root must be an object")
    result: dict[str, int] = {}
    for field in ("messages_ready", "messages_unacknowledged", "consumers"):
        value = payload.get(field)
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ValueError(f"RabbitMQ field {field} must be a non-negative integer")
        result[field] = value
    return result


def status(successful: int, failed: int) -> str:
    if successful == 0:
        return "unavailable"
    if failed:
        return "partial"
    return "ok"


def rounded(value: float | None, digits: int = 6) -> float | None:
    return None if value is None else round(value, digits)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--actuator-url", required=True)
    parser.add_argument("--rabbit-url", required=True)
    parser.add_argument("--interval-ms", type=int, default=250)
    parser.add_argument("--http-timeout-ms", type=int, default=1000)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.interval_ms <= 0:
        parser.error("--interval-ms must be positive")
    if args.http_timeout_ms <= 0:
        parser.error("--http-timeout-ms must be positive")

    rabbit_username = os.environ.get("RABBITMQ_USERNAME", "guest")
    rabbit_password = os.environ.get("RABBITMQ_PASSWORD", "guest")
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda _signum, _frame: stop.set())
    signal.signal(signal.SIGINT, lambda _signum, _frame: stop.set())

    started_at = utc_now()
    samples: list[dict[str, Any]] = []
    actuator_successes = 0
    rabbit_successes = 0
    errors: Counter[str] = Counter()
    timeout_seconds = args.http_timeout_ms / 1000
    next_sample = time.monotonic()

    while not stop.is_set():
        sample: dict[str, Any] = {"timestamp_utc": utc_now()}
        try:
            prometheus = fetch_text(args.actuator_url, timeout_seconds=timeout_seconds)
            sample.update(parse_prometheus(prometheus))
            missing_metrics = [
                field
                for field in ("process_cpu_usage", "jvm_heap_used_bytes")
                if sample.get(field) is None
            ]
            if not missing_metrics:
                actuator_successes += 1
            else:
                errors[f"actuator: required metrics absent: {','.join(missing_metrics)}"] += 1
        except (OSError, ValueError, urllib.error.URLError) as exc:
            errors[f"actuator: {type(exc).__name__}: {exc}"] += 1
            sample.update(
                process_cpu_usage=None,
                jvm_heap_used_bytes=None,
                jvm_heap_max_bytes=None,
            )

        try:
            rabbit = fetch_rabbit(
                args.rabbit_url,
                username=rabbit_username,
                password=rabbit_password,
                timeout_seconds=timeout_seconds,
            )
            sample.update(
                rabbit_messages_ready=rabbit["messages_ready"],
                rabbit_messages_unacknowledged=rabbit["messages_unacknowledged"],
                rabbit_consumers=rabbit["consumers"],
            )
            rabbit_successes += 1
        except (OSError, ValueError, json.JSONDecodeError, urllib.error.URLError) as exc:
            errors[f"rabbitmq: {type(exc).__name__}: {exc}"] += 1
            sample.update(
                rabbit_messages_ready=None,
                rabbit_messages_unacknowledged=None,
                rabbit_consumers=None,
            )
        samples.append(sample)

        next_sample += args.interval_ms / 1000
        stop.wait(max(0.0, next_sample - time.monotonic()))

    def maximum(field: str) -> float | int | None:
        values = [sample[field] for sample in samples if sample.get(field) is not None]
        return max(values) if values else None

    max_heap_used = maximum("jvm_heap_used_bytes")
    heap_utilizations = [
        sample["jvm_heap_used_bytes"] / sample["jvm_heap_max_bytes"]
        for sample in samples
        if sample.get("jvm_heap_used_bytes") is not None
        and sample.get("jvm_heap_max_bytes") is not None
        and sample["jvm_heap_max_bytes"] > 0
    ]
    result = {
        "schema_version": 1,
        "started_at": started_at,
        "finished_at": utc_now(),
        "interval_ms": args.interval_ms,
        "sample_attempts": len(samples),
        "actuator": {
            "status": status(actuator_successes, len(samples) - actuator_successes),
            "successful_samples": actuator_successes,
            "failed_samples": len(samples) - actuator_successes,
            "max_process_cpu_usage": rounded(maximum("process_cpu_usage")),
            "max_jvm_heap_used_bytes": rounded(float(max_heap_used)) if max_heap_used is not None else None,
            "max_jvm_heap_used_mib": rounded(float(max_heap_used) / 1024 / 1024, 3)
            if max_heap_used is not None
            else None,
            "max_jvm_heap_utilization": rounded(max(heap_utilizations)) if heap_utilizations else None,
        },
        "rabbitmq": {
            "status": status(rabbit_successes, len(samples) - rabbit_successes),
            "successful_samples": rabbit_successes,
            "failed_samples": len(samples) - rabbit_successes,
            "max_messages_ready": maximum("rabbit_messages_ready"),
            "max_messages_unacknowledged": maximum("rabbit_messages_unacknowledged"),
            "max_consumers": maximum("rabbit_consumers"),
        },
        "error_counts": dict(sorted(errors.items())),
        "samples": samples,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, args.output)


if __name__ == "__main__":
    main()
