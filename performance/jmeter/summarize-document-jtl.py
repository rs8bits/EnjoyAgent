#!/usr/bin/env python3
"""Summarize upload, optional polling, and synthetic end-to-end JMeter samples."""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def percentile(sorted_values: list[int], ratio: float) -> int:
    if not sorted_values:
        return 0
    return sorted_values[max(0, math.ceil(len(sorted_values) * ratio) - 1)]


def summarize(rows: list[dict[str, str]]) -> dict[str, Any]:
    if not rows:
        return {
            "samples": 0,
            "failures": 0,
            "error_rate": 0.0,
            "throughput_rps": 0.0,
            "latency_ms": {},
            "response_codes": {},
        }

    elapsed = sorted(int(row["elapsed"]) for row in rows)
    started_at = min(int(row["timeStamp"]) for row in rows)
    finished_at = max(int(row["timeStamp"]) + int(row["elapsed"]) for row in rows)
    duration_seconds = max((finished_at - started_at) / 1000, 0.001)
    failures = sum(row.get("success", "").lower() != "true" for row in rows)
    response_codes = Counter(row.get("responseCode", "") for row in rows)
    return {
        "samples": len(rows),
        "failures": failures,
        "error_rate": round(failures / len(rows), 6),
        "started_at_utc": datetime.fromtimestamp(started_at / 1000, timezone.utc).isoformat(),
        "finished_at_utc": datetime.fromtimestamp(finished_at / 1000, timezone.utc).isoformat(),
        "duration_seconds": round(duration_seconds, 3),
        "throughput_rps": round(len(rows) / duration_seconds, 3),
        "latency_ms": {
            "average": round(statistics.fmean(elapsed), 3),
            "p50": percentile(elapsed, 0.50),
            "p90": percentile(elapsed, 0.90),
            "p95": percentile(elapsed, 0.95),
            "p99": percentile(elapsed, 0.99),
            "max": max(elapsed),
        },
        "response_codes": dict(sorted(response_codes.items())),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jtl", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    with args.jtl.open(newline="", encoding="utf-8") as source:
        rows = list(csv.DictReader(source))
    if not rows:
        raise SystemExit("JTL contains no samples")

    by_label: dict[str, list[dict[str, str]]] = {
        "DOCUMENT_UPLOAD": [],
        "DOCUMENT_POLL": [],
        "DOCUMENT_E2E": [],
    }
    for row in rows:
        label = row.get("label", "")
        if label in by_label:
            by_label[label].append(row)

    if not by_label["DOCUMENT_UPLOAD"]:
        raise SystemExit("JTL contains no DOCUMENT_UPLOAD samples")

    summary = {
        "jtl_samples_total": len(rows),
        "upload": summarize(by_label["DOCUMENT_UPLOAD"]),
        "poll": summarize(by_label["DOCUMENT_POLL"]),
        "end_to_end": summarize(by_label["DOCUMENT_E2E"])
        if by_label["DOCUMENT_E2E"]
        else None,
    }
    rendered = json.dumps(summary, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)


if __name__ == "__main__":
    main()
