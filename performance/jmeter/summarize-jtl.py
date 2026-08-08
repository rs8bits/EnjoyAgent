#!/usr/bin/env python3
import argparse
import csv
import json
import math
import statistics
from datetime import datetime, timezone
from pathlib import Path


def percentile(values: list[int], ratio: float) -> int:
    if not values:
        return 0
    index = max(0, math.ceil(len(values) * ratio) - 1)
    return values[index]


def main() -> None:
    parser = argparse.ArgumentParser(description="Summarize a JMeter CSV JTL file")
    parser.add_argument("jtl", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--target-rpm", type=float)
    args = parser.parse_args()

    with args.jtl.open(newline="", encoding="utf-8") as source:
        rows = list(csv.DictReader(source))
    if not rows:
        raise SystemExit("JTL contains no samples")

    elapsed = sorted(int(row["elapsed"]) for row in rows)
    started_at = min(int(row["timeStamp"]) for row in rows)
    finished_at = max(int(row["timeStamp"]) + int(row["elapsed"]) for row in rows)
    duration_seconds = max((finished_at - started_at) / 1000, 0.001)
    failures = sum(row.get("success", "").lower() != "true" for row in rows)
    summary = {
        "scenario": rows[0].get("label", "").split(":", 1)[-1],
        "samples": len(rows),
        "started_at_utc": datetime.fromtimestamp(started_at / 1000, timezone.utc).isoformat(),
        "finished_at_utc": datetime.fromtimestamp(finished_at / 1000, timezone.utc).isoformat(),
        "duration_seconds": round(duration_seconds, 3),
        "throughput_rps": round(len(rows) / duration_seconds, 3),
        "error_rate": round(failures / len(rows), 6),
        "latency_ms": {
            "average": round(statistics.fmean(elapsed), 3),
            "p50": percentile(elapsed, 0.50),
            "p90": percentile(elapsed, 0.90),
            "p95": percentile(elapsed, 0.95),
            "p99": percentile(elapsed, 0.99),
            "max": max(elapsed),
        },
    }
    if args.target_rpm is not None:
        target_rps = args.target_rpm / 60
        summary["target_rps"] = round(target_rps, 3)
        summary["achievement_ratio"] = round(summary["throughput_rps"] / target_rps, 6)

    rendered = json.dumps(summary, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)


if __name__ == "__main__":
    main()
