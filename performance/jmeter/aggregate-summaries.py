#!/usr/bin/env python3
import argparse
import json
import statistics
from pathlib import Path


def distribution(values: list[float]) -> dict[str, float]:
    return {
        "median": round(statistics.median(values), 3),
        "min": round(min(values), 3),
        "max": round(max(values), 3),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Aggregate repeated JMeter summary.json files")
    parser.add_argument("summaries", nargs="+", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    rows = [json.loads(path.read_text(encoding="utf-8")) for path in args.summaries]
    scenarios = {row["scenario"] for row in rows}
    if len(scenarios) != 1:
        raise SystemExit(f"Expected one scenario, got: {sorted(scenarios)}")

    result = {
        "scenario": rows[0]["scenario"],
        "runs": len(rows),
        "total_samples": sum(row["samples"] for row in rows),
        "error_rate": distribution([row["error_rate"] for row in rows]),
        "throughput_rps": distribution([row["throughput_rps"] for row in rows]),
        "latency_ms": {
            name: distribution([row["latency_ms"][name] for row in rows])
            for name in ("average", "p50", "p90", "p95", "p99", "max")
        },
        "sources": [str(path) for path in args.summaries],
    }
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)


if __name__ == "__main__":
    main()
