#!/usr/bin/env python3
"""Aggregate a controlled asynchronous-ingestion tuning matrix."""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from collections import Counter, OrderedDict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable


@dataclass(frozen=True)
class Run:
    case_id: str
    worker_concurrency: int
    prefetch: int
    embedding_batch_size: int
    repetition: int
    result_dir: Path
    summary: dict[str, Any]
    embedding: dict[str, Any]
    runtime: dict[str, Any]


def require_int(value: str, *, field: str, source: Path) -> int:
    try:
        parsed = int(value)
    except ValueError:
        raise SystemExit(f"{source}: {field} must be an integer") from None
    if parsed <= 0:
        raise SystemExit(f"{source}: {field} must be positive")
    return parsed


def require_number(value: Any, *, field: str, source: Path) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise SystemExit(f"{source}: {field} must be numeric")
    if not math.isfinite(float(value)):
        raise SystemExit(f"{source}: {field} must be finite")
    return float(value)


def load_json(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise SystemExit(f"Required result does not exist: {path}") from None
    except json.JSONDecodeError as exc:
        raise SystemExit(f"Invalid JSON in {path}: {exc}") from None
    if not isinstance(payload, dict):
        raise SystemExit(f"{path}: JSON root must be an object")
    return payload


def load_runtime(path: Path) -> dict[str, Any]:
    if path.exists():
        return load_json(path)
    return {
        "schema_version": 1,
        "status": "unavailable",
        "fatal_error": "runtime-samples.json is missing",
        "sample_attempts": 0,
        "actuator": {"status": "unavailable"},
        "rabbitmq": {"status": "unavailable"},
        "error_counts": {"runtime-samples.json is missing": 1},
        "samples": [],
    }


def resolve_result_dir(raw: str, manifest: Path) -> Path:
    path = Path(raw)
    if not path.is_absolute():
        path = (manifest.parent / path).resolve()
    return path


def load_runs(manifest: Path) -> tuple[list[Run], list[dict[str, str]]]:
    try:
        source = manifest.open(newline="", encoding="utf-8")
    except FileNotFoundError:
        raise SystemExit(f"Manifest does not exist: {manifest}") from None
    with source:
        rows = list(csv.DictReader(source, delimiter="\t"))

    required = {
        "case_id",
        "worker_concurrency",
        "prefetch",
        "embedding_batch_size",
        "repetition",
        "phase",
        "status",
        "result_dir",
    }
    if not rows:
        raise SystemExit(f"{manifest}: manifest contains no runs")
    missing = required - set(rows[0])
    if missing:
        raise SystemExit(f"{manifest}: missing columns: {', '.join(sorted(missing))}")

    runs: list[Run] = []
    rejected: list[dict[str, str]] = []
    seen_repetitions: set[tuple[str, int]] = set()
    case_configs: dict[str, tuple[int, int, int]] = {}
    for row in rows:
        if row["phase"] != "formal":
            continue
        if row["status"] != "success":
            rejected.append({key: row[key] for key in required})
            continue

        workers = require_int(row["worker_concurrency"], field="worker_concurrency", source=manifest)
        prefetch = require_int(row["prefetch"], field="prefetch", source=manifest)
        batch = require_int(row["embedding_batch_size"], field="embedding_batch_size", source=manifest)
        repetition = require_int(row["repetition"], field="repetition", source=manifest)
        identity = (row["case_id"], repetition)
        if identity in seen_repetitions:
            raise SystemExit(f"{manifest}: duplicate formal run {identity[0]} repetition {identity[1]}")
        seen_repetitions.add(identity)

        config = (workers, prefetch, batch)
        prior = case_configs.setdefault(row["case_id"], config)
        if prior != config:
            raise SystemExit(f"{manifest}: case {row['case_id']} has inconsistent parameters")

        result_dir = resolve_result_dir(row["result_dir"], manifest)
        summary_path = result_dir / "summary.json"
        embedding_path = result_dir / "embedding-stats-after.json"
        runtime_path = result_dir / "runtime-samples.json"
        summary = load_json(summary_path)
        embedding = load_json(embedding_path)
        runtime = load_runtime(runtime_path)
        for section in ("upload", "end_to_end"):
            if not isinstance(summary.get(section), dict):
                raise SystemExit(f"{summary_path}: {section} object is required")
        runs.append(
            Run(
                case_id=row["case_id"],
                worker_concurrency=workers,
                prefetch=prefetch,
                embedding_batch_size=batch,
                repetition=repetition,
                result_dir=result_dir,
                summary=summary,
                embedding=embedding,
                runtime=runtime,
            )
        )
    if not runs:
        raise SystemExit(f"{manifest}: no successful formal runs")
    return runs, rejected


def distribution(values: list[float], *, digits: int = 6) -> dict[str, float]:
    return {
        "median": round(statistics.median(values), digits),
        "min": round(min(values), digits),
        "max": round(max(values), digits),
    }


def metric(run: Run, section: str, *path: str) -> float:
    value: Any = run.summary[section]
    dotted = section
    for key in path:
        dotted += f".{key}"
        if not isinstance(value, dict) or key not in value:
            raise SystemExit(f"{run.result_dir / 'summary.json'}: missing {dotted}")
        value = value[key]
    return require_number(value, field=dotted, source=run.result_dir / "summary.json")


def embedding_metric(run: Run, field: str) -> float:
    return require_number(
        run.embedding.get(field),
        field=field,
        source=run.result_dir / "embedding-stats-after.json",
    )


def runtime_metric(run: Run, section: str, field: str) -> float | None:
    payload = run.runtime.get(section)
    if not isinstance(payload, dict):
        return None
    value = payload.get(field)
    if value is None:
        return None
    return require_number(value, field=f"{section}.{field}", source=run.result_dir / "runtime-samples.json")


def aggregate_runtime(runs: list[Run]) -> dict[str, Any]:
    specifications = {
        "max_process_cpu_usage": ("actuator", "max_process_cpu_usage"),
        "max_jvm_heap_used_mib": ("actuator", "max_jvm_heap_used_mib"),
        "max_jvm_heap_utilization": ("actuator", "max_jvm_heap_utilization"),
        "max_messages_ready": ("rabbitmq", "max_messages_ready"),
        "max_messages_unacknowledged": ("rabbitmq", "max_messages_unacknowledged"),
        "max_consumers": ("rabbitmq", "max_consumers"),
    }
    metrics: dict[str, Any] = {}
    for output_name, (section, field) in specifications.items():
        values = [value for run in runs if (value := runtime_metric(run, section, field)) is not None]
        metrics[output_name] = {
            "available_runs": len(values),
            "missing_runs": len(runs) - len(values),
            "distribution": distribution(values) if values else None,
        }

    section_statuses: dict[str, dict[str, int]] = {}
    for section in ("actuator", "rabbitmq"):
        counts: Counter[str] = Counter()
        for run in runs:
            payload = run.runtime.get(section)
            value = payload.get("status") if isinstance(payload, dict) else None
            counts[str(value or "unavailable")] += 1
        section_statuses[section] = dict(sorted(counts.items()))

    error_counts: Counter[str] = Counter()
    for run in runs:
        errors = run.runtime.get("error_counts", {})
        if not isinstance(errors, dict):
            error_counts["runtime error_counts is not an object"] += 1
            continue
        for message, count in errors.items():
            if isinstance(count, bool) or not isinstance(count, int) or count < 0:
                error_counts[f"invalid runtime error count for {message}"] += 1
            else:
                error_counts[str(message)] += count
    return {
        "section_statuses": section_statuses,
        "sample_attempts": distribution(
            [require_number(run.runtime.get("sample_attempts", 0), field="sample_attempts", source=run.result_dir / "runtime-samples.json") for run in runs]
        ),
        "metrics": metrics,
        "error_counts_total": dict(sorted(error_counts.items())),
    }


def aggregate_case(runs: list[Run]) -> dict[str, Any]:
    first = runs[0]
    document_counts = [metric(run, "end_to_end", "samples") for run in runs]
    embedding_requests_per_document = [
        embedding_metric(run, "embedding_requests") / documents
        for run, documents in zip(runs, document_counts, strict=True)
    ]
    inputs_per_request = [
        embedding_metric(run, "inputs_total") / max(embedding_metric(run, "embedding_requests"), 1)
        for run in runs
    ]
    failures = [metric(run, "end_to_end", "failures") for run in runs]
    embedding_errors = [embedding_metric(run, "errors_total") for run in runs]
    for run, observed_inputs_per_request in zip(runs, inputs_per_request, strict=True):
        if observed_inputs_per_request > run.embedding_batch_size:
            raise SystemExit(
                f"{run.result_dir}: observed {observed_inputs_per_request:.3f} embedding inputs/request "
                f"exceeds configured batch size {run.embedding_batch_size}; the JAR may not contain "
                "the tuning property"
            )
        observed_in_flight = embedding_metric(run, "max_in_flight")
        if observed_in_flight > run.worker_concurrency:
            raise SystemExit(
                f"{run.result_dir}: embedding max_in_flight={observed_in_flight:g} exceeds "
                f"worker_concurrency={run.worker_concurrency}; another workload may have shared the mock"
            )
        observed_consumers = runtime_metric(run, "rabbitmq", "max_consumers")
        if observed_consumers is not None and observed_consumers != run.worker_concurrency:
            raise SystemExit(
                f"{run.result_dir}: RabbitMQ consumers={observed_consumers:g} does not match "
                f"worker_concurrency={run.worker_concurrency}; the runtime configuration is not isolated"
            )

    latency_paths = {
        "average_ms": ("latency_ms", "average"),
        "p50_ms": ("latency_ms", "p50"),
        "p95_ms": ("latency_ms", "p95"),
        "p99_ms": ("latency_ms", "p99"),
    }
    return {
        "parameters": {
            "worker_concurrency": first.worker_concurrency,
            "prefetch": first.prefetch,
            "embedding_batch_size": first.embedding_batch_size,
            "maximum_unacked_deliveries": first.worker_concurrency * first.prefetch,
        },
        "runs": len(runs),
        "documents_total": int(sum(document_counts)),
        "end_to_end_failures_total": int(sum(failures)),
        "embedding_errors_total": int(sum(embedding_errors)),
        "upload": {
            name: distribution([metric(run, "upload", *path) for run in runs])
            for name, path in latency_paths.items()
        },
        "end_to_end": {
            **{
                name: distribution([metric(run, "end_to_end", *path) for run in runs])
                for name, path in latency_paths.items()
            },
            "completion_throughput_rps": distribution(
                [metric(run, "end_to_end", "throughput_rps") for run in runs]
            ),
        },
        "embedding": {
            "requests_per_document": distribution(embedding_requests_per_document),
            "inputs_per_request": distribution(inputs_per_request),
            "max_in_flight": distribution(
                [embedding_metric(run, "max_in_flight") for run in runs]
            ),
        },
        "runtime_pressure": aggregate_runtime(runs),
        "sources": [str(run.result_dir) for run in sorted(runs, key=lambda item: item.repetition)],
    }


def percent_change(before: float, after: float) -> float | None:
    if before == 0:
        return None
    return round((after - before) / before * 100, 3)


def improvement(before: float, after: float, *, higher_is_better: bool) -> float | None:
    change = percent_change(before, after)
    if change is None:
        return None
    return change if higher_is_better else -change


def compare_to_baseline(baseline: dict[str, Any], candidate: dict[str, Any]) -> dict[str, Any]:
    fields: list[tuple[str, Callable[[dict[str, Any]], float], bool]] = [
        ("end_to_end_p95_ms", lambda item: item["end_to_end"]["p95_ms"]["median"], False),
        (
            "completion_throughput_rps",
            lambda item: item["end_to_end"]["completion_throughput_rps"]["median"],
            True,
        ),
        ("upload_p95_ms", lambda item: item["upload"]["p95_ms"]["median"], False),
        (
            "embedding_requests_per_document",
            lambda item: item["embedding"]["requests_per_document"]["median"],
            False,
        ),
    ]
    result: dict[str, Any] = {}
    for name, getter, higher_is_better in fields:
        before = getter(baseline)
        after = getter(candidate)
        result[name] = {
            "baseline": before,
            "candidate": after,
            "percent_change": percent_change(before, after),
            "improvement_percent": improvement(before, after, higher_is_better=higher_is_better),
        }
    return result


def choose(cases: dict[str, dict[str, Any]], *, max_in_flight: int) -> dict[str, str | None]:
    valid = {
        case_id: result
        for case_id, result in cases.items()
        if result["end_to_end_failures_total"] == 0 and result["embedding_errors_total"] == 0
    }
    if not valid:
        return {"lowest_end_to_end_p95": None, "highest_completion_throughput": None, "balanced": None}

    lowest_latency = min(valid, key=lambda key: valid[key]["end_to_end"]["p95_ms"]["median"])
    highest_throughput = max(
        valid,
        key=lambda key: valid[key]["end_to_end"]["completion_throughput_rps"]["median"],
    )
    constrained = {
        case_id: result
        for case_id, result in valid.items()
        if result["embedding"]["max_in_flight"]["median"] <= max_in_flight
    }
    balanced = (
        min(constrained, key=lambda key: constrained[key]["end_to_end"]["p95_ms"]["median"])
        if constrained
        else None
    )
    return {
        "lowest_end_to_end_p95": lowest_latency,
        "highest_completion_throughput": highest_throughput,
        "balanced": balanced,
    }


def markdown_report(result: dict[str, Any]) -> str:
    def formatted(values: dict[str, float], *, scale: float = 1.0, suffix: str = "") -> str:
        median = values["median"] * scale
        minimum = values["min"] * scale
        maximum = values["max"] * scale
        return f"{median:.3f}{suffix} ({minimum:.3f}–{maximum:.3f})"

    def observed(case: dict[str, Any], field: str, *, percent: bool = False) -> str:
        metric_result = case["runtime_pressure"]["metrics"][field]
        values = metric_result["distribution"]
        if values is None:
            return "n/a"
        return formatted(values, scale=100, suffix="%") if percent else formatted(values)

    lines = [
        "# Async ingestion tuning matrix",
        "",
        "Figures use `median (min–max)` across successful formal runs. Completion throughput is based on the",
        "synthetic `DOCUMENT_E2E` sample window; upload throughput is intentionally not used for tuning.",
        "",
        "| Case | W/P/B | E2E P95 ms | Completion RPS | Embed req/doc | Embed in-flight | Queue ready | Unacked | App CPU | Heap MiB | Failures |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for case_id, case in result["cases"].items():
        parameters = case["parameters"]
        lines.append(
            "| {case} | {workers}/{prefetch}/{batch} | {e2e} | {throughput} | "
            "{requests} | {in_flight} | {ready} | {unacked} | {cpu} | {heap} | {failures} |".format(
                case=case_id,
                workers=parameters["worker_concurrency"],
                prefetch=parameters["prefetch"],
                batch=parameters["embedding_batch_size"],
                e2e=formatted(case["end_to_end"]["p95_ms"]),
                throughput=formatted(case["end_to_end"]["completion_throughput_rps"]),
                requests=formatted(case["embedding"]["requests_per_document"]),
                in_flight=formatted(case["embedding"]["max_in_flight"]),
                ready=observed(case, "max_messages_ready"),
                unacked=observed(case, "max_messages_unacknowledged"),
                cpu=observed(case, "max_process_cpu_usage", percent=True),
                heap=observed(case, "max_jvm_heap_used_mib"),
                failures=case["end_to_end_failures_total"] + case["embedding_errors_total"],
            )
        )
    lines.extend(
        [
            "",
            "Queue and resource figures are sampled peaks. A queue value of zero means no non-zero value was observed;",
            "it does not prove the instantaneous queue depth was always zero.",
            "",
            "## Changes from baseline",
            "",
            "Positive improvement is better. Upload P95 is included as a regression guard even though it is not the tuning objective.",
            "",
            "| Case | E2E P95 improvement | Completion-rate improvement | Upload P95 improvement | Embedding-request improvement |",
            "|---|---:|---:|---:|---:|",
        ]
    )
    for case_id, comparison in result["comparisons_to_baseline"].items():
        lines.append(
            "| {case} | {e2e:+.3f}% | {throughput:+.3f}% | {upload:+.3f}% | {requests:+.3f}% |".format(
                case=case_id,
                e2e=comparison["end_to_end_p95_ms"]["improvement_percent"],
                throughput=comparison["completion_throughput_rps"]["improvement_percent"],
                upload=comparison["upload_p95_ms"]["improvement_percent"],
                requests=comparison["embedding_requests_per_document"]["improvement_percent"],
            )
        )
    selection = result["selection"]
    minimum_runs = min(case["runs"] for case in result["cases"].values())
    repetition_note = (
        "Every case in this report includes at least three formal repetitions."
        if minimum_runs >= 3
        else "Treat this as exploratory and confirm the selected cases with at least three formal repetitions."
    )
    lines.extend(
        [
            "",
            "## Mechanical selections",
            "",
            f"- Lowest E2E P95: `{selection['lowest_end_to_end_p95']}`",
            f"- Highest completion throughput: `{selection['highest_completion_throughput']}`",
            (
                f"- Balanced (max observed embedding in-flight <= {result['selection_policy']['max_in_flight']}): "
                f"`{selection['balanced']}`"
            ),
            "",
            f"These are measurements, not a production recommendation. {repetition_note}",
            "Select a concurrency ceiling that the real embedding provider and database can sustain.",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="runs.tsv emitted by run-ingest-matrix.sh")
    parser.add_argument("--baseline-case", default="baseline-w2-p1-b10")
    parser.add_argument("--max-in-flight", type=int, default=4)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--markdown-output", type=Path)
    args = parser.parse_args()
    if args.max_in_flight <= 0:
        parser.error("--max-in-flight must be positive")

    runs, rejected = load_runs(args.manifest)
    grouped: OrderedDict[str, list[Run]] = OrderedDict()
    for run in runs:
        grouped.setdefault(run.case_id, []).append(run)
    cases = OrderedDict((case_id, aggregate_case(case_runs)) for case_id, case_runs in grouped.items())
    if args.baseline_case not in cases:
        raise SystemExit(f"Baseline case is missing from successful runs: {args.baseline_case}")

    baseline = cases[args.baseline_case]
    comparisons = OrderedDict(
        (case_id, compare_to_baseline(baseline, case))
        for case_id, case in cases.items()
        if case_id != args.baseline_case
    )
    result: dict[str, Any] = {
        "schema_version": 1,
        "manifest": str(args.manifest.resolve()),
        "baseline_case": args.baseline_case,
        "successful_runs": len(runs),
        "rejected_runs": rejected,
        "semantics": {
            "percent_change": "(candidate - baseline) / baseline * 100",
            "improvement_percent": "positive means better; latency/errors/requests are lower-is-better, throughput is higher-is-better",
            "completion_throughput": "DOCUMENT_E2E samples divided by their observed sample window",
        },
        "cases": cases,
        "comparisons_to_baseline": comparisons,
        "selection_policy": {"max_in_flight": args.max_in_flight, "requires_zero_errors": True},
        "selection": choose(cases, max_in_flight=args.max_in_flight),
    }

    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    report = markdown_report(result)
    if args.markdown_output:
        args.markdown_output.parent.mkdir(parents=True, exist_ok=True)
        args.markdown_output.write_text(report, encoding="utf-8")
    print(report)


if __name__ == "__main__":
    main()
