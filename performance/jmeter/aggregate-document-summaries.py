#!/usr/bin/env python3
"""Aggregate repeated document-ingestion summaries and compare sync with async."""

from __future__ import annotations

import argparse
import json
import statistics
from collections import Counter
from pathlib import Path
from typing import Any


LATENCY_METRICS = ("average", "p50", "p90", "p95", "p99", "max")
SAMPLE_METRICS = ("samples", "failures", "error_rate", "duration_seconds", "throughput_rps")


def distribution(values: list[float]) -> dict[str, float]:
    return {
        "median": round(statistics.median(values), 6),
        "min": round(min(values), 6),
        "max": round(max(values), 6),
    }


def require_number(value: Any, *, source: Path, field: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise SystemExit(f"{source}: {field} must be numeric")
    return float(value)


def load_summary(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise SystemExit(f"Summary does not exist: {path}") from None
    except json.JSONDecodeError as exc:
        raise SystemExit(f"Invalid JSON in {path}: {exc}") from None
    if not isinstance(payload, dict):
        raise SystemExit(f"{path}: summary root must be an object")
    if not isinstance(payload.get("upload"), dict):
        raise SystemExit(f"{path}: upload summary is required")
    for section_name in ("poll", "end_to_end"):
        section = payload.get(section_name)
        if section is not None and not isinstance(section, dict):
            raise SystemExit(f"{path}: {section_name} must be an object or null")
    return payload


def aggregate_section(
    rows: list[dict[str, Any]],
    paths: list[Path],
    section_name: str,
) -> dict[str, Any] | None:
    available: list[tuple[dict[str, Any], Path]] = []
    for row, path in zip(rows, paths, strict=True):
        section = row.get(section_name)
        if isinstance(section, dict):
            sample_count = require_number(
                section.get("samples"),
                source=path,
                field=f"{section_name}.samples",
            )
            if sample_count > 0:
                available.append((section, path))
    if not available:
        return None

    result: dict[str, Any] = {
        "available_runs": len(available),
        "missing_runs": len(rows) - len(available),
    }
    for metric in SAMPLE_METRICS:
        values = [
            require_number(section.get(metric), source=path, field=f"{section_name}.{metric}")
            for section, path in available
        ]
        result[metric] = distribution(values)
    result["total_samples"] = int(sum(section["samples"] for section, _path in available))
    result["total_failures"] = int(sum(section["failures"] for section, _path in available))

    latency_rows: list[tuple[dict[str, Any], Path]] = []
    for section, path in available:
        latency = section.get("latency_ms")
        if not isinstance(latency, dict):
            raise SystemExit(f"{path}: {section_name}.latency_ms must be an object")
        latency_rows.append((latency, path))
    result["latency_ms"] = {
        metric: distribution(
            [
                require_number(latency.get(metric), source=path, field=f"{section_name}.latency_ms.{metric}")
                for latency, path in latency_rows
            ]
        )
        for metric in LATENCY_METRICS
    }

    response_codes: Counter[str] = Counter()
    for section, path in available:
        codes = section.get("response_codes", {})
        if not isinstance(codes, dict):
            raise SystemExit(f"{path}: {section_name}.response_codes must be an object")
        for code, count in codes.items():
            response_codes[str(code)] += int(
                require_number(count, source=path, field=f"{section_name}.response_codes.{code}")
            )
    result["response_codes_total"] = dict(sorted(response_codes.items()))
    return result


def aggregate_group(paths: list[Path]) -> dict[str, Any]:
    rows = [load_summary(path) for path in paths]
    jtl_values = [
        require_number(row.get("jtl_samples_total"), source=path, field="jtl_samples_total")
        for row, path in zip(rows, paths, strict=True)
    ]
    return {
        "runs": len(rows),
        "jtl_samples_total": distribution(jtl_values),
        "upload": aggregate_section(rows, paths, "upload"),
        "poll": aggregate_section(rows, paths, "poll"),
        "end_to_end": aggregate_section(rows, paths, "end_to_end"),
        "sources": [str(path) for path in paths],
    }


def delta(sync_value: float, async_value: float, *, higher_is_better: bool | None) -> dict[str, Any]:
    absolute = async_value - sync_value
    percent_change = None if sync_value == 0 else absolute / sync_value * 100
    if higher_is_better is None or percent_change is None:
        improvement = None
    else:
        improvement = percent_change if higher_is_better else -percent_change
    return {
        "sync_median": round(sync_value, 6),
        "async_median": round(async_value, 6),
        "async_minus_sync": round(absolute, 6),
        "percent_change": round(percent_change, 3) if percent_change is not None else None,
        "improvement_percent": round(improvement, 3) if improvement is not None else None,
    }


def compare_section(
    sync_section: dict[str, Any] | None,
    async_section: dict[str, Any] | None,
) -> dict[str, Any] | None:
    if sync_section is None or async_section is None:
        return None

    directions: dict[str, bool | None] = {
        "samples": None,
        "failures": False,
        "error_rate": False,
        "duration_seconds": False,
        "throughput_rps": True,
    }
    comparison: dict[str, Any] = {
        metric: delta(
            sync_section[metric]["median"],
            async_section[metric]["median"],
            higher_is_better=directions[metric],
        )
        for metric in SAMPLE_METRICS
    }
    comparison["latency_ms"] = {
        metric: delta(
            sync_section["latency_ms"][metric]["median"],
            async_section["latency_ms"][metric]["median"],
            higher_is_better=False,
        )
        for metric in LATENCY_METRICS
    }
    return comparison


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sync", nargs="+", type=Path, help="Synchronous summary.json files")
    parser.add_argument("--async", dest="async_paths", nargs="+", type=Path, help="Asynchronous summary.json files")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not args.sync and not args.async_paths:
        parser.error("provide at least one of --sync or --async")

    groups: dict[str, dict[str, Any]] = {}
    if args.sync:
        groups["sync"] = aggregate_group(args.sync)
    if args.async_paths:
        groups["async"] = aggregate_group(args.async_paths)

    result: dict[str, Any] = {"groups": groups}
    if "sync" in groups and "async" in groups:
        result["comparison"] = {
            "semantics": {
                "percent_change": "(async - sync) / sync * 100",
                "improvement_percent": "positive means better; throughput is higher-is-better, latency/error are lower-is-better",
            },
            "upload": compare_section(groups["sync"]["upload"], groups["async"]["upload"]),
            "end_to_end": compare_section(
                groups["sync"]["end_to_end"],
                groups["async"]["end_to_end"],
            ),
        }

    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)


if __name__ == "__main__":
    main()
