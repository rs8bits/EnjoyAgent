#!/usr/bin/env python3
"""Controllable OpenAI-compatible embedding server for fault drills.

The control endpoint switches atomically between a deterministic success mode
and a deterministic HTTP 504 mode.  It is deliberately local-only by default
and has no dependency outside the Python standard library.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import signal
import threading
import time
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


MAX_LINE_BYTES = 8192


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def deterministic_embedding(value: str, dimensions: int) -> list[float]:
    """Return a stable unit vector without third-party numeric libraries."""
    state = int.from_bytes(hashlib.sha256(value.encode("utf-8")).digest()[:8], "big")
    vector: list[float] = []
    for _ in range(dimensions):
        state ^= state >> 12
        state ^= (state << 25) & 0xFFFFFFFFFFFFFFFF
        state ^= state >> 27
        state = (state * 0x2545F4914F6CDD1D) & 0xFFFFFFFFFFFFFFFF
        vector.append(((state >> 11) / float(1 << 53)) * 2.0 - 1.0)
    norm = math.sqrt(sum(component * component for component in vector)) or 1.0
    return [round(component / norm, 8) for component in vector]


class ControlState:
    def __init__(self, mode: str, delay_ms: int) -> None:
        self._lock = threading.Lock()
        self.mode = mode
        self.delay_ms = delay_ms
        self.reset()

    def configure(self, *, mode: str, delay_ms: int) -> dict[str, Any]:
        with self._lock:
            self.mode = mode
            self.delay_ms = delay_ms
            return self._snapshot_unlocked()

    def reset(self) -> None:
        with self._lock:
            self.started_at = utc_now()
            self.requests_total = 0
            self.embedding_requests = 0
            self.success_total = 0
            self.timeout_total = 0
            self.inputs_total = 0
            self.in_flight = 0
            self.max_in_flight = 0
            self.last_request_at: str | None = None

    def request_started(self) -> tuple[str, int]:
        with self._lock:
            self.requests_total += 1
            self.embedding_requests += 1
            self.in_flight += 1
            self.max_in_flight = max(self.max_in_flight, self.in_flight)
            self.last_request_at = utc_now()
            return self.mode, self.delay_ms

    def request_finished(self, *, inputs: int, timed_out: bool) -> None:
        with self._lock:
            self.in_flight -= 1
            self.inputs_total += inputs
            if timed_out:
                self.timeout_total += 1
            else:
                self.success_total += 1

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return self._snapshot_unlocked()

    def _snapshot_unlocked(self) -> dict[str, Any]:
        return {
            "started_at": self.started_at,
            "mode": self.mode,
            "delay_ms": self.delay_ms,
            "requests_total": self.requests_total,
            "embedding_requests": self.embedding_requests,
            "success_total": self.success_total,
            "timeout_total": self.timeout_total,
            "inputs_total": self.inputs_total,
            "in_flight": self.in_flight,
            "max_in_flight": self.max_in_flight,
            "last_request_at": self.last_request_at,
        }


class FaultServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(
        self,
        address: tuple[str, int],
        handler: type[BaseHTTPRequestHandler],
        args: argparse.Namespace,
    ) -> None:
        super().__init__(address, handler)
        self.args = args
        self.control = ControlState(args.mode, args.delay_ms)


class Handler(BaseHTTPRequestHandler):
    server: FaultServer
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler contract
        path = self.path.rstrip("/")
        if path == "/health":
            self.send_json(HTTPStatus.OK, {"status": "UP"})
            return
        if path in ("/stats", "/control"):
            self.send_json(HTTPStatus.OK, self.server.control.snapshot())
            return
        self.send_error_json(HTTPStatus.NOT_FOUND, "Endpoint not found")

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler contract
        path = self.path.rstrip("/")
        if path == "/stats/reset":
            self.server.control.reset()
            self.send_json(HTTPStatus.OK, self.server.control.snapshot())
            return
        if path == "/control":
            self.configure()
            return
        if path == "/v1/embeddings":
            self.embed()
            return
        self.send_error_json(HTTPStatus.NOT_FOUND, "Endpoint not found")

    def configure(self) -> None:
        try:
            payload = json.loads(self.read_request_body())
            mode = payload.get("mode")
            delay_ms = payload.get("delay_ms")
            if mode not in ("success", "request_timeout", "gateway_timeout"):
                raise ValueError(
                    "mode must be success, request_timeout, or gateway_timeout"
                )
            if not isinstance(delay_ms, int) or delay_ms < 0:
                raise ValueError("delay_ms must be a non-negative integer")
        except (json.JSONDecodeError, ValueError) as exc:
            self.send_error_json(HTTPStatus.BAD_REQUEST, str(exc))
            return
        self.send_json(
            HTTPStatus.OK,
            self.server.control.configure(mode=mode, delay_ms=delay_ms),
        )

    def embed(self) -> None:
        input_count = 0
        timed_out = False
        try:
            payload = json.loads(self.read_request_body())
            inputs = payload.get("input") if isinstance(payload, dict) else None
            if isinstance(inputs, str):
                inputs = [inputs]
            if not isinstance(inputs, list) or not inputs:
                raise ValueError("input must be a non-empty string or list of strings")
            if not all(isinstance(item, str) for item in inputs):
                raise ValueError("every input item must be a string")
            input_count = len(inputs)
            requested_dimensions = payload.get("dimensions")
            if requested_dimensions not in (None, self.server.args.dimensions):
                raise ValueError(
                    "requested dimensions do not match configured dimensions"
                )
        except (json.JSONDecodeError, ValueError) as exc:
            self.send_error_json(HTTPStatus.BAD_REQUEST, str(exc))
            return

        mode, delay_ms = self.server.control.request_started()
        try:
            if delay_ms:
                time.sleep(delay_ms / 1000)
            if mode in ("request_timeout", "gateway_timeout"):
                timed_out = True
                timeout_status = (
                    HTTPStatus.REQUEST_TIMEOUT
                    if mode == "request_timeout"
                    else HTTPStatus.GATEWAY_TIMEOUT
                )
                self.send_error_json(
                    timeout_status,
                    "Injected upstream embedding timeout",
                )
                return

            model = payload.get("model") or self.server.args.model
            embeddings = [
                {
                    "object": "embedding",
                    "index": index,
                    "embedding": deterministic_embedding(value, self.server.args.dimensions),
                }
                for index, value in enumerate(inputs)
            ]
            prompt_tokens = sum(max(1, len(value) // 4) for value in inputs)
            self.send_json(
                HTTPStatus.OK,
                {
                    "object": "list",
                    "data": embeddings,
                    "model": model,
                    "usage": {
                        "prompt_tokens": prompt_tokens,
                        "total_tokens": prompt_tokens,
                    },
                },
            )
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            self.server.control.request_finished(
                inputs=input_count,
                timed_out=timed_out,
            )

    def read_request_body(self) -> bytes:
        maximum = self.server.args.max_request_bytes
        content_length_text = self.headers.get("Content-Length")
        if content_length_text is not None:
            try:
                content_length = int(content_length_text)
            except ValueError as exc:
                raise ValueError("Invalid Content-Length") from exc
            if content_length <= 0:
                raise ValueError("Request body must not be empty")
            if content_length > maximum:
                raise ValueError("Request body is too large")
            body = self.rfile.read(content_length)
            if len(body) != content_length:
                raise ValueError("Incomplete request body")
            return body

        if self.headers.get("Transfer-Encoding", "").lower() != "chunked":
            raise ValueError("Content-Length or chunked transfer encoding is required")

        body = bytearray()
        while True:
            size_line = self.rfile.readline(MAX_LINE_BYTES)
            if not size_line or len(size_line) >= MAX_LINE_BYTES:
                raise ValueError("Invalid chunk header")
            try:
                chunk_size = int(size_line.split(b";", 1)[0].strip(), 16)
            except ValueError as exc:
                raise ValueError("Invalid chunk size") from exc
            if chunk_size == 0:
                while True:
                    trailer = self.rfile.readline(MAX_LINE_BYTES)
                    if trailer == b"\r\n":
                        break
                    if not trailer or len(trailer) >= MAX_LINE_BYTES:
                        raise ValueError("Invalid chunk trailer")
                break
            if len(body) + chunk_size > maximum:
                raise ValueError("Request body is too large")
            chunk = self.rfile.read(chunk_size)
            if len(chunk) != chunk_size or self.rfile.read(2) != b"\r\n":
                raise ValueError("Incomplete chunked request body")
            body.extend(chunk)
        if not body:
            raise ValueError("Request body must not be empty")
        return bytes(body)

    def send_error_json(self, status: HTTPStatus, message: str) -> None:
        self.send_json(
            status,
            {
                "error": {
                    "message": message,
                    "type": "server_error" if status >= 500 else "invalid_request_error",
                    "param": None,
                    "code": "injected_timeout" if status in (408, 504) else None,
                }
            },
        )

    def send_json(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format_string: str, *args: object) -> None:
        if self.server.args.access_log:
            super().log_message(format_string, *args)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18082)
    parser.add_argument("--dimensions", type=int, default=1024)
    parser.add_argument("--model", default="fault-injection-embedding-model")
    parser.add_argument(
        "--mode",
        choices=("success", "request_timeout", "gateway_timeout"),
        default="success",
    )
    parser.add_argument("--delay-ms", type=int, default=20)
    parser.add_argument("--max-request-bytes", type=int, default=16 * 1024 * 1024)
    parser.add_argument("--access-log", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    if args.dimensions <= 0:
        parser.error("--dimensions must be positive")
    if args.delay_ms < 0:
        parser.error("--delay-ms cannot be negative")
    if args.max_request_bytes <= 0:
        parser.error("--max-request-bytes must be positive")
    return args


def main() -> None:
    args = parse_args()
    server = FaultServer((args.host, args.port), Handler, args)

    def stop_server(_signum: int, _frame: object) -> None:
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop_server)
    signal.signal(signal.SIGINT, stop_server)
    print(
        f"Fault embedding server listening on http://{args.host}:{args.port} "
        f"dimensions={args.dimensions} mode={args.mode} delay_ms={args.delay_ms}",
        flush=True,
    )
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
