#!/usr/bin/env python3
"""Deterministic OpenAI-compatible embedding server for local performance tests."""

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


class RequestBodyTooLarge(ValueError):
    """Raised when an incoming request exceeds the configured safety limit."""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class Statistics:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.started_monotonic = time.monotonic()
        self.started_at = utc_now()
        self.requests_total = 0
        self.embedding_requests = 0
        self.inputs_total = 0
        self.errors_total = 0
        self.in_flight = 0
        self.max_in_flight = 0
        self.processing_seconds_total = 0.0
        self.last_request_at: str | None = None

    def request_started(self) -> None:
        with self._lock:
            self.requests_total += 1
            self.in_flight += 1
            self.max_in_flight = max(self.max_in_flight, self.in_flight)
            self.last_request_at = utc_now()

    def request_finished(self, *, inputs: int, failed: bool, elapsed: float) -> None:
        with self._lock:
            self.in_flight -= 1
            self.embedding_requests += 1
            self.inputs_total += inputs
            self.errors_total += int(failed)
            self.processing_seconds_total += elapsed

    def request_rejected(self) -> None:
        with self._lock:
            self.requests_total += 1
            self.errors_total += 1
            self.last_request_at = utc_now()

    def reset(self) -> None:
        with self._lock:
            self.started_monotonic = time.monotonic()
            self.started_at = utc_now()
            self.requests_total = 0
            self.embedding_requests = 0
            self.inputs_total = 0
            self.errors_total = 0
            self.in_flight = 0
            self.max_in_flight = 0
            self.processing_seconds_total = 0.0
            self.last_request_at = None

    def snapshot(self, *, dimensions: int, delay_ms: int) -> dict[str, Any]:
        with self._lock:
            completed = self.embedding_requests
            return {
                "started_at": self.started_at,
                "uptime_seconds": round(time.monotonic() - self.started_monotonic, 3),
                "configured_dimensions": dimensions,
                "configured_delay_ms": delay_ms,
                "requests_total": self.requests_total,
                "embedding_requests": completed,
                "inputs_total": self.inputs_total,
                "errors_total": self.errors_total,
                "in_flight": self.in_flight,
                "max_in_flight": self.max_in_flight,
                "processing_seconds_total": round(self.processing_seconds_total, 6),
                "average_processing_ms": round(
                    self.processing_seconds_total * 1000 / completed, 3
                )
                if completed
                else 0.0,
                "last_request_at": self.last_request_at,
            }


def deterministic_embedding(value: str, dimensions: int) -> list[float]:
    """Generate a stable unit vector without external numeric dependencies."""
    state = int.from_bytes(hashlib.sha256(value.encode("utf-8")).digest()[:8], "big")
    vector: list[float] = []
    for _ in range(dimensions):
        # xorshift64* gives deterministic, inexpensive pseudo-random components.
        state ^= state >> 12
        state ^= (state << 25) & 0xFFFFFFFFFFFFFFFF
        state ^= state >> 27
        state = (state * 0x2545F4914F6CDD1D) & 0xFFFFFFFFFFFFFFFF
        vector.append(((state >> 11) / float(1 << 53)) * 2.0 - 1.0)
    norm = math.sqrt(sum(component * component for component in vector)) or 1.0
    return [round(component / norm, 8) for component in vector]


class EmbeddingServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], handler: type[BaseHTTPRequestHandler], args: argparse.Namespace):
        super().__init__(address, handler)
        self.args = args
        self.statistics = Statistics()


class Handler(BaseHTTPRequestHandler):
    server: EmbeddingServer
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        if self.path.rstrip("/") == "/health":
            self.send_json(HTTPStatus.OK, {"status": "UP"})
            return
        if self.path.rstrip("/") == "/stats":
            self.send_json(
                HTTPStatus.OK,
                self.server.statistics.snapshot(
                    dimensions=self.server.args.dimensions,
                    delay_ms=self.server.args.delay_ms,
                ),
            )
            return
        self.send_error_json(HTTPStatus.NOT_FOUND, "Endpoint not found")

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        if self.path.rstrip("/") == "/stats/reset":
            self.server.statistics.reset()
            self.send_json(HTTPStatus.OK, {"status": "reset"})
            return
        if self.path.rstrip("/") != "/v1/embeddings":
            self.send_error_json(HTTPStatus.NOT_FOUND, "Endpoint not found")
            return

        try:
            request_body = self.read_request_body()
        except RequestBodyTooLarge as exc:
            self.server.statistics.request_rejected()
            self.send_error_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, str(exc))
            return
        except ValueError as exc:
            self.server.statistics.request_rejected()
            self.send_error_json(HTTPStatus.BAD_REQUEST, str(exc))
            return

        started = time.monotonic()
        input_count = 0
        failed = True
        self.server.statistics.request_started()
        try:
            payload = json.loads(request_body)
            inputs = payload.get("input") if isinstance(payload, dict) else None
            if isinstance(inputs, str):
                inputs = [inputs]
            if not isinstance(inputs, list) or not inputs or not all(isinstance(item, str) for item in inputs):
                raise ValueError("input must be a non-empty string or list of strings")
            input_count = len(inputs)

            requested_dimensions = payload.get("dimensions")
            if requested_dimensions is not None and requested_dimensions != self.server.args.dimensions:
                raise ValueError(
                    f"requested dimensions={requested_dimensions}, configured dimensions={self.server.args.dimensions}"
                )
            if payload.get("encoding_format", "float") not in (None, "float"):
                raise ValueError("only encoding_format=float is supported")

            if self.server.args.delay_ms:
                time.sleep(self.server.args.delay_ms / 1000)

            model = payload.get("model") or self.server.args.model
            data = [
                {
                    "object": "embedding",
                    "index": index,
                    "embedding": deterministic_embedding(value, self.server.args.dimensions),
                }
                for index, value in enumerate(inputs)
            ]
            prompt_tokens = sum(max(1, len(value) // 4) for value in inputs)
            failed = False
            self.send_json(
                HTTPStatus.OK,
                {
                    "object": "list",
                    "data": data,
                    "model": model,
                    "usage": {"prompt_tokens": prompt_tokens, "total_tokens": prompt_tokens},
                },
            )
        except (json.JSONDecodeError, ValueError) as exc:
            self.send_error_json(HTTPStatus.BAD_REQUEST, str(exc))
        except (BrokenPipeError, ConnectionResetError):
            # The client disconnected; statistics still record the failed request.
            pass
        finally:
            self.server.statistics.request_finished(
                inputs=input_count,
                failed=failed,
                elapsed=time.monotonic() - started,
            )

    def read_request_body(self) -> bytes:
        """Read fixed-length or HTTP/1.1 chunked bodies with a hard size cap.

        Spring's reactive HTTP client may stream an embedding request using
        ``Transfer-Encoding: chunked``.  Treating a missing Content-Length as a
        zero-byte body made otherwise valid performance runs fail with 413.
        """
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
                raise RequestBodyTooLarge(
                    f"Request body exceeds the {maximum}-byte limit"
                )
            body = self.rfile.read(content_length)
            if len(body) != content_length:
                raise ValueError("Incomplete request body")
            return body

        transfer_encodings = {
            value.strip().lower()
            for value in self.headers.get("Transfer-Encoding", "").split(",")
            if value.strip()
        }
        if "chunked" not in transfer_encodings:
            raise ValueError("Content-Length or chunked Transfer-Encoding is required")

        body = bytearray()
        while True:
            size_line = self.rfile.readline(128)
            if not size_line or len(size_line) >= 128 or not size_line.endswith(b"\r\n"):
                raise ValueError("Invalid chunk header")
            try:
                chunk_size = int(size_line[:-2].split(b";", 1)[0].strip(), 16)
            except ValueError as exc:
                raise ValueError("Invalid chunk size") from exc
            if chunk_size == 0:
                # Consume optional trailer headers and their terminating CRLF.
                while True:
                    trailer = self.rfile.readline(8192)
                    if trailer == b"\r\n":
                        break
                    if not trailer or len(trailer) >= 8192:
                        raise ValueError("Invalid chunk trailer")
                break
            if len(body) + chunk_size > maximum:
                raise RequestBodyTooLarge(
                    f"Request body exceeds the {maximum}-byte limit"
                )
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
                    "type": "invalid_request_error" if status < 500 else "server_error",
                    "param": None,
                    "code": None,
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
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--dimensions", type=int, default=1024)
    parser.add_argument("--delay-ms", type=int, default=50)
    parser.add_argument("--model", default="perf-embedding-model")
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
    server = EmbeddingServer((args.host, args.port), Handler, args)

    def stop_server(_signum: int, _frame: object) -> None:
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop_server)
    signal.signal(signal.SIGINT, stop_server)
    print(
        f"Embedding mock listening on http://{args.host}:{args.port} "
        f"dimensions={args.dimensions} delay_ms={args.delay_ms}",
        flush=True,
    )
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
